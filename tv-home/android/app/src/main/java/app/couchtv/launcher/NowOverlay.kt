package app.couchtv.launcher

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
 * has window blur switched off, so the card is a translucent dark pane rather than real glass.
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
    private fun times() {
        el?.text = mmss(pos)
        rem?.text = if (dur > 0) "\u2212" + mmss(dur - pos) else ""
        bar?.progress = if (dur > 0) (pos / dur * 1000).toInt().coerceIn(0, 1000) else 0
    }
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
                    where?.text = "Playing on $speaker"
                    title?.text = a.optString("media_title")
                    artist?.text = a.optString("media_artist").ifEmpty { a.optString("media_album_artist") }
                    pos = at; dur = length; playing = true; times()
                    if (bmp != null) { art?.setImageBitmap(bmp); artUrl = pic }
                }
            }
        }
    }

    private fun show(ctx: Context) {
        if (!Settings.canDrawOverlays(ctx)) return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val px = ctx.resources.displayMetrics.widthPixels / 1920f   // sizes as the home's 1080p page
        fun d(v: Float) = (v * px).toInt()
        val r = 44 * px
        // the rest-mode music bar (idle.css .np-wide): one row the dock's width
        val pane = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(d(20f), d(20f), d(36f), d(20f))
            // the dock's glass as near as a window can come with blur switched off on this TV: a milky
            // white wash, lighter at the top like the dock's specular light, and its bright rim
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#4DFFFFFF"), Color.parseColor("#33FFFFFF"))).apply {
                cornerRadius = r
                setStroke(d(2f).coerceAtLeast(1), Color.parseColor("#66FFFFFF"))
            }
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, r) } }
            clipToOutline = true; elevation = 8 * px
        }
        val img = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.parseColor("#33FFFFFF"))
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, 20 * px) } }
            clipToOutline = true
        }
        pane.addView(img, LinearLayout.LayoutParams(d(104f), d(104f)))
        fun line(sizePx: Float, color: String, medium: Boolean) = TextView(ctx).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizePx * px)
            setTextColor(Color.parseColor(color)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            typeface = android.graphics.Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", android.graphics.Typeface.NORMAL)
            setShadowLayer(8 * px, 0f, 1 * px, Color.parseColor("#8C000000"))   // readable over a bright photo
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(d(18f), 0, d(24f), 0) }
        val w = line(18f, "#CCFFFFFF", true); val t = line(28f, "#FFFFFFFF", true); val a = line(26f, "#D9FFFFFF", false)
        col.addView(w); col.addView(t); col.addView(a)
        pane.addView(col, LinearLayout.LayoutParams(d(520f), LinearLayout.LayoutParams.WRAP_CONTENT))
        val e = line(20f, "#D9FFFFFF", false).apply { gravity = Gravity.END }
        pane.addView(e, LinearLayout.LayoutParams(d(64f), LinearLayout.LayoutParams.WRAP_CONTENT))
        val pb = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressDrawable = android.graphics.drawable.LayerDrawable(arrayOf(
                GradientDrawable().apply { setColor(Color.parseColor("#59FFFFFF")); cornerRadius = 8 * px },
                android.graphics.drawable.ClipDrawable(GradientDrawable().apply { setColor(Color.parseColor("#FFFFFFFF")); cornerRadius = 8 * px }, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
            )).apply { setId(0, android.R.id.background); setId(1, android.R.id.progress) }
        }
        pane.addView(pb, LinearLayout.LayoutParams(0, d(8f), 1f).apply { leftMargin = d(16f); rightMargin = d(16f) })
        val rm = line(20f, "#D9FFFFFF", false)
        pane.addView(rm, LinearLayout.LayoutParams(d(72f), LinearLayout.LayoutParams.WRAP_CONTENT))
        // the controls, as on the bar (the screensaver has the remote, so they only show the state)
        // the controls, drawn (the TV's font only has the emoji kind); they only show the state:
        // the screensaver has the remote
        val ctl = object : View(ctx) {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; setShadowLayer(6 * px, 0f, 1 * px, Color.parseColor("#66000000"))
            }
            override fun onDraw(c: android.graphics.Canvas) {
                val h = height.toFloat(); val u = h / 2; val cy = h / 2
                fun tri(x: Float, dir: Float) {
                    val p = android.graphics.Path(); p.moveTo(x, cy - u * 0.55f); p.lineTo(x + dir * u * 0.8f, cy); p.lineTo(x, cy + u * 0.55f); p.close(); c.drawPath(p, paint)
                }
                // rewind: two triangles pointing left
                tri(u * 1.6f, -1f); tri(u * 2.4f, -1f)
                // pause: two rounded bars
                val px0 = u * 4.2f
                c.drawRoundRect(px0, cy - u * 0.6f, px0 + u * 0.38f, cy + u * 0.6f, u * 0.1f, u * 0.1f, paint)
                c.drawRoundRect(px0 + u * 0.7f, cy - u * 0.6f, px0 + u * 1.08f, cy + u * 0.6f, u * 0.1f, u * 0.1f, paint)
                // forward: two triangles pointing right
                tri(u * 6.6f, 1f); tri(u * 7.4f, 1f)
            }
        }
        ctl.setLayerType(View.LAYER_TYPE_SOFTWARE, null)   // for the soft shadow
        pane.addView(ctl, LinearLayout.LayoutParams(d(150f), d(38f)).apply { leftMargin = d(28f) })
        val lp = WindowManager.LayoutParams(ctx.resources.displayMetrics.widthPixels - d(160f), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = d(120f) }   // above ambient's clock and caption
        try { wm.addView(pane, lp) } catch (ex: Exception) { return }
        card = pane; art = img; where = w; title = t; artist = a; bar = pb; el = e; rem = rm; artUrl = null
        pane.alpha = 0f; pane.translationY = 40 * px
        pane.animate().alpha(1f).translationY(0f).setDuration(500).start()
        main.removeCallbacks(tick); main.postDelayed(tick, 1000)
    }

    private fun hide() {
        val v = card ?: return
        card = null; art = null; where = null; title = null; artist = null; bar = null; el = null; rem = null; artUrl = null
        main.removeCallbacks(tick)
        val ctx = app ?: return
        v.animate().alpha(0f).setDuration(300).withEndAction {
            try { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Exception) {}
        }.start()
    }
}
