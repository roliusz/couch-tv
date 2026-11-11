package app.couchtv.launcher.home

import android.content.Context
import app.couchtv.launcher.Trakt
import app.couchtv.launcher.WatchLog
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/** A card in the row: a show to keep watching, an upcoming episode, or a home card. */
data class Show(
    val id: String, val title: String, val season: Int = 0, val episode: Int = 0, val episodeTitle: String? = null,
    var app: AppItem? = null, val art: String? = null, val progress: Double? = null, val remainingMin: Int = 0,
    val who: String? = null, val lastPlayed: Long = 0, val trakt: Boolean = false, val next: Boolean = false,
    val premiere: Boolean = false, val isPremiere: Boolean = true, val firstAired: Long = 0, val finished: Boolean = false,
    val home: String? = null   // "room", "climate", "weather"
)

/**
 * Keep watching and Upcoming, as the web home builds them with no bridge (app.js watchedShows,
 * traktNext, mergeTrakt, premieresNow): what this TV recorded (WatchLog) and Trakt's next episodes.
 */
class Shows(private val ctx: Context, private val store: Store, private val apps: Apps) {
    private val NOT_SHOWS = Regex("^(com\\.google\\.android\\.youtube\\.tv|com\\.sony\\.dtv\\.tv.*|com\\.google\\.android\\.tv)$")
    private val NETWORK_APPS = listOf(
        Regex("netflix", RegexOption.IGNORE_CASE) to listOf("com.netflix.ninja"),
        Regex("hbo|\\bmax\\b", RegexOption.IGNORE_CASE) to listOf("com.wbd.stream"),
        Regex("apple", RegexOption.IGNORE_CASE) to listOf("com.apple.atve.sony.appletv", "com.apple.atve.androidtv.appletv"),
        Regex("disney", RegexOption.IGNORE_CASE) to listOf("com.disney.disneyplus"),
        Regex("prime|amazon", RegexOption.IGNORE_CASE) to listOf("com.amazon.amazonvideo.livingroom"),
        Regex("youtube", RegexOption.IGNORE_CASE) to listOf("com.google.android.youtube.tv"),
        Regex("paramount", RegexOption.IGNORE_CASE) to listOf("com.cbs.ca", "com.cbs.ott"),
        Regex("hulu", RegexOption.IGNORE_CASE) to listOf("com.hulu.livingroomplus"),
        Regex("peacock", RegexOption.IGNORE_CASE) to listOf("com.peacocktv.peacockandroid"))

    private fun appFor(pkg: String) = apps.all.firstOrNull { it.pkg == pkg }
    private fun appForNetwork(n: String?): AppItem? {
        if (n.isNullOrEmpty()) return null
        for ((re, pk) in NETWORK_APPS) if (re.containsMatchIn(n)) pk.firstNotNullOfOrNull { appFor(it) }?.let { return it }
        return null
    }
    private fun strs(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.opt(it) as? String }
    private fun iso(s: String?): Long = if (s.isNullOrEmpty()) 0 else try {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        f.parse(s.substring(0, minOf(19, s.length)))?.time ?: 0
    } catch (e: Exception) { 0 }

    fun key(title: String) = title.lowercase().replace(Regex("[^a-z0-9]+"), "")
    fun picks(): JSONObject = store.kept("tvhome.showapp") as? JSONObject ?: JSONObject()
    fun picked(s: Show): AppItem? = picks().optString(key(s.title)).ifEmpty { null }?.let { appFor(it) }
    fun setPick(s: Show, pkg: String?) { val p = picks(); if (pkg != null) p.put(key(s.title), pkg) else p.remove(key(s.title)); store.keep("tvhome.showapp", p) }
    private fun sig(s: Show) = "${s.season}:${s.episode}:${if (s.next) "n" else "p"}"
    fun hide(s: Show) { val h = store.kept("tvhome.hidden_shows") as? JSONObject ?: JSONObject(); h.put(key(s.title), sig(s)); store.keep("tvhome.hidden_shows", h) }
    private fun shown(s: Show): Boolean { val h = (store.kept("tvhome.hidden_shows") as? JSONObject)?.optString(key(s.title)); return h.isNullOrEmpty() || h != sig(s) }

    /** Everyone's Keep watching: the TV's records for that person (or no one), merged with Trakt. */
    fun forPerson(who: String, everyone: Boolean): List<Show> {
        val since = System.currentTimeMillis() - 14 * 86400000L
        val log = try { WatchLog.load(ctx) } catch (e: Exception) { JSONArray() }
        val mine = (0 until log.length()).mapNotNull { log.optJSONObject(it) }
            .filter { !NOT_SHOWS.matches(it.optString("package")) && it.optLong("last_played") >= since }
            .map { e -> Show(e.optString("id"), e.optString("title"), e.optInt("season"), e.optInt("episode"), e.optString("episode_title").takeIf { it.isNotEmpty() && it != "null" },
                appFor(e.optString("package")) ?: AppItem("app", e.optString("package"), e.optString("package")), e.optString("art").ifEmpty { null },
                if (e.has("progress") && !e.isNull("progress")) e.optDouble("progress") else null, e.optInt("remaining_min"),
                e.optString("who").ifEmpty { null }, e.optLong("last_played")) }
            .filter { everyone || it.who == null || it.who == who }
        return merge(mine, traktNext(who)).filter(::shown).map { s -> picked(s)?.let { s.copy(app = it) } ?: s }.take(12)
    }

