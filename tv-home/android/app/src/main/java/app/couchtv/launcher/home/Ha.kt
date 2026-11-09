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

    // ---- Now Playing: the first speaker in ha.json "players" that is playing or paused ----
    data class Playing(val state: String, val title: String, val artist: String, val picture: String?, val duration: Double, var position: Double, val speaker: String, val entity: String, val at: Long)
    fun playing(done: (Playing?) -> Unit) {
        val h = cfg() ?: return
        val ps = h.optJSONArray("players") ?: return
        io.execute {
            var found: Playing? = null
            for (i in 0 until ps.length()) {
                val p = ps.optJSONObject(i) ?: continue
                val id = p.optString("id"); val st = get(h, "/api/states/$id") ?: continue
                val s = st.optString("state")
                if (s != "playing" && s != "paused") continue
                val a = st.optJSONObject("attributes") ?: JSONObject()
                val pic = a.optString("entity_picture").ifEmpty { null }?.let { if (it.startsWith("http")) it else h.getString("url").trimEnd('/') + it }
                var pos = a.optDouble("media_position", 0.0)
                val upd = a.optString("media_position_updated_at")
                if (s == "playing" && upd.isNotEmpty()) try {
                    val t = java.time.OffsetDateTime.parse(upd).toInstant().toEpochMilli()
                    pos += (System.currentTimeMillis() - t) / 1000.0
                } catch (e: Exception) {}
                found = Playing(s, a.optString("media_title"), a.optString("media_artist"), pic, a.optDouble("media_duration", 0.0), pos,
                    p.optString("name", a.optString("friendly_name")), id, System.currentTimeMillis())
                break
            }
            main.post { done(found) }
        }
    }
    fun player(entity: String, action: String) {
        val h = cfg() ?: return
        val svc = when (action) { "play" -> "media_play_pause"; "next" -> "media_next_track"; "prev" -> "media_previous_track"; else -> return }
        io.execute { post(h, "media_player/$svc", JSONObject().put("entity_id", entity)) }
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
