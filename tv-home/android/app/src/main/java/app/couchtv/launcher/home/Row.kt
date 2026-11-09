package app.couchtv.launcher.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.View
import android.widget.FrameLayout
import java.text.SimpleDateFormat
import java.util.Date

/** A person's round picture: web/avatars/<id>.png when there is one, else their letter on their colour. */
object Avatar {
    private val PALETTE = intArrayOf(0xFFFF9F0A.toInt(), 0xFF30B350.toInt(), 0xFF0A84FF.toInt(), 0xFFBF5AF2.toInt(), 0xFFFF375F.toInt(), 0xFF00A5A8.toInt(), 0xFF5E5CE6.toInt(), 0xFFAC8E68.toInt())
    private val faces = HashMap<String, Bitmap?>()
    fun colour(p: Store.Person): Int {
        p.colour?.let { try { return Color.parseColor(it) } catch (e: Exception) {} }
        var h = 0L; for (ch in p.id) h = (h * 31 + ch.code) and 0xffffffffL
        return PALETTE[(h % PALETTE.size).toInt()]
    }
    fun face(ctx: Context, id: String): Bitmap? = faces.getOrPut(id) {
        try { ctx.assets.open("www/tv/avatars/$id.png").use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }
    }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Color.WHITE }
    fun draw(ctx: Context, c: Canvas, person: Store.Person, cx: Float, cy: Float, d: Float) {
        val r = d / 2
        val f = face(ctx, person.id)
        if (f != null) {
            val n = c.save(); c.clipPath(Path().apply { addCircle(cx, cy, r, Path.Direction.CW) })
            c.drawBitmap(f, null, RectF(cx - r, cy - r, cx + r, cy + r), p); c.restoreToCount(n)
        } else {
            p.color = colour(person); c.drawCircle(cx, cy, r, p)
            letter.typeface = Ui.interBold; letter.textSize = d * 0.55f
            c.drawText(person.name.trim().take(1).uppercase(), cx, cy + letter.textSize * 0.36f, letter)
        }
    }
}

/** The date line over the clock, with the weather (app.css .theme-night .date). */
@SuppressLint("ViewConstructor")
class DateLine(ctx: Context, private val home: HomeActivity) : View(ctx) {
    var scene: Scene? = null
        set(v) { field = v; refresh() }
    private val dayP = Ui.paint(Ui.interMid, 2.125f)
    private val wxP = Ui.paint(Ui.inter, 2.125f)
    private var text = ""
    fun refresh() {
        val s = SimpleDateFormat("EEEE d MMMM", L.locale()).format(Date())
        text = s.replaceFirstChar { it.uppercase() }
        invalidate()
    }
    override fun onDraw(c: Canvas) {
        val sc = scene ?: return
        val w = home.weather
        dayP.color = sc.dateColor; wxP.color = sc.dateWx
        if (sc.dateShadow) { dayP.setShadowLayer(Ui.r(1.125f), 0f, Ui.r(0.125f), 0x40000000); wxP.setShadowLayer(Ui.r(1.125f), 0f, Ui.r(0.125f), 0x40000000) }
        else { dayP.clearShadowLayer(); wxP.clearShadowLayer() }
        val temp = w?.let { home.tempText(it.temp) } ?: ""
        val words = w?.let { " " + Weather.words(it.code) } ?: ""
        val ic = Ui.r(1.875f); val gapA = Ui.r(1f); val gapB = Ui.r(0.4f)
        var total = dayP.measureText(text) + if (w != null) gapA + ic + gapB + wxP.measureText(temp + words) else 0f
        // too long for the space: drop the weather's words, then the weather
        val room = width - Ui.r(36f)
        var showWords = true; var showWx = w != null
        if (total > room && w != null) { showWords = false; total -= wxP.measureText(words) }
        if (total > room) { showWx = false }
        var x = sc.dateLeft?.let { Ui.r(it) } ?: (width - (if (showWx) total else dayP.measureText(text))) / 2
        val base = Ui.r(sc.dateTop) + Ui.r(1.9f)
        c.drawText(text, x, base, dayP)
        if (!showWx || w == null) return
        x += dayP.measureText(text) + gapA
        Icons.weather(w.code, w.day).draw(c, x, base - Ui.r(1.55f), ic, sc.dateWx)
        x += ic + gapB
        c.drawText(temp + if (showWords) words else "", x, base, wxP)
    }
}

