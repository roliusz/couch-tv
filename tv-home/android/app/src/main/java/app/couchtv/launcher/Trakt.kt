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
    private const val STALE_MS = 14 * 86_400_000L      // only the last two weeks: older is not "keep watching"
    private const val PREMIERES_MS = 6 * 3_600_000L
    private const val CREDITS = 92.0                   // percent: this far in, only the credits are left
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

    /** Record one episode as watched in the person's Trakt history (the household asked for it on
     *  a Keep watching card), then fetch the list again so the card moves on. */
    fun markWatched(ctx: Context, who: String, show: Long, season: Int, episode: Int) {
        val w = safe(who) ?: return
        if (show <= 0 || season < 0 || episode <= 0) return
        io.execute {
            val tok = access(ctx, w) ?: return@execute
            val body = JSONObject().put("shows", JSONArray().put(JSONObject()
                .put("ids", JSONObject().put("trakt", show))
                .put("seasons", JSONArray().put(JSONObject().put("number", season)
                    .put("episodes", JSONArray().put(JSONObject().put("number", episode)))))))
            val (c, _) = call(ctx, "POST", "/sync/history", body, bearer = tok)
            if (c in 200..299) {
                val f = File(ctx.filesDir, "upnext21-$w.json")
                f.delete()
                fetch(ctx, w)?.let { f.writeText(it.toString()) }
            }
            main.post { onChange?.invoke(w) }
        }
    }

    fun cancel(who: String) { safe(who)?.let { linking.remove(it); main.post { onChange?.invoke(it) } } }

    fun unlink(ctx: Context, who: String) {
        val w = safe(who) ?: return
        prefs(ctx).edit().remove("trakt.tok.$w").apply()
        File(ctx.filesDir, "upnext21-$w.json").delete()
        File(ctx.filesDir, "premieres-$w.json").delete()
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
        val f = File(ctx.filesDir, "upnext21-$w.json")
        val stale = !f.exists() || System.currentTimeMillis() - f.lastModified() > FRESH_MS
        if (stale && token(ctx, w) != null && busy.add(w)) io.execute {
            try { fetch(ctx, w)?.let { f.writeText(it.toString()); main.post { onChange?.invoke(w) } } }
            finally { busy.remove(w) }
        }
        return if (token(ctx, w) != null && f.exists()) f.readText() else "[]"
    }

    /** Season and series premieres coming up for the shows the person follows on Trakt (watched,
     *  collected or on the watchlist), soonest first, one per show: kept for six hours, since the
     *  calendar rarely changes. Fetched again in the background when stale. */
    fun premieres(ctx: Context, who: String): String {
        val w = safe(who) ?: return "[]"
        val f = File(ctx.filesDir, "premieres-$w.json")
        val stale = !f.exists() || System.currentTimeMillis() - f.lastModified() > PREMIERES_MS
        if (stale && token(ctx, w) != null && busy.add("p:$w")) io.execute {
            try { fetchPremieres(ctx, w)?.let { f.writeText(it.toString()); main.post { onChange?.invoke(w) } } }
            finally { busy.remove("p:$w") }
        }
        return if (token(ctx, w) != null && f.exists()) f.readText() else "[]"
    }

    private fun fetchPremieres(ctx: Context, who: String): JSONArray? {
        val tok = access(ctx, who) ?: return null
        val today = java.time.LocalDate.now().toString()
        val (c, s) = call(ctx, "GET", "/calendars/my/shows/premieres/$today/33?extended=full,images", bearer = tok)
        if (c != 200) return null
        val now = System.currentTimeMillis()
        val seen = HashSet<Long>()
        val out = JSONArray()
        val a = JSONArray(s)
        (0 until a.length()).map { a.getJSONObject(it) }.sortedBy { it.optString("first_aired") }.forEach { e ->
            val show = e.optJSONObject("show") ?: return@forEach
            val ep = e.optJSONObject("episode") ?: return@forEach
            val id = show.optJSONObject("ids")?.optLong("trakt") ?: return@forEach
            val at = try { java.time.Instant.parse(e.optString("first_aired")).toEpochMilli() } catch (x: Exception) { return@forEach }
            if (at < now - 86_400_000L || !seen.add(id)) return@forEach   // aired already (today's still counts)
            out.put(JSONObject()
                .put("id", "t$id").put("title", show.optString("title"))
                .put("season", ep.optInt("season")).put("episode", ep.optInt("number"))
                .put("first_aired", e.optString("first_aired"))
                .put("network", show.optString("network"))
                .put("streams", streamsHere(ctx, show.optJSONObject("ids")?.optLong("tmdb") ?: 0L))
                .put("art", art(ctx, id, show) ?: JSONObject.NULL))
        }
        return out
    }

    private fun picture(o: JSONObject?): String? {
        val imgs = o?.optJSONObject("images") ?: return null
        for (k in listOf("fanart", "thumb", "screenshot", "banner", "poster")) {
            val a = imgs.optJSONArray(k) ?: continue
            val u = a.optString(0).takeIf { it.isNotEmpty() } ?: continue
            return if (u.startsWith("http")) u else "https://$u"
        }
        return null
    }

    /** The show's wide artwork (no spoilers, unlike an episode still): from the watched list if it
     *  carried pictures, else asked for, and remembered since a show's artwork rarely changes. */
    private val showArt = HashMap<Long, String?>()
    private fun art(ctx: Context, id: Long, show: JSONObject): String? {
        picture(show)?.let { return it }
        if (showArt.containsKey(id)) return showArt[id]
        val (c, s) = call(ctx, "GET", "/shows/$id?extended=images")
        val u = if (c == 200) picture(JSONObject(s)) else null
        if (c == 200) showArt[id] = u
        return u
    }

    /** Where the show streams in the household's country (Trakt's "Watch now", for VIP accounts):
     *  the service names, so the page can pick an app on this TV when the show's own network (often
     *  a broadcaster, such as BBC Two) has none. Read loosely: every "source" or "name" found under
     *  the country's subscription and free lists. Nothing (no VIP, not offered) leaves it empty. */
    private val showWhere = HashMap<Long, JSONArray>()
    private fun whereToWatch(ctx: Context, id: Long, bearer: String): JSONArray {
        showWhere[id]?.let { return it }
        val cc = try { JSONObject(prefs(ctx).getString("config", "{}")!!).optString("country") } catch (e: Exception) { "" }
            .lowercase().takeIf { Regex("[a-z]{2}").matches(it) } ?: return JSONArray()
        val (c, s) = call(ctx, "GET", "/shows/$id/watchnow/$cc", bearer = bearer)
        val out = JSONArray()
        if (c == 200) try {
            val names = LinkedHashSet<String>()
            fun walk(v: Any?, keep: Boolean) {
                when (v) {
                    is JSONObject -> v.keys().forEach { k ->
                        val inList = keep || k in setOf("subscription", "subscriptions", "free", "ads", "flatrate")
                        if (inList && (k == "source" || k == "name")) v.optString(k).takeIf { it.isNotEmpty() }?.let { names.add(it) }
                        else walk(v.opt(k), inList)
                    }
                    is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i), keep)
                }
            }
            walk(JSONObject(s), false)
            names.forEach { out.put(it) }
        } catch (e: Exception) {}
        if (c == 200 || c == 403 || c == 404) showWhere[id] = out
        return out
    }

    /** The episode after the one watched last, as Netflix and Apple TV pick it. Trakt's own
     *  next_episode is the first never watched: nothing for a show being watched again (The Office
     *  all seen years ago, now on season 5 once more), and an old gap far behind where someone
     *  really is when they skipped an episode once. The last one watched was the finale: nothing. */
    /** How many times each episode of a show was watched, from the person's watch history. */
    private fun plays(ctx: Context, show: Long, tok: String): Map<Pair<Int, Int>, Int> {
        val out = HashMap<Pair<Int, Int>, Int>()
        val (c, s) = call(ctx, "GET", "/sync/history/shows/$show?limit=2000", bearer = tok)
        if (c != 200) return out
        try {
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                val e = a.getJSONObject(i).optJSONObject("episode") ?: continue
                val k = e.optInt("season") to e.optInt("number")
                out[k] = (out[k] ?: 0) + 1
            }
        } catch (e: Exception) {}
        return out
    }

    private fun after(pr: JSONObject, plays: Map<Pair<Int, Int>, Int>): JSONObject? {
        // every aired episode in order, and when each was last watched (Trakt's progress)
        val order = ArrayList<Pair<Int, Int>>()
        var newest = ""; var latest: Pair<Int, Int>? = null
        pr.optJSONArray("seasons")?.let { ss ->
            for (i in 0 until ss.length()) {
                val se = ss.getJSONObject(i); val n = se.optInt("number")
                if (n == 0) continue
                val eps = se.optJSONArray("episodes") ?: continue
                for (j in 0 until eps.length()) {
                    val ep = eps.getJSONObject(j); val k = n to ep.optInt("number")
                    order.add(k)
                    val w = ep.optString("last_watched_at").takeIf { it.isNotEmpty() && it != "null" } ?: continue
                    if (w > newest) { newest = w; latest = k }
                }
            }
        }
        val last = latest ?: return pr.optJSONObject("next_episode")
        val at = order.indexOf(last)
        if (at < 0) return pr.optJSONObject("next_episode")
        // The episode watched last decides. Watched more than once, it is a rewatch (The Office on
        // its second time through): the next one is simply the one after it. Watched once, the next
        // is the first later one never watched, and none means finished: marking a whole series
        // watched dates its episodes at their release, older than the ones really watched, so the
        // "latest" can be mid-series while every episode is in fact watched.
        val p = plays[last] ?: 1
        val rewatch = p > 1
        for (i in at + 1 until order.size) {
            val k = order[i]
            if (rewatch || (plays[k] ?: 0) == 0) {
                pr.optJSONObject("next_episode")?.let { if (it.optInt("season") == k.first && it.optInt("number") == k.second) return it }
                return JSONObject().put("season", k.first).put("number", k.second)
            }
        }
        return null
    }

    /** Where the show streams in the household's country, from TMDB (JustWatch's data): the
     *  services by name, subscription first (Netflix for The Office in NL, Apple TV for Small
     *  Prophets), so the card shows the app it is really watched in, not the network it first
     *  aired on. Needs a TMDB key (tmdb.json, apply.sh); without one, or not on TMDB, nothing. */
    private val showStreams = HashMap<Long, JSONArray>()
    private fun streamsHere(ctx: Context, tmdb: Long): JSONArray {
        if (tmdb <= 0) return JSONArray()
        showStreams[tmdb]?.let { return it }
        val key = try { JSONObject(prefs(ctx).getString("tmdb", "{}")!!).optString("api_key") } catch (e: Exception) { "" }
        val cc = try { JSONObject(prefs(ctx).getString("config", "{}")!!).optString("country") } catch (e: Exception) { "" }.uppercase()
        if (key.isEmpty() || !Regex("[A-Z]{2}").matches(cc)) return JSONArray()
        val out = JSONArray()
        // a v4 read access token (a long JWT) goes in the header, a v3 key in the address
        val bearer = key.length > 60
        val c = URL("https://api.themoviedb.org/3/tv/$tmdb/watch/providers" + (if (bearer) "" else "?api_key=$key"))
            .openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6000; c.readTimeout = 12000
            if (bearer) c.setRequestProperty("Authorization", "Bearer $key")
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode == 200) {
                val here = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONObject("results")?.optJSONObject(cc)
                val seen = HashSet<String>()
                for (kind in listOf("flatrate", "free", "ads", "rent", "buy")) {
                    val a = here?.optJSONArray(kind) ?: continue
                    for (i in 0 until a.length()) a.getJSONObject(i).optString("provider_name").takeIf { it.isNotEmpty() && seen.add(it) }?.let { out.put(it) }
                }
                showStreams[tmdb] = out
            }
        } catch (e: Exception) {
        } finally { c.disconnect() }
        return out
    }

    /** Whether an episode has aired (Trakt's first_aired is in the past); asked once per episode. */
    private val airDates = HashMap<String, Long>()
    private fun aired(ctx: Context, show: Long, season: Int, episode: Int, tok: String): Boolean {
        val k = "$show/$season/$episode"
        val at = airDates[k] ?: run {
            val (c, s) = call(ctx, "GET", "/shows/$show/seasons/$season/episodes/$episode?extended=full", bearer = tok)
            if (c != 200) return true   // cannot tell: show it
            val t = try { JSONObject(s).optString("first_aired").takeIf { it.isNotEmpty() && it != "null" }
                ?.let { java.time.Instant.parse(it).toEpochMilli() } ?: Long.MAX_VALUE } catch (e: Exception) { 0L }
            airDates[k] = t; t
        }
        return at <= System.currentTimeMillis()
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
        // episodes stopped partway (Trakt's playback, from whatever reports to it): show id ->
        // the paused one, the most recent per show
        val paused = HashMap<Long, JSONObject>()
        call(ctx, "GET", "/sync/playback/episodes?extended=full&limit=50", bearer = tok).let { (pc, ps) ->
            if (pc == 200) try {
                val a = JSONArray(ps)
                for (i in 0 until a.length()) {
                    val p = a.getJSONObject(i)
                    val sid = p.optJSONObject("show")?.optJSONObject("ids")?.optLong("trakt") ?: continue
                    if (p.optDouble("progress", 0.0) >= CREDITS) continue
                    val have = paused[sid]
                    if (have == null || p.optString("paused_at") > have.optString("paused_at")) paused[sid] = p
                }
            } catch (e: Exception) {}
        }
        // Only finished episodes say what is recent: a pause's date cannot be trusted (old Prime
        // Video pauses come back dated this week), so a pause never brings a show in by itself and
        // never makes it newer. It only gives the next episode its bar (below).
        val watched = JSONArray(s)
        val rows = (0 until watched.length()).map { watched.getJSONObject(it) }
            .sortedByDescending { it.optString("last_watched_at") }
            .filter { it.optJSONObject("show")?.optJSONObject("ids")?.optLong("trakt") !in hidden }
            // a show not watched in the last two weeks is no longer "keep watching"
            .filter { r -> try { System.currentTimeMillis() - java.time.Instant.parse(r.optString("last_watched_at")).toEpochMilli() < STALE_MS } catch (e: Exception) { true } }
            .take(SHOWS)
        val out = JSONArray()
        for (r in rows) {
            val show = r.getJSONObject("show")
            val id = show.getJSONObject("ids").getLong("trakt")
            // stopped partway through the very episode that comes next: carry on with it, with its
            // bar. A pause on any other episode (an old one, The Office S3 while on S5) is left out.
            var pause = paused[id]?.takeIf { it.optString("paused_at") >= r.optString("last_watched_at") }
            val (pc, ps) = call(ctx, "GET", "/shows/$id/progress/watched?hidden=false&specials=false&extended=images", bearer = tok)
            if (pc != 200) continue
            val next = after(JSONObject(ps), plays(ctx, id, tok))
            // not out yet (Youth's next episode airs next week): not "next" until it airs
            if (next != null && !aired(ctx, id, next.optInt("season"), next.optInt("number"), tok)) {
                // the TV's own card for it (HBO Max's bare record) waits too
                out.put(JSONObject().put("id", "t$id").put("title", show.optString("title")).put("finished", true)
                    .put("last_played", r.optString("last_watched_at")))
                continue
            }
            if (next == null) {   // finished: the page drops the TV's own card for it too
                out.put(JSONObject().put("id", "t$id").put("title", show.optString("title")).put("finished", true)
                    .put("last_played", r.optString("last_watched_at")))
                continue
            }
            val pe = pause?.optJSONObject("episode")
            if (pe == null || pe.optInt("season") != next.optInt("season") || pe.optInt("number") != next.optInt("number")) pause = null
            out.put(JSONObject()
                .put("id", "t$id").put("title", show.optString("title"))
                .put("season", next.optInt("season")).put("episode", next.optInt("number"))
                .put("episode_title", next.optString("title").takeIf { it.isNotEmpty() && it != "null" } ?: JSONObject.NULL)
                .put("network", show.optString("network"))
                .put("watch", whereToWatch(ctx, id, tok))
                .put("streams", streamsHere(ctx, show.getJSONObject("ids").optLong("tmdb")))
                .put("art", art(ctx, id, show) ?: picture(next) ?: JSONObject.NULL)
                .put("last_played", r.optString("last_watched_at"))
                .apply {
                    if (pause != null) {
                        val pct = pause.optDouble("progress", 0.0) / 100
                        put("progress", pct)
                        val rt = pause.optJSONObject("episode")?.optInt("runtime") ?: 0
                        if (rt > 0) put("remaining_min", Math.max(1, Math.round(rt * (1 - pct)).toInt()))
                    }
                })
        }
        return out
    }
}
