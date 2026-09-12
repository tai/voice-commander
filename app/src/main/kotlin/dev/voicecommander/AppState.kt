package dev.voicecommander

import android.content.Context
import android.content.SharedPreferences
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Shared state between the service, the accessibility service and the
 * activities. Prototype-simple: a singleton with the few cross-component
 * values. Settings live in SharedPreferences.
 */
object AppState {
    const val PREFS = "vc"

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- cross-component delivery state ------------------------------------
    var frontmostPackage: String? = null
    var focusedNode: AccessibilityNodeInfo? = null
        set(value) {
            if (field !== value) field?.recycle()
            field = value
        }
    /** Non-null while the review popup is open; EditActivity delivers through it. */
    var deliverAction: ((String) -> Unit)? = null
    var service: android.app.Service? = null

    // --- settings ------------------------------------------------------------
    var apiKey: String
        get() = service?.let { prefs(it).getString("api_key", "") } ?: ""
        set(v) { service?.let { prefs(it).edit().putString("api_key", v).apply() } }

    val model: String
        get() = service?.let { prefs(it).getString("model", "gpt-5.6-luna") } ?: "gpt-5.6-luna"

    val language: String
        get() = service?.let { prefs(it).getString("language", "ja-JP") } ?: "ja-JP"

    fun isAccessibilityActive(): Boolean {
        val ctx = service ?: return false
        return try {
            // Ask the system, don't string-parse the settings: the stored
            // component may be shorthand or fully-qualified depending on who
            // enabled it (Settings UI writes the full form; adb the short).
            val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo?.serviceInfo?.packageName == "dev.voicecommander" }
        } catch (e: Exception) {
            false
        }
    }
}