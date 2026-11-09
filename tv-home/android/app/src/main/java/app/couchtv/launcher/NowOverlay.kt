package app.couchtv.launcher

import app.couchtv.launcher.home.NowBar as HomeNowBar
import app.couchtv.launcher.home.Ui as HomeUi
import app.couchtv.launcher.home.L as HomeL
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The music card over ambient mode (fork addition). While the screensaver runs, the speakers in
 * ha.json ("players") are read from Home Assistant every few seconds as the home does, and while
 * one plays the rest-mode music bar floats along the bottom, above ambient's clock: the artwork, "Playing on <speaker>", the song, the artist
 * and how far in. It has no buttons (the screensaver has the remote; any key ends it). This TV
 * has window blur switched off, so there is no box: the row sits on the photo over a soft shade.
 */
object NowOverlay {
    private const val POLL_MS = 5000L
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var app: Context? = null
    private var dreaming = false
    private var card: View? = null
    private var art: ImageView? = null
    private var where: TextView? = null
    private var title: TextView? = null
    private var artist: TextView? = null
    private var bar: ProgressBar? = null
    private var el: TextView? = null
    private var rem: TextView? = null
    private var artUrl: String? = null
    private var pos = 0.0; private var dur = 0.0; private var playing = false
    // the time moves on every second between Home Assistant's readings
    private val tick = object : Runnable {
        override fun run() {
            if (card == null) return
            if (playing) pos = minOf(pos + 1, if (dur > 0) dur else pos + 1)
            times()
            main.postDelayed(this, 1000)
        }
    }
    private fun mmss(s: Double): String { val t = maxOf(0, s.toInt()); return "${t / 60}:" + "%02d".format(t % 60) }
    private fun times() { barView?.let { it.pos = pos; it.dur = dur; it.playing = playing; it.invalidate() } }
    private var barView: HomeNowBar? = null
    private var started = false

    fun start(ctx: Context) {
        app = ctx.applicationContext
        if (started) return
        started = true
        val f = IntentFilter().apply { addAction(Intent.ACTION_DREAMING_STARTED); addAction(Intent.ACTION_DREAMING_STOPPED) }
        ctx.applicationContext.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                dreaming = i.action == Intent.ACTION_DREAMING_STARTED
                main.removeCallbacks(poll)
                if (dreaming) main.post(poll) else hide()
            }
        }, f)
    }

    private fun ha(ctx: Context): JSONObject? =
        ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getString("ha", null)?.let { try { JSONObject(it) } catch (e: Exception) { null } }

    private fun get(h: JSONObject, path: String): JSONObject? = try {
        val c = URL(h.getString("url").trimEnd('/') + path).openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 5000
        c.setRequestProperty("Authorization", "Bearer " + h.optString("token"))
        val o = if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().use { it.readText() }) else null
        c.disconnect(); o
    } catch (e: Exception) { null }

    private val poll = object : Runnable {
        override fun run() {
            val ctx = app ?: return
            if (!dreaming) return
            main.postDelayed(this, POLL_MS)
            val h = ha(ctx) ?: return
            val players = h.optJSONArray("players") ?: return
            io.execute {
                var pick: JSONObject? = null; var label: String? = null
                for (i in 0 until players.length()) {
                    val po = players.optJSONObject(i)
                    val id = po?.optString("id") ?: players.optString(i)
                    if (!Regex("media_player\\.[a-z0-9_]+").matches(id)) continue
                    val st = get(h, "/api/states/$id") ?: continue
                    if (st.optString("state") == "playing") { pick = st; label = po?.optString("name")?.takeIf { it.isNotEmpty() }; break }
                }
                val p = pick
                if (p == null) { main.post { playing = false; hide() }; return@execute }
                val a = p.optJSONObject("attributes") ?: JSONObject()
                val pic = a.optString("entity_picture").takeIf { it.isNotEmpty() }?.let { if (it.startsWith("http")) it else h.getString("url").trimEnd('/') + it }
                val bmp = if (pic != null && pic != artUrl) try {
                    val c = URL(pic).openConnection() as HttpURLConnection
                    c.setRequestProperty("Authorization", "Bearer " + h.optString("token"))
                    c.connectTimeout = 3000; c.readTimeout = 6000
                    val b = c.inputStream.use { BitmapFactory.decodeStream(it) }; c.disconnect(); b
                } catch (e: Exception) { null } else null
                var at = a.optDouble("media_position", 0.0)
                a.optString("media_position_updated_at").takeIf { it.isNotEmpty() }?.let {
                    try { at += (System.currentTimeMillis() - java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli()) / 1000.0 } catch (e: Exception) {}
                }
                val length = a.optDouble("media_duration", 0.0)
                val speaker = label ?: a.optString("friendly_name")
                main.post {
                    if (!dreaming) return@post
                    if (card == null) show(ctx)
                    barView?.let { b -> b.speaker = speaker; b.title = a.optString("media_title")
                        b.artist = a.optString("media_artist").ifEmpty { a.optString("media_album_artist") } }
                    pos = at; dur = length; playing = true; times()
                    if (bmp != null) { barView?.art = bmp; artUrl = pic }
                }
            }
        }
    }

    private fun show(ctx: Context) {
        if (!Settings.canDrawOverlays(ctx)) return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val px = ctx.resources.displayMetrics.widthPixels / 1920f   // sizes as the home's 1080p page
        fun d(v: Float) = (v * px).toInt()
        // the resting music bar, the same as on the home (home/NowBar.kt), on a dark glass of its own
        HomeUi.init(ctx); HomeL.load(ctx)
        val nb = HomeNowBar(ctx, null)
        barView = nb
        // no box: the row sits on the photo, over a soft shade that darkens the bottom of the screen
        // from clear to about half (as ambient does behind its own clock and captions)
        val shade = FrameLayout(ctx)   // no darkening: the screensaver shows as it is, only the bar sits on it
        shade.addView(nb, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = d(80f); rightMargin = d(80f); bottomMargin = d(120f)   // above ambient's clock and caption
        })
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, d(380f),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM }
        try { wm.addView(shade, lp) } catch (ex: Exception) { return }
        card = shade; artUrl = null
        shade.alpha = 0f
        shade.animate().alpha(1f).setDuration(600).start()
        main.removeCallbacks(tick); main.postDelayed(tick, 1000)
    }

    private fun hide() {
        val v = card ?: return
        card = null; barView = null; artUrl = null
        main.removeCallbacks(tick)
        val ctx = app ?: return
        v.animate().alpha(0f).setDuration(300).withEndAction {
            try { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Exception) {}
        }.start()
    }
}
