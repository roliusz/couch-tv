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
    @Volatile private var procs = listOf<Process>()
    private var last: ((Int, Boolean) -> Unit)? = null
    /** Ask again (the household missed Android's question): the reading starts over. */
    fun restart() { procs.forEach { try { it.destroy() } catch (e: Exception) {} }; running = false; last?.let { start(it) } }

    /** Whether this device keeps its battery to itself (an XGIMI with no battery in Android). */
    fun wanted(): Boolean = android.os.Build.MANUFACTURER.equals("XGIMI", ignoreCase = true)

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
                // the last reading already in the log
                val past = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "raw", "-s", "BatteryView:D")).also { procs = listOf(it) }
                var last: Pair<Int, Boolean>? = null
                BufferedReader(InputStreamReader(past.inputStream)).useLines { ls -> ls.forEach { s -> parse(s)?.let { last = it } } }
                Log.i(TAG, "last reading: " + (last?.first ?: "none"))
                last?.let { (l, c) -> main.post { done(l, c) } }
                // then each new one, as XGIMI writes it
                val p = Runtime.getRuntime().exec(arrayOf("logcat", "-T", "1", "-v", "raw", "-s", "BatteryView:D")).also { procs = listOf(it) }
                BufferedReader(InputStreamReader(p.inputStream)).useLines { ls -> ls.forEach { s -> parse(s)?.let { (l, c) -> main.post { done(l, c) } } } }
            } catch (e: Exception) { Log.w(TAG, "the log could not be read: $e") }
            running = false
        }, "battery-log").apply { isDaemon = true }.start()
    }
}