/** Top left: who is watching (or the sidebar's symbol), on the glass. OK opens the sidebar. */
@SuppressLint("ViewConstructor")
class SideTab(ctx: Context, private val home: HomeActivity) : View(ctx) {
    private val face = Glass.Face()
    private val nameP = Ui.paint(Ui.interBold, 1.375f, 0xE6FFFFFF.toInt())
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private var focusT = 0f
    private val fs = Spring(0f) { focusT = it; val s = 1f + 0.08f * it; scaleX = s; scaleY = s; invalidate(); (parent as? View)?.invalidate() }
    init { pivotX = 0f }
    fun focusOn(on: Boolean) = fs.to(if (on) 1f else 0f, if (on) Motion.FOCUS else Motion.UNFOCUS)
    private val named get() = home.people.size >= 2
    override fun onMeasure(w: Int, h: Int) {
        val hh = Ui.r(3.25f)
        val ww = if (named) Ui.r(0.375f + 2.5f + 0.75f + 1.25f) + nameP.measureText(home.person()?.name ?: "") else hh
        setMeasuredDimension(ww.toInt(), hh.toInt())
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) { pivotY = height / 2f }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val rad = h / 2
        val loc = IntArray(2); getLocationOnScreen(loc)
        face.draw(c, w, h, rad, loc[0].toFloat(), loc[1].toFloat(), if (home.onLight(this)) Glass.WASH_LIGHT else Glass.WASH, s = scaleX)
        if (focusT > 0f) { bg.color = Ui.alpha(if (home.onLight(this)) 0xFF1D1D1F.toInt() else Color.WHITE, focusT); c.drawRoundRect(0f, 0f, w, h, rad, rad, bg) }
        val light = home.onLight(this)
        val ink = Ui.mix(if (light) 0xFF1D1D1F.toInt() else 0xE6FFFFFF.toInt(), if (light) Color.WHITE else 0xFF111111.toInt(), focusT)
        val p = home.person()
        if (named && p != null) {
            Avatar.draw(context, c, p, Ui.r(0.375f + 1.25f), h / 2, Ui.r(2.5f))
            nameP.color = ink
            c.drawText(p.name, Ui.r(0.375f + 2.5f + 0.75f), h / 2 + nameP.textSize * 0.36f, nameP)
        } else {
            bg.color = Ui.mix(0x24FFFFFF, 0x1A111111, focusT); c.drawCircle(w / 2, h / 2, Ui.r(1.1875f), bg)
            Icons.menu.draw(c, w / 2 - Ui.r(0.8f), h / 2 - Ui.r(0.8f), Ui.r(1.6f), ink, 1.9f)
        }
    }
}

/** The glass bubble that drops in at the top with a message, and slides away by itself. */
class ToastView(ctx: Context) : View(ctx) {
    var home: HomeActivity? = null
    private val face = Glass.Face()
    private val p = Ui.paint(Ui.interBold, 1.625f)
    private var text = ""
    private val drop = Spring(-1f) { translationY = it * Ui.r(9f); visibility = if (it <= -0.99f) INVISIBLE else VISIBLE; (parent as? View)?.invalidate() }
    private val hide = Runnable { drop.to(-1f, 0.52f) }
    init { visibility = INVISIBLE; drop.snap(-1f) }
    fun show(t: String, ms: Long = 4000) {
        if (t.isEmpty()) return
        text = Ui.fit(p, t, Ui.r(56f)); requestLayout(); invalidate()
        drop.to(0f, 0.52f)
        removeCallbacks(hide); postDelayed(hide, ms)
    }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((p.measureText(text) + Ui.r(4f)).toInt().coerceAtLeast(1), Ui.ri(4.125f))
    override fun onDraw(c: Canvas) {
        val loc = IntArray(2); getLocationOnScreen(loc)
        val light = home?.onLight(this) == true
        face.draw(c, width.toFloat(), height.toFloat(), Ui.r(2f), loc[0].toFloat(), loc[1].toFloat(), if (light) Glass.WASH_LIGHT else Glass.WASH)
        p.color = if (light) 0xFF1D1D1F.toInt() else Color.WHITE
        c.drawText(text, Ui.r(2f), height / 2f + p.textSize * 0.36f, p)
    }
}

