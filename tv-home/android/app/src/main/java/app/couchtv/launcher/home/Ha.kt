package app.couchtv.launcher.home

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.util.concurrent.Executors

/**
 * Home Assistant for the native home: the living room (ha.json "room": lights, scenes, climate),
 * the sidebar's scenes and the Now Playing speakers. The address and token stay in the launcher
 * (prefs "home", key "ha", sent by apply.sh). Only the entities named in ha.json are touched.
 */
class Ha(private val ctx: Context) {
    init {
        // pictures Home Assistant serves (a player's artwork through its media proxy) are asked
        // for with the same login as everything else
        Net.authFor = { u -> cfg()?.let { h -> val base = h.optString("url").trimEnd('/'); if (base.isNotEmpty() && u.startsWith(base)) "Bearer " + h.optString("token") else null } }
    }
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private fun cfg(): JSONObject? = ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getString("ha", null)?.let { try { JSONObject(it) } catch (e: Exception) { null } }
    val on get() = cfg() != null

    private fun get(h: JSONObject, path: String): JSONObject? = try {
        val c = java.net.URL(h.getString("url").trimEnd('/') + path).openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 5000
        c.setRequestProperty("Authorization", "Bearer " + h.optString("token"))
        val o = if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().use { it.readText() }) else null
        c.disconnect(); o
    } catch (e: Exception) { null }
    private fun post(h: JSONObject, path: String, body: JSONObject): Boolean = try {
        val c = java.net.URL(h.getString("url").trimEnd('/') + "/api/services/" + path).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 4000; c.readTimeout = 8000
        c.setRequestProperty("Authorization", "Bearer " + h.optString("token"))
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val ok = c.responseCode in 200..299; c.disconnect(); ok
    } catch (e: Exception) { false }

    /** The answer Home Assistant gave: its status and the start of what it said. */
    private fun postFor(h: JSONObject, path: String, body: JSONObject): String = try {
        val c = java.net.URL(h.getString("url").trimEnd('/') + "/api/services/" + path).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 4000; c.readTimeout = 8000
        c.setRequestProperty("Authorization", "Bearer " + h.optString("token"))
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val txt = ((if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "").take(160)
        c.disconnect(); "$code $txt"
    } catch (e: Exception) { "failed: $e" }

    // ---- the living room ----
    data class Light(val id: String, val name: String, var on: Boolean, val available: Boolean, var brightness: Int?)
    data class Climate(val name: String, var mode: String, var action: String, val current: Double?, var target: Double?, val min: Double, val max: Double, val step: Double)
    data class Room(val label: String, val lights: List<Light>, val scenes: List<Pair<String, String>>, val climate: Climate?)

    fun room(done: (Room?) -> Unit) {
        val h = cfg() ?: return main.post { done(null) }.let { }
        val r = h.optJSONObject("room") ?: return main.post { done(null) }.let { }
        io.execute {
            val ids = r.optJSONArray("lights") ?: JSONArray()
            val lights = (0 until ids.length()).mapNotNull { i ->
                val id = ids.optString(i); val st = get(h, "/api/states/$id") ?: return@mapNotNull null
                val a = st.optJSONObject("attributes") ?: JSONObject(); val b = a.optInt("brightness", -1)
                Light(id, a.optString("friendly_name", id), st.optString("state") == "on", st.optString("state") != "unavailable", if (b >= 0) Math.round(b * 100 / 255f) else null)
            }
            val sc = r.optJSONArray("scenes") ?: JSONArray()
            val scenes = (0 until sc.length()).mapNotNull { sc.optJSONObject(it) }.map { it.optString("id") to it.optString("name", it.optString("id")) }
            val climate = r.optString("climate").ifEmpty { null }?.let { id -> get(h, "/api/states/$id") }?.let { st ->
                val a = st.optJSONObject("attributes") ?: JSONObject()
                Climate(a.optString("friendly_name"), st.optString("state"), a.optString("hvac_action", ""),
                    if (a.has("current_temperature") && !a.isNull("current_temperature")) a.optDouble("current_temperature") else null,
                    if (a.has("temperature") && !a.isNull("temperature")) a.optDouble("temperature") else null,
                    a.optDouble("min_temp", 16.0), a.optDouble("max_temp", 30.0), a.optDouble("target_temp_step", 0.5))
            }
            val room = if (lights.isEmpty() && climate == null) null else Room(r.optString("label"), lights, scenes, climate)
            main.post { done(room) }
        }
    }
    fun light(id: String, on: Boolean, pct: Int?, after: () -> Unit) {
        val h = cfg() ?: return
        val ids = h.optJSONObject("room")?.optJSONArray("lights") ?: return
        if ((0 until ids.length()).none { ids.optString(it) == id }) return
        io.execute {
            val body = JSONObject().put("entity_id", id)
            if (on && pct != null && pct in 1..100) body.put("brightness_pct", pct)
            post(h, if (on) "light/turn_on" else "light/turn_off", body)
            main.postDelayed(after, 1200)
        }
    }
    fun climate(on: Boolean? = null, target: Double? = null, after: () -> Unit) {
        val h = cfg() ?: return
        val id = h.optJSONObject("room")?.optString("climate").orEmpty().ifEmpty { return }
        io.execute {
            if (on != null) post(h, if (on) "climate/turn_on" else "climate/turn_off", JSONObject().put("entity_id", id))
            if (target != null) post(h, "climate/set_temperature", JSONObject().put("entity_id", id).put("temperature", target))
            main.postDelayed(after, 1500)
        }
    }

    // ---- scenes (the sidebar's list, and the living room's) ----
    data class HaScene(val id: String, val name: String, val icon: String?)
    fun scenes(): List<HaScene> {
        val a = cfg()?.optJSONArray("scenes") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { HaScene(it.optString("id"), it.optString("name", it.optString("id")), it.optString("icon").ifEmpty { null }) }
    }
    fun run(entity: String, done: (Boolean) -> Unit) {
        val h = cfg() ?: return done(false)
        if (!Regex("(scene|script)\\.[a-z0-9_]+").matches(entity)) return done(false)
        io.execute { val ok = post(h, entity.substringBefore('.') + "/turn_on", JSONObject().put("entity_id", entity)); main.post { done(ok) } }
    }

    // ---- rain on the glass: as the dashboard decides it (casa-v3.js _rainWanted) ----
    private val RAINY = setOf("rainy", "pouring", "lightning-rainy", "snowy-rainy", "hail")
    /** Raining outside (ha.json "rain": "weather", a weather entity) or switched on by hand
     *  ("flag", an input_boolean). done(null) when Home Assistant could not be asked. */
    fun raining(done: (Boolean?) -> Unit) {
        val h = cfg() ?: return
        val r = h.optJSONObject("rain") ?: return
        val weather = r.optString("weather").takeIf { Regex("weather\\.[a-z0-9_]+").matches(it) }
        val flag = r.optString("flag").takeIf { Regex("input_boolean\\.[a-z0-9_]+").matches(it) }
        if (weather == null && flag == null) return
        io.execute {
            val f = flag?.let { get(h, "/api/states/$it") }
            val w = weather?.let { get(h, "/api/states/$it") }
            val ans: Boolean? = if (f == null && w == null) null
                else f?.optString("state") == "on" || (w != null && w.optString("state") in RAINY)
            main.post { done(ans) }
        }
    }

    // ---- Now Playing: everything playing. ha.json's "players" (the speakers, by the names given
    // there, playing or paused) first, then any other media player Home Assistant has playing ----
    data class Playing(val state: String, val title: String, val artist: String, val picture: String?, val duration: Double, var position: Double, val speaker: String, val entity: String, val at: Long, val speakerOfOurs: Boolean = true, val listed: String? = null)
    private fun reading(h: JSONObject, id: String, st: JSONObject, name: String?, ours: Boolean): Playing {
        val s = st.optString("state")
        val a = st.optJSONObject("attributes") ?: JSONObject()
        val pic = a.optString("entity_picture").ifEmpty { null }?.let { if (it.startsWith("http")) it else h.getString("url").trimEnd('/') + it }
        var pos = a.optDouble("media_position", 0.0)
        val upd = a.optString("media_position_updated_at")
        if (s == "playing" && upd.isNotEmpty()) try {
            val t = java.time.OffsetDateTime.parse(upd).toInstant().toEpochMilli()
            pos += (System.currentTimeMillis() - t) / 1000.0
        } catch (e: Exception) {}
        val title = a.optString("media_title").ifEmpty { a.optString("app_name") }
        return Playing(s, title, a.optString("media_artist").ifEmpty { a.optString("media_album_artist") }, pic, a.optDouble("media_duration", 0.0), pos,
            name ?: a.optString("friendly_name", id), id, System.currentTimeMillis(), ours)
    }
    /** What is playing or paused among the house's media players: the Home Assistant dashboard's
     *  own list (ha.json "media": each room's players, in room order, as its Media filter shows
     *  them), else just the speakers in "players". Named as the dashboard names them, else as
     *  "players" does, else as Home Assistant does. Only the Google Cast entities listed: a speaker's
     *  other entities (living_room_speaker_2, its DLNA side) are not read or controlled. */
    fun playingAll(done: (List<Playing>) -> Unit) {
        val h = cfg() ?: return
        val ps = h.optJSONArray("players") ?: JSONArray()
        val speakers = HashMap<String, String>()
        for (i in 0 until ps.length()) ps.optJSONObject(i)?.let { speakers[it.optString("id")] = it.optString("name") }
        val list = ArrayList<Pair<String, String?>>()
        val groups = HashSet<String>()
        val media = h.optJSONArray("media")
        if (media != null) for (i in 0 until media.length()) media.optJSONObject(i)?.let {
            list.add(it.optString("id") to it.optString("name").ifEmpty { null })
            if (it.optBoolean("group")) groups.add(it.optString("id"))
        }
        else speakers.keys.forEach { list.add(it to null) }
        io.execute {
            val states = HashMap<String, JSONObject>()
            try {
                val c = java.net.URL(h.getString("url").trimEnd('/') + "/api/states").openConnection() as HttpURLConnection
                c.connectTimeout = 3000; c.readTimeout = 8000
                c.setRequestProperty("Authorization", "Bearer " + h.optString("token"))
                if (c.responseCode == 200) {
                    val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { o -> val id = o.optString("entity_id"); if (id.startsWith("media_player.")) states[id] = o }
                }
                c.disconnect()
            } catch (e: Exception) {}
            if (states.isEmpty()) { main.post { done(emptyList()) }; return@execute }
            val out = ArrayList<Playing>()
            for ((id, name) in list.distinctBy { it.first }) {
                if (!Regex("media_player\\.[a-z0-9_]+").matches(id)) continue
                val st = states[id]?.takeIf { it.optString("state") in setOf("playing", "paused") } ?: continue
                out.add(reading(h, st.optString("entity_id"), st, name ?: speakers[id]?.ifEmpty { null }, id in speakers).copy(listed = id))
            }
            // a group playing (Speakers) has each of its speakers report the same song: one entry,
            // the first listed (the group comes before its speakers)
            // the whole-house group (ha.json "group": the Speakers) playing: that alone, the rest ignored
            val one = out.firstOrNull { it.listed in groups }?.let { listOf(it) }
                ?: out.distinctBy { if (it.title.isEmpty()) it.entity else it.title + "\u0000" + it.artist }
            main.post { done(one) }
        }
    }
    /** A control for what is playing, on that player only (a Google Cast entity). Separate play and
     *  pause by what it is doing, and the two-way play/pause if it offers only that. */
    fun player(entity: String, action: String, state: String? = null, listed: String? = null) {
        val h = cfg() ?: return
        val svcs = when (action) {
            "play" -> when (state) { "playing" -> listOf("media_pause", "media_play_pause"); "paused" -> listOf("media_play", "media_play_pause"); else -> listOf("media_play_pause") }
            "next" -> listOf("media_next_track"); "prev" -> listOf("media_previous_track"); else -> return
        }
        io.execute {
            for (svc in svcs) {
                val r = postFor(h, "media_player/$svc", JSONObject().put("entity_id", entity))
                if (r.startsWith("2")) return@execute
                android.util.Log.w("Ha", "player $entity $svc: $r")
            }
        }
    }
}

