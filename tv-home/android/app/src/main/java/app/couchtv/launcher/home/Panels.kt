package app.couchtv.launcher.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.View

/** Rounded to a tenth, without a trailing ".0" (21.5°, 22°). */
fun deg(v: Double) = (Math.round(v * 10) / 10.0).let { if (it == Math.floor(it)) it.toInt().toString() else it.toString() } + "°"

/**
 * The home cards' panels (homecards.css .hpanel): the lights and the living room's scenes, the
 * climate, the week's weather. A list on the left, one big number on the right.
 */
@SuppressLint("ViewConstructor")
class HomePanel(ctx: Context, private val home: HomeActivity) : View(ctx) {
    private var kind: String? = null
    private var i = 0
    private var shown = 0f
    private val fade = Spring(0f, 0.002f) { shown = it; alpha = it; visibility = if (it <= 0.001f) INVISIBLE else VISIBLE; invalidate() }
    private val face = Glass.Face()
    private val h2 = Ui.paint(Ui.interBold, 3f)
    private val row = Ui.paint(Ui.inter, 1.625f)
    private val sub = Ui.paint(Ui.interBold, 1.1875f, 0x8CFFFFFF.toInt())
    private val name = Ui.paint(Ui.interBold, 1.75f, 0xB8FFFFFF.toInt())
    private val big = Ui.paint(Ui.antonio, 11f)
    private val line = Ui.paint(Ui.inter, 1.625f, 0xB8FFFFFF.toInt())
    private val hint = Ui.paint(Ui.inter, 1.375f, 0x99FFFFFF.toInt()).apply { textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private var climateSend: Runnable? = null
    init { visibility = INVISIBLE; fade.snap(0f) }

    private var backdrop: Bitmap? = null
    fun open(k: String) {
        home.zone = "hpanel"
        home.grab(Ui.r(1.5f), 1.2f, 0.9f) { b -> backdrop = b; kind = k; i = 0; fade.to(1f, 0.3f); invalidate(); home.askRoom() }
    }
    fun close() { kind = null; home.zone = "shelf"; fade.to(0f, 0.3f); home.paint() }
    fun redraw() { if (kind != null) invalidate() }

    fun move(dx: Int, dy: Int) {
        val r = home.room ?: return
        if (kind == "room") {
            if (dy != 0) { i = (i + dy).coerceIn(0, r.lights.size + r.scenes.size - 1); invalidate(); return }
            val l = r.lights.getOrNull(i) ?: return
            if (dx != 0 && l.available) {
                val b = if (l.on) (l.brightness ?: 100) else 0
                val nb = (Math.round((b + dx * 10) / 10.0) * 10).toInt().coerceIn(0, 100)
                setLight(l, nb > 0, if (nb > 0) nb else null)
            }
        } else if (kind == "climate" && dx != 0) {
            val cl = r.climate ?: return
            if (cl.mode == "off" || cl.target == null) return
            val st = if (cl.step > 0) cl.step else 0.5
            cl.target = (Math.round((cl.target!! + dx * st) / st) * st).coerceIn(cl.min, cl.max)
            invalidate(); home.shelf.invalidateGlass()
            climateSend?.let { removeCallbacks(it) }
            climateSend = Runnable { home.ha.climate(target = cl.target) { home.askRoom() } }.also { postDelayed(it, 700) }   // several presses send one change
        }
    }
    private fun setLight(l: Ha.Light, on: Boolean, pct: Int?) {
        l.on = on; if (pct != null) l.brightness = pct
        invalidate(); home.shelf.invalidateGlass()
        home.ha.light(l.id, on, pct) { home.askRoom() }
    }
    fun ok() {
        val r = home.room ?: return
        if (kind == "room") {
            if (i >= r.lights.size) {
                val sc = r.scenes.getOrNull(i - r.lights.size) ?: return
                home.ha.run(sc.first) { }; home.toast.show(sc.second); postDelayed({ home.askRoom() }, 2500)
                return
            }
            val l = r.lights[i]; if (l.available) setLight(l, !l.on, null)
        } else if (kind == "climate") {
            val cl = r.climate ?: return
            val on = cl.mode == "off"
            cl.mode = if (on) "heat" else "off"; if (!on) cl.action = "off"
            invalidate(); home.shelf.invalidateGlass()
            home.ha.climate(on = on) { home.askRoom() }
        }
    }

    override fun onDraw(c: Canvas) {
        val k = kind ?: return
        val w = width.toFloat(); val h = height.toFloat()
        face.draw(c, w, h, 0f, 0f, 0f, 0xB30A0A0E.toInt(), backdrop = backdrop)
        val x0 = Ui.r(7.5f); val y0 = Ui.r(6f); val mainX = x0 + Ui.r(26f + 5f)
        c.drawText(L.t(when (k) { "room" -> "hc_lights"; "climate" -> "hc_climate"; else -> "hc_weather" }), x0, y0 + Ui.r(2.7f), h2)
        val r = home.room
        var hintText = ""
        when (k) {
            "room" -> if (r != null) {
                var y = y0 + Ui.r(5.5f)
                val at = i.coerceIn(0, r.lights.size + r.scenes.size - 1)
                r.lights.forEachIndexed { n, l -> y = listRow(c, x0, y, l.name, n == at, l.on) }
                if (r.scenes.isNotEmpty()) { y += Ui.r(1.5f); c.drawText(L.t("hc_scenes"), x0 + Ui.r(1.25f), y + Ui.r(1.2f), sub); y += Ui.r(2.125f) }
                r.scenes.forEachIndexed { n, s -> y = listRow(c, x0, y, s.second, r.lights.size + n == at, true) }
                val l = r.lights.getOrNull(at)
                if (l != null) {
                    val b = if (l.on) (l.brightness ?: 100) else 0
                    if (!l.available) ctl(c, mainX, y0, l.name, "–", 0f, L.t("hc_na"), false, 0)
                    else ctl(c, mainX, y0, l.name, if (l.on) "$b%" else L.t("hc_off"), b / 100f, if (l.on) L.t("hc_on") else L.t("hc_off"), l.on, 0xFFFFC074.toInt())
                    hintText = L.t("hc_room_hint")
                } else {
                    ctl(c, mainX, y0, L.t("hc_scenes"), r.scenes[at - r.lights.size].second, -1f, L.t("hc_scene_run"), true, 0, small = true)
                    hintText = L.t("hc_scene_hint")
                }
            }
            "climate" -> r?.climate?.let { cl ->
                val on = cl.mode != "off"; val heat = cl.action == "heating" || cl.mode == "heat"
                val fr = cl.target?.let { ((it - cl.min) / maxOf(1.0, cl.max - cl.min)).toFloat() } ?: 0f
                ctl(c, mainX, y0, r.label.ifEmpty { cl.name }, cl.target?.let { deg(it) } ?: "–", fr,
                    (cl.current?.let { L.t("hc_now", "t" to deg(it)) + " · " } ?: "") + home.shelf.climateWords(cl), on, if (heat) 0xFFFFC074.toInt() else 0xFF7CC4FF.toInt())
                hintText = L.t("hc_climate_hint")
            }
            else -> home.weather?.let { wx ->
                var y = y0 + Ui.r(0.75f)
                val cols = floatArrayOf(7f, 2.5f, 0f, 5f, 5f, 4.5f); val wMain = minOf(Ui.r(60f), w - mainX - x0)
                wx.days.forEachIndexed { n, d ->
                    val base = y + Ui.r(2.1f)
                    line.color = Color.WHITE; c.drawText(home.shelf.dayName(d.date, n), mainX, base, line)
                    Icons.weather(d.code, true).draw(c, mainX + Ui.r(8f), base - Ui.r(1.75f), Ui.r(2.125f), Color.WHITE, 1.6f)
                    line.color = 0xB8FFFFFF.toInt(); c.drawText(Ui.fit(line, Weather.words(d.code), wMain - Ui.r(31f)), mainX + Ui.r(11.5f), base, line)
                    line.textAlign = Paint.Align.RIGHT
                    line.color = 0xFF7CC4FF.toInt(); d.rain?.let { c.drawText("$it%", mainX + wMain - Ui.r(10.5f), base, line) }
                    line.color = Color.WHITE; c.drawText(home.tempText(d.hi), mainX + wMain - Ui.r(5f), base, line)
                    line.color = 0x8CFFFFFF.toInt(); c.drawText(home.tempText(d.lo), mainX + wMain, base, line)
                    line.textAlign = Paint.Align.LEFT
                    y += Ui.r(3.9f)
                    if (n < wx.days.size - 1) { fill.color = 0x1FFFFFFF; c.drawRect(mainX, y - Ui.r(0.0625f), mainX + wMain, y, fill) }
                }
                hintText = L.t("hc_back_hint")
                if (cols.isEmpty()) return
            }
        }
        c.drawText(hintText, w / 2, h - Ui.r(3f), hint)
    }
    private fun listRow(c: Canvas, x: Float, y: Float, text: String, focus: Boolean, lit: Boolean): Float {
        val hh = Ui.r(3.875f)
        if (focus) { fill.color = 0xEBFFFFFF.toInt(); c.drawRoundRect(x, y, x + Ui.r(26f), y + hh, Ui.r(1f), Ui.r(1f), fill) }
        row.color = if (focus) 0xFF111111.toInt() else if (lit) Color.WHITE else 0xB8FFFFFF.toInt()
        row.typeface = if (focus) Ui.interBold else Ui.inter
        c.drawText(Ui.fit(row, text, Ui.r(23.5f)), x + Ui.r(1.25f), y + hh / 2 + row.textSize * 0.36f, row)
        return y + hh + Ui.r(0.375f)
    }
    private fun ctl(c: Canvas, x: Float, y: Float, n: String, value: String, frac: Float, s: String, on: Boolean, colour: Int, small: Boolean = false) {
        val wBar = Ui.r(44f)
        c.drawText(n, x, y + Ui.r(2.2f), name)
        big.textSize = Ui.r(if (small) 6f else 11f); big.color = if (on) Color.WHITE else 0x8CFFFFFF.toInt()
        val bTop = y + Ui.r(2.75f) + Ui.r(0.5f)
        val fm = big.fontMetrics
        c.drawText(value, x, bTop - fm.ascent * 0.92f, big)
        var yy = bTop + big.textSize * 1.0f
        if (frac >= 0f) {
            yy += Ui.r(2f)
            fill.color = 0x29FFFFFF; c.drawRoundRect(x, yy, x + wBar, yy + Ui.r(0.25f), Ui.r(0.125f), Ui.r(0.125f), fill)
            fill.color = if (on && colour != 0) colour else 0x8CFFFFFF.toInt()
            c.drawRoundRect(x, yy, x + wBar * frac.coerceIn(0f, 1f), yy + Ui.r(0.25f), Ui.r(0.125f), Ui.r(0.125f), fill)
            yy += Ui.r(0.25f + 1.5f)
        } else yy += Ui.r(1.5f)
        c.drawText(s, x, yy + Ui.r(1.7f), line)
    }
}

/**
 * Now Playing (nowplaying.css): music from Home Assistant, top right, laid out like the iOS lock
 * screen player. Only previous, play/pause and next take focus.
 */
@SuppressLint("ViewConstructor")
class NowPlayingCard(ctx: Context, private val home: HomeActivity) : View(ctx) {
    val on get() = home.playing != null
    private var npi = 1
    private var from = "grid"
    private val face = Glass.Face()
    private val where = Ui.paint(Ui.inter, 1.125f, 0x8CFFFFFF.toInt())
    private val title = Ui.paint(Ui.interBold, 1.75f)
    private val artist = Ui.paint(Ui.inter, 1.625f, 0x8CFFFFFF.toInt())
    private val time = Ui.paint(Ui.inter, 1.25f, 0x8CFFFFFF.toInt())
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val artP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var art: Bitmap? = null
    private var artFor: String? = null
    private var shown = 0f
    private val appear = Spring(0f, 0.002f) {
        shown = it
        // resting: the card stays off the top (the wide bar shows the song); a new song changes only what is on it
        if (!home.idle) { alpha = it; translationY = -Ui.r(0.75f) * (1 - it); val s = 0.97f + 0.03f * it; scaleX = s; scaleY = s }
        visibility = if (it <= 0.001f) INVISIBLE else VISIBLE; (parent as? View)?.invalidate() }
    private val focusS = FloatArray(3)
    private val springs = Array(3) { k -> Spring(0f) { focusS[k] = it; invalidate() } }
    private var level = 0f
    private val ticker = object : Runnable { override fun run() { home.playing?.let { if (it.state == "playing") { it.position += 1.0; invalidate() } }; postDelayed(this, 1000) } }
    init { visibility = INVISIBLE; appear.snap(0f); pivotX = Ui.r(30f); pivotY = 0f; post(ticker) }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(Ui.ri(30f), Ui.ri(15.5f))

