package app.couchtv.launcher.home

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * The XGIMI projector's battery (fork addition). Android reports no battery there: XGIMI reads it
 * from its own hardware service, which ordinary apps cannot reach, and its battery display writes
 * each change to the system log ("BatteryView: refreshBatteryState() called with: batteryLevel =
 * 63, isCharging = false"). With the household's leave to read the log (READ_LOGS, granted by
 * apply.sh with READ_LOGS=1), this follows those lines only, the last one at start and each new
 * one after, and ignores everything else.
 */
object BatteryLog {
    private const val TAG = "BatteryLog"
    private val main = Handler(Looper.getMainLooper())
    private val line = Regex("""batteryLevel = (\d{1,3}), isCharging = (true|false)""")
    private val status = Regex("""BatteryStatus\(charging=(true|false), level=(\d{1,3})""")
    @Volatile private var running = false
    /** An exact reading has come in (the rough one stops). */
    @Volatile var got = false
    @Volatile private var procs = listOf<Process>()
    private var last: ((Int, Boolean) -> Unit)? = null
    /** Ask again (the household missed Android's question): the reading starts over. */
    fun restart() { procs.forEach { try { it.destroy() } catch (e: Exception) {} }; running = false; last?.let { start(it) } }

    /** Whether this device keeps its battery to itself (an XGIMI with no battery in Android). */
    fun wanted(): Boolean = android.os.Build.MANUFACTURER.equals("XGIMI", ignoreCase = true)

    /** XGIMI's rough level for the Google TV home's battery widget (empty, low, high, full, and
     *  charging), which needs no question: shown until the first exact reading. -1 when unreadable. */
    fun rough(ctx: android.content.Context): Pair<Int, Boolean>? = try {
        ctx.contentResolver.query(android.net.Uri.parse("content://tvlauncher.widget/widget"), null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val icon = c.getString(c.getColumnIndexOrThrow("icon")) ?: return null
            val lv = when { "full" in icon -> 100; "high" in icon -> 70; "low" in icon -> 30; "empty" in icon -> 8; else -> return null }
            lv to ("charging" in icon)
        }
    } catch (e: Exception) { null }

    fun start(done: (Int, Boolean) -> Unit) {
        if (running || !wanted()) return
        running = true; last = done
        Thread({
            fun parse(s: String): Pair<Int, Boolean>? {
                line.find(s)?.let { return it.groupValues[1].toInt() to (it.groupValues[2] == "true") }
                status.find(s)?.let { return it.groupValues[2].toInt() to (it.groupValues[1] == "true") }
                return null
            }
            try {
                // one reader only (Android asks the household once per reader): what the log already
                // holds, then each new line as XGIMI writes it
                val p = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "raw", "-s", "BatteryView:D")).also { procs = listOf(it) }
                BufferedReader(InputStreamReader(p.inputStream)).useLines { ls -> ls.forEach { s -> parse(s)?.let { (l, c) -> got = true; main.post { done(l, c) } } } }
            } catch (e: Exception) { Log.w(TAG, "the log could not be read: $e") }
            running = false
        }, "battery-log").apply { isDaemon = true }.start()
    }
}
