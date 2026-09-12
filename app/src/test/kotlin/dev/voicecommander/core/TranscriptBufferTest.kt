package dev.voicecommander.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptBufferTest {
    @Test
    fun `partial replaces the previous partial instead of appending`() {
        val b = TranscriptBuffer()
        b.onPartial("明日の会議")
        b.onPartial("明日の会議は3時")
        assertEquals("明日の会議は3時", b.text)
    }

    @Test
    fun `final supersedes the partial and joins without CJK spaces`() {
        val b = TranscriptBuffer()
        b.onPartial("明日の会議は3時")
        b.onFinal("明日の会議は3時")
        b.onPartial("、いや4時")
        b.onFinal("、いや4時")
        assertEquals("明日の会議は3時、いや4時", b.text)
    }

    @Test
    fun `latin segments join with a space`() {
        val b = TranscriptBuffer()
        b.onFinal("list")
        b.onFinal("unsafe")
        assertEquals("list unsafe", b.text)
    }

    @Test
    fun `reset clears everything`() {
        val b = TranscriptBuffer()
        b.onFinal("hello")
        b.onPartial("world")
        b.reset()
        assertEquals("", b.text)
    }
}