    fun update() {
        val p = home.playing
        appear.to(if (p != null) 1f else 0f, 0.42f)
        val pic = p?.picture
        if (pic != artFor) { artFor = pic; art = null; if (pic != null) home.io.execute { val b = Net.bitmap(context, pic, 256); post { if (artFor == pic) { art = b; invalidate() } } } }
        invalidate()
    }
    fun paint() { for (k in 0..2) springs[k].to(if (home.zone == "np" && k == npi) 1f else 0f, if (home.zone == "np" && k == npi) Motion.FOCUS else Motion.UNFOCUS) }
    fun enter(z: String) { from = z; home.tabFrom = z; home.zone = "np"; npi = 1; home.paint() }
    fun leave() { home.zone = if (from == "tab") "tab" else if (from == "np") "grid" else from; home.paint() }
    fun move(dx: Int, dy: Int) {
        if (dy > 0) { leave(); return }
        if (dx < 0 && npi == 0) { home.zone = "tab"; home.paint(); return }
        if (dx != 0) { npi = (npi + dx).coerceIn(0, 2); paint() }
    }
    fun act(what: String = listOf("prev", "play", "next")[npi]) {
        val p = home.playing ?: return
        home.ha.player(p.entity, what)
        if (what == "play") { home.playing = p.copy(state = if (p.state == "playing") "paused" else "playing") }
        invalidate()
        postDelayed({ home.askPlaying() }, 1200)
    }