/** Open-Meteo, as the web home calls it with no bridge: now, and seven days. */
object Weather {
    data class Day(val date: String, val code: Int, val hi: Int, val lo: Int, val rain: Int?)
    data class Now(val temp: Int, val code: Int, val day: Boolean, val days: List<Day>)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    val WMO = mapOf(0 to "wx_clear", 1 to "wx_mostly_clear", 2 to "wx_partly", 3 to "wx_cloudy", 45 to "wx_fog", 48 to "wx_fog",
        51 to "wx_drizzle", 53 to "wx_drizzle", 55 to "wx_drizzle", 56 to "wx_drizzle", 57 to "wx_drizzle", 61 to "wx_light_rain",
        63 to "wx_rain", 65 to "wx_heavy_rain", 66 to "wx_rain", 67 to "wx_rain", 80 to "wx_showers", 81 to "wx_showers",
        82 to "wx_heavy_showers", 95 to "wx_storms", 96 to "wx_storms", 99 to "wx_storms")
    fun words(code: Int) = WMO[code]?.let { L.t(it) } ?: ""

    fun fetch(p: Store.Place, done: (Now?) -> Unit) {
        io.execute {
            val now = try {
                val u = "https://api.open-meteo.com/v1/forecast?latitude=${p.lat}&longitude=${p.lon}&current=temperature_2m,weather_code,is_day" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=" +
                    java.net.URLEncoder.encode(p.timezone.ifEmpty { "auto" }, "UTF-8") + "&forecast_days=7"
                val c = java.net.URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 8000
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() }); c.disconnect()
                val cur = j.getJSONObject("current"); val d = j.optJSONObject("daily") ?: JSONObject()
                val t = d.optJSONArray("time") ?: JSONArray()
                val days = (0 until t.length()).map { i ->
                    Day(t.optString(i), Math.round(d.getJSONArray("weather_code").optDouble(i)).toInt(), Math.round(d.getJSONArray("temperature_2m_max").optDouble(i)).toInt(),
                        Math.round(d.getJSONArray("temperature_2m_min").optDouble(i)).toInt(), d.optJSONArray("precipitation_probability_max")?.let { a -> if (a.isNull(i)) null else a.optInt(i) })
                }
                Now(Math.round(cur.optDouble("temperature_2m")).toInt(), Math.round(cur.optDouble("weather_code")).toInt(), cur.optInt("is_day", 1) == 1, days)
            } catch (e: Exception) { null }
            main.post { done(now) }
        }
    }
}
