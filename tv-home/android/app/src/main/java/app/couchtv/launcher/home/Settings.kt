package app.couchtv.launcher.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import app.couchtv.launcher.BuildConfig
import app.couchtv.launcher.Trakt
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Home settings (app.js "Home settings"): sections on the left, their rows on the right. Up and
 * down move, OK changes a row, holding OK picks a row up to move it, Back goes back a step and then
 * closes. Saved where the web home keeps them, so both homes agree.
 */
@SuppressLint("ViewConstructor")
class SettingsScreen(ctx: Context, private val home: HomeActivity) : FrameLayout(ctx) {
    class SRow(val kind: String, val key: String, val label: String = "", val value: String? = null, val sub: String? = null,
               val checked: Boolean = false, val danger: Boolean = false, val confirm: String? = null, val thumb: (() -> Bitmap?)? = null,
               val iconPkg: String? = null, val person: Store.Person? = null, val act: (() -> Unit)? = null, val move: ((Int) -> Unit)? = null)

    private val st get() = home.store.st
    private val secs = listOf("language" to Icons.globe, "location" to Icons.pin, "weather" to Icons.cloud, "look" to Icons.look,
        "apps" to Icons.apps, "people" to Icons.person, "quick" to Icons.bolt, "about" to Icons.info)
    private var col = "secs"; private var sec = 0; private var ri = 0
    private var keyFocus: String? = null
    private var subPage: Triple<String, () -> String, () -> List<SRow>>? = null   // back key, title, rows
    private var subCrumb = ""
    private var lift: String? = null
    private var confirm: String? = null
    private var dirty = false
    private var msg = ""
    var typing = false; private set
    private var typeSpec: TypeSpec? = null
    class TypeSpec(val label: String, val value: String, val url: Boolean = false, val live: ((String) -> Unit)? = null, val submit: (String) -> Unit)
    private data class Geo(val q: String = "", val list: List<JSONObject>? = null, val busy: Boolean = false, val err: Boolean = false)
    private var geo = Geo()
    private var geoTicket = 0

