package app.couchtv.launcher.home

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.view.PixelCopy
import android.view.Choreographer
import android.widget.FrameLayout
import app.couchtv.launcher.Trakt
import app.couchtv.launcher.WatchLog
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * The TV home, native (a port of tv-home/web: app.js with its CSS). Same scenes, cards, dock,
 * sidebar and settings, kept in the same places, so the web home and this one share everything.
 */
class HomeActivity : Activity() {
    val main = Handler(Looper.getMainLooper())
    val io = Executors.newFixedThreadPool(2)
    lateinit var store: Store
    lateinit var apps: Apps
    lateinit var shows: Shows
    lateinit var ha: Ha

    lateinit var root: ShadowFrame
    lateinit var scene: SceneView
    lateinit var topShelf: TopShelf
    lateinit var date: DateLine
    lateinit var tab: SideTab
    lateinit var shelf: Shelf
    lateinit var dock: Dock
    lateinit var panel: HomePanel
    lateinit var side: Sidebar
    lateinit var np: NowPlayingCard
    lateinit var npWide: NowBar
    lateinit var toast: ToastView
    lateinit var veil: View
    var settings: SettingsScreen? = null

    // who is watching
    var people = mutableListOf<Store.Person>()
    var who = ""
    val nobody get() = people.isEmpty()
    var weather: Weather.Now? = null
    var room: Ha.Room? = null
    var playing: Ha.Playing? = null

    // focus: grid (the dock), shelf, tab, np, side, hpanel, settings
    var zone = "grid"
    private var scenes = listOf<Scene>()
    private var bag = ArrayList<Int>()
    private var sceneAt = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        Motion.calm = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        intent?.let { app.couchtv.launcher.storeExtras(this, it) }   // settings from apply.sh
        Ui.init(this)
        app.couchtv.launcher.Doorbell.start(this)   // the door camera over whatever is on, when someone rings
        app.couchtv.launcher.NowOverlay.start(this)   // the music card over ambient mode
        store = Store(this).also { it.load() }
        L.load(this); L.set(store.st.optString("lang").ifEmpty { store.home.optString("language", "en") })
        apps = Apps(this, store)
        shows = Shows(this, store, apps)
        ha = Ha(this)
        loadPeople()

