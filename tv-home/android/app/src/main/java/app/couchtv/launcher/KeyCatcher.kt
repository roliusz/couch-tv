package app.couchtv.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * The remote's quick settings (gear) button opens our Quick Settings (fork addition). On Google TV
 * that button is the dashboard key, which the system hands only to the Google TV home by name;
 * with that home switched off it does nothing. An accessibility service sees a key before the
 * system does, so this one takes that single key and opens (or closes) Quick Settings over
 * whatever is playing. Every other key goes on untouched, and it reads nothing on the screen.
 *
 * The household switches it on themselves: Settings, System (or Device Preferences),
 * Accessibility, "Quick Settings button".
 */
class KeyCatcher : AccessibilityService() {
    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_NOTIFICATION) return false
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            try {
                startActivity(Intent(this, app.couchtv.launcher.home.QuickActivity::class.java)
                    .setAction("android.app.action.TOGGLE_NOTIFICATION_HANDLER_PANEL")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (x: Exception) {}
        }
        return true   // the press and its release are ours
    }
    private var lastGuard = 0L
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (e.packageName?.toString() != GOOGLE_HOME) return
        // The event names the window's view, not the activity. With that home meant to be off,
        // any window of its own means it is back.
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastGuard < 1500) return
        lastGuard = now
        android.util.Log.w("KeyCatcher", "the Google TV home came up: back to couch-tv")
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClassName(this, "app.couchtv.launcher.home.HomeActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        } catch (x: Exception) { android.util.Log.w("KeyCatcher", "guard: $x") }
    }
    override fun onInterrupt() {}

    companion object { const val GOOGLE_HOME = "com.google.android.apps.tv.launcherx" }
}
