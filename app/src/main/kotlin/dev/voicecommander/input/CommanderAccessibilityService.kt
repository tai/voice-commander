package dev.voicecommander.input

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle
import dev.voicecommander.AppState
import android.view.accessibility.AccessibilityNodeInfo.*
import android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT
import android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE

/**
 * Two jobs: know which app is frontmost (routing + popup footer), and know the
 * focused editable node (ACTION_SET_TEXT delivery). Reads no other content.
 */
class CommanderAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "VCA11y"
        @Volatile var instance: CommanderAccessibilityService? = null
    }

    override fun onServiceConnected() {
        instance = this
        Log.d(TAG, "service.connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName?.toString() == "dev.voicecommander") return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                // System chrome (status/nav, IMEs) fires window events that would
                // clobber the real target; keep the last genuine app frontmost.
                if (pkg == "com.android.systemui" || pkg.contains("inputmethod")) return
                AppState.frontmostPackage = pkg
                Log.d(TAG, "event.window pkg=$pkg cls=${event.className}")
                AppState.focusedNode = null
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val node = event.source ?: return
                if (node.isEditable) {
                    AppState.focusedNode = AccessibilityNodeInfo.obtain(node)
                    Log.d(TAG, "event.focused editable cls=${event.className}")
                }
            }
        }
    }

    /** Write text into the focused editable node, if one is live right now. */
    fun sendText(text: String): Boolean {
        val node = findFocusedEditableNode() ?: return false
        return try {
            val args = Bundle().apply { putCharSequence(ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            val ok = node.performAction(ACTION_SET_TEXT, args)
            node.recycle()
            ok
        } catch (e: Exception) {
            false
        }
    }

    /** Live lookup: the focused editable node in the active window, or null.
     *  Cached state goes stale while another app churns its views (Chrome
     *  content events), so delivery must re-query rather than trust the cache. */
    fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        return try {
            val root = rootInActiveWindow ?: return null
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: root
            val hit = if (focused.isEditable) nodeOrNull(focused) else null
            root.recycle()
            hit
        } catch (e: Exception) {
            null
        }
    }

    /** Live source of the frontmost package; window-state events are not
     *  reliable for every focus move (Chrome's omnibox never fires one). */
    fun liveFrontmostPackage(): String? = try {
        rootInActiveWindow?.packageName?.toString()
    } catch (e: Exception) {
        null
    }

    /**
     * Termux exposes a toolbar text input (com.termux:id/terminal_toolbar_text_input)
     * that forwards text to the terminal. Test channel for keyless input.
     */
    fun injectToolbarText(text: String): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            val nodes = root.findAccessibilityNodeInfosByViewId("com.termux:id/terminal_toolbar_text_input")
            val node = nodes.firstOrNull() ?: run { root.recycle(); return false }
            val okF = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply { putCharSequence(ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            val okT = node.performAction(ACTION_SET_TEXT, args)
            Log.d(TAG, "injectToolbar focus=$okF settext=$okT")
            node.recycle()
            root.recycle()
            okF && okT
        } catch (e: Exception) {
            Log.d(TAG, "injectToolbar error $e")
            false
        }
    }

    private fun nodeOrNull(n: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        if (n.isEditable) AccessibilityNodeInfo.obtain(n) else null

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        AppState.frontmostPackage = null
        AppState.focusedNode = null
        return super.onUnbind(intent)
    }
}