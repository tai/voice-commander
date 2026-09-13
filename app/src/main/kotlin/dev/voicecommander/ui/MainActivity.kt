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
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.voicecommander.AppState
import dev.voicecommander.core.Mode
import dev.voicecommander.core.Mode.Companion.effectivePrompt
import dev.voicecommander.overlay.InputService

/**
 * Tabbed configuration UI: General / Mode / Status.
 * Plain views, no dependencies. Status tab holds the permission walkthrough
 * and readiness check; Mode tab holds enable/disable + per-mode prompt editor.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lateinit var titleView: TextView
        val names = listOf("General", "Mode", "Status")
        val panels = listOf(
            ScrollView(this).apply { addView(panelGeneral()) },
            ScrollView(this).apply { addView(panelMode()) },
            ScrollView(this).apply { addView(panelStatus()) },
        )
        val container = FrameLayout(this).apply { panels.forEach { addView(it) } }

        // --- drawer ------------------------------------------------------------
        val drawerItems = names.map { name ->
            TextView(this).apply {
                text = name
                textSize = 16f
                setPadding(dp(20), dp(16), dp(20), dp(16))
            }
        }
        val drawerHead = TextView(this).apply {
            text = "VoiceCommander · config"
            textSize = 15f
            setTextColor(Color.BLACK)
            setPadding(dp(20), dp(24), dp(20), dp(12))
        }
        val drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
            addView(drawerHead)
            drawerItems.forEach { addView(it) }
        }
        val drawerLp = FrameLayout.LayoutParams(dp(260).toInt(), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
        val scrim = View(this).apply {
            setBackgroundColor(0x66000000)
            visibility = View.GONE
        }
        container.addView(scrim, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        container.addView(drawer, drawerLp)

        fun style(i: Int) {
            drawerItems.forEachIndexed { idx, v ->
                v.setBackgroundColor(if (idx == i) Color.rgb(224, 231, 238) else Color.TRANSPARENT)
                v.setTextColor(if (idx == i) Color.BLACK else Color.rgb(60, 60, 60))
            }
        }

        fun closeDrawer() {
            scrim.visibility = View.GONE
            drawer.visibility = View.GONE
        }

        fun show(i: Int) {
            android.util.Log.d("VCMain", "nav ${names[i]}")
            titleView.text = names[i]
            style(i)
            panels.forEachIndexed { c, p -> p.visibility = if (c == i) View.VISIBLE else View.GONE }
            closeDrawer()
        }
        drawerItems.forEachIndexed { i, v -> v.setOnClickListener { show(i) } }
        scrim.setOnClickListener { closeDrawer() }

        val hamburger = TextView(this).apply {
            text = "☰"
            textSize = 22f
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        titleView = TextView(this).apply {
            text = names[0]
            textSize = 18f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER_VERTICAL
        }
        fun openDrawer() {
            scrim.visibility = View.VISIBLE
            drawer.visibility = View.VISIBLE
        }
        hamburger.setOnClickListener { openDrawer() }
        val topbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(hamburger)
            addView(titleView)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(topbar)
            addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val sid = resources.getIdentifier("status_bar_height", "dimen", "android")
        val sb = if (sid > 0) resources.getDimensionPixelSize(sid) else 0
        root.setPadding(0, sb, 0, 0)   // keep the topbar below the status bar
        setContentView(root)
        show(0)
    }

    // ---------------------------------------------------------------- General

    private fun panelGeneral(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val title = TextView(this).apply {
            text = "VoiceCommander"
            textSize = 22f
            setTextColor(Color.BLACK)
        }
        val subtitle = TextView(this).apply {
            text = "Speak a task — the intent goes to your agent (or document)."
            textSize = 13f
            setTextColor(Color.rgb(60, 60, 60))
            setPadding(0, dp(4), 0, dp(16))
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
        val save = button("Save settings") {
            AppState.prefs(this@MainActivity).edit()
                .putString("api_key", keyEdit.text.toString().trim())
                .putString("model", modelEdit.text.toString().trim().ifBlank { "gpt-5.6-luna" })
                .putString("language", langEdit.text.toString().trim().ifBlank { "ja-JP" })
                .putString("termux_tmux_session",
                    tmuxEdit.text.toString().trim().takeIf { it.matches(Regex("[a-zA-Z0-9_-]+")) } ?: "")
                .apply()
            AppState.apiKey = keyEdit.text.toString().trim()
            Toast.makeText(this, "saved", Toast.LENGTH_SHORT).show()
        }
        col.addView(title)
        col.addView(subtitle)
        col.addView(view("API key")); col.addView(keyEdit)
        col.addView(view("Model")); col.addView(modelEdit)
        col.addView(view("Language")); col.addView(langEdit)
        col.addView(view("Termux tmux session")); col.addView(tmuxEdit)
        col.addView(save)
        return col
    }

    // ---------------------------------------------------------------- Mode

    private fun panelMode(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        col.addView(TextView(this).apply {
            text = "Modes"
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, dp(10))
        })
        val enabled = (AppState.prefs(this)
            .getStringSet(Mode.ENABLED_KEY, null) ?: Mode.entries.map { it.name }.toSet())
            .toMutableSet()
        Mode.entries.forEach { m ->
            var inToggle = false
            val check = CheckBox(this).apply {
                text = m.label
                isChecked = m.name in enabled
                setOnCheckedChangeListener { _, checked ->
                    if (inToggle) return@setOnCheckedChangeListener
                    when {
                        checked -> enabled.add(m.name)
                        enabled.size > 1 -> enabled.remove(m.name)
                        else -> {
                            inToggle = true; isChecked = true; inToggle = false
                            Toast.makeText(this@MainActivity, "keep at least one mode", Toast.LENGTH_SHORT).show()
                            return@setOnCheckedChangeListener
                        }
                    }
                    AppState.prefs(this@MainActivity).edit().putStringSet(Mode.ENABLED_KEY, enabled).apply()
                }
            }
            col.addView(check)
            col.addView(TextView(this).apply {
                text = m.description
                textSize = 11f
                setTextColor(Color.rgb(120, 120, 120))
                setPadding(dp(36), 0, 0, dp(6))
            })

            val editor = EditText(this).apply {
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                minLines = 6
                gravity = Gravity.TOP
                setText(effectivePrompt(m, AppState.prefs(this@MainActivity).getString(promptKey(m), "")))
                visibility = View.GONE
            }
            val saveP = button("Save prompt") {
                AppState.prefs(this@MainActivity)
                    .edit().putString(promptKey(m), editor.text.toString()).apply()
                Toast.makeText(this, "prompt saved", Toast.LENGTH_SHORT).show()
            }.apply { visibility = View.GONE }
            val resetP = button("Restore default prompt") {
                AppState.prefs(this@MainActivity).edit().remove(promptKey(m)).apply()
                editor.setText(m.prompt)
                Toast.makeText(this, "default restored", Toast.LENGTH_SHORT).show()
            }.apply { visibility = View.GONE }
            val editBtn = button("Edit prompt") {
                val show = editor.visibility != View.VISIBLE
                editor.visibility = if (show) View.VISIBLE else View.GONE
                saveP.visibility = if (show) View.VISIBLE else View.GONE
                resetP.visibility = if (show) View.VISIBLE else View.GONE
            }
            col.addView(editBtn)
            col.addView(editor)
            col.addView(saveP)
            col.addView(resetP)
            col.addView(view(" ")) // spacing
        }
        return col
    }

    private fun promptKey(m: Mode) = "prompt_${m.name}"

    // ---------------------------------------------------------------- Status

    private fun panelStatus(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(30, 30, 30))
            setPadding(0, dp(12), 0, 0)
        }
        val mic = button("1 · Microphone permission") {
            if (Build.VERSION.SDK_INT >= 23) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        val overlay = button("2 · Display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        val acc = button("3 · Accessibility (delivery into apps)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val notif = button("4 · Notifications (Android 13+)") {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        val battery = button("5 · Ignore battery optimisations") {
            @Suppress("DEPRECATION", "BatteryLife")
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
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
        col.addView(view("Setup"))
        col.addView(mic); col.addView(overlay); col.addView(acc); col.addView(notif); col.addView(battery)
        col.addView(view("Service"))
        col.addView(start); col.addView(stop); col.addView(check)
        col.addView(status)
        return col
    }

    // ---------------------------------------------------------------- shared

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

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        if (!::status.isInitialized) return
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