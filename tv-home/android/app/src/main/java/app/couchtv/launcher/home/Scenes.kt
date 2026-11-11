package app.couchtv.launcher.home

import android.content.Context
import android.graphics.*
import android.view.View
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * The lock screen scenes (app.js SCENE_LIB, app.css .s-*, backgrounds/mine/scenes.css): a photo,
 * a big glass clock whose fill is the frosted photo behind each digit, and a cut-out of the scenery
 * drawn over the clock. They take turns, shuffled, crossfading.
 */
data class Scene(
    val id: String, val name: String = "", val photo: String? = null, val file: File? = null, val url: String? = null,
    val fg: Boolean = false, val glass: Boolean = false, val water: Float = 0f, val own: Boolean = false, val n: Int = 0,
    // where the clock and the date sit (rem), and how they look
    val clockTop: Float = 9.375f, val clockSize: Float = 25f, val clockLeft: Float? = null,
    val dateTop: Float = 6f, val dateLeft: Float? = null,
    val dateColor: Int = Color.WHITE, val dateWx: Int = 0xC7FFFFFF.toInt(), val dateShadow: Boolean = true,
    val photoDim: Float = 1f, val fillBright: Float = 1f, val fillSat: Float = 1f, val fillAlpha: Float = 1f,
    val rimColor: Int = 0x8CFFFFFF.toInt(), val rimFill: Int = 0x1AFFFFFF, val sheen: Boolean = true,
    val shade: IntArray? = null, val shadeStops: FloatArray? = null,
    // the clock's drop shadow: offset and blur (rem) and colour; blur 0 = none
    val shDy: Float = 0.375f, val shBlur: Float = 1.75f, val shColor: Int = 0x29000000
)

object Scenes {
    val builtIn = listOf(
        Scene("night", photo = "scene-night", fg = true, glass = true, photoDim = 0.86f, fillBright = 1.15f, fillSat = 1.2f, fillAlpha = 0.9f,
            rimColor = 0x80FFD7E1.toInt(), rimFill = 0x0FFFC8D7, dateColor = 0xEBFFE8EE.toInt(), dateWx = 0xC7FFE8EE.toInt()),
        Scene("golden", photo = "scene-golden", fg = true, glass = true, clockTop = 9.5f, clockSize = 24.375f, dateTop = 6.625f),
        Scene("water", photo = "scene-water", glass = true, water = 0.55f, clockTop = 16.75f, clockSize = 26.25f, dateTop = 13.5f,
            shDy = 0.5f, shBlur = 1.875f, shColor = 0x59001428,
            shade = intArrayOf(0x6B000C18, 0x1F000C18, 0, 0x80001428.toInt()), shadeStops = floatArrayOf(0f, 0.4f, 0.52f, 1f)),
        Scene("fog", photo = "scene-fog", fg = true, glass = true, clockTop = 10.75f, clockSize = 29.375f, clockLeft = 6f, dateTop = 7.25f, dateLeft = 6.5f,
            dateColor = 0xFF16201D.toInt(), dateWx = 0xB816201D.toInt(), dateShadow = false, fillBright = 0.46f, fillSat = 0.8f, fillAlpha = 0.8f,
            rimColor = 0x99FFFFFF.toInt(), rimFill = 0, sheen = false, shBlur = 0f),
        Scene("dawn", photo = "scene-dawn", fg = true, glass = true, clockTop = 14.375f, dateTop = 11f, shDy = 0.5f, shBlur = 2.125f, shColor = 0x4D3C1E0A,
            shade = intArrayOf(0x6B321C0C, 0), shadeStops = floatArrayOf(0f, 0.55f))
    )

