package dev.voicecommander.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.CheckBox
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.voicecommander.AppState
import dev.voicecommander.core.Mode
import dev.voicecommander.overlay.InputService

/**
 * Permission walkthrough + the two real settings (API key, model → language).
 * Every step opens the system screen or dialog; status shows what's missing.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun buildUi() {
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(30, 30, 30))
            setPadding(0, dp(12), 0, 0)
        }
        val keyEdit = EditText(this).apply {
            hint = "OpenAI API key (for INTENT; RAW works without it)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(AppState.apiKey)
        }
        val modelEdit = EditText(this).apply {
            hint = "Model (default gpt-5.6-luna)"
            setText(AppState.model)
        }
        val langEdit = EditText(this).apply {
            hint = "Speech language (e.g. ja-JP, en-US)"
            setText(AppState.language)
        }
        val tmuxEdit = EditText(this).apply {
            hint = "Termux tmux session name (e.g. agent); empty = clipboard+paste"
            setText(AppState.prefs(this@MainActivity).getString("termux_tmux_session", "") ?: "")
        }

        val title = TextView(this).apply {
            text = "VoiceCommander"
            textSize = 22f
            setTextColor(Color.BLACK)
        }
        val subtitle = TextView(this).apply {
            text = "Hold the floating button, speak, release → Send / Edit / Cancel.\nGrant the four permissions below once, then Start."
            textSize = 13f
            setTextColor(Color.rgb(60, 60, 60))
            setPadding(0, dp(4), 0, dp(16))
        }

        val save = button("Save settings") {
            AppState.prefs(this@MainActivity).edit()
                .putString("api_key", keyEdit.text.toString().trim())
                .putString("model", modelEdit.text.toString().trim().ifBlank { "gpt-5.6-luna" })
                .putString("language", langEdit.text.toString().trim().ifBlank { "ja-JP" })
                .apply()
            AppState.apiKey = keyEdit.text.toString().trim() // keep singleton fresh
            val session = tmuxEdit.text.toString().trim()
            if (session.matches(Regex("[a-zA-Z0-9_-]+")) || session.isEmpty()) {
                AppState.prefs(this@MainActivity).edit().putString("termux_tmux_session", session).apply()
            }
            refreshStatus()
            Toast.makeText(this, "saved", Toast.LENGTH_SHORT).show()
        }

        val mic = button("1 · Microphone permission") {
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
        }
        val overlay = button("2 · Display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        val acc = button("3 · Accessibility (delivery into apps)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val notif = button("4 · Notifications (Android 13+)") {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
        }
        val battery = button("5 · Ignore battery optimisations") {
            @Suppress("DEPRECATION")
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                @Suppress("BatteryLife")
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        }
        val start = button("Start input service") {
            startForegroundService(Intent(this, InputService::class.java))
            refreshStatus()
        }
        val stop = button("Stop input service") {
            stopService(Intent(this, InputService::class.java))
            refreshStatus()
        }
        val check = button("Check Status") {
            refreshStatus()
            Toast.makeText(this, "status refreshed", Toast.LENGTH_SHORT).show()
        }
        val termux = button("Termux helper: in Termux run  pkg install termux-api\n            and set  allow-external-apps=true  in ~/.termux/termux.properties") {}

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            addView(title)
            addView(subtitle)
            addView(view("API key")); addView(keyEdit)
            addView(view("Model")); addView(modelEdit)
            addView(view("Language")); addView(langEdit)
            addView(view("Termux tmux session")); addView(tmuxEdit)
            addView(view("Modes"))
            addView(save)
            addView(view("Setup")); addView(mic); addView(overlay); addView(acc); addView(notif); addView(battery)
            addView(view("Service")); addView(start); addView(stop); addView(check)
            addView(view("Termux")); addView(termux)
            addView(status)
        }
        ModesBlock().attach(this@MainActivity, col)
        val scroll = ScrollView(this).apply {
            addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
    }

    private fun view(label: String) = TextView(this).apply {
        text = label
        textSize = 12f
        setTextColor(Color.rgb(120, 120, 120))
        setPadding(0, dp(16), 0, dp(2))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun refreshStatus() {
        val accActive = AppState.isAccessibilityActive()
        val overlayGranted = Settings.canDrawOverlays(this)
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notifGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val runCmd = ContextCompat.checkSelfPermission(this, "com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED
        val tmux = AppState.prefs(this).getString("termux_tmux_session", "") ?: ""
        status.text = """
            mic: ${if (micGranted) "✓" else "✗ need grant"}
            overlay: ${if (overlayGranted) "✓" else "✗ need grant"}
            accessibility: ${if (accActive) "✓" else "✗ need enable"}
            notifications: ${if (notifGranted) "✓" else "✗ need grant (API 33+)"}
            termux RUN_COMMAND: ${if (runCmd) "✓" else "✗ need grant"}
            tmux session: ${if (tmux.isEmpty()) "— not set (falls back to paste)" else tmux}
            service: ${if (AppState.service != null) "running" else "stopped"}
            frontmost: ${AppState.frontmostPackage ?: "(unknown — enable accessibility)"}
        """.trimIndent()
    }
}
/** Per-mode enable/disable section (issue #7): one toggle row per mode,
 *  persisted as a StringSet; at least one mode must stay enabled. */
private class ModesBlock {
    fun attach(activity: MainActivity, col: LinearLayout) {
        val enabled = (AppState.prefs(activity)
            .getStringSet(Mode.ENABLED_KEY, null) ?: Mode.entries.map { it.name }.toSet())
            .toMutableSet()
        Mode.entries.forEach { m ->
            var inToggle = false
            val check = CheckBox(activity).apply {
                text = m.label
                isChecked = m.name in enabled
                setOnCheckedChangeListener { _, checked ->
                    if (inToggle) return@setOnCheckedChangeListener
                    when {
                        checked -> enabled.add(m.name)
                        enabled.size > 1 -> enabled.remove(m.name)
                        else -> {
                            inToggle = true; isChecked = true; inToggle = false
                            Toast.makeText(activity, "keep at least one mode enabled", Toast.LENGTH_SHORT).show()
                            return@setOnCheckedChangeListener
                        }
                    }
                    AppState.prefs(activity).edit().putStringSet(Mode.ENABLED_KEY, enabled).apply()
                    AppState.prefs(activity).edit().putString(Mode.PREF_KEY,
                        Mode.byId(AppState.prefs(activity).getString(Mode.PREF_KEY, Mode.DEFAULT_ID).let {
                            if (it in enabled) it else enabled.first()
                        }).name).apply()
                }
            }
            val desc = TextView(activity).apply {
                text = m.description
                textSize = 11f
                setTextColor(Color.rgb(120, 120, 120))
                setPadding(dp(activity, 36), 0, 0, dp(activity, 6))
            }
            col.addView(check)
            col.addView(desc)
        }
    }
    private fun dp(a: MainActivity, v: Int) = (v * a.resources.displayMetrics.density).toInt()
}