/**
 * The row above the dock: the living room and the weather, Upcoming, then Keep watching, five in
 * view; each part has its heading, moving with the cards (keepwatching.css, homecards.css).
 */
@SuppressLint("ViewConstructor")
class Shelf(ctx: Context, private val home: HomeActivity) : FrameLayout(ctx) {
    private val track = ShadowFrame(ctx)
    private val heads = FrameLayout(ctx).apply { clipChildren = false }
    val cards = ArrayList<CardView>()
    var s = 0
    var first = 0
    private var prems = listOf<Show>()
    private var premAt = 0
    private val cardW = Ui.r(20.5f); private val gap = Ui.r(1.875f); private val left = Ui.r(5f)
    private val top = Ui.r(39.625f)
    private val scroll = Spring(0f, 0.05f) { track.translationX = it; heads.translationX = it; invalidateGlass() }

    init {
        clipChildren = false
        addView(heads, LayoutParams(-1, -2).apply { topMargin = Ui.ri(36.375f) })
        addView(track, LayoutParams(-1, Ui.ri(11.5f)).apply { topMargin = top.toInt() })
    }
    fun count() = cards.size
    fun focused() = cards.getOrNull(s)
    fun focusedX() = cards.getOrNull(s)?.let { left + it.left + track.translationX + it.width / 2f } ?: 0f
    fun invalidateGlass() = cards.forEach { if (it.show.home != null) it.invalidate() }

    private var kw = listOf<Show>()
    private var arts = mapOf<String, Bitmap?>()
    fun setData(list: List<Show>, prem: List<Show>, art: Map<String, Bitmap?>) {
        kw = list; prems = prem; arts = art
        if (premAt >= prems.size) premAt = 0
        render()
    }
    private fun homeCards(): List<Show> {
        val out = ArrayList<Show>()
        val r = home.room
        if (r != null && r.lights.isNotEmpty()) out.add(Show("hc-room", L.t("hc_lights"), home = "room"))
        if (r?.climate != null) out.add(Show("hc-climate", L.t("hc_climate"), home = "climate"))
        if (home.store.wxShow && home.weather?.days?.isNotEmpty() == true) out.add(Show("hc-weather", L.t("hc_weather"), home = "weather"))
        return out
    }
    /** New readings: redraw the row only when a card comes or goes, else each home card in place. */
    fun homeChanged() {
        val want = homeCards().map { it.home }; val have = cards.mapNotNull { it.show.home }
        if (want != have) { render(); home.paint() } else invalidateGlass()
    }

    fun render() {
        val hc = homeCards()
        val prem = prems.getOrNull(premAt)
        val all = hc + listOfNotNull(prem) + kw
        val keepFocus = cards.getOrNull(s)?.show?.id
        track.removeAllViews(); track.shadows.clear(); heads.removeAllViews(); cards.clear()
        all.forEachIndexed { i, sh ->
            val c = CardView(context, sh, home.shows, home.apps, if (sh.home != null) { cv: Canvas, card: CardView -> drawHome(cv, card) } else null)
            sh.art?.let { u -> c.art = arts[u] }
            c.screenX = { left + c.left + track.translationX }
            c.screenY = { top + translationY }
            track.addView(c, LayoutParams(cardW.toInt(), Ui.ri(11.5f)).apply { leftMargin = (left + i * (cardW + gap)).toInt() })
            if (sh.home != null) track.shadow(c, { Ui.r(1.5f) }, { 14f + (Ui.r(2.5f) - 14f) * c.focusT }, { 8f + (Ui.r(1.5f) - 8f) * c.focusT }, { Ui.mix(0x1F000000, 0x73000000, c.focusT) })
            cards.add(c)
        }
        fun head(t: String, at: Int) {
            val v = HeadText(context, t)
            heads.addView(v, LayoutParams(-2, -2).apply { leftMargin = (left + at * (cardW + gap)).toInt() })
        }
        if (hc.isNotEmpty()) head(home.room?.label?.ifEmpty { null } ?: L.t("hc_home"), 0)
        if (prem != null) head(L.t("upcoming"), hc.size)
        val ki = hc.size + (if (prem != null) 1 else 0)
        if ((hc.isNotEmpty() || prem != null) && all.size > ki) head(L.t("keep_watching"), ki)
        else if (hc.isEmpty() && prem == null && kw.isNotEmpty()) head(L.t("keep_watching"), 0)
        visibility = if (all.isEmpty()) INVISIBLE else VISIBLE
        keepFocus?.let { id -> all.indexOfFirst { it.id == id }.let { if (it >= 0) s = it } }
        s = s.coerceIn(0, maxOf(0, cards.size - 1))
        removeCallbacks(turn)
        if (prems.size > 1) postDelayed(turn, 30000)
        paint(home.zone == "shelf" || home.zone == "hpanel", snap = true)
    }