    /** The household's own scenes (backgrounds/mine/scenes.js) and where their clocks sit (scenes.css). */
    fun mine(ctx: Context): List<Scene> {
        val js = read(ctx, "www/tv/backgrounds/mine/scenes.js") ?: return emptyList()
        val css = read(ctx, "www/tv/backgrounds/mine/scenes.css") ?: ""
        val out = ArrayList<Scene>()
        for (m in Regex("""\{\s*id:\s*"([^"]+)"([^}]*)\}""").findAll(js)) {
            val id = m.groupValues[1]; val rest = m.groupValues[2]
            fun str(k: String) = Regex("""$k:\s*"([^"]*)"""").find(rest)?.groupValues?.get(1)
            fun bool(k: String) = Regex("""$k:\s*true""").containsMatchIn(rest)
            fun rem(sel: String, prop: String) = Regex("""\.${Regex.escape(sel)}[^{]*\{[^}]*?$prop:\s*([\d.]+)rem""").find(css)?.groupValues?.get(1)?.toFloatOrNull()
            out.add(Scene(id, name = str("name") ?: id, photo = str("photo"), fg = bool("fg"), glass = bool("glass"),
                clockTop = rem("s-$id .gclock", "top") ?: 9.375f, clockSize = rem("s-$id .gclock", "font-size") ?: 25f,
                dateTop = rem("scene-$id .date", "top") ?: 6f))
        }
        return out
    }
    private fun read(ctx: Context, path: String) = try { ctx.assets.open(path).bufferedReader().use { it.readText() } } catch (e: Exception) { null }

    /** Photos added in Home settings: no cut-out, no glass copy; the clock is frosted from the photo. */
    fun own(ctx: Context, st: org.json.JSONObject): List<Scene> {
        val a = st.optJSONArray("photos") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("id").isNotEmpty() }.mapIndexedNotNull { i, p ->
            val f = p.optString("file").ifEmpty { null }?.let { File(File(ctx.filesDir, "photos"), it) }
            val u = p.optString("url").ifEmpty { null }
            if (f == null && u == null) null else Scene("p-" + p.optString("id"), file = f, url = u, own = true, n = i + 1)
        }
    }

    /** Which scenes take turns and in what order (app.js sceneState). */
    fun state(all: List<Scene>, st: org.json.JSONObject, home: org.json.JSONObject): Pair<List<String>, List<String>> {
        val ids = all.map { it.id }
        val order: MutableList<String>; val off: MutableList<String>
        val so = st.optJSONArray("scene_order")
        if (so != null) {
            order = (0 until so.length()).map { so.optString(it) }.filter { it in ids }.distinct().toMutableList()
            order.addAll(ids.filter { it !in order })
            val sf = st.optJSONArray("scenes_off")
            off = if (sf == null) mutableListOf() else (0 until sf.length()).map { sf.optString(it) }.filter { it in ids }.toMutableList()
        } else {
            val builtIds = all.filter { !it.own }.map { it.id }
            val hs = home.optJSONArray("scenes")
            var pick = if (hs == null) emptyList() else (0 until hs.length()).map { hs.optString(it) }.filter { it in builtIds }.distinct()
            if (pick.isEmpty()) pick = builtIds
            order = (pick + ids.filter { it !in pick }).toMutableList()
            off = builtIds.filter { it !in pick }.toMutableList()
        }
        return order to off
    }
}

/**
 * The scene layers: the photo, the glass clock, the cut-out, the shade. Two of each, crossfading.
 * The clock's fill uses the scene's glass copy when it has one, else the photo frosted on the TV;
 * the same frosted photo is the glass for every panel (Glass).
 */