        root = ShadowFrame(this).apply { setBackgroundColor(Color.BLACK) }
        scene = SceneView(this)
        topShelf = TopShelf(this, this)
        date = DateLine(this, this)
        tab = SideTab(this, this)
        shelf = Shelf(this, this)
        dock = Dock(this, this)
        panel = HomePanel(this, this)
        side = Sidebar(this, this)
        np = NowPlayingCard(this, this)
        npWide = NowBar(this, this).apply { visibility = View.GONE }
        toast = ToastView(this).also { it.home = this }
        veil = View(this).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        val mp = FrameLayout.LayoutParams.MATCH_PARENT
        root.addView(scene, mp, mp)
        scene.setLayerType(View.LAYER_TYPE_HARDWARE, null)   // drawn once, reused while nothing in it changes
        root.addView(topShelf, mp, mp)
        root.addView(date, mp, mp)
        root.addView(shelf, mp, mp)
        root.addView(dock, mp, mp)
        root.addView(tab, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.ri(5f); topMargin = Ui.ri(3.75f) })
        root.addView(np, FrameLayout.LayoutParams(-2, -2, Gravity.END).apply { rightMargin = Ui.ri(5f); topMargin = Ui.ri(3.75f) })
        root.addView(npWide, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = Ui.ri(5f); rightMargin = Ui.ri(5f); bottomMargin = Ui.ri(2.875f) })
        root.addView(panel, mp, mp)
        root.addView(side, mp, mp)
        root.addView(toast, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL).apply { topMargin = Ui.ri(3.75f) })
        root.addView(veil, mp, mp)
        setContentView(root)
        // over a light background the white glass gets a deeper shadow, so its shape still shows
        root.shadow(tab, { tab.height / 2f }, { if (onLight(tab)) 20f else 14f }, { if (onLight(tab)) 6f else 8f }, { if (onLight(tab)) 0x40000000 else 0x1F000000 })
        root.shadow(np, { Ui.r(2.25f) }, { if (onLight(np)) 24f else 14f }, { if (onLight(np)) 8f else 8f }, { if (onLight(np)) 0x40000000 else 0x1F000000 })
        root.shadow(npWide, { Ui.r(2.75f) }, { 14f }, { 8f }, { 0x1F000000 })
        root.shadow(toast, { Ui.r(2f) }, { Ui.r(1.5f) }, { Ui.r(0.375f) }, { if (onLight(toast)) 0x40000000 else 0x26000000 })
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        Glass.listeners.add { dock.invalidateGlass(); shelf.invalidateGlass(); tab.invalidate(); np.invalidate(); toast.invalidate() }
        scene.onShown = { s -> date.scene = s }
        scene.onChange = { glassDirty() }
        buildScenes()
        tick()
        refreshWeather()
        refresh()
        askRoom()
        askPlaying()
        Trakt.onChange = { main.post { refresh(); settings?.traktChanged() } }
        WatchLog.onChange = { main.post { refresh() } }
        if (!WatchLog.connected) try { WatchLog.start(this) } catch (e: Exception) {}
        idleSoon()
        // adb: am start ... --ez rest true, to look at the resting home without waiting (as ?scene= does for scenes)
        // adb: --ez npdemo true, a sample song to look at the music card and bar with no speaker playing
        if (intent?.getBooleanExtra("npdemo", false) == true) main.postDelayed({
            playing = Ha.Playing("playing", "Golden Hour Drive", "The Paper Lanterns", null, 214.0, 64.0, "Living room speaker", "", System.currentTimeMillis())
            np.update(); np.requestLayout(); wideUpdate() }, 1500)
        if (intent?.getBooleanExtra("rest", false) == true) { main.removeCallbacks(goIdle); main.postDelayed(goIdle, 4000) }
    }

    private var away = false
    override fun onStop() {
        super.onStop(); away = true
        // while away the dock and the cards wait below the screen (and the corners above it), so
        // coming back they rise in over the scene instead of appearing on an empty screen
        listOf(dock to Ui.r(16f), shelf to Ui.r(34f), tab to -Ui.r(12f), np to -Ui.r(26f)).forEach { (v, y) -> v.animate().cancel(); v.translationY = y }
    }
    override fun onResume() {
        super.onResume()
        // back on the home (from an app, or Home pressed anywhere): the home rises into place, and
        // the glass is made again from what is behind it now (the scene may have changed meanwhile)
        endRest()
        if (veil.alpha > 0f || away) {
            veil.animate().cancel(); veil.alpha = 0f
            // once the scene is on screen (two frames), then the rise
            Choreographer.getInstance().postFrameCallback { Choreographer.getInstance().postFrameCallback { comeBack() } }
        }
        away = false
        glassDirty(); main.postDelayed({ glassDirty() }, 400); main.postDelayed({ glassDirty() }, 1800)
        Trakt.onChange = { main.post { refresh(); settings?.traktChanged() } }
        WatchLog.onChange = { main.post { refresh() } }
        refresh(); refreshWeather(); askRoom()
    }
    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        endRest()
        if (i != null && (i.hasExtra("config_b64") || i.hasExtra("ha_b64") || i.hasExtra("trakt_b64"))) { app.couchtv.launcher.storeExtras(this, i); restart() }
        // Home pressed while the home is in front: back to the start
        if (zone == "side") side.close(false)
        if (zone == "hpanel") panel.close()
        settings?.let { if (zone == "settings") it.close() }
        goHome(false)
    }

    // ------------------------------------------------------------------ people
    fun loadPeople() {
        people = store.people()
        who = store.who
        if (people.none { it.id == who }) who = people.firstOrNull()?.id ?: "both"
    }
    fun pickWho(id: String) {
        if (id == who) return
        who = id; store.who = id
        shelf.first = 0; shelf.s = 0
        refresh(); tab.invalidate(); tab.requestLayout()
    }
    fun person() = people.firstOrNull { it.id == who }

    // ------------------------------------------------------------------ scenes and the clock
    fun buildScenes() {
        val all = Scenes.builtIn + Scenes.mine(this) + Scenes.own(this, store.st)
        val (order, off) = Scenes.state(all, store.st, store.home)
        scenes = order.filter { it !in off }.mapNotNull { id -> all.firstOrNull { it.id == id } }.ifEmpty { listOf(Scenes.builtIn[0]) }
        bag.clear()
        sceneAt = (Math.random() * scenes.size).toInt()
        // adb: am start ... --es scene water, to look at one scene (as the web home's ?scene=)
        intent?.getStringExtra("scene")?.let { id -> scenes.indexOfFirst { it.id == id }.let { if (it >= 0) sceneAt = it } }
        scene.show(scenes[sceneAt])
        main.removeCallbacks(nextScene)
        if (scenes.size > 1) main.postDelayed(nextScene, store.sceneMs)
    }
    private val nextScene = object : Runnable {
        override fun run() {
            if (zone != "settings" && scenes.size > 1) {
                if (bag.isEmpty()) { bag.addAll(scenes.indices.filter { it != sceneAt }); bag.shuffle() }
                sceneAt = bag.removeAt(0)
                scene.show(scenes[sceneAt])
            }
            main.postDelayed(this, store.sceneMs)
        }
    }
    private fun tick() {
        val c = Calendar.getInstance()
        scene.time = Clock.hm(c, store.clock24)
        val h = c.get(Calendar.HOUR_OF_DAY)
        scene.night = h >= 21 || h < 6
        date.refresh()
        side.clockChanged()
        main.postDelayed(::tick, 60000L - System.currentTimeMillis() % 60000L + 30L)   // just after the minute turns
    }
    fun refreshWeather() {
        if (!store.wxShow) { weather = null; date.refresh(); shelf.render(); return }
        Weather.fetch(store.place) { w -> if (w != null) { weather = w; date.refresh(); shelf.homeChanged(); panel.redraw() } }
        main.removeCallbacks(wxAgain); main.postDelayed(wxAgain, store.wxEvery * 60000L)
    }
    private val wxAgain = Runnable { refreshWeather() }
    fun tempText(c: Int) = if (store.wxF) "${Math.round(c * 9 / 5.0 + 32)}°F" else "$c°"

    // ------------------------------------------------------------------ Home Assistant
    fun askRoom() {
        ha.room { r -> room = r; shelf.homeChanged(); panel.redraw() }
        main.removeCallbacks(roomAgain); main.postDelayed(roomAgain, 60000)
    }
    private val roomAgain = Runnable { askRoom() }
    fun askPlaying() {
        ha.playing { p -> val was = np.on; playing = p; np.update(); if (!np.on && zone == "np") np.leave(); if (was != np.on) np.requestLayout(); wideUpdate() }
        main.removeCallbacks(playAgain); main.postDelayed(playAgain, if (hasWindowFocus()) 5000 else 30000)
    }
    private val playAgain = Runnable { if (intent?.getBooleanExtra("npdemo", false) != true) askPlaying() }

    // ------------------------------------------------------------------ data
    fun refresh() {
        if (zone == "settings" || dock.lift != null) return
        io.execute {
            apps.load()
            val list = shows.forPerson(who, nobody)
            val prem = shows.premieres(who)
            // the icons and the artwork, before anything is drawn
            (apps.all.map { it.pkg }).forEach { apps.icon(it) }
            val arts = HashMap<String, Bitmap?>()
            (list + prem).mapNotNull { it.art }.distinct().forEach { u -> arts[u] = Net.bitmap(this, u, 640) }
            main.post {
                shelf.setData(list, prem, arts)
                dock.render()
                if (!touched) startFocus()
                paint()
            }
        }
    }
    var touched = false
    fun startFocus() { shelf.s = 0; dock.g = 0; zone = "grid" }

    // ------------------------------------------------------------------ focus
    fun paint() {
        topShelf.focus(if (zone == "shelf" && !apps.expanded && !idle) shelf.focused()?.show else null)
        shelf.paint(zone == "shelf" || zone == "hpanel")
        dock.paint(zone == "grid")
        tab.focusOn(zone == "tab")
        np.paint()
    }
    fun move(dx: Int, dy: Int) {
        touched = true
        when (zone) {
            "side" -> { side.move(dx, dy); return }
            "settings" -> { settings?.move(dx, dy); return }
            "hpanel" -> { panel.move(dx, dy); return }
            "tab" -> { if (dy > 0) fromTab() else if (dx > 0 && np.on) np.enter(tabFrom); return }
            "np" -> { np.move(dx, dy); return }
        }
        if (dock.lift != null) { dock.moveLift(dx, dy); return }
        if (zone == "shelf") {
            if (dx < 0 && shelf.s == 0) { shelf.bump(-1); return }
            if (dy < 0) { upTop(shelf.focusedX()); return }
            if (dx != 0) { if (!shelf.step(dx)) shelf.bump(dx) }
            else if (dy > 0 && dock.count() > 0) { dock.g = dock.nearest(shelf.focusedX()); zone = "grid" }
        } else {
            val r = dock.move(dx, dy)
            if (r == -1) { if (shelf.count() > 0 && !apps.expanded) zone = "shelf" else { upTop(dock.focusedX()); return } }
        }
        paint()
    }
    var tabFrom = "grid"
    private fun upTop(x: Float) {
        if (np.on && x > Ui.width / 2f) { np.enter(zone); return }
        tabFrom = zone; zone = "tab"; paint()
    }
    fun fromTab() { zone = if (tabFrom == "tab" || tabFrom == "np") "grid" else tabFrom; paint() }

    fun activate() {
        touched = true
        when (zone) {
            "side" -> { side.activate(); return }
            "settings" -> { settings?.activate(); return }
            "hpanel" -> { panel.ok(); return }
            "tab" -> { side.open(); return }
            "np" -> { np.act(); return }
        }
        if (dock.lift != null) { dock.drop(); return }
        if (zone == "shelf") {
            val c = shelf.focused() ?: return
            val s = c.show
            if (s.home != null) { panel.open(s.home); return }
            if (s.premiere && s.app == null) { toast.show(L.t("prem_on", "title" to s.title, "day" to shows.premiereDay(s.firstAired))); return }
            val app = s.app ?: return
            launch(c) { if (!apps.launch(app.pkg)) abortLaunch() }
            return
        }
        val t = dock.focused() ?: return
        val a = t.item
        if (a.kind == "more") { apps.expanded = true; dock.render(grow = true); paint(); return }
        apps.opened(a.pkg); t.isNew = false
        launch(t) { if (!apps.launch(a.pkg)) { abortLaunch(); toast.show(L.t("store_none")) } }
    }
    /** OK held: pick an app up to move it, or a show's menu in the sidebar. */
    fun hold(): Boolean {
        touched = true
        when (zone) {
            "settings" -> return settings?.hold() ?: false
            "side", "tab", "np", "hpanel" -> return false
        }
        if (dock.lift != null) { dock.drop(); return true }
        if (zone == "grid") return dock.hold()
        if (zone == "shelf") {
            val c = shelf.focused() ?: return false
            if (c.show.home != null) return false
            side.openShow(c.show); return true
        }
        return false
    }
    fun goHome(back: Boolean) {
        when (zone) {
            "side" -> { side.back(); return }
            "tab" -> { fromTab(); return }
            "np" -> { np.leave(); return }
            "settings" -> { settings?.back(); return }
            "hpanel" -> { panel.close(); return }
        }
        if (dock.lift != null) { dock.drop(); return }
        if (apps.expanded) { apps.expanded = false; dock.render(grow = true); startFocus(); paint(); return }
        val atStart = zone == "grid" && dock.g == 0
        if (back && atStart) { side.open(); return }
        startFocus(); paint()
    }

    // ------------------------------------------------------------------ launching: pressed, then a fade to black
    private var launching = false
    fun launch(t: Tile, go: () -> Unit) {
        if (launching) return
        launching = true
        t.press(true)
        veil.animate().alpha(1f).setStartDelay(90).setDuration(380).setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 0.6f, 1f)).start()
        main.postDelayed({ go(); t.press(false) }, 50)
        main.postDelayed({ if (launching && hasWindowFocus()) abortLaunch() }, 3500)
    }
    fun abortLaunch() { comeBack() }
    private fun comeBack() {
        launching = false
        veil.animate().cancel(); veil.alpha = 0f
        if (Motion.calm) return
        // the dock and the cards slide up into place from below the screen, the person tab and
        // Now Playing drop in from the top (the resting distances, idle.css), quickly
        val ease = android.view.animation.PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
        listOf(Triple(dock, Ui.r(16f), 0L), Triple(shelf, Ui.r(34f), 40L), Triple(tab, -Ui.r(12f), 80L), Triple(np, -Ui.r(26f), 80L)).forEach { (v, from, d) ->
            v.animate().cancel(); v.alpha = 1f; if (v.translationY == 0f) v.translationY = from
            v.animate().translationY(0f).setStartDelay(d).setDuration(560).setInterpolator(ease).setUpdateListener { root.invalidate() }.start()
        }
    }

    // ------------------------------------------------------------------ resting (OLED): the dock and cards slide away
    var idle = false; private set
    private val goIdle = Runnable {
        if (zone !in listOf("grid", "shelf", "tab", "np") || apps.expanded || dock.lift != null) { idleSoon(); return@Runnable }
        idle = true
        topShelf.off()
        val out = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
        dock.animate().translationY(Ui.r(16f)).setDuration(900).setInterpolator(out).setUpdateListener { root.invalidate() }.start()
        shelf.animate().translationY(Ui.r(34f)).setDuration(900).setInterpolator(out).setUpdateListener { root.invalidate() }.start()
        tab.animate().translationY(-Ui.r(12f)).setDuration(900).setInterpolator(out).setUpdateListener { root.invalidate() }.start()
        np.animate().translationY(-Ui.r(26f)).setDuration(900).setInterpolator(out).setUpdateListener { root.invalidate() }.start()
        val a = android.animation.ValueAnimator.ofFloat(0f, 1f).setDuration(2000)
        a.addUpdateListener { scene.resting = it.animatedValue as Float }; a.start()
        drift()
        // the music card, once it has left the top, comes back along the bottom in one row
        main.postDelayed({ if (idle && playing != null) { wideUpdate(); npWide.visibility = View.VISIBLE; npWide.translationY = Ui.r(14f)
            npWide.animate().translationY(0f).setDuration(900).setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)).setUpdateListener { root.invalidate() }.start() } }, 950)
    }
    /** The resting bar shows what Now Playing shows. */
    private fun wideUpdate() {
        val p = playing
        if (p == null) { if (npWide.visibility == View.VISIBLE) npWide.visibility = View.GONE; return }
        // music starting while resting: the bar comes up along the bottom
        if (idle && npWide.visibility != View.VISIBLE) { npWide.visibility = View.VISIBLE; npWide.translationY = Ui.r(14f)
            npWide.animate().translationY(0f).setDuration(900).setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)).setUpdateListener { root.invalidate() }.start() }
        npWide.speaker = p.speaker; npWide.title = p.title; npWide.artist = p.artist
        npWide.pos = p.position; npWide.dur = p.duration; npWide.playing = p.state == "playing"
        if (p.picture != null) io.execute { val b = Net.bitmap(this, p.picture, 256); main.post { npWide.art = b } }
        npWide.invalidate()
    }
    /** While resting, the scene and the date drift a few pixels, very slowly (idle.css, 240 s). */
    private var driftAnim: android.animation.ValueAnimator? = null
    private fun drift() {
        driftAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).setDuration(240000).apply {
            repeatMode = android.animation.ValueAnimator.REVERSE; repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.PathInterpolator(0.42f, 0f, 0.58f, 1f)
            addUpdateListener { v -> val t = v.animatedValue as Float
                // 0 → middle (−14, 8, 1.02) → end (14, −8, 1.02), as the keyframes
                val x = if (t < 0.5f) -14f * (t * 2) else -14f + 28f * ((t - 0.5f) * 2)
                val y = if (t < 0.5f) 8f * (t * 2) else 8f - 16f * ((t - 0.5f) * 2)
                val s = 1f + 0.02f * minOf(1f, t * 2)
                scene.translationX = x; scene.translationY = y; scene.scaleX = s; scene.scaleY = s
                date.translationX = x; date.translationY = y; glassDirty() }
            start()
        }
    }
    fun idleSoon() { main.removeCallbacks(goIdle); main.postDelayed(goIdle, (store.idleMinutes * 60000).toLong()) }
    /** Standby ends at once, with no animation (coming back to the home does its own). */
    private fun endRest() {
        if (!idle) return
        idle = false
        main.removeCallbacks(goIdle)
        driftAnim?.cancel()
        npWide.animate().cancel(); npWide.visibility = View.GONE
        listOf(scene, date).forEach { it.animate().cancel(); it.translationX = 0f; it.translationY = 0f; it.scaleX = 1f; it.scaleY = 1f }
        scene.resting = 0f
        np.update()
        idleSoon()
    }
    private fun wake(): Boolean {
        if (!idle) { idleSoon(); return false }
        idle = false
        listOf(shelf, dock, tab, np).forEach { it.animate().translationY(0f).alpha(1f).setDuration(900).setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)).setUpdateListener { root.invalidate() }.start() }
        driftAnim?.cancel()
        if (npWide.visibility == View.VISIBLE) npWide.animate().translationY(Ui.r(14f)).setDuration(420).setUpdateListener { root.invalidate() }.withEndAction { npWide.visibility = View.GONE }.start()
        listOf(scene, date).forEach { it.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(900).setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)).start() }
        val a = android.animation.ValueAnimator.ofFloat(scene.resting, 0f).setDuration(900)
        a.addUpdateListener { scene.resting = it.animatedValue as Float }; a.start()
        idleSoon(); paint()
        return true
    }

    // ------------------------------------------------------------------ keys: OK acts on release, so a hold can pick up
    private var okDown = false
    private var okHeld = false
    private var wakeKey = -1
    private val holdRun = Runnable { okHeld = true; if (!hold()) activate() }
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val k = e.keyCode
        if (settings?.typing == true && zone == "settings") return super.dispatchKeyEvent(e)
        if (e.action == KeyEvent.ACTION_DOWN && idle) { if (e.repeatCount == 0) { wakeKey = k; wake() }; return true }
        if (e.action == KeyEvent.ACTION_UP && wakeKey == k) { wakeKey = -1; return true }
        if (e.action == KeyEvent.ACTION_DOWN) idleSoon()
        val ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A || k == KeyEvent.KEYCODE_BUTTON_SELECT
        val back = k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE || k == KeyEvent.KEYCODE_BUTTON_B
        if (ok) {
            if (e.action == KeyEvent.ACTION_DOWN) {
                if (e.repeatCount > 0 || okDown) return true
                okDown = true; okHeld = false
                pressFocused(true)
                main.postDelayed(holdRun, 600)
            } else if (e.action == KeyEvent.ACTION_UP && okDown) {
                main.removeCallbacks(holdRun); pressFocused(false)
                val held = okHeld; okDown = false; okHeld = false
                if (!held) activate()
            }
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return if (back || k in DIRS) true else super.dispatchKeyEvent(e)
        when (k) {
            KeyEvent.KEYCODE_DPAD_LEFT -> move(-1, 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> move(1, 0)
            KeyEvent.KEYCODE_DPAD_UP -> move(0, -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(0, 1)
            KeyEvent.KEYCODE_MENU -> if (e.repeatCount == 0) hold()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (np.on) np.act("play") else return super.dispatchKeyEvent(e)
            else -> if (back) goHome(true) else return super.dispatchKeyEvent(e)
        }
        return true
    }
    private val DIRS = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
    private fun pressFocused(on: Boolean) {
        val t: Tile? = when (zone) { "grid" -> dock.focused(); "shelf" -> shelf.focused(); else -> null }
        t?.press(on)
    }

    override fun onBackPressed() { goHome(true) }

    /**
     * Over a near-white background the words on the glass turn dark (adapt.js): the lightness of
     * the picture behind a piece, with a gap between the two thresholds so it never flickers.
     */
    private val lightNow = HashMap<View, Boolean>()
    fun onLight(v: View): Boolean {
        val src = (if (topShelf.showing) topShelf.thumb else scene.thumb) ?: return false
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        val x0 = (loc[0] * 192 / Ui.width).coerceIn(0, 191); val y0 = (loc[1] * 108 / Ui.height).coerceIn(0, 107)
        val x1 = ((loc[0] + v.width) * 192 / Ui.width).coerceIn(x0 + 1, 192); val y1 = ((loc[1] + v.height) * 108 / Ui.height).coerceIn(y0 + 1, 108)
        var sum = 0.0; var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val p = src.getPixel(x, y)
            fun lin(c: Int): Double { val f = c / 255.0; return if (f <= 0.04045) f / 12.92 else Math.pow((f + 0.055) / 1.055, 2.4) }
            sum += 0.2126 * lin(Color.red(p)) + 0.7152 * lin(Color.green(p)) + 0.0722 * lin(Color.blue(p)); n++
        }
        val yy = sum / maxOf(1, n)
        val l = (if (yy > 0.008856) 116 * Math.cbrt(yy) - 16 else 903.3 * yy) / 100
        // adapt.js: dark words only over a near-white sky (the shadow keeps the glass's shape elsewhere)
        val was = lightNow[v] ?: false
        val now = if (was) l > 0.82 else l > 0.86
        lightNow[v] = now
        return now
    }

    /** The background changed: the live glass records it again, once, on the next frame. */
    private var glassQueued = false
    fun glassDirty() {
        if (glassQueued) return
        glassQueued = true
        Choreographer.getInstance().postFrameCallback { glassQueued = false; if (scene.current != null) Glass.record(listOf(scene, topShelf)) }
    }

    /**
     * What is on the screen right now, blurred: the backdrop for the sidebar, a panel or Home
     * settings, so they show what is really behind them (the dock, the cards), as the web glass does.
     */
    fun grab(radiusPx: Float, sat: Float, bright: Float, done: (Bitmap?) -> Unit) {
        val bmp = try { Bitmap.createBitmap(Ui.width, Ui.height, Bitmap.Config.ARGB_8888) } catch (e: Throwable) { done(null); return }
        try {
            PixelCopy.request(window, bmp, { r ->
                if (r != PixelCopy.SUCCESS) { bmp.recycle(); done(null); return@request }
                io.execute { val b = try { Glass.blurred(bmp, radiusPx, sat, bright) } catch (e: Throwable) { null }; bmp.recycle(); main.post { done(b) } }
            }, main)
        } catch (e: Exception) { bmp.recycle(); done(null) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SettingsScreen.PICK) return
        val uris = ArrayList<android.net.Uri>()
        if (resultCode == RESULT_OK && data != null) {
            data.clipData?.let { c -> for (k in 0 until c.itemCount) uris.add(c.getItemAt(k).uri) }
            if (uris.isEmpty()) data.data?.let { uris.add(it) }
        }
        settings?.photosPicked(uris)
    }

    fun openSettings() {
        val s = settings ?: SettingsScreen(this, this).also { settings = it; root.addView(it, root.indexOfChild(toast), FrameLayout.LayoutParams(-1, -1)) }
        s.open()
    }
    fun openTvSettings() {
        if (!apps.launch(Apps.SETTINGS)) try { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { toast.show(L.t("tv_settings_none")) }
    }
    /** Settings changed what the home shows: read everything again (the web home restarts its page). */
    fun restart() {
        store.load(); L.set(store.st.optString("lang").ifEmpty { store.home.optString("language", "en") })
        loadPeople(); buildScenes(); refreshWeather(); refresh(); date.refresh(); tab.requestLayout(); tab.invalidate()
    }
}
