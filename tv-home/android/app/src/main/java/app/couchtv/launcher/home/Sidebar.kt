package app.couchtv.launcher.home

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.view.View

/**
 * The sidebar (sidebar.css): who is watching, the Store, ambient mode, the input list, Home
 * Assistant scenes and Settings; a second level for picking who is watching, and a show's own
 * menu (hold OK on a card): remove it, mark it watched, which app it opens in.
 */
@SuppressLint("ViewConstructor")
class Sidebar(ctx: Context, private val home: HomeActivity) : View(ctx) {
    private class Row(val kind: String? = null, val label: String = "", val value: String = "", val icon: Icon? = null,
                      val person: Store.Person? = null, val appPkg: String? = null, val check: Boolean = false,
                      val head: Boolean = false, val title: String? = null, val note: String? = null, val id: String = "")
    private var level = "main"
    private var i = 0
    private var show: Show? = null
    private var from = "grid"
    private val haState = HashMap<String, String>()
    private var open = 0f
    private val slide = Spring(0f, 0.002f) { open = it; visibility = if (it <= 0.001f) INVISIBLE else VISIBLE; invalidate() }
    private val face = Glass.Face()
    private val dim = Paint()
    private val rowP = Ui.paint(Ui.interMid, 1.5f)
    private val headP = Ui.paint(Ui.interBold, 1.5f)
    private val valP = Ui.paint(Ui.inter, 1.25f, 0x8CFFFFFF.toInt())
    private val secP = Ui.paint(Ui.interBold, 1.1875f, 0x80FFFFFF.toInt())
    private val noteP = Ui.paint(Ui.inter, 1.25f, 0x99FFFFFF.toInt())
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val appP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var time = ""
    private var grad: Shader? = null
    private var rowFocus = 0f
    private val rowSpring = Spring(0f) { rowFocus = it; invalidate() }
    init { visibility = INVISIBLE; slide.snap(0f) }

    fun clockChanged() {
        val c = java.util.Calendar.getInstance()
        val hm = Clock.hm(c, home.store.clock24)
        time = if (home.store.clock24) hm else hm + " " + L.t(if (c.get(java.util.Calendar.HOUR_OF_DAY) < 12) "am" else "pm")
        if (open > 0f) invalidate()
    }

    private fun rows(): List<Row> {
        val out = ArrayList<Row>()
        if (level == "app") {
            val s = show ?: return out
            val cur = home.shows.picks().optString(home.shows.key(s.title)).ifEmpty { null }
            out.add(Row(title = s.title))
            out.add(Row("show-hide", L.t("show_hide"), icon = Icons.hide))
            if (home.shows.canMark(s)) out.add(Row("show-mark", L.t("show_mark", "ep" to "S${s.season}, E${s.episode}"), icon = Icons.check))
            out.add(Row(title = L.t("show_app", "title" to s.title)))
            out.add(Row("app-auto", L.t("show_app_auto"), icon = Icons.check, check = cur == null))
            home.apps.dock().filter { it.kind == "app" && !it.store }.forEach { a -> out.add(Row("app-pick", a.name, appPkg = a.pkg, check = a.pkg == cur)) }
            return out
        }
        if (level == "who") {
            out.add(Row(title = L.t("who_watching")))
            home.people.forEach { p -> out.add(Row("pick", p.name, person = p, check = p.id == home.who, id = p.id)) }
            return out
        }
        val p = home.person()
        if (home.people.size >= 2 && p != null) out.add(Row("who", p.name, time, person = p, head = true))
        else out.add(Row(null, L.t("settings"), time, head = true))
        out.add(Row("store", L.t("store"), icon = Icons.store))
        out.add(Row("ambient", L.t("ambient"), icon = Icons.ambient))
        if (quickInput() && inputsIntent() != null) out.add(Row("input", L.t("input"), icon = Icons.input))
        val scenes = home.ha.scenes()
        if (scenes.isNotEmpty()) {
            out.add(Row(title = L.t("home_scenes")))
            scenes.forEach { sc -> val st = haState[sc.id]; out.add(Row("ha", sc.name, if (st == "busy") "…" else if (st == "fail") "!" else "", Icons.ha(sc.icon), check = st == "ok", id = sc.id)) }
        }
        out.add(Row(title = L.t("settings")))
        out.add(Row("home-settings", L.t("gear_home"), icon = Icons.sliders))
        out.add(Row("tv-settings", L.t("gear_tv"), icon = Icons.gear))
        return out
    }
    private fun quickInput(): Boolean {
        val q = home.store.st.optJSONArray("quick")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: home.store.strings(home.store.cfg.optJSONArray("tvs")?.optJSONObject(0)?.optJSONArray("quick")).ifEmpty { listOf("sleep", "picture") }
        return "input" in q
    }
    fun inputsIntent(): Intent? = listOf("android.settings.TV_INPUT_SETTINGS", "com.android.tv.action.VIEW_INPUTS", "com.google.android.tv.action.VIEW_INPUTS", "android.media.tv.action.SETUP_INPUTS")
        .map { Intent(it) }.firstOrNull { context.packageManager.resolveActivity(it, 0) != null }
    private fun focusable() = rows().filter { it.kind != null }

