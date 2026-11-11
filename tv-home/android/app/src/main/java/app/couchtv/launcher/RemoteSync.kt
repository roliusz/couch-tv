package app.couchtv.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Unfolded Circle Remote 3 follows the TV (fork addition). When the TV's screen comes on, by
 * any means (its own remote, Home Assistant, the Remote 3, a timer), the remote's TV activity is
 * marked on, so its buttons drive the TV; when the screen goes off, it is marked off and the remote
 * goes back to its home: the activity is started and stopped with the remote's own commands
 * (Unfolded Circle's REST API, activity.on / activity.off). Its steps are the discrete "TV on" and
 * "TV off"; each is sent a few seconds after the change, so it finds the TV already so and does
 * nothing (stopping the activity on the remote by hand still turns the TV off).
 *
 * uc-remote.json (host, api_key with the remote-ui scope, activity), written by you next to
 * config.json and sent by apply.sh; git ignores it. A remote asleep in its dock may not answer
 * straight away, so "on" is tried again for two minutes, "off" for about half a minute.
 */
object RemoteSync {
    private const val TAG = "RemoteSync"
    private val thread = HandlerThread("remote-sync").apply { start() }
    private val bg = Handler(thread.looper)
    private var started = false
    @Volatile private var want: Boolean? = null
    private var gen = 0

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = follow(app, i.action == Intent.ACTION_SCREEN_ON)
        }, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })
        // the home starting with the TV on (after an update, or the TV starting up): on
        if ((app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) follow(app, true)
    }

    private fun cfg(ctx: Context): JSONObject? =
        ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getString("uc", null)?.let { try { JSONObject(it) } catch (e: Exception) { null } }

    private fun follow(ctx: Context, on: Boolean) {
        val c = cfg(ctx) ?: return
        want = on
        val g = ++gen
        bg.removeCallbacksAndMessages(null)
        val deadline = System.currentTimeMillis() + if (on) 120_000L else 25_000L
        lateinit var attempt: Runnable
        attempt = Runnable {
            if (g != gen || want != on) return@Runnable
            if (send(c, on)) { Log.i(TAG, "activity " + (if (on) "on" else "off")); return@Runnable }
            if (System.currentTimeMillis() < deadline) bg.postDelayed(attempt, if (on) 5000L else 1500L)
            else Log.w(TAG, "the remote did not answer")
        }
        // A few seconds first either way, so the remote's TV connection has seen the TV come on (or
        // go off) and the activity's "TV on" (or "TV off") step finds it already so and does
        // nothing: no power press that could switch the TV straight back. Going off, the TV is
        // kept awake in the background meanwhile so the message still goes out.
        if (!on) try {
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "couchtv:remote-sync").acquire(30_000L)
        } catch (e: Exception) {}
        bg.postDelayed(attempt, if (on) 5000L else 7000L)
    }

    private fun put(host: String, key: String, path: String, body: JSONObject): Int = try {
        val u = URL("http://$host$path").openConnection() as HttpURLConnection
        u.requestMethod = "PUT"; u.doOutput = true; u.connectTimeout = 2500; u.readTimeout = 6000
        u.setRequestProperty("Authorization", "Bearer $key")
        u.setRequestProperty("Content-Type", "application/json")
        u.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = u.responseCode; u.disconnect(); code
    } catch (e: Exception) { -1 }

    private fun send(c: JSONObject, on: Boolean): Boolean {
        val host = c.optString("host"); val key = c.optString("api_key"); val act = c.optString("activity")
        if (host.isEmpty() || key.isEmpty() || !Regex("[A-Za-z0-9_.:-]+").matches(act)) return false
        // The activity started or stopped as from the remote itself, so its screen goes into the
        // activity or back home. Its steps are "TV on" and "TV off" (not a toggle), which do
        // nothing to a TV already in that state.
        val code = put(host, key, "/api/entities/$act/command", JSONObject().put("cmd_id", if (on) "activity.on" else "activity.off"))
        if (code in 200..299) return true
        if (code != 401 && code != 403) { if (code > 0) Log.w(TAG, "the remote said $code"); return code == 409 }
        // a key not allowed to run commands: only mark the state (the screen may stay where it is)
        val c2 = put(host, key, "/api/entities/$act/state", JSONObject().put("state", if (on) "ON" else "OFF"))
        if (c2 !in 200..299 && c2 > 0) Log.w(TAG, "the remote said $c2")
        return c2 in 200..299 || c2 == 409
    }
}