    /** The next premiere, swapped into its card where it stands (a short fade). */
    private val turn = object : Runnable {
        override fun run() {
            val i = cards.indexOfFirst { it.show.premiere }
            if (i >= 0 && prems.size > 1) {
                val c = cards[i]
                val f = Spring(1f, 0.01f) { c.turning = it }
                f.to(0f, 0.35f)
                postDelayed({
                    premAt = (premAt + 1) % prems.size
                    val n = prems[premAt]; c.show = n; c.art = n.art?.let { arts[it] }
                    if (n.art != null && !arts.containsKey(n.art)) home.io.execute { val b = Net.bitmap(context, n.art, 640); post { if (c.show === n) c.art = b } }
                    f.to(1f, 0.35f)
                }, 350)
            }
            postDelayed(this, 30000)
        }
    }

    fun paint(on: Boolean, snap: Boolean = false) {
        cards.forEachIndexed { i, c -> if (snap) c.snapFocus(on && i == s) else c.focus(on && i == s) }
        if (!on) return
        // as Apple TV: focus walks across the cards in view; the row slides once it reaches the
        // second-to-last one (going right) or the second one (going left)
        val step = cardW + gap
        val fit = maxOf(2, ((Ui.width - 2 * left + step - cardW) / step).toInt())
        if (s > first + fit - 2) first = s - (fit - 2)
        if (s < first + 1) first = s - 1
        first = first.coerceIn(0, maxOf(0, cards.size - fit))
        if (snap) scroll.snap(-first * step) else scroll.to(-first * step, Motion.ROW)
    }
    fun step(dx: Int): Boolean { val n = s + dx; if (n < 0 || n >= cards.size) return false; s = n; return true }
    fun bump(dir: Int) {
        val c = cards.getOrNull(s) ?: return
        if (Motion.calm) return
        val b = Spring(0f, 0.05f) { c.translationX = it }
        b.to(Ui.r(0.75f) * dir, 0.1f)
        postDelayed({ b.to(0f, Motion.KICK) }, 100)
    }

