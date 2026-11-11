package app.couchtv.launcher.home

import android.content.Context
import android.graphics.*
import android.view.View

/**
 * The music level meter (nowplaying.css .np-level): five bars, each rising and falling between a
 * quarter and full height on its own slow beat (900, 700, 1000, 800, 950 ms, eased, back and
 * forth), started at different points so they never move together.
 */
object Level {
    private val DUR = floatArrayOf(900f, 700f, 1000f, 800f, 950f)
    private val DELAY = floatArrayOf(0f, -300f, -150f, -450f, -600f)
    private fun ease(t: Float) = if (t < 0.5f) 2 * t * t else 1 - (-2 * t + 2).let { it * it } / 2
    /** Each bar's height, 0.25 to 1, at this moment; still bars sit at 0.3. */
    fun at(k: Int, playing: Boolean): Float {
        if (!playing) return 0.3f
        val now = (System.nanoTime() / 1_000_000L % 3_600_000L).toFloat() - DELAY[k]
        val d = DUR[k]
        val cycle = (now / d).toInt(); var t = (now % d) / d
        if (cycle % 2 == 1) t = 1 - t   // alternate: up, then back down
        return 0.25f + 0.75f * ease(t)
    }
    fun draw(c: Canvas, right: Float, cy: Float, playing: Boolean, color: Int, p: Paint) {
        p.color = color
        for (k in 0..4) {
            val hh = Ui.r(1.5f) * at(k, playing)
            val bx = right - (5 - k) * Ui.r(0.375f)
            c.drawRoundRect(bx, cy - hh / 2, bx + Ui.r(0.1875f), cy + hh / 2, Ui.r(0.1f), Ui.r(0.1f), p)
        }
    }
}

/**
 * The resting music bar (idle.css .np-wide): Now Playing laid out in one row as wide as the dock,
 * at the bottom of the screen: artwork and song, the bar filling the middle, the level and the
 * controls. On the home's glass while it rests; over ambient mode, on a dark glass of its own
 * (nothing of the screensaver can be blurred from here).
 */
