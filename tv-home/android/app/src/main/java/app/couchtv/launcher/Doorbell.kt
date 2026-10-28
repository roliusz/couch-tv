package app.couchtv.launcher

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The doorbell on the TV (fork addition), as on the tablet: Home Assistant's "Doorbell Camera"
 * automation turns a helper on for 45 seconds when someone is at the door (ha.json "doorbell":
 * helper, camera, name). While the TV is on, the launcher looks at that helper every few seconds;
 * when it is on, a card with the door camera slides in at the top right over whatever is playing,
 * and goes when the helper goes off. The camera runs on a battery, so it shows snapshots (every two
 * seconds), as the dashboards do, not a live stream. The card never takes the remote: what is
 * playing keeps playing and is controlled as usual.
 *
 * Over other apps it needs "display over other apps", which apply.sh grants over ADB.
 */
object Doorbell {
    private const val CHECK_MS = 3000L
    private const val PICTURE_MS = 2000L
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var app: Context? = null
    private var card: View? = null
    private var picture: ImageView? = null
    private var started = false

    fun start(ctx: Context) {
        app = ctx.applicationContext
        if (started) return
        started = true
        main.post(check)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)
    private fun ha(ctx: Context): JSONObject? =
        prefs(ctx).getString("ha", null)?.let { try { JSONObject(it) } catch (e: Exception) { null } }
    private fun bell(ctx: Context): JSONObject? = ha(ctx)?.optJSONObject("doorbell")
        ?.takeIf { Regex("input_boolean\\.[a-z0-9_]+").matches(it.optString("helper")) && Regex("camera\\.[a-z0-9_]+").matches(it.optString("camera")) }

    private fun open(h: JSONObject, path: String): HttpURLConnection {
        val c = URL(h.getString("url").trimEnd('/') + path).openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 6000
        c.setRequestProperty("Authorization", "Bearer " + h.optString("token"))
        return c
    }

    private val check = object : Runnable {
        override fun run() {
            val ctx = app ?: return
            main.postDelayed(this, CHECK_MS)
            val awake = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
            val h = ha(ctx); val b = bell(ctx)
            if (!awake || h == null || b == null) { if (card != null) hide(); return }
            io.execute {
                val on = try {
                    val c = open(h, "/api/states/" + b.getString("helper"))
                    val s = if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optString("state") else null
                    c.disconnect(); s == "on"
                } catch (e: Exception) { null }
                if (on == null) return@execute   // Home Assistant not answering: leave things as they are
                main.post { if (on && card == null) show(ctx, b) else if (!on && card != null) hide() }
            }
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            val ctx = app ?: return
            if (card == null) return
            val h = ha(ctx); val b = bell(ctx)
            if (h != null && b != null) io.execute {
                try {
                    val c = open(h, "/api/camera_proxy/" + b.getString("camera"))
                    val bmp = if (c.responseCode == 200) c.inputStream.use { BitmapFactory.decodeStream(it) } else null
                    c.disconnect()
                    if (bmp != null) main.post { picture?.setImageBitmap(bmp) }
                } catch (e: Exception) {}
            }
            main.postDelayed(this, PICTURE_MS)
        }
    }

    private fun show(ctx: Context, b: JSONObject) {
        if (!Settings.canDrawOverlays(ctx)) return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dp = ctx.resources.displayMetrics.density
        val w = (720 * dp / 1.5f).toInt(); val ht = w * 9 / 16
        val frame = FrameLayout(ctx).apply {
            background = GradientDrawable().apply { setColor(Color.parseColor("#E61C1C21")); cornerRadius = 22 * dp }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, 22 * dp) }
            }
            clipToOutline = true
            elevation = 12 * dp
        }
        val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.BLACK) }
        frame.addView(img, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // the name on a soft dark band along the bottom, as the Keep watching cards
        val band = View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Color.parseColor("#99000000")))
        }
        frame.addView(band, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (56 * dp).toInt(), Gravity.BOTTOM))
        val label = TextView(ctx).apply {
            text = "🔔  " + b.optString("name").ifEmpty { "Doorbell" }
            setTextColor(Color.WHITE); textSize = 15f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setShadowLayer(6f, 0f, 1f, Color.parseColor("#80000000"))
            setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), (12 * dp).toInt())
        }
        frame.addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        val lp = WindowManager.LayoutParams(w, ht, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (32 * dp).toInt(); y = (32 * dp).toInt()
        }
        try { wm.addView(frame, lp) } catch (e: Exception) { return }
        card = frame; picture = img
        frame.alpha = 0f; frame.translationY = -24 * dp; frame.scaleX = 0.96f; frame.scaleY = 0.96f
        frame.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(420)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.8f, 0.2f, 1f)).start()
        main.removeCallbacks(refresh); main.post(refresh)
    }

    private fun hide() {
        val v = card ?: return
        card = null; picture = null
        main.removeCallbacks(refresh)
        val ctx = app ?: return
        v.animate().alpha(0f).translationY(-16 * ctx.resources.displayMetrics.density).setDuration(300).withEndAction {
            try { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Exception) {}
        }.start()
    }
}