    private val canvas = Board(ctx)
    private val input = EditText(ctx).apply {
        visibility = GONE; setTextColor(Color.WHITE); textSize = 0f; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.r(1.875f))
        typeface = Ui.interBold; background = null; setSingleLine(); imeOptions = EditorInfo.IME_ACTION_DONE
        setOnEditorActionListener { _, a, ev -> if (a == EditorInfo.IME_ACTION_DONE || a == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { submitTyping(); true } else false }
        addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { typeSpec?.live?.invoke(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        setOnKeyListener { _, k, e -> if (e.action == KeyEvent.ACTION_DOWN && (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN)) { stopTyping(); move(0, if (k == KeyEvent.KEYCODE_DPAD_UP) -1 else 1); true } else false }
    }
    private var shown = 0f
    private val fade = Spring(0f, 0.002f) { shown = it; alpha = it; visibility = if (it <= 0.001f) INVISIBLE else VISIBLE }
    private val thumbs = HashMap<String, Bitmap?>()

    init {
        addView(canvas, LayoutParams(-1, -1))
        addView(input, LayoutParams(Ui.ri(60f), Ui.ri(3f)).apply { leftMargin = Ui.ri(5f + 36f + 4f + 1.75f); topMargin = Ui.ri(3.75f + 8.8f) })
        visibility = INVISIBLE; fade.snap(0f)
    }

    private var backdrop: Bitmap? = null
    fun open() {
        home.zone = "settings"
        home.grab(Ui.r(2.5f), 1.4f, 1f) { b -> backdrop = b; canvas.invalidate() }
        col = "secs"; sec = 0; ri = 0; keyFocus = null; subPage = null; lift = null; confirm = null; msg = ""
        fade.to(1f, 0.3f); canvas.invalidate()
    }
    fun close() {
        if (typing) stopTyping()
        if (lift != null) dropRow()
        fade.to(0f, 0.3f)
        home.zone = "grid"
        if (dirty) { dirty = false; home.restart() } else home.paint()
    }
    private fun save() { home.store.saveSettings(); dirty = true }

    // ---------------------------------------------------------------- rows
    private fun rowsNow(): List<SRow> = subPage?.third?.invoke() ?: sectionRows(secs[sec].first)
    private fun focusable(r: SRow?) = r != null && r.kind != "head" && r.kind != "info"
    private fun head(t: String) = SRow("head", "h:$t", t)
    private fun info(key: String, label: String, value: String? = null, sub: String? = null) = SRow("info", key, label, value, sub)
    private fun sectionRows(id: String) = when (id) {
        "language" -> L.langs.map { (lid, name, _) -> SRow("choice", "lang:$lid", name, checked = L.lang == lid, act = { st.put("lang", lid); L.set(lid); save() }) } + info("lang-note", L.t("lang_note"))
        "location" -> locationRows()
        "weather" -> listOf(
            SRow("toggle", "wx-show", L.t("wx_show"), if (home.store.wxShow) L.t("on") else L.t("off"), act = { st.put("wx_show", !home.store.wxShow); save(); home.refreshWeather() }),
            SRow("cycle", "wx-unit", L.t("wx_units"), if (home.store.wxF) "°F (Fahrenheit)" else "°C (Celsius)", act = { st.put("wx_unit", if (home.store.wxF) "c" else "f"); save(); home.date.refresh(); home.shelf.invalidateGlass() }),
            SRow("cycle", "wx-every", L.t("wx_every"), L.t("every_min", "n" to home.store.wxEvery), act = { val e = home.store.wxEvery; st.put("wx_every", if (e == 15) 30 else if (e == 30) 60 else 15); save() }),
            info("wx-provider", L.t("wx_provider"), "Open-Meteo", L.t("wx_provider_note")), info("wx-night", L.t("wx_night_only")))
        "look" -> lookRows()
        "apps" -> appsRows()
        "people" -> peopleRows()
        "quick" -> quickRows()
        else -> listOf(info("ver", L.t("about_version"), BuildConfig.VERSION_NAME), info("code", L.t("about_code"), "github.com/Seagey/couch-tv"),
            info("run", L.t("about_running"), L.t("run_alone")), info("kept", L.t("about_kept"), L.t("kept_tv")),
            SRow("action", "reset", L.t("about_reset"), sub = L.t("about_reset_sub"), danger = true, confirm = L.t("confirm_reset"), act = ::resetSettings))
    }

    private fun locationRows(): List<SRow> {
        val p = home.store.place
        val rows = mutableListOf(info("loc-now", L.t("loc_now"), p.name.ifEmpty { L.t("loc_unset") }, if (p.timezone != "auto") p.timezone else null))
        rows.add(SRow("input", "loc-search", L.t("loc_search"), geo.q, act = { startTyping(TypeSpec(L.t("loc_type"), geo.q, live = { findPlaces(it, false) }, submit = { findPlaces(it, true) })) }))
        when {
            geo.busy -> rows.add(info("loc-busy", L.t("loc_searching")))
            geo.err -> rows.add(info("loc-err", L.t("loc_error")))
            geo.list?.isEmpty() == true -> rows.add(info("loc-none", L.t("loc_none", "q" to geo.q)))
        }
        geo.list?.takeIf { it.isNotEmpty() }?.let { list ->
            rows.add(head(L.t("loc_results")))
            list.forEachIndexed { i, pl ->
                val cur = st.optJSONObject("place")
                rows.add(SRow("choice", "place:$i", pl.optString("name"), sub = pl.optString("area"),
                    checked = cur != null && cur.optDouble("lat") == pl.optDouble("lat") && cur.optDouble("lon") == pl.optDouble("lon"), act = {
                        st.put("place", JSONObject().put("name", pl.optString("name")).put("lat", pl.optDouble("lat")).put("lon", pl.optDouble("lon")).put("timezone", pl.optString("timezone")))
                        save(); msg = L.t("loc_set", "place" to pl.optString("name")); home.refreshWeather()
                    }))
            }
        }
        if (st.has("place")) rows.add(SRow("action", "loc-reset", L.t("loc_reset"), act = { st.remove("place"); save(); msg = L.t("loc_set", "place" to home.store.place.name); home.refreshWeather() }))
        rows.add(info("loc-note", L.t("loc_note")))
        return rows
    }
    private fun findPlaces(q0: String, go: Boolean) {
        val q = q0.trim(); geo = geo.copy(q = q)
        removeCallbacks(geoRun)
        if (q.length < 2) { geo = Geo(q); canvas.invalidate(); return }
        geoRun = Runnable {
            val ticket = ++geoTicket
            geo = geo.copy(busy = true, err = false); canvas.invalidate()
            home.io.execute {
                val list: List<JSONObject>? = try {
                    val lang = L.lang.take(2)
                    val c = java.net.URL("https://geocoding-api.open-meteo.com/v1/search?count=8&format=json&language=$lang&name=" + java.net.URLEncoder.encode(q, "UTF-8")).openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 5000; c.readTimeout = 8000
                    val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() }); c.disconnect()
                    val a = j.optJSONArray("results") ?: JSONArray()
                    (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { r ->
                        // two decimals is about a kilometre: the town, never the house
                        JSONObject().put("name", r.optString("name")).put("area", listOf(r.optString("admin1"), r.optString("country")).filter { it.isNotEmpty() }.joinToString(", "))
                            .put("lat", Math.round(r.optDouble("latitude") * 100) / 100.0).put("lon", Math.round(r.optDouble("longitude") * 100) / 100.0).put("timezone", r.optString("timezone", "auto"))
                    }
                } catch (e: Exception) { null }
                post {
                    if (ticket != geoTicket) return@post
                    geo = if (list == null) Geo(q, null, false, true) else Geo(q, list)
                    if (go && list?.isNotEmpty() == true && !typing) { col = "rows"; keyFocus = "place:0" }
                    canvas.invalidate()
                }
            }
        }
        if (go) geoRun.run() else postDelayed(geoRun, 500)
    }
    private var geoRun = Runnable {}

    private fun lookRows(): List<SRow> {
        val all = Scenes.builtIn + Scenes.mine(context) + Scenes.own(context, st)
        val (order, off) = Scenes.state(all, st, home.store.home)
        val rows = mutableListOf(head(L.t("scenes_head")))
        order.forEach { id ->
            val sc = all.firstOrNull { it.id == id } ?: return@forEach
            rows.add(SRow("check", "scene:$id", if (sc.own) L.t("scene_photo", "n" to sc.n) else sc.name.ifEmpty { L.t("scene_$id") }, checked = id !in off,
                thumb = { thumb(sc) }, act = { toggleScene(id) }, move = { d -> moveScene(id, d) }))
        }
        val secsNow = (home.store.sceneMs / 1000).toInt()
        val steps = listOf(10, 15, 20, 30, 45, 60, 120)
        rows.add(SRow("cycle", "scene-secs", L.t("scene_secs"), L.t("n_secs", "n" to secsNow), act = { val i = steps.indexOf(secsNow); st.put("scene_seconds", if (i < 0) 20 else steps[(i + 1) % steps.size]); save(); home.buildScenes() }))
        rows.add(SRow("cycle", "clock", L.t("clock"), if (home.store.clock24) L.t("clock_24") else L.t("clock_12"), act = { st.put("clock", if (home.store.clock24) "12h" else "24h"); save() }))
        rows.add(head(L.t("photos_head")))
        if (pickIntent() != null) rows.add(SRow("action", "photo-pick", L.t("photos_pick"), sub = L.t("photos_pick_sub"), act = { pickPhotos() }))
        else rows.add(info("photo-nopick", L.t("photos_no_pick"), null, L.t("photos_no_pick_sub")))
        rows.add(SRow("input", "photo-url", L.t("photos_url"), act = { startTyping(TypeSpec(L.t("photos_url_type"), "", url = true, submit = ::addPhotoUrl)) }))
        val photos = st.optJSONArray("photos") ?: JSONArray()
        (0 until photos.length()).mapNotNull { photos.optJSONObject(it) }.forEachIndexed { i, p ->
            val sc = Scenes.own(context, JSONObject().put("photos", JSONArray().put(p))).firstOrNull()
            rows.add(SRow("action", "photo-rm:" + p.optString("id"), L.t("photo_remove", "n" to i + 1), danger = true, thumb = { sc?.let { thumb(it) } }, act = { removePhoto(p.optString("id")) }))
        }
        rows.add(info("photos-note", L.t("photos_note")))
        return rows
    }
    private fun thumb(sc: Scene): Bitmap? = thumbs.getOrPut(sc.id) {
        try {
            val o = BitmapFactory.Options().apply { inSampleSize = 8 }
            when {
                sc.photo != null -> context.assets.open("www/tv/backgrounds/${sc.photo}.jpg").use { BitmapFactory.decodeStream(it, null, o) }
                sc.file != null -> BitmapFactory.decodeFile(sc.file.path, o)
                else -> null
            }
        } catch (e: Exception) { null }
    }
    private fun sceneState() = Scenes.state(Scenes.builtIn + Scenes.mine(context) + Scenes.own(context, st), st, home.store.home).let { it.first.toMutableList() to it.second.toMutableList() }
    private fun setScenes(order: List<String>, off: List<String>) { st.put("scene_order", JSONArray(order)); st.put("scenes_off", JSONArray(off)); save(); home.buildScenes() }
    private fun toggleScene(id: String) {
        val (order, off) = sceneState()
        if (id in off) off.remove(id) else if (order.size - off.size <= 1) { msg = L.t("one_scene"); return } else off.add(id)
        setScenes(order, off)
    }
    private fun moveScene(id: String, d: Int) {
        val (order, off) = sceneState(); val i = order.indexOf(id); val j = i + d
        if (i < 0 || j < 0 || j >= order.size) return
        order[i] = order[j]; order[j] = id; setScenes(order, off)
    }
    private fun pickIntent(): Intent? {
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        if (context.packageManager.resolveActivity(open, 0) != null) return open
        val get = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        return if (context.packageManager.resolveActivity(get, 0) != null) get else null
    }
    private fun pickPhotos() { val i = pickIntent() ?: run { msg = L.t("photos_no_pick"); return }; try { home.startActivityForResult(i, PICK) } catch (e: Exception) { msg = L.t("photos_no_pick") } }
    fun photosPicked(uris: List<Uri>) {
        home.io.execute {
            val names = uris.take(20).mapNotNull { Photos.copy(context, it) }
            post {
                val photos = st.optJSONArray("photos") ?: JSONArray()
                var n = 0
                names.forEach { f -> if (photos.length() < 20) { photos.put(JSONObject().put("id", uid()).put("file", f)); n++ } }
                if (n > 0) { st.put("photos", photos); save(); msg = if (n == 1) L.t("photos_added") else L.t("photos_added_n", "n" to n) }
                canvas.invalidate()
            }
        }
    }
    private fun uid() = java.lang.Long.toString(System.currentTimeMillis(), 36) + java.lang.Long.toString((Math.random() * 1296).toLong(), 36)
    private fun addPhotoUrl(u0: String) {
        var u = u0.trim(); if (u.isEmpty()) return
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(u)) u = "https://$u"
        if ((st.optJSONArray("photos")?.length() ?: 0) >= 20) { msg = L.t("photos_full"); return }
        msg = L.t("photos_checking"); canvas.invalidate()
        home.io.execute {
            val ok = Net.bitmap(context, u, 640) != null
            post {
                if (ok) { st.put("photos", (st.optJSONArray("photos") ?: JSONArray()).put(JSONObject().put("id", uid()).put("url", u))); save(); msg = L.t("photos_added") } else msg = L.t("photos_bad")
                canvas.invalidate()
            }
        }
    }
    private fun removePhoto(id: String) {
        val a = st.optJSONArray("photos") ?: return
        val keep = JSONArray()
        for (i in 0 until a.length()) { val p = a.optJSONObject(i) ?: continue; if (p.optString("id") == id) { p.optString("file").ifEmpty { null }?.let { Photos.remove(context, it) } } else keep.put(p) }
        st.put("photos", keep); save()
    }

