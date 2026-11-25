package app.couchtv.launcher.home

import android.graphics.*
import android.view.View

/**
 * The look of the dashboard's Christmas mode besides the snow (casa-v3-style.js): the scene behind
 * a frosted window (its photo and cut-out softly blurred, the clock in front of the blur, a cold
 * haze thickening towards the edges over all of it) and a faint green, red and gold light over the
 * whole screen. Nothing in it moves, so each picture is made once and only drawn after that.
 */
object Xmas {
    /** Whether new pictures (a scene's photo, its glass) are to be made frosted. */
    @Volatile var on = false

    private fun ellipse(c: Canvas, w: Float, h: Float, rx: Float, ry: Float, cx: Float, cy: Float, cols: IntArray, stops: FloatArray, p: Paint) {
        p.shader = RadialGradient(0f, 0f, 1f, cols, stops, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(rx * w, ry * h); postTranslate(cx * w, cy * h) })
        }
        c.drawRect(0f, 0f, w, h, p)
    }
    /** .xfrost: flat haze everywhere, thicker towards the edges. */
    val haze: Bitmap by lazy {
        Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b)
            c.drawColor(Color.argb(31, 214, 234, 255))
            ellipse(c, 480f, 270f, 1.35f, 1f, 0.5f, 0.44f,
                intArrayOf(Color.argb(0, 222, 240, 255), Color.argb(0, 222, 240, 255), Color.argb(51, 222, 240, 255), Color.argb(102, 236, 247, 255)),
                floatArrayOf(0f, 0.38f, 0.76f, 1f), Paint(Paint.DITHER_FLAG))
        }
    }
    /** .xglow on black: screened over the screen, black leaves it as it is. */
    val glow: Bitmap by lazy {
        Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b); val p = Paint(Paint.DITHER_FLAG)
            c.drawColor(Color.BLACK)
            fun spot(rx: Float, ry: Float, cx: Float, cy: Float, r: Int, g: Int, bl: Int, a: Float) =
                ellipse(c, 480f, 270f, rx, ry, cx, cy, intArrayOf(Color.argb((255 * a).toInt(), r, g, bl), Color.argb(0, r, g, bl), Color.argb(0, r, g, bl)), floatArrayOf(0f, 0.72f, 1f), p)
            spot(0.48f, 0.34f, 1f, 0.02f, 226, 178, 86, 0.16f)
            spot(0.56f, 0.42f, 1f, 1f, 196, 46, 52, 0.22f)
            spot(0.58f, 0.44f, 0.06f, 0f, 38, 140, 74, 0.24f)
        }
    }
    /** A picture behind the frosted window: saturate(.88) brightness(1.06) and half the dashboard's
     *  blur(0.5vw), which read too heavy on a TV; at half size. Premultiplied while blurring, so a cut-out's edge does not pick up black. */
    fun frosted(src: Bitmap): Bitmap {
        val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val w = Ui.width / 2; val h = maxOf(1, w * soft.height / soft.width)
        // halved step by step: one big shrink skips pixels and leaves the blur grainy
        var small = soft
        while (small.width > w * 2) { val n = Bitmap.createScaledBitmap(small, small.width / 2, maxOf(1, small.height / 2), true); if (small !== soft) small.recycle(); small = n }
        val last = Bitmap.createScaledBitmap(small, w, h, true); if (small !== soft && small !== last) small.recycle()
        if (soft !== src && soft !== last) soft.recycle()
        val px = IntArray(w * h); last.getPixels(px, 0, w, 0, 0, w, h)
        val cut = last.hasAlpha()
        if (cut) for (i in px.indices) { val v = px[i]; val a = v ushr 24; px[i] = (a shl 24) or (((v shr 16 and 255) * a / 255) shl 16) or (((v shr 8 and 255) * a / 255) shl 8) or ((v and 255) * a / 255) }
        Glass.blurPixels(px, IntArray(w * h), w, h, 2)   // about 5 px on screen
        if (cut) for (i in px.indices) { val v = px[i]; val a = v ushr 24; px[i] = if (a == 0) 0 else (a shl 24) or (minOf(255, (v shr 16 and 255) * 255 / a) shl 16) or (minOf(255, (v shr 8 and 255) * 255 / a) shl 8) or minOf(255, (v and 255) * 255 / a) }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        Glass.saturate(out, 0.88f, 1.06f)
        return out.copy(Bitmap.Config.HARDWARE, false) ?: out
    }
}

/** The green, red and gold light, over the cards and the dock so the whole home shares it. */
class XmasGlow(ctx: android.content.Context) : View(ctx) {
    private val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.SCREEN }
    private val dst = RectF()
    private val fade = Spring(0f, 0.002f) { v -> p.alpha = (255 * v.coerceIn(0f, 1f)).toInt(); visibility = if (v <= 0.002f) GONE else VISIBLE; invalidate() }
    init { visibility = GONE; isFocusable = false }
    // blended straight onto the screen: never through a layer of its own
    override fun hasOverlappingRendering() = false
    fun want(on: Boolean) = fade.to(if (on) 1f else 0f, 0.8f)
    override fun onDraw(c: Canvas) { dst.set(0f, 0f, width.toFloat(), height.toFloat()); c.drawBitmap(Xmas.glow, null, dst, p) }
}