    private fun traktNext(who: String): List<Show> {
        val a = try { JSONArray(Trakt.upNext(ctx, who)) } catch (e: Exception) { return emptyList() }
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { e ->
            if (e.optBoolean("finished")) Show(e.optString("id"), e.optString("title"), finished = true)
            else Show(e.optString("id"), e.optString("title"), e.optInt("season"), e.optInt("episode"), e.optString("episode_title").takeIf { it.isNotEmpty() && it != "null" },
                strs(e.optJSONArray("streams")).firstNotNullOfOrNull { appForNetwork(it) } ?: appForNetwork(e.optString("network"))
                    ?: strs(e.optJSONArray("watch")).firstNotNullOfOrNull { appForNetwork(it) },
                e.optString("art").ifEmpty { null }, if (e.has("progress") && !e.isNull("progress")) e.optDouble("progress") else null,
                e.optInt("remaining_min"), null, iso(e.optString("last_played")), trakt = true, next = !e.has("progress") || e.isNull("progress"))
        }
    }

    private fun merge(mine: List<Show>, next0: List<Show>): List<Show> {
        val done = next0.filter { it.finished }.map { key(it.title) }
        val out = mine.filter { key(it.title) !in done }.toMutableList()
        next0.filter { !it.finished }.forEach { n ->
            val i = out.indexOfFirst { key(it.title) == key(n.title) }
            if (i < 0) { out.add(n); return@forEach }
            val tv = out[i]
            var m = n.copy(app = tv.app ?: n.app, art = n.art ?: tv.art)
            if (tv.lastPlayed > n.lastPlayed) {
                m = m.copy(lastPlayed = tv.lastPlayed)
                if (tv.season > 0 && tv.episode > 0 && tv.progress != null)
                    m = m.copy(season = tv.season, episode = tv.episode, episodeTitle = tv.episodeTitle, progress = tv.progress, remainingMin = tv.remainingMin, next = false)
            }
            out[i] = m
        }
        return out.sortedWith(compareByDescending<Show> { if (it.trakt) 1 else 0 }.thenByDescending { it.lastPlayed })
    }

    /** Upcoming: premieres and new episodes on the person's Trakt calendar, soonest first. */
    fun premieres(who: String): List<Show> {
        val a = try { JSONArray(Trakt.premieres(ctx, who)) } catch (e: Exception) { return emptyList() }
        val now = System.currentTimeMillis()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { e ->
            Show(e.optString("id"), e.optString("title"), e.optInt("season"), e.optInt("episode"),
                app = strs(e.optJSONArray("streams")).firstNotNullOfOrNull { appForNetwork(it) } ?: appForNetwork(e.optString("network")),
                art = e.optString("art").ifEmpty { null }, premiere = true, isPremiere = e.optBoolean("premiere", true), firstAired = iso(e.optString("first_aired")))
        }.filter { it.firstAired > now - 86400000L }.map { s -> picked(s)?.let { s.copy(app = it) } ?: s }
    }

    fun premiereDay(at: Long): String {
        val d = Calendar.getInstance().apply { timeInMillis = at; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val days = Math.round((d.timeInMillis - today.timeInMillis) / 86400000.0).toInt()
        if (days <= 0) return L.t("prem_today")
        if (days == 1) return L.t("prem_tomorrow")
        val f = if (days < 7) SimpleDateFormat("EEEE", L.locale()) else SimpleDateFormat("EEE d MMM", L.locale())
        return f.format(Date(at))
    }

    /** The line under a card's title (app.js cardHTML). */
    fun meta(s: Show): String {
        val ep = if (s.season > 0 && s.episode > 0) "S${s.season}, E${s.episode}" else s.app?.name ?: ""
        if (s.premiere) {
            val what = if (!s.isPremiere) ep else if (s.episode == 1 && s.season > 1) "S${s.season} " + L.t("prem_word") else if (s.season == 1 && s.episode == 1) L.t("prem_series") else ep
            return what + " · " + premiereDay(s.firstAired)
        }
        val left = s.remainingMin
        return (if (s.next) L.t("up_next") + " · " else "") + ep + (if (left > 0) " · " + (if (left >= 60) "${left / 60}h ${left % 60}m" else "${left}m") else "")
    }
    fun canMark(s: Show) = s.trakt && Regex("^t\\d+$").matches(s.id) && s.episode > 0
    fun mark(who: String, s: Show) = Trakt.markWatched(ctx, who, s.id.drop(1).toLongOrNull() ?: 0L, s.season, s.episode)
}