    // ---- the home cards (homecards.css) ----
    private val hk = Ui.paint(Ui.interBold, 1.1875f, 0xB8FFFFFF.toInt())
    private val big = Ui.paint(Ui.interBold, 2f)
    private val small = Ui.paint(Ui.inter, 1.125f, 0xB8FFFFFF.toInt())
    private val dayP = Ui.paint(Ui.inter, 1.0625f, 0xB8FFFFFF.toInt())
    private val hiP = Ui.paint(Ui.interBold, 1.8125f)
    private val loP = Ui.paint(Ui.inter, 0.9375f, 0x8CFFFFFF.toInt())   // .hcard .hday i
    init { listOf(hk, big, small, dayP, hiP, loP).forEach { it.setShadowLayer(Ui.r(0.5f), 0f, Ui.r(0.0625f), 0x59000000) } }
    private fun drawHome(c: Canvas, card: CardView) {
        val w = card.width.toFloat(); val h = card.height.toFloat()
        val px = Ui.r(1.25f); val py = Ui.r(1.125f)
        val kind = card.show.home
        val icon = when (kind) { "room" -> Icons.bulb; "climate" -> Icons.thermo; else -> Icons.cloud }
        icon.draw(c, px, py + Ui.r(0.0625f), Ui.r(1.375f), 0xB8FFFFFF.toInt())
        c.drawText(card.show.title, px + Ui.r(1.875f), py + Ui.r(1.15f), hk)
        val r = home.room
        when (kind) {
            "room" -> if (r != null) {
                val lit = r.lights.count { it.on }
                c.drawText(Ui.fit(big, if (lit > 0) L.t("hc_lit", "n" to lit, "m" to r.lights.size) else L.t("hc_off"), w - 2 * px), px, h - py - Ui.r(1.75f), big)
                c.drawText(Ui.fit(small, r.label, w - 2 * px), px, h - py - Ui.r(0.3f), small)
            }
            "climate" -> r?.climate?.let { cl ->
                c.drawText(cl.current?.let { "${Math.round(it * 10) / 10.0}°".replace(".0°", "°") } ?: "–", px, h - py - Ui.r(1.75f), big)
                c.drawText(Ui.fit(small, climateWords(cl), w - 2 * px), px, h - py - Ui.r(0.3f), small)
            }
            else -> home.weather?.let { wx ->
                val days = wx.days.take(4); val colW = (w - 2 * px) / days.size
                days.forEachIndexed { i, d ->
                    val cx = px + colW * i + colW / 2
                    dayP.textAlign = Paint.Align.CENTER; hiP.textAlign = Paint.Align.CENTER; loP.textAlign = Paint.Align.CENTER
                    dayP.color = if (i == 0) Color.WHITE else 0xB8FFFFFF.toInt(); dayP.typeface = if (i == 0) Ui.interBold else Ui.inter
                    val y0 = h - py - Ui.r(5.95f)
                    c.drawText(dayName(d.date, i), cx, y0, dayP)
                    Icons.weather(d.code, true).draw(c, cx - Ui.r(0.8125f), y0 + Ui.r(0.35f), Ui.r(1.625f), Color.WHITE, 1.6f)
                    c.drawText(home.tempText(d.hi), cx, y0 + Ui.r(4.2f), hiP)
                    c.drawText(home.tempText(d.lo), cx, y0 + Ui.r(5.55f), loP)
                }
            }
        }
    }
    fun climateWords(cl: Ha.Climate): String {
        val to = cl.target?.let { "${Math.round(it * 10) / 10.0}°".replace(".0°", "°") } ?: ""
        if (cl.mode == "off") return L.t("hc_off")
        return L.t(if (cl.action == "cooling" || cl.mode == "cool") "hc_cooling" else if (cl.action == "heating" || cl.mode == "heat") "hc_heating" else "hc_set", "t" to to)
    }
    fun dayName(iso: String, i: Int): String {
        if (i == 0) return L.t("hc_today")
        return try { SimpleDateFormat("EEE", L.locale()).format(SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(iso)!!).replace(".", "").replaceFirstChar { it.uppercase() } } catch (e: Exception) { "" }
    }
}

class HeadText(ctx: Context, private val text: String) : View(ctx) {
    private val p = Ui.paint(Ui.interBold, 1.5625f, 0xC7FFFFFF.toInt()).apply { setShadowLayer(Ui.r(1f), 0f, Ui.r(0.125f), 0x66000000) }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((p.measureText(text) + Ui.r(1f)).toInt(), Ui.ri(2.25f))
    override fun onDraw(c: Canvas) { c.drawText(text, 0f, Ui.r(1.7f), p) }
}

/**
 * The dock: the first eleven apps and More apps, on the glass. More apps grows it into every app,
 * twelve to a row, the App Store first; the cards step aside (glass.css, app.css .grid).
 */
@SuppressLint("ViewConstructor")
class Dock(ctx: Context, private val home: HomeActivity) : ShadowFrame(ctx) {
    private val glass = GlassBack(ctx)
    val tiles = ArrayList<AppTile>()
    var g = 0
    var lift: String? = null
    private val iw = Ui.r(7f); private val colGap = Ui.r(1.625f); private val rowGap = Ui.r(3f)
    private val padX = Ui.r(2.25f); private val padY = Ui.r(2f)
    private var cols = 12

    inner class GlassBack(ctx: Context) : View(ctx) {
        val face = Glass.Face()
        override fun onDraw(c: Canvas) { face.draw(c, width.toFloat(), height.toFloat(), Ui.r(3.625f), left.toFloat(), top.toFloat()) }
    }
    init { clipChildren = false; addView(glass, LayoutParams(0, 0)); glass.visibility = INVISIBLE; shadow(glass, { Ui.r(3.625f) }, { 14f }, { 8f }, { 0x1F000000 }) }
    fun invalidateGlass() = glass.invalidate()
    fun count() = tiles.size
    fun focused() = tiles.getOrNull(g)
    fun focusedX() = tiles.getOrNull(g)?.let { it.left + it.width / 2f } ?: 0f

