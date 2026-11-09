package app.couchtv.launcher.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.View

/**
 * The top shelf (topshelf.css): resting on a Keep watching or Upcoming card, its show fills the
 * screen behind the home, as on Apple TV: the artwork, the title and what is next, with the scene's
 * clock and date stepping aside. It changes only once focus has settled (600 ms, 450 once showing),
 * and the picture is decoded before it fades in, so the fade never stutters.
 */
@SuppressLint("ViewConstructor")
class TopShelf(ctx: Context, private val home: HomeActivity) : View(ctx) {
    private var want: String? = null
    private var pics = arrayOfNulls<Bitmap>(2)
    private var front = 0
    private var picT = 1f
    private var on = 0f
    private var infoT = 0f
    private var title = ""
    private var meta = ""
    var showing = false; private set
    var thumb: Bitmap? = null
    private val shown = Spring(0f, 0.002f) { on = it; visibility = if (it <= 0.001f) INVISIBLE else VISIBLE; home.date.alpha = 1f - it; invalidate(); home.glassDirty() }
    private val info = Spring(0f, 0.002f) { infoT = it; invalidate() }
    private val cross = Spring(1f, 0.002f) { picT = it; invalidate(); home.glassDirty() }
    private val p = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadeH = Paint(); private val shadeV = Paint()
    private val titleP = Ui.paint(Ui.interBold, 4.75f).apply { setShadowLayer(Ui.r(1.5f), 0f, Ui.r(0.125f), 0x73000000) }
    private val metaP = Ui.paint(Ui.interBold, 1.75f, 0xD1FFFFFF.toInt()).apply { setShadowLayer(Ui.r(1.5f), 0f, Ui.r(0.125f), 0x73000000) }
    private val wait = Runnable { load() }
    private var card: Show? = null
    init { visibility = INVISIBLE }

    /** Focus moved: the card under it, or null when focus is not on a show. */
    fun focus(s: Show?) {
        val art = s?.takeIf { it.home == null }?.art
        if (art == null) { removeCallbacks(wait); want = null; off(); return }
        if (art == want) return
        want = art; card = s
        removeCallbacks(wait); postDelayed(wait, if (showing) 450 else 600)
    }
    private fun load() {
        val u = want ?: return; val s = card ?: return
        home.io.execute {
            val b = Net.bitmap(context, u, 1280)?.let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) ?: it else it }
            post {
                if (want != u || b == null) return@post
                title = s.title
                val ep = if (s.season > 0 && s.episode > 0) "S${s.season}, E${s.episode}" else ""
                meta = listOf(if (s.next) L.t("up_next") else "", ep, s.episodeTitle ?: "", s.app?.name ?: "").filter { it.isNotEmpty() }.joinToString(" · ")
                val back = 1 - front
                pics[back] = b; front = back
                thumb = try { Bitmap.createScaledBitmap(if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b, 192, 108, true) } catch (e: Exception) { null }
                cross.snap(0f); cross.to(1f, 0.32f)
                if (!showing) { showing = true; shown.to(1f, 0.6f); info.snap(0f); postDelayed({ if (showing) info.to(1f, 0.5f) }, 150) }
            }
        }
    }
    fun off() {
        removeCallbacks(wait); want = null
        if (!showing) return
        showing = false; shown.to(0f, 0.6f); info.to(0f, 0.3f)
        postDelayed({ if (!showing) { pics = arrayOfNulls(2) } }, 900)
    }

    fun paintInto(c: Canvas) = onDraw(c)
    override fun onDraw(c: Canvas) {
        if (on <= 0f) return
        val w = width.toFloat(); val h = height.toFloat()
        p.alpha = (255 * on).toInt()
        c.drawColor(Ui.alpha(Color.BLACK, on))
        val old = pics[1 - front]; val cur = pics[front]
        if (old != null && picT < 1f) { cover(c, old, (255 * on).toInt()) }
        if (cur != null) cover(c, cur, (255 * on * picT).toInt())
        if (shadeH.shader == null) {
            shadeH.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(0x9E000000.toInt(), 0x40000000, 0), floatArrayOf(0f, 0.45f, 0.7f), Shader.TileMode.CLAMP)
            shadeV.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(0x59000000, 0, 0, 0x8C000000.toInt()), floatArrayOf(0f, 0.22f, 0.45f, 1f), Shader.TileMode.CLAMP)
        }
        shadeH.alpha = (255 * on).toInt(); shadeV.alpha = shadeH.alpha
        c.drawRect(0f, 0f, w, h, shadeH); c.drawRect(0f, 0f, w, h, shadeV)
        if (infoT > 0f) {
            val dy = Ui.r(0.75f) * (1 - infoT)
            titleP.alpha = (255 * infoT).toInt(); metaP.alpha = (209 * infoT).toInt()
            val x = Ui.r(5f); var y = Ui.r(12f) + dy + titleP.textSize * 0.9f
            // up to two lines, the second cut with an ellipsis
            val maxW = Ui.r(60f)
            if (titleP.measureText(title) <= maxW) c.drawText(title, x, y, titleP)
            else {
                val words = title.split(" "); var n = words.size
                while (n > 1 && titleP.measureText(words.take(n).joinToString(" ")) > maxW) n--
                c.drawText(words.take(n).joinToString(" "), x, y, titleP)
                y += titleP.textSize * 1.05f
                c.drawText(Ui.fit(titleP, words.drop(n).joinToString(" "), maxW), x, y, titleP)
            }
            c.drawText(Ui.fit(metaP, meta, Ui.r(60f)), x, y + Ui.r(1f + 1.75f), metaP)
        }
    }
    private val dst = RectF()
    private fun cover(c: Canvas, b: Bitmap, a: Int) {
        val k = maxOf(width.toFloat() / b.width, height.toFloat() / b.height); val bw = b.width * k; val bh = b.height * k
        dst.set((width - bw) / 2, (height - bh) / 2, (width + bw) / 2, (height + bh) / 2)
        p.alpha = a; c.drawBitmap(b, null, dst, p)
    }
}
