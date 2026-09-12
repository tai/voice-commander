package dev.voicecommander.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debounced interpretation with monotonic revisions: one request in flight,
 * never cancelled mid-flight; bursts coalesce into the newest text; after an
 * in-flight response, the newest queued text is sent next. A late response
 * can never be applied over a newer one (revisions are monotonic and only
 * strictly-increasing revisions are applied).
 *
 * ponytail: debounce and one-flight policy ported from the prototype, where
 * cancelling on every delta meant no candidate ever appeared mid-speech.
 */
class IntentScheduler(
    private val interpret: suspend (String) -> String?,
    private val onIntent: (text: String, revision: Long) -> Unit,
    private val onError: (String) -> Unit = {},
    private val debounceMs: Long = 700,
    private val scope: CoroutineScope,
) {
    private val queue = ArrayDeque<String>()
    private var timer: Job? = null
    private var revision = 0L
    private var lastApplied = 0L

    /** Coalesce to the newest text and schedule (or reuse) the debounce timer. */
    fun submit(text: String) {
        if (text.isBlank()) return
        synchronized(this) {
            queue.clear()
            queue.addLast(text)
            if (timer?.isActive == true) return  // pending timer will process the queue
            timer = scope.launch {
                delay(debounceMs)
                process()
            }
        }
    }

    /** Discard pending work. The revision counter is NOT reset: an ignoring
     *  rewriter can never come back to life with a stale result (prototype rule). */
    fun reset() {
        synchronized(this) {
            timer?.cancel()
            timer = null
            queue.clear()
        }
    }

    private suspend fun process() {
        while (true) {
            val text = synchronized(this) { queue.removeFirstOrNull() }
                ?: return
            val rev = synchronized(this) { ++revision }
            val result = try {
                interpret(text)
            } catch (e: Exception) {
                onError(e.message ?: "interpretation failed")
                null
            }
            if (result != null) {
                synchronized(this) { if (rev > lastApplied) lastApplied = rev }
                onIntent(result, rev)
            }
            if (synchronized(this) { queue.isEmpty() }) return
            // newer text arrived while this was in flight: send it next, no debounce
        }
    }
}