    private fun appsRows(): List<SRow> {
        val apps = home.apps
        val (items, line) = apps.arrange()
        val hidden = apps.all.filter { it.pkg in apps.hiddenTv }
        if (items.isEmpty() && hidden.isEmpty()) return listOf(info("apps-none", L.t("apps_none")))
        fun row(it: AppItem, where: String) = SRow("item", "app:" + it.pkg, it.name, L.t(where), iconPkg = it.pkg, act = { cycleApp(it.pkg) },
            move = if (where == "app_hidden") null else { d -> moveAppRow(it.pkg, d) })
        val rows = mutableListOf(head(L.t("apps_home", "n" to line, "max" to apps.dockSize)))
        items.take(line).forEach { rows.add(row(it, "app_on_home")) }
        rows.add(head(L.t("apps_more")))
        items.drop(line).forEach { rows.add(row(it, "app_in_more")) }
        if (hidden.isNotEmpty()) { rows.add(head(L.t("apps_hidden"))); hidden.forEach { rows.add(row(it, "app_hidden")) } }
        rows.add(info("apps-note", L.t("apps_note")))
        return rows
    }
    private fun setHidden(list: List<String>) { st.put("hidden_apps", JSONArray(list)); save() }
    private fun cycleApp(pkg: String) {
        val apps = home.apps
        if (pkg in apps.hiddenTv) { setHidden(apps.hiddenTv - pkg); apps.materialise(); toHome(pkg) }
        else {
            val o = apps.materialise(); val i = o.packages.indexOf(pkg); if (i < 0) return
            if (i < o.line) { o.packages.removeAt(i); o.line -= 1; o.packages.add(o.line, pkg); apps.persist() }
            else { o.packages.removeAt(i); setHidden(apps.hiddenTv + pkg); apps.persist() }
        }
        dirty = true; home.dock.render()
    }
    private fun toHome(pkg: String) {
        val apps = home.apps; val o = apps.order ?: return
        o.packages.remove(pkg)
        if (o.line < apps.dockSize) { o.packages.add(o.line, pkg); o.line += 1 }
        else { o.packages.add(o.line - 1, pkg); msg = L.t("moved_more", "name" to apps.nameOf(o.packages[o.line])) }
        apps.persist()
    }
    private fun moveAppRow(pkg: String, d: Int) {
        val o = home.apps.order ?: return; val i = o.packages.indexOf(pkg); val j = i + d
        if (i < 0) return
        fun swap(a: Int, b: Int) { val x = o.packages[a]; o.packages[a] = o.packages[b]; o.packages[b] = x }
        if (d > 0 && i == o.line - 1) o.line -= 1
        else if (d < 0 && i == o.line) { if (o.line < home.apps.dockSize) o.line += 1 else swap(i, i - 1) }
        else if (j >= 0 && j < o.packages.size) swap(i, j)
        home.dock.render()
    }

