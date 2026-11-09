package app.couchtv.launcher.home

import android.content.Context
import android.graphics.*
import android.view.View
import android.view.ViewOutlineProvider

/**
 * Anything that takes focus: one focus value (0 to 1) on a spring, 0.12 s in and 0.39 s out, and
 * a press that pushes it in (motion.js).
 */
abstract class Tile(ctx: Context) : View(ctx) {
    var focusT = 0f; private set
    var pressT = 0f; private set
    var focused = false; private set
    private val fs = Spring(0f) { focusT = it; applied() }
    private val ps = Spring(0f) { pressT = it; applied() }
    open val scaleFocus = 1.1f
    open val liftFocus = 0f
    fun focus(on: Boolean) {
        if (on == focused) return
        focused = on
        fs.to(if (on) 1f else 0f, if (on) Motion.FOCUS else Motion.UNFOCUS)
        translationZ = if (on) 2f else 0f
    }
    fun snapFocus(on: Boolean) { focused = on; fs.snap(if (on) 1f else 0f); translationZ = if (on) 2f else 0f }
    fun press(on: Boolean) = ps.to(if (on) 1f else 0f, if (on) Motion.PRESS else Motion.FOCUS)
    protected open fun applied() {
        val s = (1f + (scaleFocus - 1f) * focusT) * (1f - 0.06f * pressT)
        scaleX = s; scaleY = s
        if (liftFocus != 0f) translationY = -Ui.r(liftFocus) * focusT   // only what lifts: never undoes a glide
        invalidate()
        (parent as? ShadowFrame)?.invalidate()   // the shadow under it follows
    }
    fun rounded(rad: Float, shadow: Boolean = true) {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, rad); if (!shadow) o.alpha = 0f }
        }
        clipToOutline = true
    }
}

/** An app in the dock or in More apps: its icon, rounded, growing to 1.22 when focused. */
class AppTile(ctx: Context, val item: AppItem, var icon: Bitmap?) : Tile(ctx) {
    override val scaleFocus = 1.22f
    var isNew = false
    var lifted = false
        set(v) { field = v; wiggle = 0f; invalidate() }
    var dimmed = false
        set(v) { if (v != field) { field = v; alpha = if (v) 0.7f else 1f } }   // only on a change: never undoes a fade-in
    private var wiggle = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0A84FF.toInt() }
    private val label = Ui.paint(Ui.interBold, 1.125f)
    private val rad = Ui.r(1.625f)
    private val clip = Path()
    init { elevation = Ui.r(0.5f); rounded(rad); clipToOutline = false
        outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, rad); if (icon == null) o.alpha = 0.4f } } }
    override fun applied() {
        super.applied()
        elevation = Ui.r(0.5f) + Ui.r(2.5f) * focusT
        outlineAmbientShadowColor = 0x80000000.toInt(); outlineSpotShadowColor = 0xB3000000.toInt()
        if (lifted) { val s = 1.2f; scaleX = s; scaleY = s; translationY = -Ui.r(1.5f) }
    }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        if (lifted) { wiggle += 0.16f; c.rotate(Math.sin(wiggle.toDouble()).toFloat() * 1.6f, w / 2, w / 2); postInvalidateOnAnimation() }
        clip.reset(); clip.addRoundRect(0f, 0f, w, w, rad, rad, Path.Direction.CW)
        val n = c.save(); c.clipPath(clip)
        val ic = icon
        when {
            ic != null -> c.drawBitmap(ic, null, RectF(0f, 0f, w, w), p)
            item.kind == "more" -> { face.color = 0x24FFFFFF; c.drawRect(0f, 0f, w, w, face); Icons.more.draw(c, w * 0.25f, w * 0.25f, w * 0.5f, Color.WHITE, 2.2f) }
            item.store -> { face.color = 0x24FFFFFF; c.drawRect(0f, 0f, w, w, face); Icons.store.draw(c, w * 0.25f, w * 0.25f, w * 0.5f, Color.WHITE) }
            else -> { face.color = 0xFF2A2A30.toInt(); c.drawRect(0f, 0f, w, w, face); label.textAlign = Paint.Align.CENTER
                c.drawText(Ui.fit(label, item.name, w - Ui.r(1f)), w / 2, w / 2 + label.textSize / 3, label) }
        }
        c.restoreToCount(n)
        if (lifted) { face.style = Paint.Style.STROKE; face.strokeWidth = Ui.r(0.25f); face.color = Color.WHITE; c.drawRoundRect(0f, 0f, w, w, rad, rad, face); face.style = Paint.Style.FILL }
        if (isNew) c.drawCircle(w - Ui.r(0.75f), Ui.r(0.75f), Ui.r(0.5f), dot)
    }
}

/**
 * A card in the row: a show (its artwork with a blurred, darkened band, the app's icon top right,
 * the title, a play or calendar symbol, how far in), or a home card on the dock's glass.
 */
