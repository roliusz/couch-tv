package app.couchtv.launcher.home

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.view.KeyEvent
import android.view.View

/**
 * Quick settings (fork addition), as Sony's own gear panel: a sheet that slides up from the bottom
 * over whatever is playing. Speakers, inputs, the soundbar (subwoofer, rears, night, voice),
 * picture, and a sleep timer with a way into full settings. The TV and the soundbar are changed
 * through their own network controls (Sony.kt).
 *
 * Opened by couchtv://quick (a Remote 3 button sends it to the TV as an app link) or directly.
 * It runs in a task of its own, so it opens over the app that is playing, not over the home.
 */
class QuickActivity : Activity() {
    private lateinit var panel: QuickPanel
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Ui.init(this); L.load(this)
        panel = QuickPanel(this)
        setContentView(panel)
        Sony.findBar(this)
        load()
    }
    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); load() }
    fun load() = Sony.read(this) { s -> panel.show(s) }
    override fun onPause() { super.onPause(); if (!isFinishing) panel.close() }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() = panel.close()
    override fun dispatchKeyEvent(e: KeyEvent): Boolean = panel.key(e) || super.dispatchKeyEvent(e)
}

/** The sleep timer going off: the TV switches itself off through its own control. */
class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val p = goAsync()
        ctx.getSharedPreferences("home", Context.MODE_PRIVATE).edit().remove("sleep_at").remove("sleep_min").apply()
        Sony.powerOff(ctx) { p.finish() }
    }
}

object Sleep {
    private fun pi(ctx: Context) = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, SleepReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun set(ctx: Context, min: Int) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val prefs = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)
        am.cancel(pi(ctx))
        if (min <= 0) { prefs.edit().remove("sleep_at").remove("sleep_min").apply(); return }
        val at = System.currentTimeMillis() + min * 60_000L
        am.setWindow(AlarmManager.RTC_WAKEUP, at, 30_000L, pi(ctx))
        prefs.edit().putLong("sleep_at", at).putInt("sleep_min", min).apply()
    }
    /** Minutes the running timer was set for, or 0. */
    fun chosen(ctx: Context): Int {
        val p = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)
        return if (p.getLong("sleep_at", 0L) > System.currentTimeMillis()) p.getInt("sleep_min", 0) else 0
    }
    fun left(ctx: Context): Int {
        val at = ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getLong("sleep_at", 0L)
        return if (at > System.currentTimeMillis()) ((at - System.currentTimeMillis() + 59_999L) / 60_000L).toInt() else 0
    }
}

@SuppressLint("ViewConstructor")
class QuickPanel(private val act: QuickActivity) : View(act) {
    private class Item(val label: String, val icon: Icon? = null) {
        var active: () -> Boolean = { false }
        var marks = false                         // a check mark shows when it is on
        var dim: () -> Boolean = { false }
        var ok: () -> Unit = {}
        var slider: Sony.Slider? = null           // a value moved with left and right
        var picks: List<Pair<String, String>>? = null; var pick = 0   // or one of a list
        var commit: (String) -> Unit = {}
        val rect = RectF()
        val adjustable get() = slider != null || picks != null
    }
    private class Row(val title: String, val items: List<Item>)

    private var s: Sony.State? = null
    private val rows = ArrayList<Row>()
    private var ri = 0; private var ii = 0
    private var adjusting = false
    private var msg = ""; private var msgA = 0f

    private var t = 0f
    private var closing = false
    private val open = Spring(0f, 0.001f) { t = it; invalidate(); if (closing && it <= 0.002f) act.finish() }
    private val fx = Spring(0f) { invalidate() }; private val fy = Spring(0f) { invalidate() }
    private val fw = Spring(0f) { invalidate() }
    private val msgFade = Spring(0f, 0.002f) { msgA = it; invalidate() }

