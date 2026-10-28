package app.couchtv.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Trakt "next episode" for Keep watching (fork addition). Each person links their own Trakt account
 * with the TV's device sign-in: the TV shows a short code, they enter it at trakt.tv/activate on a
 * phone, and the launcher keeps the access (refreshing it before it runs out). Then, for the shows
 * they watched most recently, Trakt's watched progress says which episode is next; finished and
 * hidden shows are left out. Results are kept for ten minutes so the home never waits on Trakt.
 *
 * The API app's ID (and secret, for older apps) come from trakt.json (written by the household, sent by apply.sh).
 * Nothing here ever reaches the page except show titles, episodes, pictures and the Trakt username.
 */
object Trakt {
    private const val API = "https://api.trakt.tv"
    private const val SHOWS = 12                 // most recently watched shows looked at
    private const val FRESH_MS = 10 * 60_000L
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    var onChange: ((String) -> Unit)? = null      // a person's list changed, or their link did
    private val linking = HashMap<String, JSONObject>()   // who -> the code being shown
    private val busy = HashSet<String>()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)
    private fun app(ctx: Context): JSONObject? =
        prefs(ctx).getString("trakt", null)?.let { try { JSONObject(it) } catch (e: Exception) { null } }
            ?.takeIf { it.optString("client_id").isNotEmpty() }   // newer Trakt apps (PKCE) have no secret
    private fun token(ctx: Context, who: String): JSONObject? =
        prefs(ctx).getString("trakt.tok.$who", null)?.let { try { JSONObject(it) } catch (e: Exception) { null } }
    private fun safe(who: String) = who.takeIf { Regex("[a-z0-9_-]{1,40}").matches(it) }

    fun ready(ctx: Context) = app(ctx) != null

    /** For the sidebar: linked (with the Trakt username), linking (with the code), or neither. */
    fun state(ctx: Context, who: String): String {
        val w = safe(who) ?: return "{}"
        token(ctx, w)?.let { return JSONObject().put("linked", true).put("user", it.optString("user")).toString() }
        linking[w]?.let { return JSONObject().put("linking", true).put("code", it.optString("user_code"))
            .put("url", it.optString("verification_url")).toString() }
        return JSONObject().put("ready", ready(ctx)).toString()
    }

    private fun call(ctx: Context, method: String, path: String, body: JSONObject? = null, bearer: String? = null): Pair<Int, String> {
        val a = app(ctx) ?: return -1 to ""
        val c = URL(API + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 6000; c.readTimeout = 12000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("trakt-api-version", "2")
            c.setRequestProperty("trakt-api-key", a.getString("client_id"))
            c.setRequestProperty("User-Agent", "couch-tv")
            bearer?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } catch (e: Exception) {
            return -1 to ""
        } finally { c.disconnect() }
    }

    /** Start the device sign-in for a person: get a code, then wait for them to enter it. */
    fun link(ctx: Context, who: String) {
        val w = safe(who) ?: return
        val a = app(ctx) ?: return
        io.execute {
            val (code, text) = call(ctx, "POST", "/oauth/device/code", JSONObject().put("client_id", a.getString("client_id")))
            if (code != 200) { main.post { onChange?.invoke(w) }; return@execute }
            val d = JSONObject(text)
            linking[w] = d
            main.post { onChange?.invoke(w) }
            val until = System.currentTimeMillis() + d.optLong("expires_in", 600) * 1000
            val every = d.optLong("interval", 5) * 1000
            while (linking[w] === d && System.currentTimeMillis() < until) {
                Thread.sleep(every)
                val (c, t) = call(ctx, "POST", "/oauth/device/token", JSONObject()
                    .put("code", d.getString("device_code")).put("client_id", a.getString("client_id"))
                    .apply { a.optString("client_secret").takeIf { it.isNotEmpty() }?.let { put("client_secret", it) } })
                when (c) {
                    200 -> { save(ctx, w, JSONObject(t)); break }
                    400, 429 -> continue                 // not entered yet / slow down
                    else -> break                        // refused, expired or already used
                }
            }
            if (linking[w] === d) linking.remove(w)
            main.post { onChange?.invoke(w) }
        }
    }

    fun cancel(who: String) { safe(who)?.let { linking.remove(it); main.post { onChange?.invoke(it) } } }

    fun unlink(ctx: Context, who: String) {
        val w = safe(who) ?: return
        prefs(ctx).edit().remove("trakt.tok.$w").apply()
        File(ctx.filesDir, "trakt-$w.json").delete()
        main.post { onChange?.invoke(w) }
    }

    private fun save(ctx: Context, who: String, t: JSONObject) {
        val tok = JSONObject().put("access", t.getString("access_token")).put("refresh", t.getString("refresh_token"))
            .put("expires", (t.optLong("created_at", System.currentTimeMillis() / 1000) + t.optLong("expires_in", 0)) * 1000)
        val (c, s) = call(ctx, "GET", "/users/settings", bearer = tok.getString("access"))
        if (c == 200) tok.put("user", JSONObject(s).optJSONObject("user")?.optString("username") ?: "")
        prefs(ctx).edit().putString("trakt.tok.$who", tok.toString()).apply()
    }

    /** A valid access token for a person, refreshed when it is within a day of running out. */
    private fun access(ctx: Context, who: String): String? {
        val t = token(ctx, who) ?: return null
        if (t.optLong("expires") - System.currentTimeMillis() > 86_400_000L) return t.getString("access")
        val a = app(ctx) ?: return null
        val (c, s) = call(ctx, "POST", "/oauth/token", JSONObject().put("refresh_token", t.getString("refresh"))
            .put("client_id", a.getString("client_id"))
            .apply { a.optString("client_secret").takeIf { it.isNotEmpty() }?.let { put("client_secret", it) } }
            .put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob").put("grant_type", "refresh_token"))
        if (c != 200) return if (c == 401 || c == 400) { unlink(ctx, who); null } else t.getString("access")
        val n = JSONObject(s)
        val keep = JSONObject().put("access", n.getString("access_token")).put("refresh", n.getString("refresh_token"))
            .put("expires", (n.optLong("created_at", System.currentTimeMillis() / 1000) + n.optLong("expires_in", 0)) * 1000)
            .put("user", t.optString("user"))
        prefs(ctx).edit().putString("trakt.tok.$who", keep.toString()).apply()
        return keep.getString("access")
    }

    /** The person's next episodes as last fetched; fetches again in the background when stale. */
    fun upNext(ctx: Context, who: String): String {
        val w = safe(who) ?: return "[]"
        val f = File(ctx.filesDir, "trakt-$w.json")
        val stale = !f.exists() || System.currentTimeMillis() - f.lastModified() > FRESH_MS
        if (stale && token(ctx, w) != null && busy.add(w)) io.execute {
            try { fetch(ctx, w)?.let { f.writeText(it.toString()); main.post { onChange?.invoke(w) } } }
            finally { busy.remove(w) }
        }
        return if (token(ctx, w) != null && f.exists()) f.readText() else "[]"
    }

    private fun picture(o: JSONObject?): String? {
        val imgs = o?.optJSONObject("images") ?: return null
        for (k in listOf("fanart", "thumb", "banner", "poster")) {
            val a = imgs.optJSONArray(k) ?: continue
            val u = a.optString(0).takeIf { it.isNotEmpty() } ?: continue
            return if (u.startsWith("http")) u else "https://$u"
        }
        return null
    }

    private fun fetch(ctx: Context, who: String): JSONArray? {
        val tok = access(ctx, who) ?: return null
        val (c, s) = call(ctx, "GET", "/sync/watched/shows?extended=full,images", bearer = tok)
        if (c != 200) return null
        val hidden = HashSet<Long>()
        val (hc, hs) = call(ctx, "GET", "/users/hidden/progress_watched?type=show&limit=200", bearer = tok)
        if (hc == 200) {
            val a = JSONArray(hs)
            for (i in 0 until a.length())
                a.getJSONObject(i).optJSONObject("show")?.optJSONObject("ids")?.optLong("trakt")?.let { hidden.add(it) }
        }
        val watched = JSONArray(s)
        val rows = (0 until watched.length()).map { watched.getJSONObject(it) }
            .sortedByDescending { it.optString("last_watched_at") }
            .filter { it.optJSONObject("show")?.optJSONObject("ids")?.optLong("trakt") !in hidden }
            .take(SHOWS)
        val out = JSONArray()
        for (r in rows) {
            val show = r.getJSONObject("show")
            val id = show.getJSONObject("ids").getLong("trakt")
            val (pc, ps) = call(ctx, "GET", "/shows/$id/progress/watched?hidden=false&specials=false&extended=images", bearer = tok)
            if (pc != 200) continue
            val next = JSONObject(ps).optJSONObject("next_episode") ?: continue
            out.put(JSONObject()
                .put("id", "t$id").put("title", show.optString("title"))
                .put("season", next.optInt("season")).put("episode", next.optInt("number"))
                .put("episode_title", next.optString("title").takeIf { it.isNotEmpty() && it != "null" } ?: JSONObject.NULL)
                .put("network", show.optString("network"))
                .put("art", picture(next) ?: picture(show) ?: JSONObject.NULL)
                .put("last_played", r.optString("last_watched_at")))
        }
        return out
    }
}
