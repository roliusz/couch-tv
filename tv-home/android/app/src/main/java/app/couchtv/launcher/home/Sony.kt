package app.couchtv.launcher.home

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The TV's and the soundbar's own network controls (Sony's REST API, "scalar web API").
 *
 * - The TV answers at 127.0.0.1. It reads freely (inputs, speakers, picture), but every change
 *   needs the pre-shared key you set under Settings > Network > Home network > IP control
 *   (sony-psk.txt next to config.json, sent by apply.sh, git ignores it).
 * - The soundbar (an HT-A7000 here) answers on port 10000 and takes changes without a key, as
 *   Home Assistant's Sony soundbar integration does. It is found on the network by its Cast name.
 */
object Sony {
    private val io = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    private const val TV = "http://127.0.0.1"

    class Input(val title: String, val uri: String, val tvInputId: String?)
    class Slider(val target: String, var value: Int, val min: Int, val max: Int)
    class State {
        var output: String? = null                      // speaker, audioSystem, speaker_hdmi, hdmi
        val inputs = ArrayList<Input>()
        var sub: Slider? = null; var rear: Slider? = null
        var night: Boolean? = null; var voice: Boolean? = null
        var pictureMode: String? = null; val pictureModes = ArrayList<String>()
        var brightness: Slider? = null
        var hasKey = false; var bar = false
    }

    fun psk(ctx: Context): String? = ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getString("sony_psk", null)?.takeIf { it.isNotEmpty() }

    private fun call(url: String, method: String, params: Any, version: String, key: String? = null): JSONObject? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 2500; c.readTimeout = 4000; c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        if (key != null) c.setRequestProperty("X-Auth-PSK", key)
        val body = JSONObject().put("method", method).put("id", 1).put("version", version)
            .put("params", if (params is JSONArray) params else JSONArray().put(params))
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val s = (if (c.responseCode < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
        c.disconnect(); s?.let { JSONObject(it) }
    } catch (e: Exception) { null }

    private fun first(o: JSONObject?): JSONArray? = o?.optJSONArray("result")?.optJSONArray(0)

    // ---- the soundbar: found once by its Cast name (HT-…), then remembered ----
    @Volatile private var barIp: String? = null
    private var looking = false
    fun findBar(ctx: Context) {
        val prefs = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)
        if (barIp == null) barIp = prefs.getString("soundbar_ip", null)
        if (looking) return
        looking = true
        val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String, e: Int) { looking = false }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) { looking = false }
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onServiceFound(s: NsdServiceInfo) {
                if (!Regex("^(HT-|HT_|SRS-|HT )", RegexOption.IGNORE_CASE).containsMatchIn(s.serviceName)) return
                try {
                    nsd.resolveService(s, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(s: NsdServiceInfo, e: Int) {}
                        override fun onServiceResolved(s: NsdServiceInfo) {
                            val ip = s.host?.hostAddress ?: return
                            io.execute {
                                if (call("http://$ip:10000/sony/audio", "getVolumeInformation", JSONObject().put("output", ""), "1.1") != null) {
                                    barIp = ip; prefs.edit().putString("soundbar_ip", ip).apply()
                                }
                            }
                        }
                    })
                } catch (e: Exception) {}
            }
        }
        try { nsd.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, l) } catch (e: Exception) { looking = false; return }
        main.postDelayed({ try { nsd.stopServiceDiscovery(l) } catch (e: Exception) {} }, 15000)
    }

    /** Everything the panel shows, read in the background. */
    fun read(ctx: Context, done: (State) -> Unit) {
        val key = psk(ctx)
        io.execute {
            val s = State(); s.hasKey = key != null
            first(call("$TV/sony/audio", "getSoundSettings", JSONObject().put("target", "outputTerminal"), "1.1"))
                ?.optJSONObject(0)?.let { s.output = it.optString("currentValue").ifEmpty { null } }
            first(call("$TV/sony/avContent", "getCurrentExternalInputsStatus", JSONArray(), "1.1"))?.let { a ->
                val seen = HashSet<String>(); val cecPorts = HashSet<String>()
                val all = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optBoolean("connection") }
                for (o in all) { val u = o.optString("uri"); if (u.startsWith("extInput:cec")) Regex("port=(\\d+)").find(u)?.let { cecPorts.add(it.groupValues[1]) } }
                for (o in all) {
                    val u = o.optString("uri"); val t = o.optString("label").ifEmpty { o.optString("title") }
                    val port = Regex("port=(\\d+)").find(u)?.groupValues?.get(1)
                    if (u.startsWith("extInput:hdmi") && port in cecPorts) continue   // the device on it has its own tile
                    if (u.startsWith("extInput:composite")) continue
                    if (!seen.add(t)) continue
                    val id = when {
                        u.startsWith("extInput:cec") -> Regex("logicalAddr=(\\d+)").find(u)?.groupValues?.get(1)?.let { la ->
                            "com.sony.dtv.tvinput.external/.ExternalTvInputService/HDMI$port" + "%05d".format(la.toInt()) }
                        u.startsWith("extInput:hdmi") && port != null -> "com.sony.dtv.tvinput.external/.ExternalTvInputService/HW${port.toInt() + 1}"
                        else -> null
                    }
                    s.inputs.add(Input(t, u, id))
                }
            }
            first(call("$TV/sony/video", "getPictureQualitySettings", JSONObject().put("target", ""), "1.0"))?.let { a ->
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    when (o.optString("target")) {
                        "pictureMode" -> {
                            s.pictureMode = o.optString("currentValue")
                            o.optJSONArray("candidate")?.let { c -> for (j in 0 until c.length()) c.optJSONObject(j)?.optString("value")?.takeIf { it.isNotEmpty() }?.let { s.pictureModes.add(it) } }
                        }
                        "brightness" -> o.optJSONArray("candidate")?.optJSONObject(0)?.let { c ->
                            s.brightness = Slider("brightness", o.optString("currentValue").toIntOrNull() ?: 0, c.optInt("min"), c.optInt("max")) }
                    }
                }
            }
            val ip = barIp
            if (ip != null) first(call("http://$ip:10000/sony/audio", "getSoundSettings", JSONObject().put("target", ""), "1.1"))?.let { a ->
                s.bar = true
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    if (!o.optBoolean("isAvailable", true)) continue
                    val cur = o.optString("currentValue")
                    fun slider(t: String) = o.optJSONArray("candidate")?.optJSONObject(0)?.let { c -> Slider(t, cur.toIntOrNull() ?: 0, c.optInt("min"), c.optInt("max")) }
                    when (o.optString("target")) {
                        "subwooferLevel" -> s.sub = slider("subwooferLevel")
                        "rearspeakerLevel" -> s.rear = slider("rearspeakerLevel")
                        "nightMode" -> s.night = cur == "on"
                        "voice" -> s.voice = cur == "on"
                    }
                }
            }
            main.post { done(s) }
        }
    }

    /** A change on the TV; done(false) when it was refused (no key, or the wrong one). */
    private fun tvSet(ctx: Context, service: String, method: String, params: Any, version: String, done: (Boolean) -> Unit) {
        val key = psk(ctx) ?: run { done(false); return }
        io.execute {
            val r = call("$TV/sony/$service", method, params, version, key)
            val ok = r != null && !r.has("error")
            main.post { done(ok) }
        }
    }

    fun setOutput(ctx: Context, v: String, done: (Boolean) -> Unit) =
        tvSet(ctx, "audio", "setSoundSettings", JSONObject().put("settings", JSONArray().put(JSONObject().put("target", "outputTerminal").put("value", v))), "1.1", done)

    fun setPicture(ctx: Context, target: String, v: String, done: (Boolean) -> Unit) =
        tvSet(ctx, "video", "setPictureQualitySettings", JSONObject().put("settings", JSONArray().put(JSONObject().put("target", target).put("value", v))), "1.0", done)

    fun pictureOff(ctx: Context, done: (Boolean) -> Unit) =
        tvSet(ctx, "system", "setPowerSavingMode", JSONObject().put("mode", "pictureOff"), "1.0", done)

    fun powerOff(ctx: Context, done: (Boolean) -> Unit) =
        tvSet(ctx, "system", "setPowerStatus", JSONObject().put("status", false), "1.0", done)

    /** An input: through the TV's control with the key, else the way any app opens one. */
    fun input(ctx: Context, i: Input, done: (Boolean) -> Unit) {
        if (psk(ctx) != null) { tvSet(ctx, "avContent", "setPlayContent", JSONObject().put("uri", i.uri), "1.0") { ok -> if (ok) done(true) else done(open(ctx, i)) }; return }
        done(open(ctx, i))
    }
    private fun open(ctx: Context, i: Input): Boolean {
        val id = i.tvInputId ?: return false
        return try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (e: Exception) { false }
    }

    /** A soundbar setting (no key needed). */
    fun bar(target: String, v: String, done: (Boolean) -> Unit) {
        val ip = barIp ?: run { done(false); return }
        io.execute {
            val r = call("http://$ip:10000/sony/audio", "setSoundSettings", JSONObject().put("settings", JSONArray().put(JSONObject().put("target", target).put("value", v))), "1.1")
            val ok = r != null && !r.has("error")
            main.post { done(ok) }
        }
    }
}