    private fun peopleRows(): List<SRow> {
        val list = home.people
        val rows = mutableListOf<SRow>()
        if (list.isEmpty()) rows.add(info("people-none", L.t("people_none")))
        list.forEach { p -> rows.add(SRow("nav", "person:" + p.id, p.name, sub = if (p.id == home.who && list.size > 1) L.t("person_on") else null, person = p,
            act = { openPerson(p.id) }, move = { d -> movePerson(p.id, d) })) }
        if (list.size < 12) rows.add(SRow("input", "people-add", L.t("people_add"), act = { startTyping(TypeSpec(L.t("people_type"), "", submit = ::addPerson)) }))
        else rows.add(info("people-full", L.t("people_full")))
        rows.add(info("people-note", L.t("people_note")))
        return rows
    }
    private fun openPerson(id: String) {
        subCrumb = L.t("sec_people")
        subPage = Triple("person:$id", { home.people.firstOrNull { it.id == id }?.name ?: "" }, {
            val p = home.people.firstOrNull { it.id == id }
            if (p == null) emptyList() else listOf(SRow("input", "p-name", L.t("person_name"), p.name, act = { startTyping(TypeSpec(L.t("person_rename_type"), p.name, submit = { v -> renamePerson(id, v) })) })) +
                traktRows(id) + SRow("action", "p-remove", L.t("person_remove", "name" to p.name), danger = true, act = { removePerson(id) })
        })
        keyFocus = "p-name"
    }
    private fun traktRows(pid: String): List<SRow> {
        if (!Trakt.ready(context)) return emptyList()
        val ts = try { JSONObject(Trakt.state(context, pid)) } catch (e: Exception) { JSONObject() }
        return when {
            ts.optBoolean("linked") -> listOf(info("trakt-on", "Trakt", ts.optString("user"), L.t("trakt_about")),
                SRow("action", "trakt-unlink", L.t("trakt_unlink"), danger = true, act = { Trakt.unlink(context, pid); home.refresh() }))
            ts.optBoolean("linking") -> listOf(info("trakt-code", L.t("trakt_go", "url" to ts.optString("url", "https://trakt.tv/activate").replace(Regex("^https?://"), "")), ts.optString("code")),
                SRow("action", "trakt-cancel", L.t("trakt_cancel"), act = { Trakt.cancel(pid) }))
            else -> listOf(SRow("action", "trakt-link", L.t("trakt_link"), sub = L.t("trakt_about"), act = { Trakt.link(context, pid) }))
        }
    }
    fun traktChanged() { if (home.zone == "settings") canvas.invalidate() }
    private fun cleanName(v: String) = v.replace(Regex("\\s+"), " ").trim().take(40)
    private fun savePeople() { home.store.savePeople(home.people); dirty = true; home.refresh() }
    private fun addPerson(v: String) {
        val name = cleanName(v); if (name.isEmpty()) return
        if (home.people.size >= 12) { msg = L.t("people_full"); return }
        var base = java.text.Normalizer.normalize(name.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("[\\u0300-\\u036f]"), "")
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).ifEmpty { "person" }
        if (base in listOf("both", "all", "everyone", "shared", "together")) base += "-1"
        var id = base; var n = 2
        while (home.people.any { it.id.lowercase() == id }) id = base + "-" + n++
        home.people.add(Store.Person(id, name)); savePeople(); keyFocus = "person:$id"
    }
    private fun renamePerson(id: String, v: String) { val n = cleanName(v); val p = home.people.firstOrNull { it.id == id } ?: return; if (n.isEmpty()) return; p.name = n; savePeople() }
    private fun removePerson(id: String) { home.people.removeAll { it.id == id }; if (home.who == id) home.pickWho(home.people.firstOrNull()?.id ?: "both"); savePeople(); subPage = null; keyFocus = "people-add" }
    private fun movePerson(id: String, d: Int) {
        val l = home.people; val i = l.indexOfFirst { it.id == id }; val j = i + d
        if (i < 0 || j < 0 || j >= l.size) return
        val x = l[i]; l[i] = l[j]; l[j] = x; home.tab.invalidate()
    }

    private fun quickNow(): MutableList<String> = st.optJSONArray("quick")?.let { a -> (0 until a.length()).map { a.optString(it) } }?.toMutableList()
        ?: home.store.strings(home.store.cfg.optJSONArray("tvs")?.optJSONObject(0)?.optJSONArray("quick")).ifEmpty { listOf("sleep", "picture") }.toMutableList()
    private fun quickRows(): List<SRow> {
        val on = quickNow(); val order = on + listOf("sleep", "picture", "input").filter { it !in on }
        return order.map { q ->
            val enabled = q in on
            SRow("check", "quick:$q", L.t("quick_$q"), checked = enabled, sub = when (q) { "sleep" -> L.t("quick_needs_bridge"); "picture" -> L.t("quick_needs_picture"); else -> if (home.side.inputsIntent() == null) L.t("quick_needs_inputs") else null },
                act = { val l = quickNow(); if (q in l) l.remove(q) else l.add(q); st.put("quick", JSONArray(l)); save() },
                move = if (enabled) { d -> val l = quickNow(); val i = l.indexOf(q); val j = i + d; if (i >= 0 && j >= 0 && j < l.size) { l[i] = l[j]; l[j] = q; st.put("quick", JSONArray(l)); save() } } else null)
        } + info("quick-note", L.t("quick_note"))
    }
    private fun resetSettings() {
        val photos = st.optJSONArray("photos") ?: JSONArray()
        for (i in 0 until photos.length()) photos.optJSONObject(i)?.optString("file")?.ifEmpty { null }?.let { Photos.remove(context, it) }
        val hidden = st.optJSONArray("hidden_apps")
        val keys = st.keys().asSequence().toList(); keys.forEach { st.remove(it) }
        hidden?.let { st.put("hidden_apps", it) }
        home.store.keep("tvhome.look", null)
        L.set(home.store.home.optString("language", "en"))
        save(); msg = L.t("reset_done")
    }

    // ---------------------------------------------------------------- typing
    private fun startTyping(spec: TypeSpec) {
        typeSpec = spec; typing = true
        input.inputType = if (spec.url) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        input.setText(spec.value); input.setSelection(input.text.length)
        input.visibility = VISIBLE; input.requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0)
        canvas.invalidate()
    }
    private fun stopTyping() {
        typing = false; typeSpec = null
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        input.visibility = GONE; input.clearFocus(); canvas.invalidate()
    }
    private fun submitTyping() { val s = typeSpec ?: return; val v = input.text.toString(); stopTyping(); s.submit(v); canvas.invalidate() }

    // ---------------------------------------------------------------- keys
    private fun firstRow(rows: List<SRow>) = rows.indexOfFirst { focusable(it) }
    private fun stepRow(rows: List<SRow>, i: Int, d: Int): Int { var j = i + d; while (j in rows.indices) { if (focusable(rows[j])) return j; j += d }; return i }
    fun move(dx: Int, dy: Int) {
        val rows = rowsNow()
        if (lift != null) { if (dy != 0) { rows.firstOrNull { it.key == lift }?.move?.invoke(dy); keyFocus = lift }; canvas.invalidate(); return }
        confirm = null
        if (col == "secs") {
            if (dy != 0) { val n = (sec + dy).coerceIn(0, secs.size - 1); if (n != sec) { sec = n; subPage = null; msg = ""; keyFocus = null; ri = 0 } }
            else if (dx > 0) enterRows()
        } else if (dy != 0) { ri = stepRow(rows, ri, dy); keyFocus = rows.getOrNull(ri)?.key }
        else if (dx < 0) leaveRows()
        canvas.invalidate()
    }
    private fun enterRows() { val rows = rowsNow(); val i = firstRow(rows); if (i < 0) return; col = "rows"; ri = i; keyFocus = rows[i].key }
    private fun leaveRows() { if (subPage != null) { keyFocus = subPage!!.first; subPage = null; return }; col = "secs"; keyFocus = null }
    fun activate() {
        if (lift != null) { dropRow(); return }
        if (col == "secs") { enterRows(); canvas.invalidate(); return }
        val r = rowsNow().getOrNull(ri) ?: return
        if (!focusable(r) || r.act == null) return
        if (r.danger && confirm != r.key) { confirm = r.key; canvas.invalidate(); return }
        confirm = null; keyFocus = r.key
        r.act.invoke()
        canvas.invalidate()
    }
    fun hold(): Boolean {
        if (lift != null) { dropRow(); return true }
        if (col != "rows") return false
        val r = rowsNow().getOrNull(ri) ?: return false
        if (r.move == null) return false
        confirm = null
        if (r.key.startsWith("app:")) home.apps.materialise()
        lift = r.key; canvas.invalidate(); return true
    }
    private fun dropRow() {
        val k = lift ?: ""; lift = null
        if (k.startsWith("app:")) { home.apps.persist(); dirty = true }
        if (k.startsWith("person:")) savePeople()
        canvas.invalidate()
    }
    fun back() {
        if (typing) { stopTyping(); return }
        if (lift != null) { dropRow(); return }
        confirm = null
        if (col == "rows") { leaveRows(); canvas.invalidate(); return }
        close()
    }

    // ---------------------------------------------------------------- drawing
    inner class Board(ctx: Context) : View(ctx) {
        private val face = Glass.Face()
        private val title = Ui.paint(Ui.interBold, 2.875f)
        private val secP = Ui.paint(Ui.interBold, 1.75f, 0xC2FFFFFF.toInt())
        private val crumb = Ui.paint(Ui.interBold, 1.3125f, 0x80FFFFFF.toInt())
        private val headP = Ui.paint(Ui.interBold, 2.375f)
        private val rowP = Ui.paint(Ui.interBold, 1.75f)
        private val subP = Ui.paint(Ui.inter, 1.25f, 0x99FFFFFF.toInt())
        private val valP = Ui.paint(Ui.interMid, 1.75f, 0x9EFFFFFF.toInt())
        private val shP = Ui.paint(Ui.interBold, 1.25f, 0x80FFFFFF.toInt()).apply { letterSpacing = 0.06f }
        private val infoP = Ui.paint(Ui.interMid, 1.4375f, 0xADFFFFFF.toInt())
        private val msgP = Ui.paint(Ui.interMid, 1.4375f, 0xFF8FD3FF.toInt())
        private val hintP = Ui.paint(Ui.inter, 1.25f, 0x80FFFFFF.toInt())
        private val typeLabel = Ui.paint(Ui.interBold, 1.3125f, 0xB3FFFFFF.toInt())
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val icP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private var scrollY = 0f
        private val scroll = Spring(0f, 0.5f) { scrollY = it; invalidate() }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            face.draw(c, w, h, 0f, 0f, 0f, 0xCC0A080C.toInt(), backdrop = backdrop)
            val sx = Ui.r(5f); val sy = Ui.r(3.75f)
            // sections
            c.drawText(L.t("set_title"), sx + Ui.r(1.25f), sy + Ui.r(2.25f + 2.9f), title)
            var y = sy + Ui.r(2.25f + 3.75f + 2.5f)
            secs.forEachIndexed { i, (id, icon) ->
                val hh = Ui.r(4.75f); val f = col == "secs" && i == sec; val on = i == sec && !f
                if (f || on) { fill.color = if (f) Color.WHITE else 0x21FFFFFF; c.drawRoundRect(sx, y, sx + Ui.r(36f), y + hh, Ui.r(1.5f), Ui.r(1.5f), fill) }
                val ink = if (f) 0xFF111111.toInt() else if (on) Color.WHITE else 0xC2FFFFFF.toInt()
                fill.color = if (f) 0x12000000 else 0x1AFFFFFF; c.drawRoundRect(sx + Ui.r(0.875f), y + Ui.r(0.75f), sx + Ui.r(4.125f), y + Ui.r(4f), Ui.r(1f), Ui.r(1f), fill)
                icon.draw(c, sx + Ui.r(0.875f + 0.6875f), y + Ui.r(0.75f + 0.6875f), Ui.r(1.875f), ink)
                secP.color = ink; c.drawText(L.t("sec_$id"), sx + Ui.r(5.375f), y + hh / 2 + secP.textSize * 0.36f, secP)
                y += hh + Ui.r(0.375f)
            }
            // the rows
            val mx = sx + Ui.r(36f + 4f); val mw = w - mx - sx
            c.drawText(if (subPage != null) subCrumb else "", mx + Ui.r(1.75f), sy + Ui.r(0.75f + 1.5f), crumb)
            c.drawText(subPage?.second?.invoke() ?: L.t("sec_" + secs[sec].first), mx + Ui.r(1.75f), sy + Ui.r(0.75f + 2f + 0.25f + 2.4f), headP)
            var top = sy + Ui.r(0.75f + 2f + 0.25f + 3f + 1.5f)
            if (typing) {
                val th = Ui.r(5.75f)
                fill.color = 0x1FFFFFFF; c.drawRoundRect(mx, top, mx + mw, top + th, Ui.r(1.5f), Ui.r(1.5f), fill)
                fill.style = Paint.Style.STROKE; fill.strokeWidth = Ui.r(0.1875f); fill.color = Color.WHITE; c.drawRoundRect(mx, top, mx + mw, top + th, Ui.r(1.5f), Ui.r(1.5f), fill); fill.style = Paint.Style.FILL
                c.drawText(typeSpec?.label ?: "", mx + Ui.r(1.75f), top + Ui.r(0.875f + 1.3f), typeLabel)
                input.translationY = top + Ui.r(2.75f) - (input.layoutParams as LayoutParams).topMargin
                input.translationX = mx + Ui.r(1.75f) - (input.layoutParams as LayoutParams).leftMargin
                top += th + Ui.r(1.25f)
            }
            val rows = rowsNow()
            if (col == "rows") {
                val k = keyFocus?.let { kf -> rows.indexOfFirst { it.key == kf } } ?: -1
                if (k >= 0 && focusable(rows[k])) ri = k else { ri = ri.coerceIn(0, maxOf(0, rows.size - 1)); if (!focusable(rows.getOrNull(ri))) { val b = stepRow(rows, ri, -1); ri = if (b != ri) b else stepRow(rows, ri, 1) } }
                if (focusable(rows.getOrNull(ri))) keyFocus = rows[ri].key else col = "secs"
            }
            val bottom = h - sy - Ui.r(5.5f)
            val view = bottom - top
            // lay the rows out, then keep the focused one well inside the list
            val ys = FloatArray(rows.size); val hs = FloatArray(rows.size); var yy = 0f
            rows.forEachIndexed { i, r ->
                val rh = when (r.kind) { "head" -> Ui.r(1.125f + 1.75f + 0.125f); "info" -> Ui.r(0.5f * 2 + 1.875f) + (if (r.sub != null) Ui.r(1.625f) else 0f); else -> Ui.r(4.75f) + (if (r.sub != null) Ui.r(1.625f) else 0f) }
                ys[i] = yy; hs[i] = rh; yy += rh + Ui.r(0.5f)
            }
            if (col == "rows" && ri in rows.indices) {
                val ft = ys[ri]; val fb = ft + hs[ri]; val edge = view / 4
                var target = scroll.t
                if (ft - edge < target) target = maxOf(0f, ft - edge) else if (fb + edge > target + view) target = fb + edge - view
                if (target != scroll.t) scroll.to(target, 0.3f)
            } else if (scroll.t != 0f) scroll.to(0f, 0.3f)
            c.save(); c.clipRect(mx - Ui.r(1.75f), top - Ui.r(1f), w, bottom + Ui.r(1f))
            rows.forEachIndexed { i, r -> drawRow(c, r, i, mx, top + ys[i] - scrollY, mw, hs[i]) }
            c.restore()
            c.drawText(msg, mx + Ui.r(1.75f), bottom + Ui.r(1.5f + 1.5f), msgP)
            val cur = rows.getOrNull(ri)
            c.drawText(when { typing -> L.t("hint_typing"); lift != null -> L.t("hint_lift"); col == "secs" -> L.t("hint_secs"); cur?.move != null -> L.t("hint_rows_move"); else -> L.t("hint_rows") }, mx + Ui.r(1.75f), bottom + Ui.r(3.5f + 1.4f), hintP)
        }
        private fun drawRow(c: Canvas, r: SRow, i: Int, x: Float, y: Float, w: Float, h: Float) {
            if (r.kind == "head") { c.drawText(r.label.uppercase(L.locale()), x + Ui.r(1.75f), y + Ui.r(1.125f + 1.35f), shP); return }
            if (r.kind == "info") {
                c.drawText(Ui.fit(infoP, r.label, w * 0.6f), x + Ui.r(1.75f), y + Ui.r(0.5f + 1.4f), infoP)
                r.value?.let { infoP.textAlign = Paint.Align.RIGHT; c.drawText(Ui.fit(infoP, it, w * 0.45f), x + w - Ui.r(1.75f), y + Ui.r(0.5f + 1.4f), infoP); infoP.textAlign = Paint.Align.LEFT }
                r.sub?.let { c.drawText(Ui.fit(subP, it, w - Ui.r(3.5f)), x + Ui.r(1.75f), y + Ui.r(0.5f + 1.875f + 1.3f), subP) }
                return
            }
            val f = col == "rows" && i == ri; val lifted = lift == r.key
            c.save()
            if (f || lifted) c.scale(if (lifted) 1.04f else 1.02f, if (lifted) 1.04f else 1.02f, x + w / 2, y + h / 2)
            if (lifted) c.translate(Ui.r(0.75f), 0f)
            fill.color = if (f) Color.WHITE else 0x14FFFFFF
            if (f) fill.setShadowLayer(Ui.r(2.5f), 0f, Ui.r(1.25f), 0x73000000)
            c.drawRoundRect(x, y, x + w, y + h, Ui.r(1.5f), Ui.r(1.5f), fill); fill.clearShadowLayer()
            val ink = if (f) 0xFF111111.toInt() else 0xEBFFFFFF.toInt()
            var tx = x + Ui.r(1.75f); val cy = y + h / 2
            when {
                r.person != null -> { Avatar.draw(context, c, r.person, tx + Ui.r(1.5f), cy, Ui.r(3f)); tx += Ui.r(3f + 1.25f) }
                r.iconPkg != null -> { val ic = home.apps.cachedIcon(r.iconPkg); val sz = Ui.r(3f)
                    if (ic != null) { val n = c.save(); c.clipPath(Path().apply { addRoundRect(tx, cy - sz / 2, tx + sz, cy + sz / 2, Ui.r(0.75f), Ui.r(0.75f), Path.Direction.CW) }); c.drawBitmap(ic, null, RectF(tx, cy - sz / 2, tx + sz, cy + sz / 2), icP); c.restoreToCount(n) }
                    else { fill.color = if (f) 0x14000000 else 0x24FFFFFF; c.drawRoundRect(tx, cy - sz / 2, tx + sz, cy + sz / 2, Ui.r(0.75f), Ui.r(0.75f), fill) }
                    tx += sz + Ui.r(1.25f) }
                r.thumb != null -> { r.thumb.invoke()?.let { b -> val tw = Ui.r(6.5f); val th = Ui.r(3.6f); val n = c.save()
                    c.clipPath(Path().apply { addRoundRect(tx, cy - th / 2, tx + tw, cy + th / 2, Ui.r(0.6f), Ui.r(0.6f), Path.Direction.CW) }); c.drawBitmap(b, null, RectF(tx, cy - th / 2, tx + tw, cy + th / 2), icP); c.restoreToCount(n); tx += tw + Ui.r(1.25f) } }
            }
            var right = x + w - Ui.r(1.75f)
            when {
                lifted -> { Icons.updown.draw(c, right - Ui.r(1.875f), cy - Ui.r(0.9375f), Ui.r(1.875f), ink, 2.2f); right -= Ui.r(3f) }
                r.kind == "choice" -> if (r.checked) { Icons.check.draw(c, right - Ui.r(1.875f), cy - Ui.r(0.9375f), Ui.r(1.875f), ink, 2.2f); right -= Ui.r(3f) }
                r.kind == "check" -> { val d = Ui.r(2.25f); val bx = right - d / 2
                    if (r.checked) { fill.color = 0xFF30D158.toInt(); c.drawCircle(bx, cy, d / 2, fill); Icons.check.draw(c, bx - Ui.r(0.6875f), cy - Ui.r(0.6875f), Ui.r(1.375f), Color.WHITE, 2.8f) }
                    else { fill.style = Paint.Style.STROKE; fill.strokeWidth = Ui.r(0.1875f); fill.color = if (f) 0x52000000 else 0x6BFFFFFF; c.drawCircle(bx, cy, d / 2 - fill.strokeWidth / 2, fill); fill.style = Paint.Style.FILL }
                    right -= d + Ui.r(1.25f) }
                r.kind == "nav" || r.kind == "input" -> { Icons.chevron.draw(c, right - Ui.r(1.875f), cy - Ui.r(0.9375f), Ui.r(1.875f), Ui.alpha(ink, 0.45f), 2.2f); right -= Ui.r(3f) }
            }
            r.value?.takeIf { it.isNotEmpty() }?.let { v -> valP.color = if (f) 0x99000000.toInt() else 0x9EFFFFFF.toInt(); valP.textAlign = Paint.Align.RIGHT
                val vv = Ui.fit(valP, v, w * 0.45f); c.drawText(vv, right, cy + valP.textSize * 0.36f, valP); right -= valP.measureText(vv) + Ui.r(1.25f); valP.textAlign = Paint.Align.LEFT }
            val label = if (confirm == r.key) r.confirm ?: L.t("confirm_remove") else r.label
            rowP.color = if (r.danger) (if (f) 0xFFC4271B.toInt() else 0xFFFF7B72.toInt()) else ink
            val ly = if (r.sub != null) cy - Ui.r(0.35f) else cy + rowP.textSize * 0.36f
            c.drawText(Ui.fit(rowP, label, right - tx), tx, ly, rowP)
            r.sub?.let { subP.color = if (f) 0x99000000.toInt() else 0x99FFFFFF.toInt(); c.drawText(Ui.fit(subP, it, right - tx), tx, ly + Ui.r(1.75f), subP) }
            c.restore()
        }
    }

    companion object { const val PICK = 4711 }
}

