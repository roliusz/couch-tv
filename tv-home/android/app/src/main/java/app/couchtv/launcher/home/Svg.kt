package app.couchtv.launcher.home

import android.graphics.*

/**
 * The web home's line icons, drawn natively from the same SVG markup (path, circle, rect), so the
 * symbols match exactly. Lines by default (stroke, round ends), filled for "solid" or class="fill".
 */
class Icon(svg: String) {
    private class Part(val path: Path, val fill: Boolean)
    private val parts = ArrayList<Part>()
    private val cur = Path()
    private var vbW = 24f; private var vbH = 24f
    private val solid = svg.contains("class=\"solid\"") || svg.contains("fill=\"currentColor\"")

    init {
        Regex("""viewBox="([\d.\s-]+)"""").find(svg)?.let { m ->
            val v = m.groupValues[1].trim().split(Regex("\\s+")).map { it.toFloat() }
            if (v.size == 4) { vbW = v[2]; vbH = v[3] }
        }
        for (m in Regex("""<(path|circle|rect)\b([^>]*)/?>""").findAll(svg)) {
            val a = attrs(m.groupValues[2])
            val fill = solid || a["class"] == "fill"
            val p = Path()
            when (m.groupValues[1]) {
                "path" -> { parse(a["d"] ?: ""); p.set(cur) }
                "circle" -> p.addCircle(f(a["cx"]), f(a["cy"]), f(a["r"]), Path.Direction.CW)
                "rect" -> { val x = f(a["x"]); val y = f(a["y"]); val rx = f(a["rx"]); p.addRoundRect(RectF(x, y, x + f(a["width"]), y + f(a["height"])), rx, rx, Path.Direction.CW) }
            }
            a["transform"]?.let { tr -> Regex("""translate\(([-\d.]+)[ ,]+([-\d.]+)\)""").find(tr)?.let { val mm = Matrix(); mm.setTranslate(it.groupValues[1].toFloat(), it.groupValues[2].toFloat()); p.transform(mm) } }
            parts.add(Part(p, fill))
        }
    }
    private fun attrs(s: String) = Regex("""([a-z-]+)="([^"]*)"""").findAll(s).associate { it.groupValues[1] to it.groupValues[2] }
    private fun f(s: String?) = s?.toFloatOrNull() ?: 0f

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val m = Matrix()
    private val tmp = Path()

    /** Draws the icon in a box of size px at (x, y). sw: line width in the icon's own units. */
    fun draw(c: Canvas, x: Float, y: Float, size: Float, color: Int, sw: Float = 1.7f) {
        val s = size / maxOf(vbW, vbH)
        m.setScale(s, s); m.postTranslate(x + (size - vbW * s) / 2, y + (size - vbH * s) / 2)
        stroke.color = color; fillP.color = color; stroke.strokeWidth = sw * s
        for (p in parts) { tmp.set(p.path); tmp.transform(m); c.drawPath(tmp, if (p.fill) fillP else stroke) }
    }

    // ---- SVG path data ----
    private fun parse(d: String) {
        cur.reset()
        val tok = Regex("""[MmLlHhVvCcSsQqTtAaZz]|-?(?:\d+\.?\d*|\.\d+)(?:e-?\d+)?""").findAll(d).map { it.value }.toList()
        var i = 0; var cmd = 'M'
        var x = 0f; var y = 0f; var sx = 0f; var sy = 0f; var cx = 0f; var cy = 0f
        fun num() = tok[i++].toFloat()
        fun more() = i < tok.size && !tok[i][0].isLetter()
        while (i < tok.size) {
            if (tok[i][0].isLetter()) { cmd = tok[i][0]; i++ }
            val rel = cmd.isLowerCase()
            when (cmd.uppercaseChar()) {
                'M' -> { var nx = num(); var ny = num(); if (rel) { nx += x; ny += y }; cur.moveTo(nx, ny); x = nx; y = ny; sx = x; sy = y; cx = x; cy = y; cmd = if (rel) 'l' else 'L' }
                'L' -> { var nx = num(); var ny = num(); if (rel) { nx += x; ny += y }; cur.lineTo(nx, ny); x = nx; y = ny; cx = x; cy = y }
                'H' -> { var nx = num(); if (rel) nx += x; cur.lineTo(nx, y); x = nx; cx = x; cy = y }
                'V' -> { var ny = num(); if (rel) ny += y; cur.lineTo(x, ny); y = ny; cx = x; cy = y }
                'C' -> { var x1 = num(); var y1 = num(); var x2 = num(); var y2 = num(); var nx = num(); var ny = num()
                    if (rel) { x1 += x; y1 += y; x2 += x; y2 += y; nx += x; ny += y }
                    cur.cubicTo(x1, y1, x2, y2, nx, ny); cx = x2; cy = y2; x = nx; y = ny }
                'S' -> { var x2 = num(); var y2 = num(); var nx = num(); var ny = num()
                    if (rel) { x2 += x; y2 += y; nx += x; ny += y }
                    cur.cubicTo(2 * x - cx, 2 * y - cy, x2, y2, nx, ny); cx = x2; cy = y2; x = nx; y = ny }
                'Q' -> { var x1 = num(); var y1 = num(); var nx = num(); var ny = num()
                    if (rel) { x1 += x; y1 += y; nx += x; ny += y }
                    cur.quadTo(x1, y1, nx, ny); cx = x1; cy = y1; x = nx; y = ny }
                'T' -> { var nx = num(); var ny = num(); if (rel) { nx += x; ny += y }
                    val x1 = 2 * x - cx; val y1 = 2 * y - cy; cur.quadTo(x1, y1, nx, ny); cx = x1; cy = y1; x = nx; y = ny }
                'A' -> { val rx = num(); val ry = num(); val rot = num(); val large = num() != 0f; val sweep = num() != 0f
                    var nx = num(); var ny = num(); if (rel) { nx += x; ny += y }
                    arc(x, y, nx, ny, rx, ry, rot, large, sweep); x = nx; y = ny; cx = x; cy = y }
                'Z' -> { cur.close(); x = sx; y = sy; cx = x; cy = y; if (more()) i++ }
                else -> i++
            }
        }
    }
    /** An SVG arc as cubic curves (the endpoint parametrisation of the SVG spec, appendix F.6). */
    private fun arc(x0: Float, y0: Float, x1: Float, y1: Float, rxIn: Float, ryIn: Float, angle: Float, large: Boolean, sweep: Boolean) {
        if (rxIn == 0f || ryIn == 0f) { cur.lineTo(x1, y1); return }
        val phi = Math.toRadians(angle.toDouble()); val cosP = Math.cos(phi); val sinP = Math.sin(phi)
        val dx = (x0 - x1) / 2.0; val dy = (y0 - y1) / 2.0
        val xp = cosP * dx + sinP * dy; val yp = -sinP * dx + cosP * dy
        var rx = Math.abs(rxIn.toDouble()); var ry = Math.abs(ryIn.toDouble())
        val lam = xp * xp / (rx * rx) + yp * yp / (ry * ry)
        if (lam > 1) { rx *= Math.sqrt(lam); ry *= Math.sqrt(lam) }
        var num = rx * rx * ry * ry - rx * rx * yp * yp - ry * ry * xp * xp
        if (num < 0) num = 0.0
        var co = Math.sqrt(num / (rx * rx * yp * yp + ry * ry * xp * xp))
        if (large == sweep) co = -co
        val cxp = co * rx * yp / ry; val cyp = -co * ry * xp / rx
        val ccx = cosP * cxp - sinP * cyp + (x0 + x1) / 2.0; val ccy = sinP * cxp + cosP * cyp + (y0 + y1) / 2.0
        fun ang(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val a = Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy); return a
        }
        val t1 = ang(1.0, 0.0, (xp - cxp) / rx, (yp - cyp) / ry)
        var dt = ang((xp - cxp) / rx, (yp - cyp) / ry, (-xp - cxp) / rx, (-yp - cyp) / ry)
        if (!sweep && dt > 0) dt -= 2 * Math.PI else if (sweep && dt < 0) dt += 2 * Math.PI
        val n = Math.ceil(Math.abs(dt) / (Math.PI / 2)).toInt().coerceAtLeast(1)
        val step = dt / n; var t = t1
        for (k in 0 until n) {
            val a = 4.0 / 3 * Math.tan(step / 4)
            val c1 = Math.cos(t); val s1 = Math.sin(t); val c2 = Math.cos(t + step); val s2 = Math.sin(t + step)
            fun px(cs: Double, sn: Double) = (ccx + rx * cs * cosP - ry * sn * sinP).toFloat()
            fun py(cs: Double, sn: Double) = (ccy + rx * cs * sinP + ry * sn * cosP).toFloat()
            val e1x = px(c1 - a * s1, s1 + a * c1); val e1y = py(c1 - a * s1, s1 + a * c1)
            val e2x = px(c2 + a * s2, s2 - a * c2); val e2y = py(c2 + a * s2, s2 - a * c2)
            cur.cubicTo(e1x, e1y, e2x, e2y, px(c2, s2), py(c2, s2))
            t += step
        }
    }
}

