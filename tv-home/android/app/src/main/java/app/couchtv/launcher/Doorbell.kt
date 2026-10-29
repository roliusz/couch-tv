package app.couchtv.launcher

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The doorbell on the TV (fork addition), as on the tablet: Home Assistant's "Doorbell Camera"
 * automation turns a helper on for 45 seconds when someone is at the door (ha.json "doorbell":
 * helper, camera, name). While the TV is on, the launcher looks at that helper every few seconds;
 * when it is on, a card with the door camera slides in at the top right over whatever is playing,
 * and goes when the helper goes off. The camera runs on a battery, so it shows snapshots (every two
 * seconds) straight away, then the live picture over it once Home Assistant has it going: a Ring
 * "Live view" camera only streams (WebRTC, as Home Assistant's own card plays it) and has no
 * stills of its own, so the live view is what fills the card. Any button on the remote hides it (that
 * press only hides it; the next one goes to what is playing again), and once hidden it stays
 * hidden for that ring.
 *
 * Over other apps it needs "display over other apps", which apply.sh grants over ADB.
 */
object Doorbell {
    private const val CHECK_MS = 3000L
    private const val PICTURE_MS = 2000L
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var app: Context? = null
    private var card: View? = null
    private var picture: ImageView? = null
    private var live: android.webkit.WebView? = null
    private var started = false
    private var dismissed = false   // hidden with the remote: not again until the helper goes off

    fun start(ctx: Context) {
        app = ctx.applicationContext
        if (started) return
        started = true
        main.post(check)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)
    private fun ha(ctx: Context): JSONObject? =
        prefs(ctx).getString("ha", null)?.let { try { JSONObject(it) } catch (e: Exception) { null } }
    private fun bell(ctx: Context): JSONObject? = ha(ctx)?.optJSONObject("doorbell")
        ?.takeIf { Regex("input_boolean\\.[a-z0-9_]+").matches(it.optString("helper")) && Regex("camera\\.[a-z0-9_]+").matches(it.optString("camera")) }

    private fun open(h: JSONObject, path: String): HttpURLConnection {
        val c = URL(h.getString("url").trimEnd('/') + path).openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 6000
        c.setRequestProperty("Authorization", "Bearer " + h.optString("token"))
        return c
    }

    private val check = object : Runnable {
        override fun run() {
            val ctx = app ?: return
            main.postDelayed(this, CHECK_MS)
            val awake = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
            val h = ha(ctx); val b = bell(ctx)
            if (!awake || h == null || b == null) { if (card != null) hide(); return }
            io.execute {
                val on = try {
                    val c = open(h, "/api/states/" + b.getString("helper"))
                    val s = if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optString("state") else null
                    c.disconnect(); s == "on"
                } catch (e: Exception) { null }
                if (on == null) return@execute   // Home Assistant not answering: leave things as they are
                main.post {
                    if (!on) dismissed = false
                    if (on && card == null && !dismissed) show(ctx, b) else if (!on && card != null) hide()
                }
            }
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            val ctx = app ?: return
            if (card == null) return
            val h = ha(ctx); val b = bell(ctx)
            if (h != null && b != null) io.execute {
                try {
                    val c = open(h, "/api/camera_proxy/" + b.getString("camera"))
                    val bmp = if (c.responseCode == 200) c.inputStream.use { BitmapFactory.decodeStream(it) } else null
                    c.disconnect()
                    if (bmp != null) main.post { picture?.setImageBitmap(bmp) }
                } catch (e: Exception) {}
            }
            main.postDelayed(this, PICTURE_MS)
        }
    }

    private fun show(ctx: Context, b: JSONObject) {
        if (!Settings.canDrawOverlays(ctx)) return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dp = ctx.resources.displayMetrics.density
        val w = (720 * dp / 1.5f).toInt(); val ht = w * 9 / 16
        val frame = FrameLayout(ctx).apply {
            background = GradientDrawable().apply { setColor(Color.parseColor("#E61C1C21")); cornerRadius = 22 * dp }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, 22 * dp) }
            }
            clipToOutline = true
            elevation = 12 * dp
        }
        val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.BLACK) }
        frame.addView(img, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val video = ha(ctx)?.let { liveView(ctx, it, b.getString("camera")) }
        if (video != null) frame.addView(video, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // the name on a soft dark band along the bottom, as the Keep watching cards
        val band = View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Color.parseColor("#99000000")))
        }
        frame.addView(band, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (56 * dp).toInt(), Gravity.BOTTOM))
        val label = TextView(ctx).apply {
            text = b.optString("name").ifEmpty { "Doorbell" }
            setTextColor(Color.WHITE); textSize = 15f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setShadowLayer(6f, 0f, 1f, Color.parseColor("#80000000"))
            setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), (12 * dp).toInt())
        }
        frame.addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        // it takes the remote while it is up, so a button press can hide it
        frame.isFocusable = true
        frame.setOnKeyListener { _, _, e ->
            if (e.action == android.view.KeyEvent.ACTION_UP) { dismissed = true; hide() }
            true
        }
        val lp = WindowManager.LayoutParams(w, ht, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END
            // just above the Now Playing card's top (60px), so the doorbell covers it completely
            x = (32 * dp).toInt(); y = (26 * dp).toInt()
        }
        try { wm.addView(frame, lp) } catch (e: Exception) { return }
        frame.requestFocus()
        card = frame; picture = img; live = video
        frame.alpha = 0f; frame.translationY = -24 * dp; frame.scaleX = 0.96f; frame.scaleY = 0.96f
        frame.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(420)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0.8f, 0.2f, 1f)).start()
        main.removeCallbacks(refresh); main.post(refresh)
    }

    private fun hide() {
        val v = card ?: return
        card = null; picture = null
        main.removeCallbacks(refresh)
        // ends the camera's live session too (the page's connection closes with it)
        live?.let { w -> main.postDelayed({ try { w.destroy() } catch (e: Exception) {} }, 400) }
        live = null
        val ctx = app ?: return
        v.animate().alpha(0f).translationY(-16 * ctx.resources.displayMetrics.density).setDuration(300).withEndAction {
            try { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Exception) {}
        }.start()
    }

    /** The live picture, as Home Assistant's own card plays it: a small page asks Home Assistant
     *  (its websocket) for a WebRTC session with the camera and plays it, muted. It stays clear
     *  until the video is actually playing, so the snapshot (or black) shows until then. */
    private fun liveView(ctx: Context, h: JSONObject, camera: String): android.webkit.WebView? = try {
        android.webkit.WebView(ctx).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = false; isFocusableInTouchMode = false
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    android.util.Log.i("Doorbell", m.message()); return true
                }
            }
            val base = h.getString("url").trimEnd('/')
            val cfg = JSONObject().put("ws", base.replaceFirst("http", "ws") + "/api/websocket")
                .put("token", h.optString("token")).put("camera", camera)
            loadDataWithBaseURL(base + "/", LIVE_PAGE.replace("/*CFG*/null", cfg.toString()), "text/html", "utf-8", null)
        }
    } catch (e: Exception) { null }   // no WebView on this TV: snapshots only

    private const val LIVE_PAGE = """<!doctype html><html><head><meta name="viewport" content="width=device-width">
<style>html,body{margin:0;height:100%;background:transparent;overflow:hidden}
video{width:100%;height:100%;object-fit:cover;opacity:0;transition:opacity .4s}video.on{opacity:1}</style></head>
<body><video id="v" autoplay muted playsinline></video><script>
var C=/*CFG*/null, v=document.getElementById("v"), id=1, pc=null, sid=null, early=[];
v.addEventListener("playing",function(){v.className="on"});
var ws=new WebSocket(C.ws);
function send(m){m.id=id++;ws.send(JSON.stringify(m));return m.id}
ws.onmessage=function(e){var m=JSON.parse(e.data);
 if(m.type==="auth_invalid"||(m.type==="result"&&!m.success))console.log("live: "+JSON.stringify(m.error||m.type));
 if(m.type==="event"&&m.event&&m.event.type==="error")console.log("live: "+JSON.stringify(m.event));
 if(m.type==="auth_required"){ws.send(JSON.stringify({type:"auth",access_token:C.token}));return}
 if(m.type==="auth_ok"){cfgId=send({type:"camera/webrtc/get_client_config",entity_id:C.camera});return}
 if(m.type==="result"&&m.id===cfgId){start(m.success&&m.result&&m.result.configuration||{});return}
 if(m.type==="event"&&m.event){var ev=m.event;
  if(ev.type==="session"){sid=ev.session_id;early.forEach(cand);early=[]}
  else if(ev.type==="answer"){pc.setRemoteDescription({type:"answer",sdp:ev.answer})}
  else if(ev.type==="candidate"&&ev.candidate){pc.addIceCandidate({candidate:ev.candidate.candidate,sdpMLineIndex:ev.candidate.sdp_m_line_index||0}).catch(function(){})}}
};
var cfgId=0;
function cand(c){send({type:"camera/webrtc/candidate",entity_id:C.camera,session_id:sid,candidate:{candidate:c.candidate,sdp_m_line_index:c.sdpMLineIndex}})}
function start(conf){pc=new RTCPeerConnection(conf);
 pc.addTransceiver("video",{direction:"recvonly"});pc.addTransceiver("audio",{direction:"recvonly"});
 pc.ontrack=function(e){if(!v.srcObject)v.srcObject=e.streams[0]||new MediaStream([e.track])};
 pc.onicecandidate=function(e){if(!e.candidate)return;if(sid)cand(e.candidate);else early.push(e.candidate)};
 pc.createOffer().then(function(o){return pc.setLocalDescription(o)}).then(function(){
  send({type:"camera/webrtc/offer",entity_id:C.camera,offer:pc.localDescription.sdp})})}
</script></body></html>"""
}
