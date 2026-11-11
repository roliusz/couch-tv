package app.couchtv.launcher.home

import android.content.Context
import android.graphics.*
import android.graphics.Typeface
import android.view.Choreographer
import app.couchtv.launcher.R

/**
 * The native home's basics: sizes in the web home's rem (1/120 of the screen width, so 16 px at
 * 1920), its two fonts, Apple-style springs and the frosted glass every panel shares.
 */
object Ui {
    var rem = 16f
    var width = 1920
    var height = 1080
    lateinit var inter: Typeface
    lateinit var interMid: Typeface
    lateinit var interBold: Typeface
    lateinit var antonio: Typeface

    fun init(ctx: Context) {
        val dm = ctx.resources.displayMetrics
        width = maxOf(dm.widthPixels, dm.heightPixels); height = minOf(dm.widthPixels, dm.heightPixels)
        rem = width / 120f
        inter = ctx.resources.getFont(R.font.inter_regular)
        interBold = ctx.resources.getFont(R.font.inter_semibold)
        interMid = Typeface.create(interBold, 500, false)
        antonio = ctx.resources.getFont(R.font.antonio_light)
    }

    fun r(x: Float) = x * rem
    fun ri(x: Float) = (x * rem).toInt()

    fun paint(face: Typeface = inter, size: Float = 1.4375f, color: Int = Color.WHITE) =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = face; textSize = r(size); this.color = color }

    /** Text cut with an ellipsis to fit a width. */
    fun fit(p: Paint, s: String, w: Float): String {
        if (p.measureText(s) <= w) return s
        var n = s.length
        while (n > 0 && p.measureText(s, 0, n) + p.measureText("…") > w) n--
        return s.substring(0, n).trimEnd() + "…"
    }

    fun alpha(c: Int, a: Float) = Color.argb((Color.alpha(c) * a).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 255) + ((((b shr s) and 255) - ((a shr s) and 255)) * t)).toInt().coerceIn(0, 255) shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }
}

/**
 * A spring set by a duration and a bounce, as tvOS (WWDC23 "Animate with springs"): a new target
 * keeps the velocity, so quick presses flow into each other. Driven by [Motion].
 */
class Spring(var x: Float, private val eps: Float = 0.0005f, val apply: (Float) -> Unit) {
    var v = 0f; var t = x
    private var k = 0f; private var c = 0f
    fun to(target: Float, d: Float, bounce: Float = 0f) {
        if (Motion.calm) { snap(target); return }
        t = target
        val w = (2 * Math.PI / d).toFloat()
        k = w * w; c = (4 * Math.PI * (1 - bounce) / d).toFloat()
        if (Math.abs(x - t) > eps || Math.abs(v) > eps) Motion.add(this)
    }
    fun snap(target: Float) { x = target; t = target; v = 0f; apply(x) }
    /** One step; false once it has come to rest. */
    fun step(dt: Float): Boolean {
        var left = dt
        while (left > 0f) {   // small steps keep a stiff spring stable on a slow frame
            val h = minOf(left, 1 / 240f)
            val a = -k * (x - t) - c * v
            v += a * h; x += v * h; left -= h
        }
        val rest = Math.abs(x - t) < eps && Math.abs(v) < eps * 10
        if (rest) { x = t; v = 0f }
        apply(x)
        return !rest
    }
}

object Motion : Choreographer.FrameCallback {
    // the focus timings measured from tvOS 26 (motion.js)
    const val FOCUS = 0.12f
    const val UNFOCUS = 0.39f
    const val PRESS = 0.14f
    const val KICK = 0.4f
    const val ROW = 0.3f

    /** Android TV's "Remove animations": everything moves at once. */
    var calm = false
    private val live = LinkedHashSet<Spring>()
    private var last = 0L
    private var running = false
    val frameHooks = ArrayList<() -> Unit>()

    fun add(s: Spring) {
        live.add(s)
        if (!running) { running = true; last = 0L; Choreographer.getInstance().postFrameCallback(this) }
    }
    override fun doFrame(now: Long) {
        val dt = if (last == 0L) 1 / 60f else ((now - last) / 1e9f).coerceIn(0.001f, 0.05f)
        last = now
        val it = live.iterator()
        while (it.hasNext()) if (!it.next().step(dt)) it.remove()
        frameHooks.forEach { it() }
        if (live.isNotEmpty()) Choreographer.getInstance().postFrameCallback(this) else running = false
    }
}

