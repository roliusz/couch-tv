package app.couchtv.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keep watching without a bridge (fork addition). Apps tell Android what they are playing (the
 * title, often the episode, artwork, length and position) through media sessions; that is what
 * the TV's own playback controls show. This listens to those sessions and keeps a short history
 * on the TV: anything that plays for two minutes or more is remembered (so trailers and autoplay
 * previews are not), a newer episode of the same show replaces the older one, and what has nearly
 * finished drops off. Each entry is tagged with who was watching (the home screen's person).
 *
 * Reading other apps' sessions needs notification access, which apply.sh grants over ADB
 * (cmd notification allow_listener). The listener service is bound by the system and keeps
 * running while other apps are in front, which is exactly when there is something to record.
 */
class NowListener : NotificationListenerService() {
    override fun onListenerConnected() { WatchLog.start(this) }
    override fun onListenerDisconnected() { WatchLog.stop() }
}

object WatchLog {
    private const val KEEP = 24                    // entries kept
    private const val MIN_PLAY_MS = 120_000L       // played this long before it counts
    private const val MIN_LENGTH_MS = 8 * 60_000L  // shorter than this (when known): a clip or a song
    private const val DONE = 0.95                  // this far through: finished, drops off
    // music and the TV's own players are not "watching"
    private val SKIP = setOf(
        "app.couchtv.launcher", "com.google.android.youtube.tvmusic", "com.spotify.tv.android",
        "com.google.android.apps.tv.launcherx", "com.sony.dtv.osat.music", "com.android.bluetooth"
    )

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var msm: MediaSessionManager? = null
    private val controllers = HashMap<MediaSession.Token, MediaController>()
    private val callbacks = HashMap<MediaSession.Token, MediaController.Callback>()
    // how long each session has played, and since when (SystemClock), by package + title
    private val played = HashMap<String, Long>()
    private val since = HashMap<String, Long>()
    @Volatile var connected = false; private set
    var onChange: (() -> Unit)? = null

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(list ?: emptyList()) }
    private val tick = object : Runnable {
        override fun run() {
            controllers.values.forEach { update(it) }
            if (connected) main.postDelayed(this, 30_000)
        }
    }

    fun start(ctx: Context) {
        app = ctx.applicationContext
        val m = ctx.getSystemService(MediaSessionManager::class.java) ?: return
        val comp = ComponentName(ctx, NowListener::class.java)
        try {
            m.addOnActiveSessionsChangedListener(sessionsChanged, comp, main)
            msm = m
            connected = true
            attach(m.getActiveSessions(comp))
            main.removeCallbacks(tick)
            main.postDelayed(tick, 30_000)
        } catch (e: SecurityException) {
            connected = false   // no notification access yet
        }
    }

    fun stop() {
        connected = false
        main.removeCallbacks(tick)
        try { msm?.removeOnActiveSessionsChangedListener(sessionsChanged) } catch (e: Exception) {}
        controllers.forEach { (t, c) -> callbacks[t]?.let { c.unregisterCallback(it) } }
        controllers.clear(); callbacks.clear()
    }

    private fun attach(list: List<MediaController>) {
        val now = list.map { it.sessionToken }.toSet()
        controllers.keys.filter { it !in now }.forEach { t ->
            controllers[t]?.let { c -> update(c); callbacks[t]?.let { c.unregisterCallback(it) } }
            controllers.remove(t); callbacks.remove(t)
        }
        for (c in list) {
            if (c.sessionToken in controllers || c.packageName in SKIP) continue
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) { update(c) }
                override fun onMetadataChanged(metadata: MediaMetadata?) { update(c) }
            }
            c.registerCallback(cb, main)
            controllers[c.sessionToken] = c
            callbacks[c.sessionToken] = cb
            update(c)
        }
    }

    /** Count playing time, and record the session once it has played long enough. */
    private fun update(c: MediaController) {
        val md = c.metadata ?: return
        val title = (md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_TITLE))?.trim()
        if (title.isNullOrEmpty()) return
        val key = c.packageName + "|" + title
        val st = c.playbackState
        val playing = st?.state == PlaybackState.STATE_PLAYING
        val now = SystemClock.elapsedRealtime()
        since[key]?.let { played[key] = (played[key] ?: 0L) + (now - it) }
        if (playing) since[key] = now else since.remove(key)
        val length = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        if (length in 1 until MIN_LENGTH_MS) return
        if ((played[key] ?: 0L) < MIN_PLAY_MS) return
        record(c.packageName, md, title, st, length)
    }

    // "S2 E3", "S2:E3", "Season 2 Episode 3" in the title or the subtitle
    private val SE = Regex("""(?i)\bS(?:eason)?\s*(\d{1,2})\s*[:,.]?\s*E(?:p(?:isode)?)?\s*(\d{1,3})\b""")

    private fun record(pkg: String, md: MediaMetadata, title: String, st: PlaybackState?, length: Long) {
        val ctx = app ?: return
        val sub = listOf(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM).firstNotNullOfOrNull { k -> md.getString(k)?.trim()?.takeIf { it.isNotEmpty() } }
        val se = SE.find(title) ?: sub?.let { SE.find(it) }
        // a series names the show in the subtitle or the album, the episode in the title
        val show = (if (se != null || sub != null) (md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: sub) else null)
            ?.replace(SE, "")?.trim(' ', '-', ':', '·')?.takeIf { it.isNotEmpty() } ?: title
        val id = (pkg + "|" + show.lowercase()).hashCode().toUInt().toString(16)
        val pos = st?.position ?: -1L
        val progress = if (length > 0 && pos >= 0) pos.toDouble() / length else -1.0

        val list = load(ctx)
        val keep = JSONArray()
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            if (o.optString("id") != id) keep.put(o)
        }
        if (progress >= DONE) { save(ctx, keep); onChange?.invoke(); return }
        val e = JSONObject()
            .put("id", id).put("package", pkg).put("title", show)
            .put("episode_title", if (show != title) title.replace(SE, "").trim(' ', '-', ':', '·') else JSONObject.NULL)
            .put("season", se?.groupValues?.get(1)?.toIntOrNull() ?: JSONObject.NULL)
            .put("episode", se?.groupValues?.get(2)?.toIntOrNull() ?: JSONObject.NULL)
            .put("progress", if (progress in 0.0..1.0) progress else JSONObject.NULL)
            .put("remaining_min", if (length > 0 && pos >= 0) ((length - pos) / 60_000L).toInt() else JSONObject.NULL)
            .put("last_played", System.currentTimeMillis())
            .put("who", ctx.getSharedPreferences("page", Context.MODE_PRIVATE).getString("watch.who", null) ?: JSONObject.NULL)
        art(ctx, id, md)?.let { e.put("art", it) }
        val out = JSONArray().put(e)
        for (i in 0 until minOf(keep.length(), KEEP - 1)) out.put(keep.getJSONObject(i))
        save(ctx, out)
        onChange?.invoke()
    }

    /** The artwork the app gave: a picture (saved next to the history) or an address for one. */
    private fun art(ctx: Context, id: String, md: MediaMetadata): String? {
        val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bmp != null) {
            try {
                val w = minOf(bmp.width, 640)
                val small = if (bmp.width > w) Bitmap.createScaledBitmap(bmp, w, (bmp.height.toLong() * w / bmp.width).toInt(), true) else bmp
                File(dir(ctx), "$id.jpg").outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                return "http://tvos.invalid/watch/$id.jpg"
            } catch (e: Exception) {}
        }
        return listOf(MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            .firstNotNullOfOrNull { k -> md.getString(k)?.takeIf { it.startsWith("http") } }
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "watch").apply { mkdirs() }
    private fun file(ctx: Context) = File(ctx.filesDir, "watch.json")

    @Synchronized fun load(ctx: Context): JSONArray =
        try { JSONArray(file(ctx).readText()) } catch (e: Exception) { JSONArray() }

    @Synchronized private fun save(ctx: Context, list: JSONArray) {
        file(ctx).writeText(list.toString())
        // artwork no entry uses any more
        val ids = (0 until list.length()).map { list.getJSONObject(it).optString("id") }.toSet()
        dir(ctx).listFiles()?.forEach { if (it.nameWithoutExtension !in ids) it.delete() }
    }

    fun artFile(ctx: Context, name: String): File? {
        if (!Regex("""[0-9a-f]{1,8}\.jpg""").matches(name)) return null
        return File(dir(ctx), name).takeIf { it.exists() }
    }

    fun forget(ctx: Context, id: String) {
        val list = load(ctx); val keep = JSONArray()
        for (i in 0 until list.length()) list.getJSONObject(i).let { if (it.optString("id") != id) keep.put(it) }
        save(ctx, keep)
    }
}
