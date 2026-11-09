package app.couchtv.launcher.home

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import org.json.JSONArray
import org.json.JSONObject

/** One tile in the dock or in More apps. */
data class AppItem(val kind: String, val name: String, val pkg: String, val store: Boolean = false)

/**
 * The apps, in the web home's order (app.js: APP_ORDER, APP_LAST, TUCKED, HIDDEN, the order kept
 * on this TV, new apps). The dock holds the first eleven; More apps shows everything.
 */
class Apps(private val ctx: Context, private val store: Store) {
    companion object {
        const val SETTINGS = "com.android.tv.settings"
        const val STORE = "com.android.vending"
        const val WEB = "web:home"
        private val ORDER = listOf(
            "com.google.android.youtube.tv", "com.netflix.ninja", "au.net.abc.iview", "com.disney.disneyplus",
            "au.com.stan.and", "au.com.streamotion.ares.tv", "com.amazon.amazonvideo.livingroom",
            "com.sbs.ondemand.tv", "com.apple.atve.androidtv.appletv", "com.cbs.ca", "au.com.ninenow.ctv",
            "com.swm.live", "au.com.tenplay", "au.com.kayosports.tv", "au.com.foxtel.atv", "com.wbd.stream",
            "com.spotify.tv.android", "com.google.android.youtube.tvmusic")
        private val LAST = listOf("com.mediatek.wwtv.tvcenter", STORE, SETTINGS)
        private val TUCKED = listOf("com.mediatek.wwtv.tvcenter", "com.google.android.youtube.tvmusic", "com.spotify.tv.android")
        private val TUCKED_NAMES = Regex("^(screen ?shar|live ?tv|usb|media ?cent|browser|device assistant)", RegexOption.IGNORE_CASE)
        private val HIDDEN = listOf("com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher", "app.couchtv.launcher",
            "com.limelight", "com.google.android.play.games", "com.google.android.videos")
    }

    var all: List<AppItem> = emptyList(); private set
    var expanded = false
    var newApps: MutableList<String> = mutableListOf()
    private val icons = HashMap<String, Bitmap>()

    data class Order(var line: Int, val packages: MutableList<String>)
    var order: Order? = null

