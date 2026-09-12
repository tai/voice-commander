package dev.voicecommander.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.voicecommander.AppState
import dev.voicecommander.R
import dev.voicecommander.core.Directive
import dev.voicecommander.core.DirectiveParser
import dev.voicecommander.core.IntentScheduler
import dev.voicecommander.core.IntentPrompt
import dev.voicecommander.core.Route
import dev.voicecommander.core.Router
import dev.voicecommander.core.TranscriptBuffer
import dev.voicecommander.input.CommanderAccessibilityService
import dev.voicecommander.openai.OpenAIClient
import dev.voicecommander.ui.EditActivity
import dev.voicecommander.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient

/**
 * The whole input surface in one service: floating hold-button, RAW/INTENT
 * panel, review popup, recognizer session, interpretation and delivery.
 * Prototype keeps it together; split out when it grows.
 */
class InputService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private val buffer = TranscriptBuffer()

    private fun log(m: String) = Log.d(TAG, m)

    private var buttonView: View? = null
    private var panelView: ViewGroup? = null
    private var popupView: ViewGroup? = null
    private var rawView: TextView? = null
    private var intentView: TextView? = null
    private var statusView: TextView? = null
    private var popupPreview: TextView? = null
    private var popupTag: TextView? = null
    private var popupTarget: TextView? = null

    private var recognizer: SpeechRecognizer? = null
    private var scheduler: IntentScheduler? = null
    private val http = OkHttpClient()

    private var sessionId = 0
    private var sessionActive = false
    private var popupOpen = false
    private var delivering = false
    private var slideOff = false
    private var intentText: String? = null
    private var intentRevision = 0L
    private var autoClosePopup = Runnable {}

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppState.service = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundInternal()
        setupScheduler()
        addButton()
        AppState.deliverAction = null
    }

    override fun onDestroy() {
        instance = null
        cancelSession(showPopup = false)
        closePanel()
        closePopup()
        buttonView?.let { runCatching { wm.removeView(it) } }
        recognizer?.destroy()
        scheduler?.reset()
        scope.cancel()
        AppState.service = null
        AppState.deliverAction = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    // ------------------------------------------------------------------ session

    private fun startSession() {
        log("session.start")
        closePopup()          // a new session invalidates a pending popup
        sessionId++
        buffer.reset()
        intentText = null
        intentRevision = 0
        sessionActive = true
        showPanel()
        setStatus("listening…")
        val sr = (recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it })
        sr.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, AppState.language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { sr.startListening(intent) }.onFailure {
            setStatus("recognizer start failed")
        }
    }

    /** Button released in bounds: review popup with the current buffer. */
    private fun endSession() {
        log("session.end buffer=${buffer.text} intent=${intentText}")
        stopRecognizer()
        sessionActive = false
        closePanel()
        if (buffer.text.isEmpty() && intentText == null) {
            toast("nothing heard")
            return
        }
        showPopup()
    }

    /** Slide-off or gesture cancel: discard everything, no popup. */
    private fun cancelSession(showPopup: Boolean) {
        stopRecognizer()
        sessionActive = false
        buffer.reset()
        intentText = null
        closePanel()
        if (showPopup) {
            // error path entry is handled by the caller; silent here
        }
    }

    private fun stopRecognizer() {
        runCatching { recognizer?.stopListening() }
    }

    // ------------------------------------------------------------------ recognizer

    /** Debug/injection entry: a transcript line as if recognized. */
    fun injectTranscript(text: String) {
        log("inject.transcript=$text")
        main.post {
            log("inject.apply sessionActive=$sessionActive popupOpen=$popupOpen")
            if (!sessionActive && !popupOpen) return@post
            buffer.onFinal(text)
            onTranscriptChanged()
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onResults(results: android.os.Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?: return
            buffer.onFinal(text)
            onTranscriptChanged()
        }
        override fun onPartialResults(partialResults: android.os.Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?: return
            buffer.onPartial(text)
            onTranscriptChanged()
        }
        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        override fun onError(error: Int) {
            if (!sessionActive && !popupOpen) return
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "no speech heard"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "timeout"
                else -> "recognizer error"
            }
            setStatus(msg)
            if (sessionActive) {
                sessionActive = false
                closePanel()
                if (buffer.text.isNotEmpty() && popupOpen.not()) showPopup()
            }
        }
    }

    private fun onTranscriptChanged() {
        val raw = buffer.text
        if (sessionActive && rawView != null) rawView?.text = raw
        if (popupOpen && intentText == null) popupPreview?.text = raw  // live RAW fallback
        if (sessionActive || popupOpen) scheduler?.submit(raw)
    }

    // ------------------------------------------------------------------ interpretation

    private fun setupScheduler() {
        scheduler = IntentScheduler(
            debounceMs = 700,
            scope = scope,
            interpret = { raw ->
                val parsed = DirectiveParser.parse(raw)
                if (parsed.directive == Directive.VERBATIM) return@IntentScheduler parsed.content
                val key = AppState.apiKey.ifBlank { return@IntentScheduler null }
                val sys = IntentPrompt.SYSTEM + when (parsed.directive) {
                    Directive.SHORTER -> "\nKeep it as short as possible."
                    Directive.ENGLISH -> "\nOutput in English."
                    else -> ""
                }
                val result = OpenAIClient(http, key, AppState.model)
                    .interpret(sys, parsed.content)
                result.exceptionOrNull()?.let { log("intent.error ${it.message?.take(120)}") }
                result.getOrNull()?.ifBlank { null }
            },
            onIntent = { text, rev -> main.post { applyIntent(text, rev) } },
            onError = { m ->
                main.post {
                    log("intent.error $m")
                    if (sessionActive || popupOpen) setStatus(m)
                }
            },
        )
    }

    /** Runs on the main thread via the scheduler's marshalled callback. */
    private fun applyIntent(text: String, rev: Long) {
        if (rev > intentRevision) {
            intentRevision = rev
            intentText = text
            log("intent.ready rev=$rev len=${text.length} text=${text.take(80)}")
        }
        if (sessionActive && intentView != null) intentView?.text = text
        if (popupOpen && delivering.not()) showPopupContent(text, tag = "interpreted")
    }

    // ------------------------------------------------------------------ UI helpers

    private fun card(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.argb(235, 20, 20, 22))
        cornerRadius = dp(18f)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun addButton() {
        val b = TextView(this).apply {
            text = "🎤"
            textSize = 22f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.argb(200, 0xE6, 0x39, 0x46))
                shape = GradientDrawable.OVAL
            }
        }
        val sz = dp(64f).toInt()
        val prefs = AppState.prefs(this)
        val savedX = prefs.getInt("button_x", Int.MIN_VALUE)
        val savedY = prefs.getInt("button_y", Int.MIN_VALUE)
        val lp = WindowManager.LayoutParams(
            sz, sz,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        if (savedX != Int.MIN_VALUE) {
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = savedX
            lp.y = savedY
        } else {
            lp.apply { gravity = Gravity.BOTTOM or Gravity.END; x = 0; y = dp(72f).toInt() }
        }
        val dragSlop = dp(56f)  // ~button radius: natural hold jitter must NOT drag
        val dm = resources.displayMetrics
        var downX = 0f
        var downY = 0f
        var dragging = false
        b.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    dragging = false
                    slideOff = false
                    startSession()   // listening starts immediately; drag repositions
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && Math.abs(e.rawX - downX) + Math.abs(e.rawY - downY) > dragSlop) {
                        dragging = true   // finger moved: reposition the button, keep listening
                    }
                    if (dragging) {
                        lp.gravity = Gravity.TOP or Gravity.START
                        lp.x = (e.rawX - sz / 2f).toInt().coerceIn(0, dm.widthPixels - sz)
                        lp.y = (e.rawY - sz / 2f).toInt().coerceIn(0, dm.heightPixels - sz)
                        runCatching { wm.updateViewLayout(b, lp) }
                    } else {
                        val bv = buttonView ?: return@setOnTouchListener true
                        val loc = IntArray(2)
                        bv.getLocationOnScreen(loc)
                        val out = e.rawX < loc[0] || e.rawX > loc[0] + bv.width ||
                            e.rawY < loc[1] || e.rawY > loc[1] + bv.height
                        if (out) slideOff = true   // slide-off still cancels when not dragging
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        // Reposition done: keep the session and still review it.
                        AppState.prefs(this).edit().putInt("button_x", lp.x).putInt("button_y", lp.y).apply()
                    }
                    if (slideOff) cancelSilently() else endSession()
                    dragging = false
                    slideOff = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelSilently()
                    dragging = false
                    slideOff = false
                }
            }
            true
        }
        try {
            wm.addView(b, lp)
            buttonView = b
        } catch (e: Exception) {
            toast("grant Display over other apps")
        }
    }

    private fun cancelSilently() {
        stopRecognizer()
        sessionActive = false
        buffer.reset()
        intentText = null
        closePanel()
    }

    private fun showPanel() {
        if (panelView != null) return
        val rawLabel = label("RAW")
        val raw = TextView(this).apply { textSize = 13f; setTextColor(Color.WHITE) }
        val intentLabel = label("INTENT")
        val intent = TextView(this).apply { textSize = 13f; setTextColor(0xFF9FE8A6.toInt()) }
        val status = TextView(this).apply { textSize = 11f; setTextColor(0xFFBBBBBB.toInt()) }
        rawView = raw; intentView = intent; statusView = status

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f).toInt(), dp(12f).toInt(), dp(14f).toInt(), dp(12f).toInt())
            background = card()
            addView(rawLabel); addView(raw)
            addView(intentLabel); addView(intent)
            addView(status)
        }
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.END; x = 0; y = dp(150f).toInt() }
        try {
            wm.addView(box, lp)
            panelView = box
        } catch (e: Exception) { /* overlay permission missing */ }
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 10f
        setTextColor(0xFF888888.toInt())
        setPadding(0, dp(8f).toInt(), 0, 2)
    }

    private fun setStatus(s: String) {
        statusView?.text = s
    }

    private fun closePanel() {
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null; rawView = null; intentView = null; statusView = null
    }

    // ------------------------------------------------------------------ review popup

    private fun showPopup() {
        log("popup.show text=${intentText ?: buffer.text}") 
        popupOpen = true
        delivering = false
        val tag = if (intentText != null) "interpreted" else "raw"
        showPopupContent(intentText ?: buffer.text, tag)
        autoClosePopup = Runnable { if (popupOpen && !delivering) closePopup() }
        main.postDelayed(autoClosePopup, AUTO_CLOSE_MS)
    }

    private fun showPopupContent(text: String, tag: String) {
        if (popupOpen.not()) return
        if (popupView == null) buildPopup()
        popupPreview?.text = text
        popupTag?.text = tag
        popupTarget?.text = "→ ${targetLabel()}"
    }

    private fun buildPopup() {
        val tag = TextView(this).apply { textSize = 10f; setTextColor(0xFF888888.toInt()) }
        popupTag = tag
        val preview = TextView(this).apply {
            textSize = 16f; setTextColor(Color.WHITE)
            setPadding(0, dp(4f).toInt(), 0, dp(8f).toInt())
        }
        popupPreview = preview
        val target = TextView(this).apply { textSize = 11f; setTextColor(0xFFBBBBBB.toInt()) }
        popupTarget = target

        val send = pill("Send", 0xFF2E7D32.toInt()) { deliverAndClose() }
        val edit = pill("Edit", 0xFF37474F.toInt()) { startEdit() }
        val cancel = pill("Cancel", 0xFFB71C1C.toInt()) { closePopup() }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(send); addView(edit); addView(cancel)
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f).toInt(), dp(14f).toInt(), dp(16f).toInt(), dp(14f).toInt())
            background = card()
            val lp0 = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(tag, lp0); addView(preview, lp0); addView(target, lp0)
            addView(row, lp0.apply { topMargin = dp(10f).toInt() })
        }
        val lp = WindowManager.LayoutParams(
            dp(300f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        runCatching { wm.addView(box, lp) }.onFailure { popupOpen = false }
        popupView = box
    }

    private fun pill(text: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(22f).toInt(), dp(10f).toInt(), dp(22f).toInt(), dp(10f).toInt())
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(22f) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun closePopup() {
        main.removeCallbacks(autoClosePopup)
        popupOpen = false
        delivering = false
        AppState.deliverAction = null
        popupView?.let { runCatching { wm.removeView(it) } }
        popupView = null; popupPreview = null; popupTag = null; popupTarget = null
    }

    // ------------------------------------------------------------------ Send

    private fun deliverAndClose() {
        val text = popupPreview?.text?.toString()?.trim() ?: return
        delivering = true
        deliver(text)
        closePopup()
        scheduler?.reset()   // nothing from this session may revise what was sent
    }

    private fun startEdit() {
        AppState.deliverAction = { text -> deliver(text); closePopup() }
        val i = Intent(this, EditActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        i.putExtra(EXTRA_TEXT, popupPreview?.text?.toString() ?: "")
        startActivity(i)
        // popup stays; EditActivity delivers on Send, back returns here
    }

    private fun deliver(text: String) {
        val a11y = CommanderAccessibilityService.instance
        val hasLiveEditableNode = a11y?.findFocusedEditableNode() != null
        val front = Router.effectiveFrontmost(
            cached = AppState.frontmostPackage,
            live = a11y?.liveFrontmostPackage(),
            ownPackage = packageName,
        )
        val route = Router.routeFor(front, hasLiveEditableNode, AppState.isAccessibilityActive(), packageName)
        log("deliver route=${route.name} frontmost=$front focusable=$hasLiveEditableNode")
        when (route) {
            Route.TERMUX -> if (!termuxType(text)) clipboard(text, "Termux busy — copied instead")
            Route.FOCUSED_FIELD -> {
                val ok = CommanderAccessibilityService.instance?.sendText(text) == true
                if (ok) toast("sent to ${AppState.frontmostPackage}")
                else clipboard(text, "no text field — copied instead")
            }
            Route.CLIPBOARD -> clipboard(text, "copied to clipboard — paste where you want")
        }
    }

    private fun clipboard(text: String, msg: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("vc", text))
        toast(msg)
    }

    /**
     * Writes the INTENT into the agent's terminal: RUN_COMMAND executes bash
     * as the Termux user, which can open the foreground session's pty slave
     * (/proc/<pid>/fd/0 → /dev/pts/N) and feed bytes into it — as if typed.
     * No Enter is appended: the user presses Return in the terminal.
     *
     * Needs com.termux.permission.RUN_COMMAND (manifest) and
     * allow-external-apps=true.
     * Note: termux-input does NOT exist in current termux-api (verified in the
     * v0.59.1 package listing); the pty write replaces it.
     */
    /** Run an arbitrary script in Termux (uid 10606) and broadcast stdout back. */
    fun termuxScript(script: String) {
        val d = "${'$'}"
        val wrapped = "OUT=\$(bash -c \"\$1\" 2>&1); am broadcast -a dev.voicecommander.TEST_ECHO -n dev.voicecommander/.overlay.StartReceiver --es text \"out=\$OUT\""
        val i = Intent("com.termux.RUN_COMMAND").apply {
            setPackage("com.termux")
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", wrapped, "vc", script))
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        startService(i)
    }

    fun termuxType(text: String): Boolean {
        val granted = checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED
        val session = AppState.prefs(this).getString("termux_tmux_session", "")?.trim().orEmpty()
        val sessionOk = session.matches(Regex("[a-zA-Z0-9_-]+"))
        log("termux.sending granted=$granted tmux=${if (sessionOk) session else "-"} text=${text.take(40)}")
        return try {
            val d = "${'$'}"  // literal $ for the bash script
            val i = Intent("com.termux.RUN_COMMAND").apply {
                setPackage("com.termux")
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            }
            if (sessionOk) {
                // tmux delivers into the SESSION's real input (same-uid socket):
                // literal text + a separate Enter key. Verified on-device.
                val script = """if tmux has-session -t ${session} 2>/dev/null; then
  tmux send-keys -t ${session} -l "${d}1"
  tmux send-keys -t ${session} Enter
  am broadcast -a dev.voicecommander.TEST_ECHO -n dev.voicecommander/.overlay.StartReceiver --es text "tmux-sent" >/dev/null 2>&1
fi
"""
                i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script, "vc", text))
                startService(i)
                toast("sent to ${session} (tmux)")
            } else {
                // v1 fallback: clipboard + visual echo; user pastes in Termux.
                val tty = AppState.prefs(this).getString("termux_tty", "")?.trim().orEmpty()
                if (tty.isNotEmpty()) {
                    val script = "printf '%s\\n' \"${d}1\" > ${tty}"
                    i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script, "vc", text))
                    startService(i)
                }
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("vc", text))
                toast("INTENT ready — long-press in Termux → Paste → Return")
            }
            true
        } catch (e: Exception) {
            log("termux.send.failed $e")
            false
        }
    }

    private fun targetLabel(): String {
        val a11y = CommanderAccessibilityService.instance
        val front = Router.effectiveFrontmost(
            cached = AppState.frontmostPackage,
            live = a11y?.liveFrontmostPackage(),
            ownPackage = packageName,
        )
        return when (Router.routeFor(front, a11y?.findFocusedEditableNode() != null, AppState.isAccessibilityActive(), packageName)) {
            Route.TERMUX -> "Termux"
            Route.FOCUSED_FIELD -> front ?: "focused app"
            Route.CLIPBOARD -> "clipboard"
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ foreground service

    private fun startForegroundInternal() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Input", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, InputService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_mic)
                .setContentTitle("VoiceCommander")
                .setContentText("Hold the floating button to speak a task")
                .setContentIntent(open)
                .addAction(0, "Stop", stop)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_stat_mic)
                .setContentTitle("VoiceCommander")
                .setContentText("Hold the floating button to speak a task")
                .setContentIntent(open)
                .addAction(0, "Stop", stop)
                .build()
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        const val TAG = "VC"
        @Volatile var instance: InputService? = null
        const val CHANNEL_ID = "input"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "dev.voicecommander.STOP"
        const val EXTRA_TEXT = "text"
        const val AUTO_CLOSE_MS = 120_000L
    }
}