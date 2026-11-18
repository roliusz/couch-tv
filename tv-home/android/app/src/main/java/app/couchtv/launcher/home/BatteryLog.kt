package app.couchtv.launcher.home


/**
 * The XGIMI projector's battery (fork addition). Android reports no battery there; the exact level
 * comes from XGIMI's power service through the home's own debug connection (SelfAdb.kt,
 * XgimiShell). This holds what needs no connection: whether this is such a device, and XGIMI's
 * rough level for the Google TV home's battery widget. The system log is never read: Android asks
 * about that on every start, which the household does not want.
 */
object BatteryLog {
    /** An exact reading has come in (the rough one stops). */
    @Volatile var got = false

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
}
