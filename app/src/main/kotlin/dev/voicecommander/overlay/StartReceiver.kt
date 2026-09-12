package dev.voicecommander.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * External start/stop hook for the input service (adb, future quick-settings
 * tile, widgets). The service itself stays exported=false.
 */
class StartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            when (intent.action) {
                ACTION_START -> {
                    context.startForegroundService(Intent(context, InputService::class.java))
                }
                ACTION_STOP -> {
                    context.stopService(Intent(context, InputService::class.java))
                }
                ACTION_INJECT -> {
                    // Debug hook: feed a transcript as if recognized, so the
                    // RAW → INTENT pipeline is drivable headlessly (adb
                    // broadcast with --es text "..."). No mic needed.
                    val text = intent.getStringExtra(EXTRA_TEXT)
                    if (!text.isNullOrBlank()) {
                        InputService.instance?.injectTranscript(text)
                    }
                }
                ACTION_PROBE_TERMUX -> {
                    val text = intent.getStringExtra(EXTRA_TEXT) ?: "probe"
                    InputService.instance?.termuxType(text)
                }
                ACTION_TEST_ECHO -> {
                    android.util.Log.d("VC", "test.echo received: ${intent.getStringExtra(EXTRA_TEXT)}")
                }
                ACTION_A11Y_INJECT -> {
                    val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
                    val ok = dev.voicecommander.input.CommanderAccessibilityService.instance
                        ?.injectToolbarText(text) == true
                    android.util.Log.d("VC", "a11y.inject done=$ok")
                }
                ACTION_TERMUX_SCRIPT -> {
                    // Debug hook: run an arbitrary bash script inside Termux (uid
                    // 10606) via RUN_COMMAND and broadcast its stdout back.
                    val script = intent.getStringExtra(EXTRA_TEXT) ?: ""
                    InputService.instance?.termuxScript(script)
                }
            }
        } catch (e: Exception) {
            // Background FGS starts are restricted on Android 15+; the app UI
            // and adb shell are the supported start paths.
        }
    }

    companion object {
        const val ACTION_START = "dev.voicecommander.START"
        const val ACTION_STOP = "dev.voicecommander.STOP"
        const val ACTION_INJECT = "dev.voicecommander.INJECT"
        const val ACTION_PROBE_TERMUX = "dev.voicecommander.PROBE_TERMUX"
        const val ACTION_TEST_ECHO = "dev.voicecommander.TEST_ECHO"
        const val ACTION_A11Y_INJECT = "dev.voicecommander.A11Y_INJECT"
        const val ACTION_TERMUX_SCRIPT = "dev.voicecommander.TERMUX_SCRIPT"
        const val EXTRA_TEXT = "text"
    }
}