/**
 * The one frosted glass: the scene's photo shrunk once (made on the TV, so any photo works) and
 * drawn as a shader lined up with the screen, with the dock's white wash and its rim. Nothing is
 * blurred per frame. A new scene crossfades its glass with the photo.
 */
object Glass {
    var now: Bitmap? = null
    var old: Bitmap? = null
    var t = 1f
    val listeners = ArrayList<() -> Unit>()
    const val WASH = 0x1FFFFFFF
    const val WASH_FOCUS = 0x38FFFFFF
    /** Over a light background the glass stays the same white; its shadow shows its shape (HomeActivity). */
    const val WASH_LIGHT = WASH
    const val RIM = 0x66FFFFFF

    fun changed() = listeners.forEach { it() }

    /**
     * The live glass: what is behind it (the scene, or a show's artwork) recorded at a quarter of
     * the screen into a RenderNode the graphics chip blurs (10 px) and saturates (150%), as
     * glass.css's backdrop-filter does. Re-recorded whenever that picture changes, in the same frame,
     * so the dock and cards never lag behind it. Every glass piece draws this one node.
     */
    var live: android.graphics.RenderNode? = null
    private var first = true
    private fun invalidateAll(v: android.view.View) { v.invalidate(); if (v is android.view.ViewGroup) for (i in 0 until v.childCount) invalidateAll(v.getChildAt(i)) }
    private var inner: android.graphics.RenderNode? = null
    fun record(views: List<android.view.View>) {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        val w = Ui.width / 4; val h = Ui.height / 4
        // the blurred picture is kept as one texture (a compositing layer) that every glass piece
        // draws, so it is blurred once per change, not once per piece per frame
        if (live == null) {
            live = android.graphics.RenderNode("glass").apply { setPosition(0, 0, w, h); setUseCompositingLayer(true, null) }
        }
        val node = inner ?: android.graphics.RenderNode("glass-blur").also { n ->
            n.setPosition(0, 0, w, h)
            val sat = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.5f) })
            n.setRenderEffect(android.graphics.RenderEffect.createChainEffect(
                android.graphics.RenderEffect.createColorFilterEffect(sat),
                android.graphics.RenderEffect.createBlurEffect(7.8f, 7.8f, Shader.TileMode.CLAMP)))   // glass.css blur(10px) = 20 screen px
            inner = n
        }
        val c = node.beginRecording(w, h)
        try {
            c.scale(0.25f, 0.25f)
            for (v in views) {
                if (v.visibility != android.view.View.VISIBLE || v.alpha <= 0f) continue
                val n = c.save()
                c.translate(v.left + v.translationX, v.top + v.translationY)
                c.scale(v.scaleX, v.scaleY, v.pivotX, v.pivotY)
                when (v) { is SceneView -> v.paintInto(c); is TopShelf -> v.paintInto(c); else -> v.draw(c) }
                c.restoreToCount(n)
            }
        } finally { node.endRecording() }
        val outer = live!!
        val oc = outer.beginRecording(w, h)
        try { oc.drawRenderNode(node) } finally { outer.endRecording() }
        if (first) {
            first = false; views.firstOrNull()?.rootView?.let { invalidateAll(it) }
            // the very first recording comes out unblurred on this TV until it is recorded again (the
            // cards rose in with sharp glass that only frosted 1.8 s later): once more, two frames on
            val again = views.toList()
            android.view.Choreographer.getInstance().postFrameCallback { android.view.Choreographer.getInstance().postFrameCallback { record(again) } }
        }
        changed()
    }

    /**
     * The frosted copy of a photo: a quarter-size copy, blurred with three box passes (close to a
     * Gaussian), then drawn stretched with filtering. Done once per photo, on a background thread.
     */
    fun frost(photo: Bitmap): Bitmap {
        val w = maxOf(1, photo.width / 4); val h = maxOf(1, photo.height / 4)
        val src = if (photo.config == Bitmap.Config.HARDWARE) photo.copy(Bitmap.Config.ARGB_8888, false) else photo
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        if (src !== photo && src !== small) src.recycle()
        val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        blurPixels(px, tmp, w, h, maxOf(1, w / 240))   // the dock's 10 px blur at 1080p
        val out = small.copy(Bitmap.Config.ARGB_8888, true); out.setPixels(px, 0, w, 0, 0, w, h)
        saturate(out, 1.5f)
        if (small !== out) small.recycle()
        return out
    }
    fun blurPixels(px: IntArray, tmp: IntArray, w: Int, h: Int, r: Int) { repeat(3) { box(px, tmp, w, h, r, true); box(tmp, px, w, h, r, false) } }
    /** A blurred quarter-size copy of any picture (a screen grab for the sidebar, say). */
    fun blurred(src: Bitmap, radiusPx: Float, sat: Float = 1f, bright: Float = 1f): Bitmap {
        val w = maxOf(1, src.width / 4); val h = maxOf(1, src.height / 4)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
        blurPixels(px, IntArray(w * h), w, h, maxOf(1, (radiusPx / 4 / 1.7f).toInt()))
        val out = if (small.isMutable) small else small.copy(Bitmap.Config.ARGB_8888, true)
        out.setPixels(px, 0, w, 0, 0, w, h)
        if (sat != 1f || bright != 1f) saturate(out, sat, bright)
        return out
    }
    fun saturate(b: Bitmap, sat: Float, bright: Float = 1f) {
        val c = Canvas(b)
        val p = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(sat); postConcat(ColorMatrix().apply { setScale(bright, bright, bright, 1f) }) }); xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        val copy = b.copy(Bitmap.Config.ARGB_8888, false); c.drawBitmap(copy, 0f, 0f, p); copy.recycle()
    }
    /** One box blur pass, along rows (horiz) or columns, edges clamped. */
    private fun box(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horiz: Boolean) {
        val n = if (horiz) w else h; val lines = if (horiz) h else w
        val div = 2 * r + 1
        for (l in 0 until lines) {
            var ra = 0; var rr = 0; var rg = 0; var rb = 0
            fun at(i: Int): Int { val k = i.coerceIn(0, n - 1); return if (horiz) src[l * w + k] else src[k * w + l] }
            for (i in -r..r) { val c = at(i); ra += c ushr 24; rr += (c shr 16) and 255; rg += (c shr 8) and 255; rb += c and 255 }
            for (i in 0 until n) {
                val o = if (horiz) l * w + i else i * w + l
                dst[o] = ((ra / div) shl 24) or ((rr / div) shl 16) or ((rg / div) shl 8) or (rb / div)
                val add = at(i + r + 1); val sub = at(i - r)
                ra += (add ushr 24) - (sub ushr 24); rr += ((add shr 16) and 255) - ((sub shr 16) and 255)
                rg += ((add shr 8) and 255) - ((sub shr 8) and 255); rb += (add and 255) - (sub and 255)
            }
        }
    }

    class Face {
        private val a = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val b = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val wash = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Ui.r(0.0625f); color = RIM }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val clipP = Path()
        private var edgeW = 0f; private var edgeH = 0f; private var edgeR = -1f
        private val lit = Path()
        private val litP = Paint(Paint.ANTI_ALIAS_FLAG)
        private var aFor: Bitmap? = null; private var bFor: Bitmap? = null
        private val m = Matrix()
        private val rr = RectF()

        private fun layer(p: Paint, bmp: Bitmap, alpha: Int, c: Canvas, rad: Float, dx: Float, dy: Float, was: Bitmap?): Bitmap {
            if (was !== bmp) p.shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            m.setScale(Ui.width.toFloat() / bmp.width, Ui.height.toFloat() / bmp.height)
            m.postTranslate(-dx, -dy); p.shader.setLocalMatrix(m); p.alpha = alpha
            c.drawRoundRect(rr, rad, rad, p); return bmp
        }
        /** dx, dy: where the piece's top left corner is on the screen. */
        /** s: the piece's own zoom (a focused card grows); the glass behind it stays lined up with the screen, never magnified. */
        fun draw(c: Canvas, w: Float, h: Float, rad: Float, dx: Float, dy: Float, washColor: Int = WASH, dim: Float = 0f, backdrop: Bitmap? = null, gradient: Shader? = null, s: Float = 1f) {
            rr.set(0f, 0f, w, h)
            val node = live
            // nothing behind it yet (the first moment after start): no stand-in, no edge, nothing
            if (backdrop == null && node == null && now == null) return
            if (backdrop == null && node != null && c.isHardwareAccelerated) {
                val n = c.save()
                clipP.reset(); clipP.addRoundRect(rr, rad, rad, Path.Direction.CW); c.clipPath(clipP)
                c.scale(1f / s, 1f / s); c.translate(-dx, -dy); c.scale(4f, 4f)
                c.drawRenderNode(node)
                c.restoreToCount(n)
            } else (backdrop ?: now)?.let { aFor = layer(a, it, 255, c, rad, dx, dy, aFor) }
            if (gradient != null) { shadow.shader = gradient; shadow.color = Color.WHITE; c.drawRoundRect(rr, rad, rad, shadow); shadow.shader = null }
            if (dim > 0f) { shadow.color = Ui.alpha(Color.BLACK, dim); c.drawRoundRect(rr, rad, rad, shadow) }
            wash.color = washColor; c.drawRoundRect(rr, rad, rad, wash)
            if (rad <= 0f) return   // full-screen glass (settings, panels) has no edge to light
            // glass.css, on the TV's 2x screen: inset 0 0 4px rgba(255,255,255,.18), a soft glow that
            // fades in over about 8 px from every edge (no hard line), and inset 1px 1px 0
            // rgba(255,255,255,.4), a crisp 2 px light along the top and left edges only
            if (edgeW != w || edgeH != h || edgeR != rad) {
                edgeW = w; edgeH = h; edgeR = rad
                val outer = Path().apply { addRoundRect(RectF(0f, 0f, w, h), rad, rad, Path.Direction.CW) }
                val moved = Path().apply { addRoundRect(RectF(2f, 2f, w + 2f, h + 2f), rad, rad, Path.Direction.CW) }
                lit.reset(); lit.op(outer, moved, Path.Op.DIFFERENCE)
            }
            val n = c.save()
            clipP.reset(); clipP.addRoundRect(0f, 0f, w, h, rad, rad, Path.Direction.CW); c.clipPath(clipP)
            // (the faint all-round glow is left out: on the TV it read as a light outline the web never shows)
            litP.color = 0x29FFFFFF; c.drawPath(lit, litP)   // as faint as it shows on the web glass
            c.restoreToCount(n)
        }
    }
}

