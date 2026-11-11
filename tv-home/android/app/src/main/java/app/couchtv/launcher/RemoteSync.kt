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
 * goes back to its home. Only the activity's state is set: its on and off steps are not run, so no
 * power command reaches the TV (Unfolded Circle's REST API: PUT /api/entities/{id}/state).
 *
 * uc-remote.json (host, api_key with the remote-ui scope, activity), written by you next to
 * config.json and sent by apply.sh; git ignores it. A remote asleep in its dock may not answer
 * straight away, so "on" is tried again for two minutes; "off" goes out at once, while the TV's
 * network is still up.
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
        val deadline = System.currentTimeMillis() + if (on) 120_000L else 15_000L
        lateinit var attempt: Runnable
        attempt = Runnable {
            if (g != gen || want != on) return@Runnable
            if (send(c, on)) { Log.i(TAG, "activity " + (if (on) "on" else "off")); return@Runnable }
            if (System.currentTimeMillis() < deadline) bg.postDelayed(attempt, if (on) 5000L else 1500L)
            else Log.w(TAG, "the remote did not answer")
        }
        bg.post(attempt)
    }

    private fun send(c: JSONObject, on: Boolean): Boolean = try {
        val host = c.optString("host"); val key = c.optString("api_key"); val act = c.optString("activity")
        if (host.isEmpty() || key.isEmpty() || !Regex("[A-Za-z0-9_.:-]+").matches(act)) false
        else {
            val u = URL("http://$host/api/entities/$act/state").openConnection() as HttpURLConnection
            u.requestMethod = "PUT"; u.doOutput = true; u.connectTimeout = 2500; u.readTimeout = 4000
            u.setRequestProperty("Authorization", "Bearer $key")
            u.setRequestProperty("Content-Type", "application/json")
            u.outputStream.use { it.write(JSONObject().put("state", if (on) "ON" else "OFF").toString().toByteArray()) }
            val code = u.responseCode; u.disconnect()
            if (code !in 200..299) Log.w(TAG, "the remote said $code")
            // 409: a sequence of that activity is running (someone started it on the remote): leave it
            code in 200..299 || code == 409
        }
    } catch (e: Exception) { false }
}