    fun load() {
        val order0 = store.strings(store.home.optJSONArray("app_order"))
        val rankList = order0 + ORDER.filter { it !in order0 }
        val hide = HIDDEN + store.strings(store.home.optJSONArray("hide_packages"))
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val pm = ctx.packageManager
        all = pm.queryIntentActivities(q, 0).filter { it.activityInfo.packageName != ctx.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { ri -> val p = ri.activityInfo.packageName; AppItem("app", if (p == STORE) L.t("app_store") else ri.loadLabel(pm).toString(), p) }
            .filter { it.pkg !in hide }
            .sortedWith(compareBy<AppItem>({ rank(it.pkg, rankList) }, { it.name.lowercase() }))
        newApps = store.strings(store.kept("tvhome.apps.new") as? JSONArray).toMutableList()
        (store.kept("tvhome.order.tv") as? JSONObject)?.let { o ->
            val p = store.strings(o.optJSONArray("packages"))
            if (p.isNotEmpty()) order = Order(o.optInt("line", 0), p.toMutableList())
        }
        detectNew()
    }
    private fun rank(p: String, list: List<String>): Int {
        val i = list.indexOf(p); if (i >= 0) return i
        val j = LAST.indexOf(p); return if (j >= 0) 2000 + j else 1000
    }
    private fun tucked(a: AppItem) = a.pkg in TUCKED || TUCKED_NAMES.containsMatchIn(a.name)
    val hiddenTv: List<String> get() = store.strings(store.st.optJSONArray("hidden_apps"))
    fun shown() = all.filter { it.pkg !in hiddenTv }
    val dockSize get() = store.dockApps

    /** The dock's apps and where the line to More apps falls (app.js arrangeItems). */
    fun arrange(): Pair<List<AppItem>, Int> {
        val rest = shown().filter { it.pkg != SETTINGS && it.pkg != STORE }
        var items = rest.filter { !tucked(it) } + rest.filter { tucked(it) }
        var line = minOf(dockSize, rest.count { !tucked(it) })
        order?.let { o ->
            val at = o.packages.withIndex().associate { it.value to it.index }
            val known = items.filter { at.containsKey(it.pkg) }.sortedBy { at[it.pkg] }
            val gained = items.filter { !at.containsKey(it.pkg) }
            items = known + gained
            // an app gone from above the line (uninstalled) leaves its place to the next one, so the
            // dock keeps its size rather than shrinking
            line = minOf(dockSize, o.line, items.count { !tucked(it) || o.packages.indexOf(it.pkg) in 0 until o.line })
        }
        return items to line
    }
    fun dock(): List<AppItem> {
        val (items, line) = arrange()
        if (!expanded) return items.take(line) + AppItem("more", L.t("more_apps"), "")
        val st = all.firstOrNull { it.pkg == STORE }
        return (if (st != null) listOf(AppItem("app", L.t("app_store"), STORE, store = true)) else emptyList()) + items
    }

    /** The arrangement as it is on screen, written down so it can be changed. */
    fun materialise(): Order {
        val (items, line) = arrange()
        return Order(line, items.map { it.pkg }.toMutableList()).also { order = it }
    }
    fun persist() {
        val o = order ?: return
        store.keep("tvhome.order.tv", JSONObject().put("line", o.line).put("packages", JSONArray(o.packages)).put("pending", true))
    }
    fun nameOf(pkg: String) = arrange().first.firstOrNull { it.pkg == pkg }?.name ?: L.t("that_app")

    /** Apps installed since this TV first listed its apps: into the dock when there is room, with a dot. */
    private fun detectNew() {
        val pk = all.filter { it.pkg != SETTINGS && it.pkg != STORE }.map { it.pkg }
        val seenAny = store.kept("tvhome.apps.seen") as? JSONArray
        if (seenAny == null) { store.keep("tvhome.apps.seen", JSONArray(pk)); return }
        val seen = store.strings(seenAny)
        val fresh = pk.filter { it !in seen }
        if (fresh.isEmpty()) return
        store.keep("tvhome.apps.seen", JSONArray(seen + fresh))
        fresh.forEach { if (it !in newApps) newApps.add(it) }
        store.keep("tvhome.apps.new", JSONArray(newApps))
        val o = materialise()
        fresh.forEach { p ->
            val app = all.firstOrNull { it.pkg == p }
            val i = o.packages.indexOf(p)
            if (i < 0 || i < o.line || (app != null && tucked(app))) return@forEach
            o.packages.removeAt(i); o.packages.add(o.line, p)
            if (o.line < dockSize) o.line += 1
        }
        persist()
    }
    fun opened(pkg: String) {
        if (newApps.remove(pkg)) store.keep("tvhome.apps.new", JSONArray(newApps))
    }

    fun launch(pkg: String): Boolean {
        if (pkg == WEB) return try { ctx.startActivity(Intent(ctx, app.couchtv.launcher.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) { false }
        val pm = ctx.packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        return try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) { false }
    }

    /** The app's square icon (the same 256 px picture the web dock shows). */
    fun icon(pkg: String): Bitmap? = synchronized(icons) { icons[pkg] } ?: run {
        val pm = ctx.packageManager
        val d = if (pkg == WEB) ctx.applicationInfo.loadIcon(pm) else try {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(pkg), 0)
                .firstOrNull()?.loadIcon(pm) ?: pm.getApplicationIcon(pkg)
        } catch (e: Exception) { return null }
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF1C1C21.toInt())   // app.css --tile, under icons with see-through parts
        if (d is android.graphics.drawable.AdaptiveIconDrawable) {
            // both layers full bleed (no round mask), so the dock's rounded square is the shape
            val n = 256 / 4
            d.background?.apply { setBounds(-n / 2, -n / 2, 256 + n / 2, 256 + n / 2); draw(c) }
            d.foreground?.apply { setBounds(-n / 2, -n / 2, 256 + n / 2, 256 + n / 2); draw(c) }
        } else { d.setBounds(0, 0, 256, 256); d.draw(c) }
        val hw = bmp.copy(Bitmap.Config.HARDWARE, false) ?: bmp
        if (hw !== bmp) bmp.recycle()
        synchronized(icons) { icons[pkg] = hw }
        hw
    }
    fun cachedIcon(pkg: String) = synchronized(icons) { icons[pkg] }
}
