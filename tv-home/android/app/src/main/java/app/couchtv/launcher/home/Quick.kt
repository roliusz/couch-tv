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
    // the hamburger key again while it is open closes it, as Sony's gear does
    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); if (panel.isOpen) panel.close() else load() }
    private val ha by lazy { Ha(this) }
    fun load() {
        Sony.read(this) { s -> panel.show(s) }
        room()
    }
    /** The living room's lights and scenes from Home Assistant (as on the home). */
    fun room() { if (ha.on) ha.room { r -> panel.room(r, ha) } }
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

/**
 * Sony's own quick settings layout (recorded from this TV's gear panel): a black bar along the
 * bottom with "Quick Settings" above it, seven tiles across (icon, name, "Audio system" where it
 * belongs, the value in blue). The tile in the middle is the chosen one: raised, a shade lighter,
 * with its choices in a list below its name, the current one in a blue band. Left and right move
 * the tiles past the middle (round and round); up and down move through the choices; OK takes one.
 * Numbers change as they move.
 */
@SuppressLint("ViewConstructor")
class QuickPanel(private val act: QuickActivity) : View(act) {
    private class Tile(val title: String, val icon: Icon, val sub: String? = null) {
        var list: () -> List<String> = { emptyList() }   // the choices shown in the list
        var options: List<String>
            get() = list()
            set(v) { list = { v } }
        var at = 0                                  // the highlighted one
        var value: () -> String = { "" }            // what the tile says while not chosen
        var link = false                            // an arrow instead of a value: OK opens it
        var dim: () -> Boolean = { false }
        var take: (Int) -> Unit = {}                // OK on a choice (or on a link)
        var number: Sony.Slider? = null             // a number moved with up and down, sent as it moves
        var send: (String) -> Unit = {}
    }

    private var s: Sony.State? = null
    private var home: Ha.Room? = null
    private var ha: Ha? = null
    fun room(r: Ha.Room?, h: Ha) {
        if (r == null) return
        home = r; ha = h
        if (s != null) { build(); scr.snap(fi.toFloat()); invalidate() }
    }
    private val tiles = ArrayList<Tile>()
    private var first = true
    private var fi = 0
    private var msg = ""; private var msgA = 0f

    private var t = 0f
    private var closing = false
    private val open = Spring(0f, 0.001f) { t = it; invalidate(); if (closing && it <= 0.002f) act.finish() }
    private var scroll = 0f
    private val scr = Spring(0f) { scroll = it; invalidate() }
    private val msgFade = Spring(0f, 0.002f) { msgA = it; invalidate() }
    // the chosen tile's list: where it has rolled to (in rows), and how far it has faded in
    private var lpos = 0f
    private val lp = Spring(0f, 0.001f) { lpos = it; invalidate() }
    private var listA = 0f
    private val listFade = Spring(0f, 0.002f) { listA = it; invalidate() }
    private fun target(tl: Tile): Float = tl.number?.let { (it.max - it.value).toFloat() } ?: tl.at.toFloat()