    override fun onDraw(c: Canvas) {
        val p = home.playing ?: return
        val w = width.toFloat(); val h = height.toFloat()
        val light = home.onLight(this)
        val ink = if (light) 0xFF1D1D1F.toInt() else Color.WHITE
        val ink2 = if (light) 0x80000000.toInt() else 0x8CFFFFFF.toInt()
        title.color = ink; artist.color = ink2; where.color = ink2; time.color = ink2
        val loc = Glass.settled(this)
        face.draw(c, w, h, Ui.r(2.25f), loc[0].toFloat(), loc[1].toFloat(), if (light) Glass.WASH_LIGHT else Glass.WASH)
        val pad = Ui.r(1.25f); val a = Ui.r(5.5f)
        // artwork
        val n = c.save(); c.clipPath(Path().apply { addRoundRect(pad, pad, pad + a, pad + a, Ui.r(1f), Ui.r(1f), Path.Direction.CW) })
        val ar = art
        if (ar != null) c.drawBitmap(ar, null, RectF(pad, pad, pad + a, pad + a), artP)
        else { fill.color = 0x24FFFFFF; c.drawRect(pad, pad, pad + a, pad + a, fill); Icons.npNote.draw(c, pad + a / 2 - Ui.r(1.125f), pad + a / 2 - Ui.r(1.125f), Ui.r(2.25f), 0xD9FFFFFF.toInt(), 1.6f) }
        c.restoreToCount(n)
        // the words
        // the words stop short of the level meter, and sit centred on the artwork as one block
        val tx = pad + a + Ui.r(1.125f); val tw = w - tx - pad - Ui.r(0.25f + 5 * 0.375f + 1f)
        val hasWhere = p.speaker.isNotEmpty()
        val block = (if (hasWhere) Ui.r(1.35f) else 0f) + Ui.r(2.1f) + Ui.r(1.95f)
        var y = pad + a / 2 - block / 2
        if (hasWhere) { c.drawText(Ui.fit(where, L.t("playing_on", "name" to p.speaker), tw), tx, y + Ui.r(1.05f), where); y += Ui.r(1.35f) }
        c.drawText(Ui.fit(title, p.title, tw), tx, y + Ui.r(1.65f), title); y += Ui.r(2.1f)
        c.drawText(Ui.fit(artist, p.artist, tw), tx, y + Ui.r(1.5f), artist)
        // the level meter (nowplaying.css): each bar on its own slow beat
        Level.draw(c, w - pad - Ui.r(0.25f) + Ui.r(0.1875f) - Ui.r(0.375f), pad + a / 2, p.state == "playing", if (light) 0x80000000.toInt() else 0x99FFFFFF.toInt(), fill)
        if (p.state == "playing") postInvalidateDelayed(40)
        // time
        val ty = pad + a + Ui.r(1.375f)
        val pos = p.position.coerceAtMost(if (p.duration > 0) p.duration else Double.MAX_VALUE)
        c.drawText(mmss(pos), pad, ty + Ui.r(1.1f), time)
        time.textAlign = Paint.Align.RIGHT
        if (p.duration > 0) c.drawText("−" + mmss(p.duration - pos), w - pad, ty + Ui.r(1.1f), time)
        time.textAlign = Paint.Align.LEFT
        val bx0 = pad + Ui.r(4.25f); val bx1 = w - pad - Ui.r(4.25f); val by = ty + Ui.r(0.5f)
        fill.color = if (light) 0x26000000 else 0x38FFFFFF; c.drawRoundRect(bx0, by, bx1, by + Ui.r(0.5f), Ui.r(0.5f), Ui.r(0.5f), fill)
        fill.color = if (light) 0xB3000000.toInt() else 0xC7FFFFFF.toInt(); val frac = if (p.duration > 0) (pos / p.duration).toFloat().coerceIn(0f, 1f) else 0f
        c.drawRoundRect(bx0, by, bx0 + (bx1 - bx0) * frac, by + Ui.r(0.5f), Ui.r(0.5f), Ui.r(0.5f), fill)
        // the controls
        val cy = ty + Ui.r(1.5f + 0.5f + 2.25f)
        val icons = listOf(Icons.npPrev, if (p.state == "playing") Icons.npPause else Icons.npPlay, Icons.npNext)
        for (k in 0..2) {
            val cx = w / 2 + (k - 1) * Ui.r(4.5f + 2.5f)
            val f = focusS[k]
            // focused: a white circle with a dark symbol; over a light sky, a dark circle with a white one
            if (f > 0.01f) { fill.color = Ui.alpha(if (light) 0xFF1D1D1F.toInt() else Color.WHITE, f); val r = Ui.r(2.25f) * (1 + 0.08f * f); c.drawCircle(cx, cy, r, fill) }
            val ink = Ui.mix(if (light) 0xFF1D1D1F.toInt() else Color.WHITE, if (light) Color.WHITE else 0xFF111111.toInt(), f)
            if (k == 1) icons[k].draw(c, cx - Ui.r(1.375f), cy - Ui.r(1.375f), Ui.r(2.75f), ink) else icons[k].draw(c, cx - Ui.r(1.5f), cy - Ui.r(1.5f), Ui.r(3f), ink)
        }
        Icons.npRoute.draw(c, w - pad - Ui.r(0.5f) - Ui.r(2.125f), cy - Ui.r(1.0625f), Ui.r(2.125f), if (light) 0x99000000.toInt() else 0x99FFFFFF.toInt(), 1.8f)
    }
    private fun mmss(s: Double): String { val t = Math.max(0, Math.round(s).toInt()); return "${t / 60}:" + (if (t % 60 < 10) "0" else "") + (t % 60) }
}
