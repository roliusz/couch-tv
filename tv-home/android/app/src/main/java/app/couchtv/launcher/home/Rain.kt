package app.couchtv.launcher.home

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Rain on the glass while it rains outside, the Home Assistant dashboard's own effect
 * (casa-v3.js): raindrop-fx with the dashboard's settings, run in a web view (rain.html) that
 * sits over the scene, its clock and the date, under the cards and the dock.
 *
 * The library draws its own background and bends it through the drops, so it is handed a
 * picture of exactly what the screen shows beneath it (the scene as drawn, clock and cut-out
 * included, and the date line), taken again when the minute, the scene or the dimming changes.
 * It switches on from the same signals the dashboard reads: Home Assistant's weather entity
 * saying rain, or the household's rain switch (ha.json "rain").
 *
 * The web view exists only while it rains; it is shown once the rain has painted and faded out
 * and let go of when it stops. While the top shelf shows a show's artwork the rain stands aside.
 */
@SuppressLint("ViewConstructor", "SetJavaScriptEnabled")
class RainLayer(private val home: HomeActivity) : FrameLayout(home) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var web: WebView? = null
    private var pageUp = false
    private var shown = 0f
    private val fade = Spring(0f, 0.002f) { shown = it; apply() }
    var on = false; private set

    init { visibility = INVISIBLE; isFocusable = false; Motion.frameHooks.add { if (web != null) apply() } }

    /** The top shelf's artwork comes first: the rain fades as it comes up. */
    private fun apply() {
        val a = shown * (1f - Glass.shelfOn.coerceIn(0f, 1f))
        alpha = a
        visibility = if (a <= 0.001f) INVISIBLE else VISIBLE
    }

    fun want(rain: Boolean) {
        if (rain == on) return
        on = rain
        if (rain) start() else stop()
    }

    private fun start() {
        if (web != null) return
        Log.i(TAG, "raining: on")
        val w = WebView(context)
        w.setBackgroundColor(Color.BLACK)
        w.settings.javaScriptEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = true
        w.isFocusable = false; w.isFocusableInTouchMode = false
        w.addJavascriptInterface(Bridge(), "TVRain")
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) { pageUp = true; again() }
        }
        addView(w, LayoutParams(-1, -1))
        web = w
        pageUp = false
        w.loadUrl("file:///android_asset/www/tv/rain.html")
    }

    private fun stop() {
        Log.i(TAG, "raining: off")
        main.removeCallbacks(rebuild)
        fade.to(0f, 0.6f)
        main.postDelayed({ if (!on) drop() }, 900)
    }
    private fun drop() {
        val w = web ?: return
        web = null; pageUp = false
        try { w.evaluateJavascript("rainStop()", null) } catch (e: Exception) {}
        removeView(w); w.destroy()
        fade.snap(0f)
    }

    /** The home went away (another app) or came back: the rain's own clock stops and starts with it. */
    fun pause() { web?.let { it.onPause(); it.pauseTimers() } }
    fun resume() { web?.let { it.resumeTimers(); it.onResume() }; if (on) again() }

    /** What is beneath has changed: a new picture for the rain, once things settle. */
    fun again() {
        if (!on || !pageUp) return
        main.removeCallbacks(rebuild)
        main.postDelayed(rebuild, 450)
    }
    private val rebuild = Runnable { picture() }

    private fun picture() {
        val w = web ?: return
        val bmp = snapshot(listOf(home.scene, home.date)) ?: return
        io.execute {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
            bmp.recycle()
            val url = "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            main.post { if (web === w) w.evaluateJavascript("rainBackground('$url')", null) }
        }
    }

    // ---- the picture beneath, at full size, drawn as the screen draws it ----
    private var r: HardwareRenderer? = null
    private var reader: ImageReader? = null
    private val node = RenderNode("rain-under")
    private fun snapshot(views: List<View>): Bitmap? = try {
        val w = Ui.width; val h = Ui.height
        val rd = reader ?: ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT).also { reader = it }
        val hr = r ?: HardwareRenderer().also { it.setSurface(rd.surface); it.setContentRoot(node); r = it }
        node.setPosition(0, 0, w, h)
        val c = node.beginRecording(w, h)
        try {
            c.drawColor(Color.BLACK)
            for (v in views) {
                if (v.visibility != View.VISIBLE || v.alpha <= 0f) continue
                val n = c.save()
                c.translate(v.left.toFloat(), v.top.toFloat())
                if (v.alpha < 1f) c.saveLayerAlpha(0f, 0f, v.width.toFloat(), v.height.toFloat(), (255 * v.alpha).toInt())
                if (v is SceneView) v.paintInto(c) else v.draw(c)
                c.restoreToCount(n)
            }
        } finally { node.endRecording() }
        hr.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        val img = rd.acquireLatestImage()
        try {
            val hb = img?.hardwareBuffer ?: throw IllegalStateException("no frame")
            val out = Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB))?.copy(Bitmap.Config.ARGB_8888, false)
            hb.close(); out
        } finally { img?.close() }
    } catch (e: Throwable) { Log.w(TAG, "picture: $e"); null }

    private inner class Bridge {
        @JavascriptInterface fun ready() { main.post { if (on && web != null) { Log.i(TAG, "raining: painted"); fade.to(1f, 0.8f) } } }
        @JavascriptInterface fun failed(why: String) { Log.w(TAG, "rain failed: $why") }
    }

    companion object { const val TAG = "Rain" }
}
