package dev.voicecommander.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectivesTest {
    @Test
    fun `shorter directive is stripped and classified`() {
        val p = DirectiveParser.parse("このディレクトリのunsafeを探して、もっと短く")
        assertEquals(Directive.SHORTER, p.directive)
        assertEquals("このディレクトリのunsafeを探して、", p.content)
    }

    @Test
    fun `english directive classifies`() {
        val p = DirectiveParser.parse("ファイルを消して英語で")
        assertEquals(Directive.ENGLISH, p.directive)
    }

    @Test
    fun `verbatim directive classifies`() {
        val p = DirectiveParser.parse("この通り入力して")
        assertEquals(Directive.VERBATIM, p.directive)
        assertEquals("この通り入力して", p.content) // empty content falls back to raw
    }

    @Test
    fun `plain utterance stays content`() {
        val p = DirectiveParser.parse("Rustのunsafeを探して")
        assertEquals(Directive.NONE, p.directive)
        assertEquals("Rustのunsafeを探して", p.content)
    }
}