class SceneView(ctx: Context) : View(ctx) {
    private class Shown(val scene: Scene, val photo: Bitmap, val fg: Bitmap?, val fill: Bitmap) {
        var a = 0f
        // drawn once and kept as textures: the photo with its shade, the clock, the cut-out
        val photoNode = RenderNode("photo").apply { setUseCompositingLayer(true, null) }
        val clockNode = RenderNode("clock").apply { setUseCompositingLayer(true, null) }
        val fgNode = RenderNode("fg").apply { setUseCompositingLayer(true, null) }
        var photoKey = ""; var clockKey = ""; var fgKey = ""
    }
    private val io = Executors.newSingleThreadExecutor()
    private var back: Shown? = null
    private var front: Shown? = null
    private val photoP = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rimP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL_AND_STROKE }
    private val sheenP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadeP = Paint()
    private val scrimP = Paint()
    private val m = Matrix()
    private val dst = RectF()
    var time = ""
        set(v) { if (v != field) { val was = field; field = v; roll(was); invalidate(); onChange?.invoke() } }
    var night = false
        set(v) { if (v != field) { field = v; invalidate() } }
    /** Dimmed and drifting while the home rests (idle.css). */
    var resting = 0f
        set(v) { field = v; invalidate(); onChange?.invoke() }
    var onShown: ((Scene) -> Unit)? = null
    /** The photo at 192 x 108, for reading how light it is behind the glass (adapt.js). */
    var thumb: Bitmap? = null
    /** The picture behind the glass changed (a crossfade frame, the clock): the glass blurs it again. */
    var onChange: (() -> Unit)? = null
    val current: Scene? get() = front?.scene
    private var fadeT = 1f
    private var glideT = 1f
    // app.css: scenes crossfade over 1.6 s; motion.js: the clock glides to its new place meanwhile
    private val ease = android.view.animation.PathInterpolator(0.42f, 0f, 0.58f, 1f)
    private val glideEase = android.view.animation.PathInterpolator(0.45f, 0f, 0.2f, 1f)
    private var anim: android.animation.ValueAnimator? = null
    private fun fadeTo(from: Float) {
        anim?.cancel()
        if (Motion.calm || from >= 1f) { fadeT = 1f; glideT = 1f; invalidate(); onChange?.invoke(); return }
        anim = android.animation.ValueAnimator.ofFloat(0f, 1f).setDuration(1600).apply {
            interpolator = null
            addUpdateListener { v -> val t = v.animatedValue as Float; fadeT = ease.getInterpolation(t); glideT = glideEase.getInterpolation(t); invalidate(); onChange?.invoke() }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(an: android.animation.Animator) { fadeT = 1f; glideT = 1f; back = null; invalidate(); onChange?.invoke() } })
            start()
        }
    }

    fun show(s: Scene) {
        io.execute {
            val photo = load(s, "") ?: return@execute
            val fg = if (s.fg) load(s, "-fg", webp = true) else null
            val glassCopy = if (s.glass) load(s, "-glass") else null
            // the clock's fill: the scene's glass copy, or (for a photo with none) the photo frosted here
            val fill = glassCopy ?: Glass.frost(photo)
            // kept on the graphics chip, so coming back to the home never waits to upload it again
            val hw = photo.copy(Bitmap.Config.HARDWARE, false) ?: photo
            val small = Bitmap.createScaledBitmap(photo, 192, 108, true)
            val fgHw = fg?.let { it.copy(Bitmap.Config.HARDWARE, false) ?: it }
            post {
                back?.let { if (it !== front) recycle(it) }
                back = front
                front = Shown(s, hw, fgHw, fill)
                thumb = small
                fadeT = 0f; glideT = 0f
                fadeTo(if (back == null) 1f else 0f)
                onShown?.invoke(s)
                invalidate()
            }
        }
    }
    private fun recycle(s: Shown) { /* the bitmaps go when nothing draws them */ }

    private fun load(s: Scene, suffix: String, webp: Boolean = false): Bitmap? = try {
        val o = BitmapFactory.Options()
        when {
            s.photo != null -> context.assets.open("www/tv/backgrounds/${s.photo}$suffix.${if (webp) "webp" else "jpg"}").use { BitmapFactory.decodeStream(it, null, o) }
            suffix.isNotEmpty() -> null
            s.file != null -> BitmapFactory.decodeFile(s.file.path, o.apply { inSampleSize = sample(s.file) })
            s.url != null -> Net.bitmap(context, s.url, 1920)
            else -> null
        }
    } catch (e: Exception) { null }
    private fun sample(f: File): Int {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var n = 1; while (o.outWidth / (n * 2) >= Ui.width) n *= 2
        return n
    }

    /** Its picture into another canvas (the live glass), without touching its own layer. */
    /** Its picture into another canvas (the live glass), drawn on its own: the kept textures are
     *  the screen's, and sharing them would let the glass take the incoming scene's fade early. */
    fun paintInto(c: Canvas) { forGlass = true; try { onDraw(c) } finally { forGlass = false } }
    private var forGlass = false
    override fun onDraw(c: Canvas) {
        val b = back; val f = front ?: return
        val gliding = b != null && fadeT < 1f
        if (gliding) drawScene(c, b!!, 1f, glideFrom = b, glideTo = f)
        drawScene(c, f, if (b != null) fadeT else 1f, glideFrom = if (gliding) b else null, glideTo = if (gliding) f else null)
        // the scrim under Keep watching and the dock (app.css .theme-night .scrim)
        if (scrimP.shader == null) scrimP.shader = LinearGradient(0f, height * 0.6f, 0f, height.toFloat(), 0, 0x80000000.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, height * 0.6f, width.toFloat(), height.toFloat(), scrimP)
        if (resting > 0f) c.drawColor(Ui.alpha(Color.BLACK, 0.18f * resting))
    }

    /** The clock's box on screen: its centre and its height (app.css .gclock, line height 0.82). */
    private fun box(sc: Scene): FloatArray {
        val size = Ui.r(sc.clockSize)
        fillP.typeface = Ui.antonio; fillP.textSize = size; fillP.letterSpacing = -0.02f
        val w = fillP.measureText(time)
        val x = sc.clockLeft?.let { Ui.r(it) + w / 2 } ?: width / 2f
        return floatArrayOf(x, Ui.r(sc.clockTop) + size * 0.41f, size * 0.82f)
    }
    private fun drawPhoto(c: Canvas, s: Shown, a: Float, dim: Float) {
        val sc = s.scene
        photoP.alpha = (255 * a).toInt()
        photoP.colorFilter = if (dim < 1f) ColorMatrixColorFilter(ColorMatrix().apply { setScale(dim, dim, dim, 1f) }) else null
        cover(s.photo); c.drawBitmap(s.photo, null, dst, photoP)
        sc.shade?.let { cols ->
            shadeP.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), cols, sc.shadeStops, Shader.TileMode.CLAMP)
            shadeP.alpha = (255 * a).toInt(); c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shadeP)
        }
    }
    private fun drawFg(c: Canvas, s: Shown, a: Float, dim: Float) {
        val f = s.fg ?: return
        photoP.alpha = (255 * a).toInt()
        photoP.colorFilter = if (dim < 1f) ColorMatrixColorFilter(ColorMatrix().apply { setScale(dim, dim, dim, 1f) }) else null
        cover(f); c.drawBitmap(f, null, dst, photoP)
    }
    /** A kept texture, drawn again only when what is in it changes. */
    private inline fun node(c: Canvas, n: RenderNode, key: String, old: String, a: Float, draw: (Canvas) -> Unit): String {
        if (key != old || !n.hasDisplayList()) {
            n.setPosition(0, 0, width, height)
            val rc = n.beginRecording(width, height)
            try { draw(rc) } finally { n.endRecording() }
        }
        // the fade is the drawing's own, never the texture's: the screen and the glass both draw
        // these, and a property on the texture would be shared between them
        n.alpha = 1f
        if (a >= 0.999f) c.drawRenderNode(n)
        else { val k = c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (255 * a).toInt()); c.drawRenderNode(n); c.restoreToCount(k) }
        return key
    }

    private fun drawScene(c: Canvas, s: Shown, a: Float, glideFrom: Shown? = null, glideTo: Shown? = null) {
        val sc = s.scene
        val dim = (if (night) 0.72f else 1f) * sc.photoDim
        // the glass draws the photo itself, never the screen's kept textures: those are rebuilt
        // after the home has been away, and the glass would copy them half made (greyer, then
        // right a moment later)
        val hw = c.isHardwareAccelerated && width > 0 && !forGlass
        if (hw) s.photoKey = node(c, s.photoNode, "$dim|$width", s.photoKey, a) { drawPhoto(it, s, 1f, dim) }
        else drawPhoto(c, s, a, dim)
        if (time.isNotEmpty()) {
            // scene change: the outgoing and incoming clocks travel together, so it reads as one
            // clock gliding to its new place (motion.js glide)
            val n = c.save()
            if (glideFrom != null && glideTo != null) {
                val A = box(glideFrom.scene); val B = box(glideTo.scene); val p = glideT
                if (s === glideFrom) { c.translate((B[0] - A[0]) * p, (B[1] - A[1]) * p); val k = 1 + (B[2] / A[2] - 1) * p; c.scale(k, k, A[0], A[1]) }
                else { c.translate((A[0] - B[0]) * (1 - p), (A[1] - B[1]) * (1 - p)); val k = 1 + (A[2] / B[2] - 1) * (1 - p); c.scale(k, k, B[0], B[1]) }
            }
            // while a minute rolls the clock is drawn as it moves; otherwise its texture is reused
            if (hw && rollMs >= 760f) s.clockKey = node(c, s.clockNode, "$time|$night|$width", s.clockKey, a) { drawClock(it, s, 1f, dim) }
            else drawClock(c, s, a, dim)
            c.restoreToCount(n)
        }
        if (hw) { if (s.fg != null) s.fgKey = node(c, s.fgNode, "$dim|$width", s.fgKey, a) { drawFg(it, s, 1f, dim) } }
        else drawFg(c, s, a, dim)
    }
    private fun cover(bmp: Bitmap) {
        val sx = width.toFloat() / bmp.width; val sy = height.toFloat() / bmp.height; val k = maxOf(sx, sy)
        val w = bmp.width * k; val h = bmp.height * k
        dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    /** The glass clock (app.css .gclock: fill, sheen, rim, a soft shadow; the water scene's reflection). */
    /**
     * The clock, and a new minute rolling only the digits that changed: the old ones up and out
     * (420 ms), the new ones up and in (640 ms, 120 ms later), as motion.js roll() does.
     */
    private fun drawClock(c: Canvas, s: Shown, a: Float, dim: Float) {
        val ms = rollMs
        drawShadow(c, s, a, time)
        if (s.scene.water > 0f) {
            // above the waterline sharp, below it the blurred reflection
            val line = height * s.scene.water
            var n = c.save(); c.clipRect(0f, -10000f, width.toFloat(), line); drawClockText(c, s, a, dim, time, 0f); c.restoreToCount(n)
            n = c.save(); c.clipRect(0f, line, width.toFloat(), height.toFloat()); drawUnder(c, s, a, dim, time); c.restoreToCount(n)
            return
        }
        if (ms >= 760f || oldTime.isEmpty() || oldTime.length != time.length) { drawClockText(c, s, a, dim, time, 0f); return }
        val sc = s.scene
        val size = Ui.r(sc.clockSize)
        fillP.typeface = Ui.antonio; fillP.textSize = size; fillP.letterSpacing = -0.02f
        val w = fillP.measureText(time)
        val x = sc.clockLeft?.let { Ui.r(it) } ?: (width - w) / 2
        var p = 0; while (p < time.length && time[p] == oldTime[p]) p++
        val split = x + fillP.measureText(time, 0, p)
        val lift = size * 0.3f
        val big = 10000f
        var n = c.save(); c.clipRect(-big, -big, split, big); drawClockText(c, s, a, dim, time, 0f); c.restoreToCount(n)
        n = c.save(); c.clipRect(split, -big, big, big)
        val o = rollOut.getInterpolation((ms / 420f).coerceIn(0f, 1f))
        if (o < 1f) drawClockText(c, s, a * (1 - o), dim, oldTime, -lift * o)
        val i = rollIn.getInterpolation(((ms - 120f) / 640f).coerceIn(0f, 1f))
        if (i > 0f) drawClockText(c, s, a * i, dim, time, lift * (1 - i))
        c.restoreToCount(n)
    }
    private var oldTime = ""
    private var rollMs = 760f
    private val rollOut = android.view.animation.PathInterpolator(0.5f, 0f, 0.75f, 0f)
    private val rollIn = android.view.animation.PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    private fun roll(from: String) {
        if (Motion.calm || from.isEmpty()) return
        oldTime = from
        android.animation.ValueAnimator.ofFloat(0f, 760f).setDuration(760).apply {
            interpolator = null
            addUpdateListener { rollMs = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(an: android.animation.Animator) { rollMs = 760f; invalidate(); onChange?.invoke() } })
            start()
        }
    }
    private fun clockX(sc: Scene, text: String): Float {
        fillP.typeface = Ui.antonio; fillP.textSize = Ui.r(sc.clockSize); fillP.letterSpacing = -0.02f
        return sc.clockLeft?.let { Ui.r(it) } ?: (width - fillP.measureText(text)) / 2
    }
    private fun clockBase(sc: Scene): Float {
        fillP.typeface = Ui.antonio; fillP.textSize = Ui.r(sc.clockSize); fillP.letterSpacing = -0.02f
        val fm = fillP.fontMetrics
        return Ui.r(sc.clockTop) + (Ui.r(sc.clockSize) * 0.82f - (fm.descent - fm.ascent)) / 2 - fm.ascent
    }

    /** The clock's parts: the frosted fill, the sheen and the rim. under: the water's reflection. */
    private fun drawClockText(c: Canvas, s: Shown, a: Float, dim: Float, time: String, dy: Float, under: Boolean = false) {
        val sc = s.scene
        val size = Ui.r(sc.clockSize)
        val base = clockBase(sc) + dy
        fillP.typeface = Ui.antonio; fillP.textSize = size; fillP.letterSpacing = -0.02f
        val fm = fillP.fontMetrics
        val w = fillP.measureText(time)
        val x = clockX(sc, time) + if (under) Ui.r(0.3125f) else 0f
        val alpha = (255 * a).toInt()
        // fill: the glass copy lined up with the screen, brightened and saturated as the scene says
        val bmp = s.fill
        m.setScale(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        fillP.shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
        val cm = ColorMatrix().apply { setSaturation(sc.fillSat) }
        val br = sc.fillBright * (if (night) 0.72f else 1f)
        cm.postConcat(ColorMatrix().apply { setScale(br, br, br, 1f) })
        if (under) { cm.postConcat(hue(-8f)); cm.postConcat(ColorMatrix().apply { setSaturation(1.4f) }) }   // .s-water .gclock.under .fill
        fillP.colorFilter = ColorMatrixColorFilter(cm)
        fillP.alpha = (alpha * sc.fillAlpha).toInt()
        c.drawText(time, x, base, fillP)
        val top = base + fm.ascent
        if (sc.sheen) {
            sheenP.typeface = Ui.antonio; sheenP.textSize = size; sheenP.letterSpacing = -0.02f
            sheenP.shader = LinearGradient(x, top, x + w * 0.36f, top + (base - top), intArrayOf(0x8CFFFFFF.toInt(), 0, 0, 0x38FFFFFF), floatArrayOf(0f, 0.38f, 0.7f, 1f), Shader.TileMode.CLAMP)
            sheenP.alpha = alpha
            c.drawText(time, x, base, sheenP)
        }
        rimP.typeface = Ui.antonio; rimP.textSize = size; rimP.letterSpacing = -0.02f
        rimP.style = Paint.Style.FILL; rimP.color = sc.rimFill; rimP.alpha = (Color.alpha(sc.rimFill) * a).toInt()
        if (sc.rimFill != 0) c.drawText(time, x, base, rimP)
        rimP.style = Paint.Style.STROKE; rimP.strokeWidth = Ui.r(0.09f)
        rimP.color = if (under) 0x80AAF0FF.toInt() else sc.rimColor   // .s-water .gclock.under .rim
        rimP.alpha = (Color.alpha(rimP.color) * 0.5f * a * (if (night) 0.72f else 1f)).toInt()
        c.drawText(time, x, base, rimP)
    }
    private fun hue(deg: Float): ColorMatrix {
        val r = Math.toRadians(deg.toDouble()); val cos = Math.cos(r).toFloat(); val sin = Math.sin(r).toFloat()
        return ColorMatrix(floatArrayOf(
            0.213f + cos * 0.787f - sin * 0.213f, 0.715f - cos * 0.715f - sin * 0.715f, 0.072f - cos * 0.072f + sin * 0.928f, 0f, 0f,
            0.213f - cos * 0.213f + sin * 0.143f, 0.715f + cos * 0.285f + sin * 0.140f, 0.072f - cos * 0.072f - sin * 0.283f, 0f, 0f,
            0.213f - cos * 0.213f - sin * 0.787f, 0.715f - cos * 0.715f + sin * 0.715f, 0.072f + cos * 0.928f + sin * 0.072f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f))
    }

    // ---- what is drawn once per minute and kept: the soft shadow, the water's blurred reflection ----
    private val cache = HashMap<String, Bitmap>()
    private fun cached(key: String, make: () -> Bitmap?): Bitmap? {
        cache[key]?.let { return it }
        if (cache.size > 6) cache.clear()
        return make()?.also { cache[key] = it }
    }
    /** drop-shadow(0 dy blur colour) of the whole clock: its shape, blurred at a quarter size. */
    private fun drawShadow(c: Canvas, s: Shown, a: Float, text: String) {
        val sc = s.scene
        if (sc.shBlur <= 0f || width == 0) return
        val q = 4
        val b = cached("sh|${sc.id}|$text|$width") {
            val w = width / q; val h = height / q
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val cv = Canvas(bmp)
            cv.scale(1f / q, 1f / q)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Ui.antonio; textSize = Ui.r(sc.clockSize); letterSpacing = -0.02f; color = sc.shColor or 0xFF000000.toInt() }
            cv.drawText(text, clockX(sc, text), clockBase(sc), p)
            val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
            Glass.blurPixels(px, IntArray(w * h), w, h, maxOf(1, (Ui.r(sc.shBlur) / q / 1.7f).toInt()))
            bmp.setPixels(px, 0, w, 0, 0, w, h); bmp
        } ?: return
        shP.alpha = (Color.alpha(sc.shColor) * a).toInt()
        c.drawBitmap(b, null, RectF(0f, Ui.r(sc.shDy), width.toFloat(), height + Ui.r(sc.shDy)), shP)
    }
    private val shP = Paint(Paint.FILTER_BITMAP_FLAG)
    /** The water scene's clock under the waterline: blurred, shifted, cooler, at 78% (.s-water .gclock.under). */
    private fun drawUnder(c: Canvas, s: Shown, a: Float, dim: Float, text: String) {
        val sc = s.scene
        val line = height * sc.water
        val y1 = (clockBase(sc) + Ui.r(sc.clockSize) * 0.3f).coerceAtMost(height.toFloat())
        if (y1 <= line) return
        val b = cached("uw|${sc.id}|$text|$dim|$width") {
            val w = width; val h = (y1 - line).toInt() + 8
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val cv = Canvas(bmp)
            cv.translate(0f, -line)
            drawClockText(cv, s, 1f, dim, text, 0f, under = true)
            val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
            Glass.blurPixels(px, IntArray(w * h), w, h, 1)   // blur(.1375rem)
            bmp.setPixels(px, 0, w, 0, 0, w, h); bmp
        } ?: return
        shP.alpha = (255 * 0.78f * a).toInt()
        c.drawBitmap(b, 0f, line, shP)
    }
}