class NowBar(ctx: Context, private val home: HomeActivity?) : View(ctx) {
    var speaker = ""; var title = ""; var artist = ""
    var art: Bitmap? = null
        set(v) { field = v; invalidate() }
    var pos = 0.0; var dur = 0.0; var playing = false
    private val face = Glass.Face()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val artP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val whereP = Ui.paint(Ui.inter, 1.125f, 0x8CFFFFFF.toInt())
    private val titleP = Ui.paint(Ui.interBold, 1.75f)
    private val artistP = Ui.paint(Ui.inter, 1.625f, 0x8CFFFFFF.toInt())
    private val timeP = Ui.paint(Ui.inter, 1.25f, 0x8CFFFFFF.toInt())
    private val shadeP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    // over ambient (no blur to calm the photo): whiter words, bars and symbols, with a soft shadow
    private val amb = home == null
    private val dim = if (amb) 0xE6FFFFFF.toInt() else 0x8CFFFFFF.toInt()
    init {
        whereP.color = dim; artistP.color = dim; timeP.color = dim
        if (amb) listOf(whereP, titleP, artistP, timeP).forEach { it.setShadowLayer(Ui.r(0.5f), 0f, Ui.r(0.0625f), 0x8C000000.toInt()) }
    }
    private val ticker = object : Runnable {
        override fun run() { if (playing) { pos = minOf(pos + 1, if (dur > 0) dur else pos + 1) }; invalidate(); postDelayed(this, 1000) }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); removeCallbacks(ticker); postDelayed(ticker, 1000) }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); removeCallbacks(ticker) }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), Ui.ri(9f))

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val rad = Ui.r(2.75f)
        val hm = home
        if (hm != null) {
            val loc = Glass.settled(this)
            face.draw(c, w, h, rad, loc[0].toFloat(), loc[1].toFloat())
        } else {
            // over ambient: a dark glass with the same light edge
            shadeP.color = 0x8C141418.toInt(); c.drawRoundRect(0f, 0f, w, h, rad, rad, shadeP)
            shadeP.color = 0x1FFFFFFF; c.drawRoundRect(0f, 0f, w, h, rad, rad, shadeP)
            path.reset(); path.addRoundRect(0f, 0f, w, h, rad, rad, Path.Direction.CW)
            val moved = Path().apply { addRoundRect(2f, 2f, w + 2f, h + 2f, rad, rad, Path.Direction.CW) }
            val lit = Path(); lit.op(path, moved, Path.Op.DIFFERENCE)
            shadeP.color = 0x29FFFFFF; c.drawPath(lit, shadeP)
        }
        // padding 1.25 2.25 1.25 1.25; gap 2.5rem between the parts
        val padL = Ui.r(1.25f); val padR = Ui.r(2.25f); val gap = Ui.r(2.5f); val cy = h / 2
        val a = Ui.r(6.5f)
        val n = c.save(); c.clipPath(Path().apply { addRoundRect(padL, cy - a / 2, padL + a, cy + a / 2, Ui.r(1.25f), Ui.r(1.25f), Path.Direction.CW) })
        val ar = art
        if (ar != null) c.drawBitmap(ar, null, RectF(padL, cy - a / 2, padL + a, cy + a / 2), artP)
        else { fill.color = 0x24FFFFFF; c.drawRect(padL, cy - a / 2, padL + a, cy + a / 2, fill); Icons.npNote.draw(c, padL + a / 2 - Ui.r(1.25f), cy - Ui.r(1.25f), Ui.r(2.5f), 0xD9FFFFFF.toInt(), 1.6f) }
        c.restoreToCount(n)
        // song: up to 34rem with the artwork
        val tx = padL + a + Ui.r(1.125f); val infoEnd = padL + Ui.r(34f); val tw = infoEnd - tx
        var y = cy - Ui.r(1.9f)
        if (speaker.isNotEmpty()) c.drawText(Ui.fit(whereP, L.t("playing_on", "name" to speaker), tw), tx, y, whereP)
        y += Ui.r(2.05f); c.drawText(Ui.fit(titleP, title, tw), tx, y, titleP)
        y += Ui.r(1.95f); c.drawText(Ui.fit(artistP, artist, tw), tx, y, artistP)
        // the controls (they only show the state), then the level, from the right
        var x = w - padR
        val ink = Color.WHITE
        Icons.npRoute.draw(c, x - Ui.r(2.125f), cy - Ui.r(1.0625f), Ui.r(2.125f), if (amb) 0xE6FFFFFF.toInt() else 0x99FFFFFF.toInt(), 1.8f); x -= Ui.r(2.125f + 0.75f)
        Icons.npNext.draw(c, x - Ui.r(3f), cy - Ui.r(1.5f), Ui.r(3f), ink); x -= Ui.r(3f + 1.5f)
        (if (playing) Icons.npPause else Icons.npPlay).draw(c, x - Ui.r(2.75f), cy - Ui.r(1.375f), Ui.r(2.75f), ink); x -= Ui.r(2.75f + 1.5f)
        Icons.npPrev.draw(c, x - Ui.r(3f), cy - Ui.r(1.5f), Ui.r(3f), ink); x -= Ui.r(3f) + gap
        Level.draw(c, x + Ui.r(0.1875f), cy, playing, if (amb) Color.WHITE else 0x99FFFFFF.toInt(), fill); x -= Ui.r(5 * 0.375f) + gap
        // the time: elapsed, the bar filling the middle, what is left
        val p0 = infoEnd + gap
        val shown = pos.coerceAtMost(if (dur > 0) dur else Double.MAX_VALUE)
        c.drawText(mmss(shown), p0, cy + timeP.textSize * 0.36f, timeP)
        timeP.textAlign = Paint.Align.RIGHT
        if (dur > 0) c.drawText("−" + mmss(dur - shown), x, cy + timeP.textSize * 0.36f, timeP)
        timeP.textAlign = Paint.Align.LEFT
        val b0 = p0 + Ui.r(3.25f + 1f); val b1 = x - Ui.r(3.25f + 1f)
        if (b1 > b0) {
            fill.color = if (amb) 0x66FFFFFF else 0x38FFFFFF; c.drawRoundRect(b0, cy - Ui.r(0.25f), b1, cy + Ui.r(0.25f), Ui.r(0.5f), Ui.r(0.5f), fill)
            fill.color = if (amb) Color.WHITE else 0xC7FFFFFF.toInt(); val f = if (dur > 0) (shown / dur).toFloat().coerceIn(0f, 1f) else 0f
            c.drawRoundRect(b0, cy - Ui.r(0.25f), b0 + (b1 - b0) * f, cy + Ui.r(0.25f), Ui.r(0.5f), Ui.r(0.5f), fill)
        }
        if (playing) postInvalidateDelayed(40)   // the level moves
    }
    private fun mmss(s: Double): String { val t = Math.max(0, Math.round(s).toInt()); return "${t / 60}:" + (if (t % 60 < 10) "0" else "") + (t % 60) }
}
