package app.couchtv.launcher.home

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The words on screen, read from the web home's own i18n.js (bundled in the app), so both homes
 * always say the same thing: en is the base, es for Spanish, es-MX only where Mexico differs.
 */
object L {
    private val S = HashMap<String, Map<String, String>>()
    var lang = "en"; private set
    val langs = listOf(Triple("en", "English", "en-AU"), Triple("es", "Español", "es-ES"), Triple("es-MX", "Español (México)", "es-MX"))

    fun load(ctx: Context) {
        if (S.isNotEmpty()) return
        val src = try { ctx.assets.open("www/tv/i18n.js").bufferedReader().use { it.readText() } } catch (e: Exception) { return }
        val block = Regex("""S(?:\.([a-z]+)|\["([a-zA-Z-]+)"\])\s*=\s*\{(.*?)\n  \};""", RegexOption.DOT_MATCHES_ALL)
        val pair = Regex("""([A-Za-z0-9_]+):\s*"((?:[^"\\]|\\.)*)"""")
        for (m in block.findAll(src)) {
            val id = m.groupValues[1].ifEmpty { m.groupValues[2] }
            S[id] = pair.findAll(m.groupValues[3]).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
        }
    }
    private fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val out = StringBuilder(); var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                when (n) {
                    'u' -> if (i + 5 < s.length) { out.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 6; continue }
                    'n' -> { out.append('\n'); i += 2; continue }
                    else -> { out.append(n); i += 2; continue }
                }
            }
            out.append(ch); i++
        }
        return out.toString()
    }
    fun set(id: String?) { lang = if (langs.any { it.first == id }) id!! else "en" }
    fun locale(): Locale = Locale.forLanguageTag(langs.first { it.first == lang }.third)

    fun t(key: String, vararg vars: Pair<String, Any?>): String {
        var s = (if (lang == "es-MX") S["es-MX"]?.get(key) else null) ?: (if (lang != "en") S["es"]?.get(key) else null) ?: S["en"]?.get(key) ?: key
        for ((k, v) in vars) if (v != null) s = s.replace("{$k}", v.toString())
        return s
    }
}

/**
 * What the home is told and what it keeps: config.json (bundled as config.js), the values the web
 * home keeps in the launcher's "page" store (Home settings, people, app order, ...), and Home
 * settings (ST). Same keys as the web home, so the two read and write the same things.
 */
class Store(val ctx: Context) {
    private val page = ctx.getSharedPreferences("page", Context.MODE_PRIVATE)
    var cfg = JSONObject(); private set
    val home: JSONObject get() = cfg.optJSONObject("home") ?: JSONObject()
    var st = JSONObject(); private set

    fun load() {
        cfg = try {
            val js = ctx.assets.open("www/tv/config.js").bufferedReader().use { it.readText() }
            val a = js.indexOf('{'); val b = js.lastIndexOf('}')
            if (a >= 0 && b > a) JSONObject(js.substring(a, b + 1)) else JSONObject()
        } catch (e: Exception) { JSONObject() }
        // Home settings: the launcher's copy wins over the bundled one (apply.sh can send newer)
        ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getString("config", null)?.let { s ->
            try { cfg = JSONObject(s) } catch (e: Exception) {}
        }
        st = (kept("tvhome.settings") as? JSONObject)?.optJSONObject("s") ?: JSONObject()
    }

    fun kept(key: String): Any? = page.getString(key, null)?.let {
        try { org.json.JSONTokener(it).nextValue() } catch (e: Exception) { null }
    }
    fun keep(key: String, value: Any?) {
        page.edit().putString(key, if (value == null) "null" else if (value is String) JSONObject.quote(value) else value.toString()).apply()
    }
    fun saveSettings() { keep("tvhome.settings", JSONObject().put("s", st).put("updated", System.currentTimeMillis()).put("pending", true)) }

    // ---- Home settings, as the web home reads them (readConfig) ----
    val clock24 get() = (st.optString("clock").ifEmpty { home.optString("clock") }) == "24h"
    val wxShow get() = st.optBoolean("wx_show", true)
    val wxF get() = st.optString("wx_unit") == "f"
    val wxEvery get() = st.optInt("wx_every", 30).let { if (it in listOf(15, 30, 60)) it else 30 }
    val sceneMs get() = maxOf(5, st.optInt("scene_seconds", home.optInt("scene_seconds", 20))) * 1000L
    val dockApps get() = home.optInt("dock_size", 11).coerceIn(1, 11)
    val idleMinutes get() = home.optDouble("idle_minutes", 3.0).let { if (it.isNaN() || it < 1) 3.0 else it }

    data class Place(val lat: Double, val lon: Double, val name: String, val timezone: String)
    val place: Place get() {
        st.optJSONObject("place")?.let { p -> if (p.has("lat") && p.has("lon")) return Place(p.optDouble("lat"), p.optDouble("lon"), p.optString("name"), p.optString("timezone", "auto")) }
        cfg.optJSONObject("weather")?.let { w -> if (w.has("lat")) return Place(w.optDouble("lat"), w.optDouble("lon"), w.optString("place"), w.optString("timezone", "auto")) }
        return Place(-33.8688, 151.2093, "Sydney", "Australia/Sydney")
    }

    fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.opt(it) as? String }

    // ---- people ----
    data class Person(val id: String, var name: String, val colour: String? = null, val extra: JSONObject = JSONObject())
    fun people(): MutableList<Person> {
        var list = cfg.optJSONArray("people") ?: JSONArray("""[{"id":"person1","name":"Person 1"},{"id":"person2","name":"Person 2"}]""")
        (kept("tvhome.people.list") as? JSONObject)?.optJSONArray("people")?.let { list = it }
        val out = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.filter { it.optString("id").isNotEmpty() && it.optString("name").isNotEmpty() }
            .map { Person(it.optString("id"), it.optString("name"), it.optString("colour").ifEmpty { null }, it) }.toMutableList()
        val ids = strings((kept("tvhome.people.order") as? JSONObject)?.optJSONArray("ids"))
        if (ids.isNotEmpty()) out.sortBy { p -> ids.indexOf(p.id).let { if (it < 0) 999 else it } }
        return out
    }
    fun savePeople(list: List<Person>) {
        val arr = JSONArray()
        list.forEach { p -> arr.put(JSONObject().put("id", p.id).put("name", p.name).apply { p.colour?.let { put("colour", it) } }) }
        keep("tvhome.people.list", JSONObject().put("people", arr).put("pending", true))
        keep("tvhome.people.order", JSONObject().put("ids", JSONArray(list.map { it.id })).put("pending", false))
    }
    fun savePeopleOrder(list: List<Person>) = keep("tvhome.people.order", JSONObject().put("ids", JSONArray(list.map { it.id })).put("pending", true))

    var who: String
        get() = page.getString("native.who", null) ?: page.getString("watch.who", null) ?: ""
        set(v) { page.edit().putString("native.who", v).putString("watch.who", v).apply() }
}