/** The icons the home uses, as the web home has them (app.js ICONS, WX, NP_ICONS, HA icons). */
object Icons {
    private val cache = HashMap<String, Icon>()
    fun of(svg: String) = cache.getOrPut(svg) { Icon(svg) }
    val screen get() = of("""<svg viewBox="0 0 24 24"><rect x="2.5" y="3.5" width="19" height="13" rx="2"/><path d="M9 20.5h6M12 16.5v4"/></svg>""")
    val moon get() = of("""<svg viewBox="0 0 24 24"><path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"/></svg>""")
    val picture get() = of("""<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5"/><path d="M12 3.5v17a8.5 8.5 0 0 0 0-17z" class="fill"/></svg>""")
    val more get() = of("""<svg viewBox="0 0 24 24"><circle cx="5" cy="12" r="1.2"/><circle cx="12" cy="12" r="1.2"/><circle cx="19" cy="12" r="1.2"/></svg>""")
    val gear get() = of("""<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="3.2"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/></svg>""")
    val input get() = of("""<svg viewBox="0 0 24 24"><rect x="2.5" y="4" width="19" height="13" rx="2.5"/><path d="M8 21h8M12 7.5v6M9.5 11l2.5 2.5 2.5-2.5"/></svg>""")
    val store get() = of("""<svg viewBox="0 0 24 24"><path d="M5 8h14l-1.2 11.2a2 2 0 0 1-2 1.8H8.2a2 2 0 0 1-2-1.8z"/><path d="M9 10.5V7a3 3 0 0 1 6 0v3.5"/></svg>""")
    val check get() = of("""<svg viewBox="0 0 24 24"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>""")
    val hide get() = of("""<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5"/><path d="M8 12h8"/></svg>""")
    val ambient get() = of("""<svg viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="12" rx="2"/><path d="M8 20h8M7 13l3-3 2 2 3-3 2 2"/></svg>""")
    val updown get() = of("""<svg viewBox="0 0 24 24"><path d="M8 9.5l4-4 4 4M8 14.5l4 4 4-4"/></svg>""")
    val sliders get() = of("""<svg viewBox="0 0 24 24"><path d="M4 7h9M17 7h3M4 17h3M11 17h9"/><circle cx="15" cy="7" r="2"/><circle cx="9" cy="17" r="2"/></svg>""")
    val chevron get() = of("""<svg viewBox="0 0 24 24"><path d="M9.5 5.5 16 12l-6.5 6.5"/></svg>""")
    val globe get() = of("""<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5"/><path d="M3.5 12h17M12 3.5c2.6 2.5 3.8 5.3 3.8 8.5s-1.2 6-3.8 8.5c-2.6-2.5-3.8-5.3-3.8-8.5s1.2-6 3.8-8.5z"/></svg>""")
    val pin get() = of("""<svg viewBox="0 0 24 24"><path d="M12 21s-6.5-6-6.5-11a6.5 6.5 0 0 1 13 0c0 5-6.5 11-6.5 11z"/><circle cx="12" cy="10" r="2.4"/></svg>""")
    val cloud get() = of("""<svg viewBox="0 0 24 24"><path d="M17.5 19H8a4 4 0 1 1 .9-7.9A5.5 5.5 0 0 1 19.4 12a3.5 3.5 0 0 1-1.9 7z"/></svg>""")
    val apps get() = of("""<svg viewBox="0 0 24 24"><rect x="3.5" y="3.5" width="7" height="7" rx="2"/><rect x="13.5" y="3.5" width="7" height="7" rx="2"/><rect x="3.5" y="13.5" width="7" height="7" rx="2"/><rect x="13.5" y="13.5" width="7" height="7" rx="2"/></svg>""")
    val person get() = of("""<svg viewBox="0 0 24 24"><circle cx="12" cy="8.5" r="3.8"/><path d="M4.5 20.5c.8-3.9 3.7-6 7.5-6s6.7 2.1 7.5 6"/></svg>""")
    val bolt get() = of("""<svg viewBox="0 0 24 24"><path d="M13 2.8 5.5 13.2h5.8L10.5 21l8-10.6h-5.9z"/></svg>""")
    val info get() = of("""<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5"/><path d="M12 11v5.5M12 7.6v.2"/></svg>""")
    val look get() = of("""<svg viewBox="0 0 24 24"><rect x="2.5" y="4.5" width="19" height="15" rx="3"/><path d="M2.5 16l5.5-5.5 4.5 4.5 3-3 6 6"/><circle cx="16.5" cy="8.8" r="1.6"/></svg>""")
    val menu get() = of("""<svg viewBox="0 0 24 24"><path d="M4 7h16M4 12h16M4 17h16"/></svg>""")
    val bulb get() = of("""<svg viewBox="0 0 24 24"><path d="M9 18h6M10 21h4M12 3a6 6 0 0 0-3.6 10.8c.6.5 1 1.2 1 2V16h5.2v-.2c0-.8.4-1.5 1-2A6 6 0 0 0 12 3z"/></svg>""")
    val thermo get() = of("""<svg viewBox="0 0 24 24"><path d="M14 14.8V5a2 2 0 0 0-4 0v9.8a4 4 0 1 0 4 0z"/></svg>""")
    val calendar get() = of("""<svg viewBox="0 0 24 24" class="solid"><path d="M7 2.5a1 1 0 0 1 1 1V5h8V3.5a1 1 0 1 1 2 0V5h1a3 3 0 0 1 3 3v11a3 3 0 0 1-3 3H5a3 3 0 0 1-3-3V8a3 3 0 0 1 3-3h1V3.5a1 1 0 0 1 1-1zM4 10v9a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-9z"/></svg>""")
    val playSmall get() = of("""<svg viewBox="0 0 24 24" class="solid"><path d="M7 4.8v14.4a1 1 0 0 0 1.5.86l11.6-7.2a1 1 0 0 0 0-1.72L8.5 3.94A1 1 0 0 0 7 4.8z"/></svg>""")
    // the web's VPN chip: a shield with a lock in it (index.html #vpn-chip)
    val vpnLock get() = of("""<svg viewBox="0 0 24 24"><path d="M12 2.6 4.6 5.4v5.5c0 4.7 3.1 8.9 7.4 10.4 4.3-1.5 7.4-5.7 7.4-10.4V5.4z"/><rect class="fill" x="9" y="11" width="6" height="4.6" rx="0.9"/><path d="M10.2 11V9.6a1.8 1.8 0 0 1 3.6 0V11"/></svg>""")
    val vpn get() = of("""<svg viewBox="0 0 24 24"><path d="M12 3l7 3v5c0 4.5-3 8.2-7 10-4-1.8-7-5.5-7-10V6z"/><path d="M9 12l2 2 4-4"/></svg>""")
    // weather, by Open-Meteo's WMO code
    val sun get() = of("""<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="4"/><path d="M12 2.5v2M12 19.5v2M4.6 4.6l1.4 1.4M18 18l1.4 1.4M2.5 12h2M19.5 12h2M4.6 19.4 6 18M18 6l1.4-1.4"/></svg>""")
    val wxMoon get() = of("""<svg viewBox="0 0 24 24"><path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"/></svg>""")
    val rain get() = of("""<svg viewBox="0 0 24 24"><path d="M17.5 15H8a4 4 0 1 1 .9-7.9A5.5 5.5 0 0 1 19.4 8a3.5 3.5 0 0 1-1.9 7z"/><path d="M9 18l-1 2.5M13 18l-1 2.5M17 18l-1 2.5"/></svg>""")
    val storm get() = of("""<svg viewBox="0 0 24 24"><path d="M17.5 15H8a4 4 0 1 1 .9-7.9A5.5 5.5 0 0 1 19.4 8a3.5 3.5 0 0 1-1.9 7z"/><path d="M12.5 15.5 10.5 19h3l-2 3.5"/></svg>""")
    fun weather(code: Int, day: Boolean) = when {
        code >= 95 -> storm; code >= 51 -> rain; code >= 3 || code == 45 || code == 48 -> cloud; day -> sun; else -> wxMoon
    }
    // Now Playing
    val npPrev get() = of("""<svg viewBox="0 0 32 24" class="solid"><path transform="translate(-1 0)" d="M15.5 5L3 12l12.5 7zM29 5L16.5 12 29 19z"/></svg>""")
    val npNext get() = of("""<svg viewBox="0 0 32 24" class="solid"><path transform="translate(1 0)" d="M3 5l12.5 7L3 19zM16.5 5L29 12l-12.5 7z"/></svg>""")
    val npPlay get() = of("""<svg viewBox="0 0 24 24" class="solid"><path d="M6.5 4.6v14.8a1 1 0 0 0 1.5.9l12-7.4a1 1 0 0 0 0-1.8l-12-7.4a1 1 0 0 0-1.5.9z"/></svg>""")
    val npPause get() = of("""<svg viewBox="0 0 24 24" class="solid"><rect x="5" y="3.5" width="4.6" height="17" rx="1.3"/><rect x="14.4" y="3.5" width="4.6" height="17" rx="1.3"/></svg>""")
    val npRoute get() = of("""<svg viewBox="0 0 24 24"><path d="M6.3 16.6a8 8 0 1 1 11.4 0M8.7 14.3a4.6 4.6 0 1 1 6.6 0"/><path d="M12 14.5l4.2 5.5H7.8z" class="fill"/></svg>""")
    val npNote get() = of("""<svg viewBox="0 0 24 24"><path d="M9 18.5V5.5l10-2v13"/><circle cx="6.5" cy="18.5" r="2.5"/><circle cx="16.5" cy="16.5" r="2.5"/></svg>""")
    // Home Assistant scenes, by name ("mdi:sofa" or "sofa"); anything else is the light bulb
    fun ha(name: String?): Icon = when (name.orEmpty().removePrefix("mdi:")) {
        "sofa" -> of("""<svg viewBox="0 0 24 24"><path d="M5 11V8.5A2.5 2.5 0 0 1 7.5 6h9A2.5 2.5 0 0 1 19 8.5V11"/><path d="M3.5 11A1.5 1.5 0 0 1 5 12.5V14h14v-1.5a1.5 1.5 0 0 1 3 0V17a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1v-4.5A1.5 1.5 0 0 1 3.5 11z"/><path d="M5 18v2M19 18v2"/></svg>""")
        "weather-sunset" -> of("""<svg viewBox="0 0 24 24"><path d="M2.5 18h19M7 18a5 5 0 0 1 10 0"/><path d="M12 7.5V10M5.3 11.3l1.8 1.8M18.7 11.3l-1.8 1.8M7.5 21h9"/></svg>""")
        "sun", "weather-sunny", "white-balance-sunny" -> of("""<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="4"/><path d="M12 2.5v2.5M12 19v2.5M2.5 12H5M19 12h2.5M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8"/></svg>""")
        "moon", "weather-night" -> moon
        else -> bulb
    }
}