    private val titleP = Ui.paint(Ui.interBold, 1.25f, 0x99FFFFFF.toInt())
    private val chipP = Ui.paint(Ui.interMid, 1.375f)
    private val valP = Ui.paint(Ui.interBold, 1.375f)
    private val msgP = Ui.paint(Ui.inter, 1.25f, 0xB3FFFFFF.toInt())
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Ui.r(0.0625f); color = 0x29FFFFFF }
    private val scrim = Paint()

    private val side = Ui.r(5f); private val bottom = Ui.r(3f)
    private val rowH = Ui.r(4.5f); private val chipH = Ui.r(3.375f)
    private val padTop = Ui.r(2f); private val padBottom = Ui.r(3.25f)
    private val itemsX = Ui.r(13.5f)
    private val panelH get() = padTop + rows.size * rowH + padBottom

    init { isFocusable = true; isFocusableInTouchMode = true; setLayerType(LAYER_TYPE_HARDWARE, null) }

    fun show(st: Sony.State) {
        s = st; build()
        if (closing) return
        if (t == 0f && open.t == 0f) open.to(1f, 0.42f)
        place(true); requestFocus()
    }
    fun close() {
        if (closing) return
        closing = true
        open.to(0f, 0.3f)
        if (rows.isEmpty() || Motion.calm) act.finish()
    }

    private fun say(m: String) { msg = m; msgFade.to(1f, 0.25f); removeCallbacks(unsay); postDelayed(unsay, 4500) }
    private val unsay = Runnable { msgFade.to(0f, 0.4f) }

    private val modeNames = mapOf("vivid" to "Vivid", "standard" to "Standard", "cinema" to "Cinema", "custom" to "Custom",
        "dolbyVisionBright" to "Dolby Vision Bright", "dolbyVisionDark" to "Dolby Vision Dark", "calibrated" to "Calibrated",
        "game" to "Game", "graphics" to "Graphics", "photo" to "Photo", "netflix" to "Netflix Adaptive", "ipm" to "IMAX Enhanced")

    private fun build() {
        val st = s ?: return
        val ctx = act
        val wasRow = rows.getOrNull(ri)?.title; val wasItem = rows.getOrNull(ri)?.items?.getOrNull(ii)?.label
        rows.clear()
        fun needKey(): Boolean { if (!st.hasKey) { say(L.t("qp_need_key")); return true }; return false }

        // 1. speakers
        val sp = listOf("speaker" to Item(L.t("qp_tv_speakers"), Icons.screen), "audioSystem" to Item(L.t("qp_soundbar"), Icons.speaker))
        for ((v, it) in sp) {
            it.active = { st.output == v }; it.dim = { !st.hasKey }; it.marks = true
            it.ok = { if (!needKey() && st.output != v) Sony.setOutput(ctx, v) { ok -> if (ok) { st.output = v; invalidate() } else say(L.t("qp_failed")) } }
        }
        rows.add(Row(L.t("qp_speakers"), sp.map { it.second }))

        // 2. inputs
        val ins = ArrayList<Item>()
        for (inp in st.inputs) ins.add(Item(inp.title, Icons.input).apply {
            ok = { Sony.input(ctx, inp) { ok -> if (ok) act.finish() else say(L.t("qp_failed")) } }
        })
        ins.add(Item(L.t("qp_all_inputs"), Icons.more).apply {
            ok = { try { ctx.startActivity(Intent("com.android.tv.action.VIEW_INPUTS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}; act.finish() }
        })
        rows.add(Row(L.t("qp_inputs"), ins))

        // 3. the soundbar
        val bar = ArrayList<Item>()
        fun slider(sl: Sony.Slider?, label: String) = sl?.let { v -> Item(label).apply { slider = v; commit = { x -> Sony.bar(v.target, x) { ok -> if (!ok) say(L.t("qp_no_bar")) } } } }
        slider(st.sub, L.t("qp_sub"))?.let { bar.add(it) }
        slider(st.rear, L.t("qp_rear"))?.let { bar.add(it) }
        fun toggle(label: String, target: String, get: () -> Boolean?, set: (Boolean) -> Unit) = Item(label).apply {
            active = { get() == true }; dim = { get() == null }; marks = true
            ok = {
                val now = get()
                if (now == null) say(L.t("qp_no_bar"))
                else { set(!now); invalidate(); Sony.bar(target, if (!now) "on" else "off") { ok -> if (!ok) { set(now); invalidate(); say(L.t("qp_no_bar")) } } }
            }
        }
        bar.add(toggle(L.t("qp_night"), "nightMode", { st.night }, { st.night = it }))
        bar.add(toggle(L.t("qp_voice"), "voice", { st.voice }, { st.voice = it }))
        rows.add(Row(L.t("qp_soundbar"), bar))

        // 4. picture
        val pic = ArrayList<Item>()
        if (st.pictureModes.isNotEmpty()) pic.add(Item(L.t("qp_mode")).apply {
            picks = st.pictureModes.map { it to (modeNames[it] ?: it.replaceFirstChar { c -> c.uppercase() }) }
            pick = st.pictureModes.indexOf(st.pictureMode).coerceAtLeast(0)
            dim = { !st.hasKey }
            commit = { v -> Sony.setPicture(ctx, "pictureMode", v) { ok -> if (ok) st.pictureMode = v else say(L.t(if (st.hasKey) "qp_failed" else "qp_need_key")) } }
        })
        st.brightness?.let { b -> pic.add(Item(L.t("qp_brightness")).apply {
            slider = b; dim = { !st.hasKey }
            commit = { v -> Sony.setPicture(ctx, "brightness", v) { ok -> if (!ok) say(L.t(if (st.hasKey) "qp_failed" else "qp_need_key")) } }
        }) }
        pic.add(Item(L.t("qp_picture_off"), Icons.picture).apply {
            dim = { !st.hasKey }
            ok = { if (!needKey()) Sony.pictureOff(ctx) { ok -> if (ok) act.finish() else say(L.t("qp_failed")) } }
        })
        rows.add(Row(L.t("qp_picture"), pic))

        // 5. sleep timer and settings
        val sl = ArrayList<Item>()
        for (m in listOf(0, 15, 30, 60, 90)) sl.add(Item(if (m == 0) L.t("qp_off") else L.t("qp_min", "n" to m), if (m == 0) null else Icons.moon).apply {
            active = { Sleep.chosen(ctx) == m }; dim = { m > 0 && !st.hasKey }; marks = true
            ok = {
                if (m == 0 || !needKey()) {
                    Sleep.set(ctx, m); invalidate()
                    if (m > 0) say(L.t("qp_left", "n" to m))
                }
            }
        })
        sl.add(Item(L.t("qp_settings"), Icons.gear).apply {
            ok = { try { ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}; act.finish() }
        })
        rows.add(Row(L.t("qp_sleep"), sl))

        // keep the focus where it was (a second reading of the TV), else on the first input
        val r = rows.indexOfFirst { it.title == wasRow }
        if (r >= 0) { ri = r; ii = rows[r].items.indexOfFirst { it.label == wasItem }.coerceAtLeast(0) }
        else { ri = 1; ii = 0 }
        layoutItems()
    }

    private fun itemW(it: Item): Float = when {
        it.slider != null -> Ui.r(17f)
        it.picks != null -> Ui.r(21f)
        else -> chipP.measureText(it.label) + Ui.r(3f) + (if (it.icon != null) Ui.r(2.25f) else 0f) + (if (it.marks) Ui.r(2.25f) else 0f)
    }
    private fun layoutItems() {
        val top = height - bottom - panelH
        for ((r, row) in rows.withIndex()) {
            var x = side + itemsX
            val cy = top + padTop + r * rowH + rowH / 2
            for (it in row.items) { val w = itemW(it); it.rect.set(x, cy - chipH / 2, x + w, cy + chipH / 2); x += w + Ui.r(1f) }
        }
    }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { layoutItems(); place(true) }

    private fun place(snap: Boolean) {
        val it = rows.getOrNull(ri)?.items?.getOrNull(ii) ?: return
        if (snap) { fx.snap(it.rect.left); fy.snap(it.rect.top); fw.snap(it.rect.width()) }
        else { fx.to(it.rect.left, 0.28f); fy.to(it.rect.top, 0.28f); fw.to(it.rect.width(), 0.28f) }
        invalidate()
    }

    private var commitRun: Runnable? = null
    private fun adjust(dx: Int) {
        val it = rows[ri].items[ii]
        if (it.dim()) { say(L.t("qp_need_key")); adjusting = false; return }
        val sl = it.slider
        val v: String
        if (sl != null) { sl.value = (sl.value + dx).coerceIn(sl.min, sl.max); v = sl.value.toString() }
        else { val p = it.picks!!; it.pick = (it.pick + dx + p.size) % p.size; v = p[it.pick].first }
        invalidate()
        commitRun?.let { removeCallbacks(it) }
        commitRun = Runnable { it.commit(v) }.also { postDelayed(it, 450) }   // several presses send one change
    }

    fun key(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return e.keyCode in NAV
        if (rows.isEmpty() || closing) return e.keyCode in NAV
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { if (adjusting) { adjusting = false; invalidate() } else close() }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val dx = if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                if (adjusting) adjust(dx)
                else { val n = (ii + dx).coerceIn(0, rows[ri].items.size - 1); if (n != ii) { ii = n; place(false) } }
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                adjusting = false
                val n = (ri + if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1).coerceIn(0, rows.size - 1)
                if (n != ri) {
                    // the item under the one we leave, as a remote expects
                    val cx = rows[ri].items[ii].rect.centerX()
                    ri = n; ii = rows[n].items.indices.minByOrNull { Math.abs(rows[n].items[it].rect.centerX() - cx) } ?: 0
                    place(false)
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                val it = rows[ri].items[ii]
                if (it.adjustable) { if (it.dim()) say(L.t("qp_need_key")) else { adjusting = !adjusting; invalidate() } }
                else it.ok()
            }
            else -> return false
        }
        return true
    }
    private val NAV = setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A)

    override fun onDraw(c: Canvas) {
        if (rows.isEmpty()) return
        val w = width.toFloat(); val h = height.toFloat()
        val ease = t.coerceIn(0f, 1f)
        // a shade over the bottom of the picture, so the panel reads over anything
        scrim.shader = LinearGradient(0f, h * 0.3f, 0f, h, 0, Ui.alpha(0xCC000000.toInt(), ease), Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.3f, w, h, scrim)

        val ph = panelH
        val dy = (1f - t) * (ph + bottom)
        c.save(); c.translate(0f, dy)
        val top = h - bottom - ph
        val rad = Ui.r(2.25f)
        fill.color = 0xF0202023.toInt()
        c.drawRoundRect(side, top, w - side, h - bottom, rad, rad, fill)
        c.drawRoundRect(side, top, w - side, h - bottom, rad, rad, rim)

        // the focus: one white pill gliding between items
        val fr = RectF(fx.x, fy.x, fx.x + fw.x, fy.x + chipH)
        fill.color = Color.WHITE
        c.drawRoundRect(fr, chipH / 2, chipH / 2, fill)

        for ((r, row) in rows.withIndex()) {
            val cy = top + padTop + r * rowH + rowH / 2
            c.drawText(Ui.fit(titleP, row.title, itemsX - Ui.r(2.5f)), side + Ui.r(2.5f), cy + titleP.textSize * 0.36f, titleP)
            for ((k, it) in row.items.withIndex()) drawItem(c, it, r == ri && k == ii)
        }
        if (msgA > 0f) {
            msgP.alpha = (msgA * 179).toInt()
            c.drawText(Ui.fit(msgP, msg, w - 2 * side - Ui.r(5f)), side + Ui.r(2.5f), h - bottom - Ui.r(1.25f), msgP)
        }
        c.restore()
    }

    private fun drawItem(c: Canvas, it: Item, focused: Boolean) {
        val r = it.rect; val rad = chipH / 2
        val dim = it.dim()
        if (!focused) {
            fill.color = if (it.active()) 0x47FFFFFF else 0x17FFFFFF
            c.drawRoundRect(r, rad, rad, fill)
        }
        val ink = when { focused -> 0xFF1D1D1F.toInt(); else -> Color.WHITE }
        val a = if (dim) 0.42f else 1f
        val cy = r.centerY()
        var x = r.left + Ui.r(1.5f)
        val sl = it.slider; val pk = it.picks
        if (sl != null || pk != null) {
            chipP.color = Ui.alpha(ink, a * (if (focused) 0.75f else 0.7f)); valP.color = Ui.alpha(ink, a)
            c.drawText(it.label, x, cy + chipP.textSize * 0.36f - (if (sl != null) Ui.r(0.3f) else 0f), chipP)
            val value = if (sl != null) sl.value.toString() else pk!![it.pick].second
            val vr = r.right - Ui.r(1.5f) - (if (focused && adjusting) Ui.r(1.25f) else 0f)
            val vw = valP.measureText(value)
            c.drawText(value, vr - vw, cy + valP.textSize * 0.36f - (if (sl != null) Ui.r(0.3f) else 0f), valP)
            if (focused && adjusting) {   // ‹ › while left and right change it
                Icons.chevron.draw(c, r.right - Ui.r(2.4f), cy - Ui.r(0.6f) - (if (sl != null) Ui.r(0.3f) else 0f), Ui.r(1.2f), Ui.alpha(ink, a), 2.2f)
                c.save(); c.scale(-1f, 1f, vr - vw - Ui.r(1.1f), cy)
                Icons.chevron.draw(c, vr - vw - Ui.r(1.7f), cy - Ui.r(0.6f) - (if (sl != null) Ui.r(0.3f) else 0f), Ui.r(1.2f), Ui.alpha(ink, a), 2.2f)
                c.restore()
            }
            if (sl != null) {   // the level, a thin track along the bottom of the chip
                val tx0 = r.left + Ui.r(1.5f); val tx1 = r.right - Ui.r(1.5f); val ty = r.bottom - Ui.r(0.75f)
                val f = if (sl.max > sl.min) (sl.value - sl.min).toFloat() / (sl.max - sl.min) else 0f
                fill.color = Ui.alpha(ink, 0.22f * a); c.drawRoundRect(tx0, ty - Ui.r(0.125f), tx1, ty + Ui.r(0.125f), Ui.r(0.125f), Ui.r(0.125f), fill)
                fill.color = Ui.alpha(ink, a); c.drawRoundRect(tx0, ty - Ui.r(0.125f), tx0 + (tx1 - tx0) * f, ty + Ui.r(0.125f), Ui.r(0.125f), Ui.r(0.125f), fill)
            }
            return
        }
        it.icon?.let { ic -> ic.draw(c, x, cy - Ui.r(0.8125f), Ui.r(1.625f), Ui.alpha(ink, a), 1.8f); x += Ui.r(2.25f) }
        chipP.color = Ui.alpha(ink, a)
        c.drawText(it.label, x, cy + chipP.textSize * 0.36f, chipP)
        if (it.marks && it.active()) Icons.check.draw(c, r.right - Ui.r(3.25f), cy - Ui.r(0.75f), Ui.r(1.5f), Ui.alpha(ink, a), 2.4f)
    }
}
