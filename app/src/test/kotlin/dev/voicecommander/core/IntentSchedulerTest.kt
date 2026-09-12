package dev.voicecommander.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentSchedulerTest {

    @Test
    fun `burst coalesces into one interpretation of the newest text`() = runTest {
        val calls = mutableListOf<String>()
        val app = StandardTestDispatcher(testScheduler)
        val s = IntentScheduler(
            interpret = { calls.add(it); null },
            onIntent = { _, _ -> },
            debounceMs = 700,
            scope = CoroutineScope(app),
        )
        s.submit("a")
        advanceTimeBy(100)
        s.submit("ab")
        advanceTimeBy(100)
        s.submit("abc")
        advanceTimeBy(699)
        app.scheduler.advanceUntilIdle()
        assertEquals(listOf("abc"), calls)
    }

    @Test
    fun `debounce suppresses an interpretation before it fires`() = runTest {
        val calls = mutableListOf<String>()
        val app = StandardTestDispatcher(testScheduler)
        val s = IntentScheduler(
            interpret = { calls.add(it); null },
            onIntent = { _, _ -> },
            debounceMs = 700,
            scope = CoroutineScope(app),
        )
        s.submit("a")
        advanceTimeBy(699)
        assertTrue(calls.isEmpty())
        advanceTimeBy(1)
        app.scheduler.advanceUntilIdle()
        // the timer job is scheduled on `app`; let testScheduler fully run it
        assertEquals(listOf("a"), calls)
    }

    @Test
    fun `late response never overrides a newer one`() = runTest {
        // Interpret call 1 is slow; call 2 is fast. onIntent must receive
        // revision 2's text and NOT later revision 1's.
        val applied = mutableListOf<Pair<Long, String>>()
        val calls = mutableListOf<String>()
        val app = StandardTestDispatcher(testScheduler)
        val s = IntentScheduler(
            interpret = { text ->
                calls.add(text)
                if (text == "first") {
                    // finish while call 2 is already queued: immediate
                    "first-intent"
                } else {
                    // delay past call 1's completion
                    advanceTimeBy(100)
                    "second-intent"
                }
            },
            onIntent = { text, rev -> applied.add(rev to text) },
            debounceMs = 700,
            scope = CoroutineScope(app),
        )
        s.submit("first")
        advanceTimeBy(700)
        advanceTimeBy(1)  // boundary task lands on the next advance
        s.submit("second")
        advanceTimeBy(700)
        advanceTimeBy(1)
        app.scheduler.advanceUntilIdle()
        assertEquals(listOf(1L to "first-intent", 2L to "second-intent"), applied)
    }

    @Test
    fun `reset clears pending work and no stale result resurrects`() = runTest {
        val onIntent = mutableListOf<String>()
        val app = StandardTestDispatcher(testScheduler)
        val s = IntentScheduler(
            interpret = { "intent-of-$it" },
            onIntent = { t, _ -> onIntent.add(t) },
            debounceMs = 700,
            scope = CoroutineScope(app),
        )
        s.submit("a")
        s.submit("b")
        s.reset()
        advanceTimeBy(2000)
        app.scheduler.advanceUntilIdle()
        assertTrue(onIntent.isEmpty())
    }
}