class CardView(ctx: Context, var show: Show, private val shows: Shows, private val apps: Apps, private val homeDraw: ((Canvas, CardView) -> Unit)?) : Tile(ctx) {
    override val scaleFocus = 1.1f
    var art: Bitmap? = null
        set(v) { field = v; blur = v?.let { bake(it) }; invalidate() }
    private var blur: Bitmap? = null
    var screenX: () -> Float = { 0f }
    var screenY: () -> Float = { 0f }
    var turning = 1f
        set(v) { field = v; invalidate() }
    val face = Glass.Face()
    private val artP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val band = Paint()
    private val none = Paint()
    val titleP = Ui.paint(Ui.interBold, 1.375f)
    val metaP = Ui.paint(Ui.interBold, 1.1875f, 0xE6FFFFFF.toInt())
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rad = Ui.r(1.5f)
    init {
        val glassy = homeDraw != null
        outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, rad); if (glassy) o.alpha = 0f } }
        clipToOutline = true; elevation = Ui.r(0.5f)
    }
    override fun applied() { super.applied(); elevation = Ui.r(0.75f) + Ui.r(2f) * focusT }
    init { outlineAmbientShadowColor = 0x80000000.toInt(); outlineSpotShadowColor = 0xB3000000.toInt() }

    /** The band's blur, baked once: the artwork at 48 px wide, saturated, faded in from the middle down. */
    private fun bake(a: Bitmap): Bitmap {
        val W = 48; val H = 27
        val src = if (a.config == Bitmap.Config.HARDWARE) a.copy(Bitmap.Config.ARGB_8888, false) else a
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.4f) }) }
        c.drawBitmap(src, null, coverRect(src, W.toFloat(), H.toFloat()), p)
        if (src !== a) src.recycle()
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            shader = LinearGradient(0f, 0f, 0f, H.toFloat(), intArrayOf(0, 0, Color.BLACK, Color.BLACK), floatArrayOf(0f, 0.48f, 0.74f, 1f), Shader.TileMode.CLAMP)
        })
        return b
    }
    private fun coverRect(b: Bitmap, w: Float, h: Float): RectF {
        val k = maxOf(w / b.width, h / b.height); val bw = b.width * k; val bh = b.height * k
        return RectF((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (homeDraw != null) {
            val loc = IntArray(2); getLocationOnScreen(loc)   // where it is now, grown and moved
            face.draw(c, w, h, rad, loc[0].toFloat(), loc[1].toFloat(), Ui.mix(Glass.WASH, Glass.WASH_FOCUS, focusT), s = scaleX)
            homeDraw.invoke(c, this)
            return
        }
        val s = show
        val alpha = (255 * turning).toInt()
        val a = art
        if (a != null) {
            artP.alpha = alpha; c.drawBitmap(a, null, coverRect(a, w, h), artP)
            blur?.let { c.drawBitmap(it, null, RectF(0f, 0f, w, h), artP) }
        } else {
            none.shader = LinearGradient(0f, 0f, w, h, 0xFF3A3A44.toInt(), 0xFF18181D.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, none)
            val big = Ui.paint(Ui.interBold, 1.75f, 0xD9FFFFFF.toInt()).apply { textAlign = Paint.Align.CENTER; this.alpha = (217 * turning).toInt() }
            c.drawText(Ui.fit(big, s.title, w - Ui.r(3f)), w / 2, h * 0.42f, big)
        }
        val top = h - Ui.r(7.35f)
        if (band.shader == null) band.shader = LinearGradient(0f, top, 0f, h, intArrayOf(0, 0x59000000, 0x8C000000.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, top, w, h, band)
        // the service's icon, top right
        s.app?.let { app -> apps.cachedIcon(app.pkg)?.let { ic ->
            val sz = Ui.r(2.25f); val x = w - Ui.r(0.875f) - sz; val y = Ui.r(0.875f)
            val n = c.save(); val p = Path().apply { addRoundRect(x, y, x + sz, y + sz, Ui.r(0.5f), Ui.r(0.5f), Path.Direction.CW) }; c.clipPath(p)
            artP.alpha = alpha; c.drawBitmap(ic, null, RectF(x, y, x + sz, y + sz), artP); c.restoreToCount(n)
        } }
        // title, then the symbol, how far in, and the episode
        val l = Ui.r(1.25f)
        titleP.alpha = alpha; metaP.alpha = (230 * turning).toInt()
        c.drawText(Ui.fit(titleP, s.title, w - 2 * l), l, h - Ui.r(3.2f), titleP)
        var x = l; val my = h - Ui.r(1.35f)
        val ic = if (s.premiere) Icons.calendar else Icons.playSmall
        ic.draw(c, x, my - Ui.r(1.0f), Ui.r(1.125f), Ui.alpha(Color.WHITE, turning))
        x += Ui.r(1.125f + 0.625f)
        s.progress?.let { pr ->
            val bw = Ui.r(2.5f); val by = my - Ui.r(0.5f)
            bar.color = Ui.alpha(0x59FFFFFF, turning); c.drawRoundRect(x, by, x + bw, by + Ui.r(0.25f), Ui.r(0.125f), Ui.r(0.125f), bar)
            bar.color = Ui.alpha(Color.WHITE, turning); c.drawRoundRect(x, by, x + bw * maxOf(0.04f, pr.toFloat()), by + Ui.r(0.25f), Ui.r(0.125f), Ui.r(0.125f), bar)
            x += bw + Ui.r(0.625f)
        }
        c.drawText(Ui.fit(metaP, shows.meta(s), w - x - l), x, my, metaP)
    }
}