/**
 * Soft shadows under glass pieces (glass.css --lg-shadow, keepwatching.css): drawn by the parent,
 * around the outside of each piece only, so nothing dark shows through the glass itself.
 */
open class ShadowFrame(ctx: android.content.Context) : android.widget.FrameLayout(ctx) {
    class Spec(val view: android.view.View, val radius: () -> Float, val blur: () -> Float, val dy: () -> Float, val color: () -> Int)
    val shadows = ArrayList<Spec>()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path(); private val clip = Path()
    init { setWillNotDraw(false); clipChildren = false }
    fun shadow(v: android.view.View, radius: () -> Float, blur: () -> Float = { Ui.r(1.25f) }, dy: () -> Float = { Ui.r(0.5f) }, color: () -> Int = { 0x4D000000 }) {
        shadows.add(Spec(v, radius, blur, dy, color)); invalidate()
    }
    /** Each piece's shadow is drawn just before the piece, above whatever lies under it. */
    override fun drawChild(c: Canvas, child: android.view.View, time: Long): Boolean {
        for (s in shadows) if (s.view === child) drawShadow(c, s)
        return super.drawChild(c, child, time)
    }
    private fun drawShadow(c: Canvas, s: Spec) {
        val v = s.view
        if (v.visibility != android.view.View.VISIBLE || v.alpha <= 0f || v.width == 0) return
        if (Glass.live == null && Glass.now == null) return   // the glass is not there yet: neither is its shadow
        val n = c.save()
        c.translate(v.left + v.translationX, v.top + v.translationY)
        c.scale(v.scaleX, v.scaleY, v.pivotX, v.pivotY)
        val w = v.width.toFloat(); val h = v.height.toFloat(); val r = s.radius()
        clip.reset(); clip.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
        c.clipOutPath(clip)
        p.color = 0xFF000000.toInt()   // solid, but clipped away above: only its shadow shows
        p.setShadowLayer(s.blur(), 0f, s.dy(), Ui.alpha(s.color(), v.alpha))
        path.reset(); path.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
        c.drawPath(path, p)
        c.restoreToCount(n)
    }
}