    private var grabbing = false
    // the scene and a show's artwork behind it change while it is open: it is drawn again
    init { Glass.listeners.add { if (open > 0f) invalidate() } }
    fun open() {
        if (home.zone == "side" || grabbing) return
        from = home.zone; if (show == null || level != "app") { level = "main"; i = 0 }
        // the sidebar's glass is the same frosted scene as every piece (brightness 0.82, sidebar.css),
        // so it follows the scene and a show's artwork changing behind it while open
        run {
            home.zone = "side"; home.paint()
            // the person tab steps aside: the panel reads as the tab growing into it (sidebar.css)
            home.tab.animate().alpha(0f).setDuration(260).setUpdateListener { home.root.invalidate() }.start()
            slide.to(1f, 0.42f); rowSpring.snap(1f); invalidate()
        }
    }
    fun openShow(s: Show) {
        show = s; open()
        level = "app"
        val cur = home.shows.picks().optString(home.shows.key(s.title)).ifEmpty { null }
        val extra = if (home.shows.canMark(s)) 2 else 1
        level = "app"
        val apps = home.apps.dock().filter { it.kind == "app" && !it.store }
        i = if (cur != null) extra + 1 + maxOf(0, apps.indexOfFirst { it.pkg == cur }) else extra
        invalidate()
    }
    fun close(paint: Boolean = true) {
        if (home.zone != "side") return
        show = null
        slide.to(0f, 0.46f)
        home.tab.animate().alpha(1f).setStartDelay(200).setDuration(260).setUpdateListener { home.root.invalidate() }.start()
        home.zone = if (from == "side" || from == "settings") "grid" else from
        if (paint) home.paint()
    }
    fun back() {
        when (level) {
            "app" -> close()
            "who" -> { level = "main"; i = 0; invalidate() }
            else -> close()
        }
    }
    fun move(dx: Int, dy: Int) {
        if (dx > 0) { close(); return }
        if (dx < 0) { if (level != "main") back(); return }
        val n = (i + dy).coerceIn(0, focusable().size - 1)
        if (n != i) { i = n; rowSpring.snap(0.6f); rowSpring.to(1f, Motion.FOCUS) }
        invalidate()
    }
    fun activate() {
        val r = focusable().getOrNull(i) ?: return
        when (r.kind) {
            "who" -> { level = "who"; i = maxOf(0, home.people.indexOfFirst { it.id == home.who }) }
            "pick" -> { home.pickWho(r.id); level = "main"; i = 0 }
            "store" -> { close(); home.apps.opened(Apps.STORE); if (!home.apps.launch(Apps.STORE)) home.toast.show(L.t("store_none")) }
            "ambient" -> { close()
                val ok = try { context.startActivity(Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.android.systemui", "com.android.systemui.Somnambulator")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) { false }
                if (!ok) home.toast.show(L.t("ambient_none")) }
            "input" -> { close(); try { context.startActivity(inputsIntent()!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { home.toast.show(L.t("no_inputs")) } }
            "ha" -> { haState[r.id] = "busy"; invalidate(); home.ha.run(r.id) { ok -> haState[r.id] = if (ok) "ok" else "fail"; invalidate(); postDelayed({ haState.remove(r.id); invalidate() }, if (ok) 2500 else 5000) } }
            "show-hide" -> { show?.let { home.shows.hide(it) }; close(); home.refresh() }
            "show-mark" -> { show?.let { home.shows.mark(home.who, it); home.toast.show(L.t("show_marked", "title" to it.title)) }; close() }
            "app-pick", "app-auto" -> { show?.let { home.shows.setPick(it, if (r.kind == "app-pick") r.appPkg else null) }; close(); home.refresh() }
            "home-settings" -> { close(false); home.openSettings() }
            "tv-settings" -> { close(); home.openTvSettings() }
        }
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (open <= 0f) return
        val w = width.toFloat(); val h = height.toFloat()
        dim.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(0x8C000000.toInt(), 0x40000000, 0), floatArrayOf(0f, 0.35f, 0.65f), Shader.TileMode.CLAMP)
        dim.alpha = (255 * open).toInt(); c.drawRect(0f, 0f, w, h, dim)
        val pw = Ui.r(21f); val px = Ui.r(2.25f) - (1 - open) * (pw + Ui.r(5.25f)); val py = Ui.r(2.25f)
        val ph = h - 2 * py
        c.save(); c.translate(px, py)
        if (grad == null) grad = LinearGradient(0f, 0f, 0f, ph, intArrayOf(0x17FFFFFF, 0x08FFFFFF, 0x05FFFFFF, 0x0DFFFFFF), floatArrayOf(0f, 0.3f, 0.75f, 1f), Shader.TileMode.CLAMP)
        face.draw(c, pw, ph, Ui.r(3.375f), px, py, 0x4D16141A, dim = 0.18f, gradient = grad)
        val all = rows(); var k = 0
        var y = Ui.r(1.5f); val x = Ui.r(1.5f); val rw = pw - 2 * x
        all.forEachIndexed { n, r ->
            if (r.title != null) {
                y += if (n == 0) Ui.r(0.25f) else Ui.r(1.25f)
                c.drawText(Ui.fit(secP, r.title, rw - Ui.r(1.5f)), x + Ui.r(0.75f), y + Ui.r(1.2f), secP); y += Ui.r(1.25f + 0.5f + 0.3f)
                return@forEachIndexed
            }
            r.note?.let { c.drawText(it, x + Ui.r(0.75f), y + Ui.r(1.5f), noteP); y += Ui.r(2f); return@forEachIndexed }
            val focus = r.kind != null && k++ == i && home.zone == "side"
            val rh = Ui.r(3.75f)
            val ink = if (focus) 0xFF111111.toInt() else 0xEBFFFFFF.toInt()
            if (focus) {
                val s = 1f + 0.04f * rowFocus
                c.save(); c.scale(s, s, x + rw / 2, y + rh / 2)
                fill.color = Color.WHITE; fill.setShadowLayer(Ui.r(1.75f), 0f, Ui.r(0.75f), 0x59000000); c.drawRoundRect(x, y, x + rw, y + rh, rh / 2, rh / 2, fill); fill.clearShadowLayer()
            }
            var tx = x + Ui.r(0.75f)
            val cy = y + rh / 2
            when {
                r.person != null -> { Avatar.draw(context, c, r.person, tx + Ui.r(1.1875f), cy, Ui.r(2.375f)); tx += Ui.r(2.375f + 0.875f) }
                r.appPkg != null -> { home.apps.cachedIcon(r.appPkg)?.let { ic ->
                    val sz = Ui.r(2.375f); val n2 = c.save(); c.clipPath(Path().apply { addRoundRect(tx, cy - sz / 2, tx + sz, cy + sz / 2, Ui.r(0.625f), Ui.r(0.625f), Path.Direction.CW) })
                    c.drawBitmap(ic, null, RectF(tx, cy - sz / 2, tx + sz, cy + sz / 2), appP); c.restoreToCount(n2) }; tx += Ui.r(2.375f + 0.875f) }
                r.icon != null -> { fill.color = if (focus) 0x1A111111 else 0x24FFFFFF; c.drawCircle(tx + Ui.r(1.1875f), cy, Ui.r(1.1875f), fill)
                    r.icon.draw(c, tx + Ui.r(1.1875f - 0.6875f), cy - Ui.r(0.6875f), Ui.r(1.375f), ink, 1.8f); tx += Ui.r(2.375f + 0.875f) }
            }
            val p = if (r.head) headP else rowP
            p.color = ink
            var right = x + rw - Ui.r(1.125f)
            if (r.check) { Icons.check.draw(c, right - Ui.r(1.375f), cy - Ui.r(0.6875f), Ui.r(1.375f), ink, 1.8f); right -= Ui.r(2f) }
            if (r.value.isNotEmpty()) { valP.color = if (focus) 0x99111111.toInt() else 0x8CFFFFFF.toInt(); valP.textAlign = Paint.Align.RIGHT; c.drawText(r.value, right, cy + valP.textSize * 0.36f, valP); right -= valP.measureText(r.value) + Ui.r(0.75f); valP.textAlign = Paint.Align.LEFT }
            c.drawText(Ui.fit(p, r.label, right - tx), tx, cy + p.textSize * 0.36f, p)
            if (focus) c.restore()
            y += rh + Ui.r(0.125f) + if (r.head) Ui.r(1f) else 0f
        }
        c.restore()
    }
}