    private val BAR = 0xFF141414.toInt(); private val COL = 0xFF1F1F1F.toInt()
    private val BLUE = 0xFF3D95F2.toInt(); private val BAND = 0xFF0A63D1.toInt()
    private val titleBig = Ui.paint(Ui.interBold, 2.25f).apply { setShadowLayer(Ui.r(0.5f), 0f, Ui.r(0.1f), 0x99000000.toInt()) }
    private val nameP = Ui.paint(Ui.inter, 1.5f).apply { textAlign = Paint.Align.CENTER }
    private val subP = Ui.paint(Ui.inter, 1.25f, 0xFFB0B0B0.toInt()).apply { textAlign = Paint.Align.CENTER }
    private val valP = Ui.paint(Ui.interMid, 1.5f, BLUE).apply { textAlign = Paint.Align.CENTER }
    private val optP = Ui.paint(Ui.interMid, 1.5f).apply { textAlign = Paint.Align.CENTER }
    private val msgP = Ui.paint(Ui.interMid, 1.375f).apply { textAlign = Paint.Align.RIGHT; setShadowLayer(Ui.r(0.5f), 0f, Ui.r(0.1f), 0x99000000.toInt()) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    // Sony's measures at 1920 × 1080 (px / 16 = rem)
    private val barH = Ui.r(16.5f); private val tileW = Ui.r(16.5f)
    private val lift = Ui.r(5f); private val rowH = Ui.r(3.5f)

    init { isFocusable = true; isFocusableInTouchMode = true; setLayerType(LAYER_TYPE_HARDWARE, null) }

    fun show(st: Sony.State) {
        s = st; build()
        if (closing) return
        if (open.t == 0f) open.to(1f, 0.42f)
        scr.snap(fi.toFloat()); tiles.getOrNull(fi)?.let { lp.snap(target(it)) }; listFade.to(1f, 0.4f); requestFocus(); invalidate()
    }
    val isOpen get() = open.t > 0f && !closing
    fun close() {
        if (closing) return
        closing = true
        open.to(0f, 0.3f)
        if (tiles.isEmpty() || Motion.calm) act.finish()
    }
    private fun say(m: String) { msg = m; msgFade.to(1f, 0.25f); removeCallbacks(unsay); postDelayed(unsay, 4500) }
    private val unsay = Runnable { msgFade.to(0f, 0.4f) }

    private val modeNames = mapOf("vivid" to "Vivid", "standard" to "Standard", "cinema" to "Cinema", "custom" to "Custom",
        "dolbyVisionBright" to "Dolby Vision Bright", "dolbyVisionDark" to "Dolby Vision Dark", "calibrated" to "Calibrated",
        "game" to "Game", "graphics" to "Graphics", "photo" to "Photo", "netflix" to "Netflix Adaptive", "ipm" to "IMAX Enhanced")

    private fun build() {
        val st = s ?: return
        val ctx = act
        val was = tiles.getOrNull(fi)?.title
        val wasAt = tiles.getOrNull(fi)?.at ?: 0
        tiles.clear()
        fun needKey(): Boolean { if (!st.hasKey) { say(L.t("qp_need_key")); return true }; return false }
        val AS = L.t("qp_audio_system")
        fun num(sl: Sony.Slider) = if (sl.value >= sl.max && sl.max > 0) L.t("qp_max") else sl.value.toString()

        // the living room: one Lights tile with each light in its list (OK switches it on or
        // off, the panel stays), one Scenes tile with each scene (OK runs it)
        home?.let { r ->
            fun short(n: String) = n.removePrefix(r.label).trim().ifEmpty { n }
            if (r.lights.isNotEmpty()) tiles.add(Tile(L.t("hc_lights"), Icons.bulb, r.label).apply {
                list = { r.lights.map { l -> short(l.name) + " · " + (if (!l.available) "—" else if (l.on) L.t("qp_on") else L.t("qp_off")) } }
                value = { val n = r.lights.count { it.on }; if (n > 0) L.t("hc_lit", "n" to n, "m" to r.lights.size) else L.t("hc_off") }
                take = { k ->
                    val l = r.lights[k]
                    if (l.available) { l.on = !l.on; invalidate(); ha?.light(l.id, l.on, null) { act.room() } }
                }
            })
            if (r.scenes.isNotEmpty()) tiles.add(Tile(L.t("qp_scenes"), Icons.qsScene, r.label).apply {
                options = r.scenes.map { it.second }
                link = true
                take = { k -> val (id, name) = r.scenes[k]; ha?.run(id) { ok -> say(if (ok) name else L.t("qp_failed")) } }
            })
        }
        // audio output
        val outs = listOf("speaker" to L.t("qp_tv_speakers"), "audioSystem" to AS)
        tiles.add(Tile(L.t("qp_audio_output"), Icons.qsOutput).apply {
            options = outs.map { it.second }
            value = { outs.firstOrNull { it.first == st.output }?.second ?: "" }
            dim = { !st.hasKey }
            take = { k -> val v = outs[k].first
                if (!needKey() && st.output != v) Sony.setOutput(ctx, v) { ok -> if (ok) { st.output = v; invalidate() } else say(L.t("qp_failed")) } }
        })
        // inputs
        tiles.add(Tile(L.t("qp_inputs"), Icons.input).apply {
            options = st.inputs.map { it.title } + L.t("qp_all_inputs")
            link = true
            take = { k ->
                val inp = st.inputs.getOrNull(k)
                if (inp != null) Sony.input(ctx, inp) { ok -> if (ok) act.finish() else say(L.t("qp_failed")) }
                else { try { ctx.startActivity(Intent("com.android.tv.action.VIEW_INPUTS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}; act.finish() }
            }
        })
        // the soundbar
        fun level(sl: Sony.Slider?, name: String) = sl?.let { v -> tiles.add(Tile(name, Icons.qsVolume, AS).apply {
            number = v; value = { num(v) }
            send = { x -> Sony.bar(v.target, x) { ok -> if (!ok) say(L.t("qp_no_bar")) } }
        }) }
        level(st.sub, L.t("qp_sub_vol"))
        level(st.rear, L.t("qp_rear_vol"))
        fun onOff(name: String, icon: Icon, target: String, get: () -> Boolean?, set: (Boolean) -> Unit) = tiles.add(Tile(name, icon, AS).apply {
            options = listOf(L.t("qp_on"), L.t("qp_off"))
            value = { when (get()) { true -> L.t("qp_on"); false -> L.t("qp_off"); else -> "" } }
            dim = { get() == null }
            take = { k ->
                val now = get(); val want = k == 0
                if (now == null) say(L.t("qp_no_bar"))
                else if (now != want) { set(want); invalidate(); Sony.bar(target, if (want) "on" else "off") { ok -> if (!ok) { set(now); invalidate(); say(L.t("qp_no_bar")) } } }
            }
        })
        onOff(L.t("qp_night_mode"), Icons.qsNight, "nightMode", { st.night }, { st.night = it })
        onOff(L.t("qp_voice_mode"), Icons.qsVoice, "voice", { st.voice }, { st.voice = it })
        // picture
        if (st.pictureModes.isNotEmpty()) tiles.add(Tile(L.t("qp_picture_mode"), Icons.picture).apply {
            val names = st.pictureModes.map { modeNames[it] ?: it.replaceFirstChar { c -> c.uppercase() } }
            options = names
            value = { names.getOrNull(st.pictureModes.indexOf(st.pictureMode)) ?: "" }
            dim = { !st.hasKey }
            take = { k -> val v = st.pictureModes[k]
                if (!needKey()) Sony.setPicture(ctx, "pictureMode", v) { ok -> if (ok) { st.pictureMode = v; invalidate() } else say(L.t("qp_failed")) } }
        })
        st.brightness?.let { b -> tiles.add(Tile(L.t("qp_brightness"), Icons.sun).apply {
            number = b; value = { b.value.toString() }; dim = { !st.hasKey }
            send = { x -> Sony.setPicture(ctx, "brightness", x) { ok -> if (!ok) say(L.t(if (st.hasKey) "qp_failed" else "qp_need_key")) } }
        }) }
        tiles.add(Tile(L.t("qp_picture_off"), Icons.screen).apply {
            link = true; dim = { !st.hasKey }
            take = { if (!needKey()) Sony.pictureOff(ctx) { ok -> if (ok) act.finish() else say(L.t("qp_failed")) } }
        })
        // sleep timer, settings
        val mins = listOf(0, 15, 30, 60, 90)
        tiles.add(Tile(L.t("qp_sleep"), Icons.moon).apply {
            options = mins.map { if (it == 0) L.t("qp_off") else L.t("qp_min", "n" to it) }
            value = { val m = Sleep.chosen(ctx); if (m == 0) L.t("qp_off") else L.t("qp_left", "n" to Sleep.left(ctx)) }
            take = { k -> val m = mins[k]
                if (m == 0 || !needKey()) { Sleep.set(ctx, m); invalidate(); if (m > 0) say(L.t("qp_left", "n" to m)) } }
        })
        tiles.add(Tile(L.t("qp_settings"), Icons.gear).apply {
            link = true
            take = { try { ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}; act.finish() }
        })
        fi = tiles.indexOfFirst { it.title == was }.takeIf { it >= 0 } ?: 0
        if (was == null || (home != null && first)) { fi = 0; first = false }
        tiles.forEach { pickCurrent(it) }
        // a fresh reading (a light just switched) keeps the highlight where it was
        tiles.getOrNull(fi)?.let { if (it.title == was) it.at = wasAt.coerceIn(0, maxOf(0, it.options.size - 1)) }
    }
    /** The list starts on what the tile is set to now. */
    private fun pickCurrent(tl: Tile) {
        if (tl.options.isEmpty()) return
        val v = tl.value(); tl.at = tl.options.indexOf(v).coerceAtLeast(0)
        if (tl.title == L.t("qp_sleep")) tl.at = listOf(0, 15, 30, 60, 90).indexOf(Sleep.chosen(act)).coerceAtLeast(0)
    }

    private var sendRun: Runnable? = null
    fun key(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return e.keyCode in NAV
        if (tiles.isEmpty() || closing) return e.keyCode in NAV
        val tl = tiles[fi]
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> close()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val dx = if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                fi = (fi + dx + tiles.size) % tiles.size
                pickCurrent(tiles[fi])
                lp.snap(target(tiles[fi])); listFade.snap(0f); listFade.to(1f, 0.45f)
                scr.to(scr.t + dx, 0.38f)   // the strip moves on one tile per press, however quick; round and round
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val up = e.keyCode == KeyEvent.KEYCODE_DPAD_UP
                val n = tl.number
                if (n != null) {
                    if (tl.dim()) { say(L.t("qp_need_key")); return true }
                    n.value = (n.value + if (up) 1 else -1).coerceIn(n.min, n.max); lp.to(target(tl), 0.3f); invalidate()
                    sendRun?.let { removeCallbacks(it) }
                    sendRun = Runnable { tl.send(n.value.toString()) }.also { postDelayed(it, 400) }   // several presses send one change
                } else if (tl.options.isNotEmpty()) {
                    tl.at = (tl.at + if (up) -1 else 1).coerceIn(0, tl.options.size - 1); lp.to(target(tl), 0.3f); invalidate()
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (tl.number != null) return true
                tl.take(tl.at)
            }
            else -> return false
        }
        return true
    }
    private val NAV = setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A)

    override fun onDraw(c: Canvas) {
        if (tiles.isEmpty()) return
        val w = width.toFloat(); val h = height.toFloat()
        val dy = (1f - t) * (barH + lift + Ui.r(4f))
        c.save(); c.translate(0f, dy)
        val top = h - barH
        fill.color = BAR; c.drawRect(0f, top, w, h, fill)
        c.drawText(L.t("qp_title"), Ui.r(4.5f), top - Ui.r(2.5625f), titleBig)
        if (msgA > 0f) { msgP.color = Ui.alpha(Color.WHITE, msgA); c.drawText(Ui.fit(msgP, msg, w * 0.55f), w - Ui.r(4.5f), top - Ui.r(2.75f), msgP) }

        // the strip: tile k sits (k - scroll) tiles from the middle, wrapped round
        val n = tiles.size
        val cx0 = w / 2
        val half = (w / 2 / tileW).toInt() + 2
        for (off in -half..half) {
            val pos = Math.floor(scroll.toDouble()).toInt() + off
            val k = ((pos % n) + n) % n
            val x = cx0 + (pos - scroll) * tileW
            if (x < -tileW || x > w + tileW) continue
            val near = 1f - Math.min(1f, Math.abs(pos - scroll))
            drawTile(c, tiles[k], x, top, k == fi && pos == Math.round(scr.t), near)
        }
        c.restore()
    }

    private fun ease(x: Float) = x * x * (3 - 2 * x)

    private fun drawTile(c: Canvas, tl: Tile, cx: Float, top: Float, chosen: Boolean, near: Float) {
        val h = height.toFloat()
        val f = ease(near.coerceIn(0f, 1f))
        // the raised, lighter column glides with the strip from tile to tile
        val y0 = top - lift * f
        if (f > 0.001f) { fill.color = Ui.mix(BAR, COL, f); c.drawRect(cx - tileW / 2, y0, cx + tileW / 2, h, fill) }
        val a = if (tl.dim()) 0.45f else 1f
        tl.icon.draw(c, cx - Ui.r(1.5f), y0 + Ui.r(1.375f), Ui.r(3f), Ui.alpha(Color.WHITE, a), 1.9f)
        nameP.color = Ui.alpha(Color.WHITE, a)
        c.drawText(Ui.fit(nameP, tl.title, tileW - Ui.r(1.5f)), cx, y0 + Ui.r(6.875f), nameP)
        tl.sub?.let { subP.alpha = (176 * a).toInt(); c.drawText(it, cx, y0 + Ui.r(9.3f), subP) }

        // the value fades out as the chosen tile's list fades in
        val show = if (chosen) listA * f else 0f
        if (show < 1f) {
            val va = a * (1f - show)
            if (tl.link) Icons.qsLink.draw(c, cx - Ui.r(0.875f), top + Ui.r(11.2f) - lift * f, Ui.r(1.75f), Ui.alpha(BLUE, va))
            else { valP.color = Ui.alpha(BLUE, va); c.drawText(Ui.fit(valP, tl.value(), tileW - Ui.r(1.5f)), cx, top + Ui.r(12.4f), valP) }
        }
        if (show <= 0.001f) return
        val hasList = tl.number != null || tl.options.isNotEmpty() || !tl.link
        if (!hasList) return
        // the list: a still blue band in the middle, the choices rolling through it (numbers: the
        // next one up above, as Sony); each one whiter the closer it is to the band
        val listTop = y0 + Ui.r(11f)
        val mid = listTop + rowH * 1.5f
        c.save(); c.clipRect(cx - tileW / 2, listTop, cx + tileW / 2, h)
        fill.color = Ui.alpha(BAND, show); c.drawRect(cx - tileW / 2, mid - rowH / 2, cx + tileW / 2, mid + rowH / 2, fill)
        fun row(label: String, at: Float) {
            val d = at - lpos
            if (Math.abs(d) > 2.6f) return
            val close = (1f - Math.min(1f, Math.abs(d))).coerceIn(0f, 1f)
            optP.color = Ui.alpha(Ui.mix(BLUE, Color.WHITE, close), a * show)
            c.drawText(Ui.fit(optP, label, tileW - Ui.r(1.5f)), cx, mid + d * rowH + optP.textSize * 0.36f, optP)
        }
        val nb = tl.number
        if (nb != null) {
            val lo = maxOf(nb.min, nb.value - 4); val hi = minOf(nb.max, nb.value + 4)
            for (v in lo..hi) row(if (v >= nb.max && nb.max > 0) L.t("qp_max") else v.toString(), (nb.max - v).toFloat())
        } else if (tl.options.isNotEmpty()) {
            for ((k, o) in tl.options.withIndex()) row(o, k.toFloat())
        } else {
            optP.color = Ui.alpha(Color.WHITE, a * show)
            c.drawText(Ui.fit(optP, tl.value(), tileW - Ui.r(1.5f)), cx, mid + optP.textSize * 0.36f, optP)
        }
        c.restore()
    }
}