/** A photo chosen in Home settings, copied into the app the right way up, no bigger than the screen needs. */
object Photos {
    private fun dir(ctx: Context) = File(ctx.filesDir, "photos").apply { mkdirs() }
    fun remove(ctx: Context, name: String) { if (Regex("[A-Za-z0-9_-]{1,64}\\.jpg").matches(name)) File(dir(ctx), name).delete() }
    fun copy(ctx: Context, uri: Uri): String? = try {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 1920 && bounds.outHeight / (sample * 2) >= 1080) sample *= 2
            val raw = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            if (raw == null) null else {
                val turn = try { cr.openInputStream(uri)?.use { when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f } } ?: 0f } catch (e: Exception) { 0f }
                val w = if (turn == 90f || turn == 270f) raw.height else raw.width
                val h = if (turn == 90f || turn == 270f) raw.width else raw.height
                val scale = minOf(1f, maxOf(1920f / w, 1080f / h))
                val bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(turn); postScale(scale, scale) }, true)
                val name = "p" + java.lang.Long.toString(System.currentTimeMillis(), 36) + "-" + (1000 + (Math.random() * 9000).toInt()) + ".jpg"
                File(dir(ctx), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                if (bmp !== raw) bmp.recycle(); raw.recycle()
                name
            }
        }
    } catch (e: Exception) { null }
}
