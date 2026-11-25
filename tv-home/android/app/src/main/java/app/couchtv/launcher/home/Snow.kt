package app.couchtv.launcher.home

import android.graphics.*
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Snow while Home Assistant's Christmas mode is on (ha.json "snow"), as the dashboard draws it:
 * the falling flakes of casa-v3.js (_flakeSync) and the drifts of casa-snow.js, with their numbers
 * and the household's own three sliders. [amount] is the Snowfall slider (1 = 100%), [melt] the
 * Melt one (0 to 1), [depth] how deep it piles, in the dashboard's pixels.
 */
data class SnowSet(val amount: Float, val melt: Float, val depth: Float)

/** The falling flakes: over the scene, its clock and the date, under the cards and the dock. */
class SnowLayer(private val home: HomeActivity) : View(home) {
    private class Flake(val x: Float, val r: Float, val fall: Float, val at: Float, val op: Float, val sway: Float, val swayS: Float, val swayAt: Float)
    private var flakes = listOf<Flake>()
    private var key = ""
    private var set: SnowSet? = null
    private var shown = 0f
    private var away = false
    private val fade = Spring(0f, 0.002f) { shown = it; apply() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    val on get() = set != null

    init { visibility = INVISIBLE; isFocusable = false; Motion.frameHooks.add { if (shown > 0f) apply() } }

    /** The top shelf's artwork comes first, as with the rain. */
    private fun apply() {
        val a = shown * (1f - Glass.shelfOn.coerceIn(0f, 1f))
        alpha = a
        val v = if (a <= 0.001f) INVISIBLE else VISIBLE
        if (v != visibility) { visibility = v; if (v == VISIBLE) invalidate() }
    }
    fun want(s: SnowSet?) {
        if (Motion.calm) return   // "Remove animations": no snow either, as on the dashboard
        set = s
        if (s != null) build(s)
        fade.to(if (s != null && flakes.isNotEmpty()) 1f else 0f, 0.8f)
    }
    fun pause() { away = true }
    fun resume() { if (away) { away = false; invalidate() } }

    private fun build(s: SnowSet) {
        // as the dashboard on a full 1920 x 1080 screen: small flakes, and as many as it allows
        val u = Ui.width / 1920f; val h = Ui.height / u
        val d = s.amount.coerceIn(0f, 2f)
        val n = if (d <= 0f) 0 else (1920f * h / 9000f * d).toInt().coerceIn(18, 170)
        if ("$n" == key) return
        key = "$n"
        flakes = List(n) {
            val t = Random.nextFloat()   // 0 far, 1 near: bigger, faster and brighter together
            val fall = (h + 10) / (52 + t * 84 + Random.nextFloat() * 14)
            val sway = 2.6f + Random.nextFloat() * 4
            Flake(Random.nextFloat() * Ui.width, (1.4f + t * 4.4f) * u / 2, fall, Random.nextFloat() * fall, 0.38f + t * 0.52f,
                (6 + Random.nextFloat() * 26) * u, sway, Random.nextFloat() * sway * 2)
        }
    }
    override fun onDraw(c: Canvas) {
        val now = (SystemClock.uptimeMillis() % 86_400_000L) / 1000.0
        for (f in flakes) {
            val k = (((now + f.at) % f.fall) / f.fall).toFloat()
            val o = when { k < 0.07f -> k / 0.07f; k > 0.86f -> (1f - k) / 0.14f; else -> 1f }
            // there and back over its own time, eased at both ends
            val sw = (-Math.cos((now + f.swayAt) / f.swayS * Math.PI)).toFloat() * f.sway
            p.alpha = (255 * f.op * o).toInt()
            c.drawCircle(f.x + sw, -f.r + k * (height + 4 * f.r), f.r, p)
        }
        if (away || visibility != VISIBLE || shown <= 0f) return
        // a small device has better things to do than 60 snow frames a second
        if (Ui.slow) postInvalidateDelayed(50) else postInvalidateOnAnimation()
    }
}

/** The snow lying on one card: columns that grow, slump into banks and melt (casa-snow.js). */
class Drift(private val seed: Int) {
    private var n = 0
    private var cols = FloatArray(0); private var tgt = FloatArray(0); private var bias = FloatArray(0)
    private val path = Path(); private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cw = 0f
    private var moved = 0f
    private fun fit(w: Float) {
        if (w == cw) return
        cw = w
        n = maxOf(4, Math.round(w / (7f * U)))
        cols = FloatArray(n); tgt = FloatArray(n)
        bias = FloatArray(n) { k -> val h = sin(k * 12.9898 + seed * 78.233) * 43758.5453; (0.35 + 0.9 * (h - Math.floor(h))).toFloat() }
    }
    /** One step; true when it has changed enough to be drawn again. All in the dashboard's pixels. */
    fun step(dt: Float, s: SnowSet?, w: Float, rad: Float): Boolean {
        fit(w)
        val grow = if (s == null) 0f else 0.12f * s.amount * dt
        // Christmas over: what lies there melts away within a few seconds
        val decay = if (s == null) min(1f, dt * 0.8f) else s.melt * 0.02f * dt
        val cap = s?.depth ?: Float.MAX_VALUE
        val sw = w / n
        for (i in 0 until n) {
            val cx = (i + 0.5f) * sw; val dx = min(cx, w - cx)
            val edge = ((dx - rad) / (14f * U)).coerceIn(0f, 1f)   // nothing settles over the rounded corners
            tgt[i] = (tgt[i] + grow * bias[i] - tgt[i] * decay).coerceIn(0f, if (s == null) 99f else cap * edge)
        }
        val k = min(0.5f, dt * 1.2f)
        for (i in 1 until n - 1) tgt[i] += ((tgt[i - 1] + tgt[i] + tgt[i + 1]) / 3 - tgt[i]) * k
        val ease = 1 - exp(-dt * 1.2f)
        var most = 0f
        for (i in 0 until n) { val d = (tgt[i] - cols[i]) * ease; cols[i] += d; most = maxOf(most, abs(d)) }
        moved += most
        if (moved * U < 0.2f) return false
        moved = 0f; return true
    }
    /** Already lying (adb: --ef snowdeep, to see it without waiting). */
    fun preset(d: Float, w: Float) { fit(w); for (i in 0 until n) tgt[i] = d * (0.5f + 0.5f * bias[i]) }
    fun empty() = cols.none { it > TH }
    fun draw(c: Canvas, w: Float) {
        if (n == 0 || w != cw) return
        val sw = w / n
        var i = 0
        while (i < n) {
            if (cols[i] <= TH) { i++; continue }
            var j = i; while (j < n && cols[j] > TH) j++
            val x0 = i * sw; val x1 = j * sw
            path.reset(); path.moveTo(x0, 0f)
            val span = j - i
            val colors = IntArray(span + 1); val stops = FloatArray(span + 1)
            for (k in i until j) {
                val xa = x0 + (k - i) * sw; val xb = xa + sw
                val ya = -cols[k] * U; val yb = -(if (k + 1 < j) cols[k + 1] else 0f) * U
                path.quadTo(xa + sw / 2, (ya + yb) / 2, xb, yb)
                // opacity follows the depth, so thin snow fades out along the drift
                val a = min(1f, cols[k] / 4.5f).toDouble().pow(1.35).toFloat()
                colors[k - i] = Ui.alpha(0xFFFAFDFF.toInt(), 0.9f * a); stops[k - i] = (k - i).toFloat() / span
            }
            colors[span] = colors[span - 1]; stops[span] = 1f
            path.lineTo(x1, 0f); path.close()
            p.shader = LinearGradient(x0, 0f, x1, 0f, colors, stops, Shader.TileMode.CLAMP)
            c.drawPath(path, p)
            i = j
        }
    }
    companion object {
        private const val TH = 0.5f
        /** The dashboard's pixel here: deep enough to see from the sofa, clear of the row's headings. */
        val U get() = Ui.width / 1600f
    }
}