    /**
     * grow: More apps opening or closing. As the web home (motion.js, regrowDock): the glass grows
     * to its new height over 460 ms, the old icons fade out, and the new rows fade and rise in one
     * after another (80 ms + 45 ms a row, 360 ms each).
     */
    fun render(grow: Boolean = false) {
        val items = home.apps.dock()
        val keep = tiles.getOrNull(g)?.item?.pkg
        val old = ArrayList(tiles)
        val from = glass.layoutParams as? LayoutParams
        val fromRect = if (from != null && glass.width > 0) floatArrayOf(from.leftMargin.toFloat(), from.topMargin.toFloat(), glass.width.toFloat(), glass.height.toFloat()) else null
        // where each app sat, so the ones in both layouts glide to their new place instead of fading
        val was = HashMap<String, FloatArray>()
        old.forEach { t -> if (t.item.pkg.isNotEmpty()) was[t.item.pkg] = floatArrayOf((t.layoutParams as LayoutParams).leftMargin + t.translationX, (t.layoutParams as LayoutParams).topMargin + t.translationY) }
        val stay = items.map { it.pkg }.toSet()
        val closing = old.size > items.size
        if (grow && !Motion.calm) {
            old.forEach { t ->
                if (t.item.pkg.isNotEmpty() && t.item.pkg in stay) removeView(t)
                else t.animate().alpha(0f).setDuration(if (closing) 170 else 160).withEndAction { removeView(t) }.start()   // leaving: fades first
            }
        } else old.forEach { removeView(it) }
        tiles.clear()
        val expanded = home.apps.expanded
        cols = if (expanded) 12 else maxOf(1, minOf(12, items.size))
        val rows = (items.size + cols - 1) / cols
        val fill = expanded || items.size >= 10
        val gw = if (fill) Ui.width - 2 * Ui.r(5f) else cols * iw + (cols - 1) * colGap + 2 * padX
        val gh = rows * iw + (rows - 1) * rowGap + 2 * padY
        val gx = (Ui.width - gw) / 2
        val gy = Ui.height - Ui.r(2.875f) - gh
        glassTo(gx, gy, gw, gh, if (grow && !Motion.calm) fromRect else null)
        glass.visibility = VISIBLE   // only once it has its place (before that it would cover the screen)
        val inner = gw - 2 * padX
        val colStep = if (fill && cols > 1) (inner - iw) / (cols - 1) else iw + colGap
        items.forEachIndexed { i, a ->
            val t = AppTile(context, a, if (a.pkg.isNotEmpty()) home.apps.cachedIcon(a.pkg) else null)
            t.isNew = a.pkg in home.apps.newApps
            val r = i / cols; val c = i % cols
            addView(t, LayoutParams(iw.toInt(), iw.toInt()).apply { leftMargin = (gx + padX + c * colStep).toInt(); topMargin = (gy + padY + r * (iw + rowGap)).toInt() })
            tiles.add(t)
            if (grow && !Motion.calm) {
                val lp = t.layoutParams as LayoutParams
                val w = was[a.pkg]
                if (w != null) {
                    // in the dock before and after: glides from where it was, with the glass
                    t.translationX = w[0] - lp.leftMargin; t.translationY = w[1] - lp.topMargin
                    // closing: only once the other apps have faded, so nothing passes over them
                    t.animate().translationX(0f).translationY(0f).setStartDelay(if (closing) 170L else 0L).setDuration(460)
                        .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.8f, 0.2f, 1f)).start()
                } else {
                    // new: once the dock's icons have passed on their way up, row by row from the top
                    // (so nothing crosses), 45 ms apart, 360 ms each
                    t.alpha = 0f; t.translationY = Ui.r(1f)
                    t.animate().alpha(1f).translationY(0f).setStartDelay(220L + r * 45L).setDuration(360)
                        .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.8f, 0.2f, 1f)).start()
                }
            }
        }
        keep?.let { k -> tiles.indexOfFirst { it.item.pkg == k }.let { if (it >= 0) g = it } }
        g = g.coerceIn(0, maxOf(0, tiles.size - 1))
        home.shelf.animate().alpha(if (expanded || lift != null) 0f else 1f).setStartDelay(if (grow && !expanded) 420L else 0L).setDuration(240).start()
        tiles.forEach { it.lifted = it.item.pkg == lift; it.dimmed = lift != null && it.item.pkg != lift }
        paint(home.zone == "grid", snap = true)
    }
    private var growAnim: android.animation.ValueAnimator? = null
    /** The glass to its new place and size; from: where it was, to grow from there. */
    private fun glassTo(gx: Float, gy: Float, gw: Float, gh: Float, from: FloatArray?) {
        growAnim?.cancel()
        fun place(x: Float, y: Float, w: Float, h: Float) {
            glass.layoutParams = LayoutParams(w.toInt(), h.toInt()).apply { leftMargin = x.toInt(); topMargin = y.toInt() }
            invalidate()
        }
        if (from == null) { place(gx, gy, gw, gh); return }
        growAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).setDuration(460).apply {
            if (from[3] > gh) startDelay = 170   // shrinking: the leaving icons fade first
            interpolator = android.view.animation.PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
            addUpdateListener { v -> val t = v.animatedValue as Float
                place(from[0] + (gx - from[0]) * t, from[1] + (gy - from[1]) * t, from[2] + (gw - from[2]) * t, from[3] + (gh - from[3]) * t) }
            start()
        }
    }
    fun paint(on: Boolean, snap: Boolean = false) {
        tiles.forEachIndexed { i, t -> if (snap) t.snapFocus(on && i == g) else t.focus(on && i == g) }
    }
    /** -1: up from the first row. */
    fun move(dx: Int, dy: Int): Int {
        val row = g / cols; val last = (tiles.size - 1) / cols
        if (dx != 0) {
            val n = g + dx
            if (n >= 0 && n < tiles.size && n / cols == row) g = n else bump(dx)
        } else if (dy < 0) { if (row == 0) return -1; g -= cols }
        else if (dy > 0 && row < last) g = minOf(g + cols, tiles.size - 1)
        return 0
    }
    private fun bump(dir: Int) {
        val t = tiles.getOrNull(g) ?: return
        if (Motion.calm) return
        val b = Spring(0f, 0.05f) { t.translationX = it }
        b.to(Ui.r(0.6f) * dir, 0.1f); postDelayed({ b.to(0f, Motion.KICK) }, 100)
    }
    fun nearest(x: Float): Int = (0 until minOf(cols, tiles.size)).minByOrNull { Math.abs(tiles[it].left + tiles[it].width / 2f - x) } ?: 0

    // ---- moving apps with the remote (hold OK) ----
    fun hold(): Boolean {
        val a = tiles.getOrNull(g)?.item ?: return false
        if (a.kind == "more") return false
        val apps = home.apps
        if (a.store) { home.toast.show(L.t("stays_put", "name" to a.name)); return true }
        val (items, line) = apps.arrange()
        val idx = items.indexOfFirst { it.pkg == a.pkg }
        if (idx < 0) return false
        if (!apps.expanded) { apps.materialise(); lift = a.pkg; render(); home.toast.show(L.t("app_lift_hint")); return true }
        if (idx < line) { home.toast.show(L.t("already_home", "name" to a.name)); return true }
        val o = apps.materialise(); var msg = L.t("now_home", "name" to a.name)
        o.packages.removeAt(idx)
        if (o.line < apps.dockSize) { o.packages.add(o.line, a.pkg); o.line += 1 }
        else { o.packages.add(o.line - 1, a.pkg); msg += " " + L.t("moved_more", "name" to apps.nameOf(o.packages[o.line])) }
        apps.persist(); apps.expanded = false; render(grow = true)
        g = o.packages.indexOf(a.pkg).coerceAtLeast(0); paint(true)
        home.toast.show(msg)
        return true
    }
    fun moveLift(dx: Int, dy: Int) {
        val apps = home.apps; val o = apps.order ?: return; val p = lift ?: return
        val i = o.packages.indexOf(p)
        if (dx != 0) {
            val j = i + dx
            if (j >= 0 && j < o.line) { o.packages[i] = o.packages[j]; o.packages[j] = p; g = j }
        } else if (dy > 0) {
            val name = apps.nameOf(p)
            o.packages.removeAt(i); o.packages.add(o.line - 1, p); o.line -= 1
            lift = null; apps.persist(); render()
            g = minOf(i, tiles.size - 1); home.toast.show(L.t("moved_more", "name" to name))
        }
        render()
    }
    fun drop() { lift = null; home.apps.persist(); render(); home.toast.show(L.t("saved")) }
}
