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
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