/** Pictures from the network (Trakt artwork, photo addresses) and the TV's own records, cached. */
object Net {
    private val mem = android.util.LruCache<String, Bitmap>(24)
    fun bitmap(ctx: Context, url: String, maxW: Int): Bitmap? {
        mem.get("$url@$maxW")?.let { return it }
        val bytes: ByteArray = try {
            if (url.startsWith("http://tvos.invalid/watch/")) {
                app.couchtv.launcher.WatchLog.artFile(ctx, url.substringAfterLast('/'))?.readBytes() ?: return null
            } else {
                val dir = File(ctx.cacheDir, "art").apply { mkdirs() }
                val f = File(dir, Integer.toHexString(url.hashCode()) + ".img")
                if (f.isFile && f.length() > 0) f.readBytes() else {
                    val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 5000; c.readTimeout = 10000; c.instanceFollowRedirects = true
                    val b = c.inputStream.use { it.readBytes() }; c.disconnect()
                    f.writeBytes(b); b
                }
            }
        } catch (e: Exception) { return null }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var n = 1; while (o.outWidth / (n * 2) >= maxW) n *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = n }) ?: return null
        mem.put("$url@$maxW", bmp)
        return bmp
    }
}

object Clock {
    fun hm(c: Calendar, h24: Boolean): String {
        val h = c.get(Calendar.HOUR_OF_DAY); val m = c.get(Calendar.MINUTE)
        val mm = (if (m < 10) "0" else "") + m
        return if (h24) (if (h < 10) "0" else "") + h + ":" + mm else ((h % 12).let { if (it == 0) 12 else it }).toString() + ":" + mm
    }
}
