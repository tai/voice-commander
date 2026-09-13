package dev.voicecommander.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModesTest {
    @Test
    fun `two modes in the PoC`() {
        assertEquals(2, Mode.entries.size)
        assertEquals(listOf("AI_INTERACTION", "DOCUMENT_EDIT"), Mode.entries.map { it.name })
    }

    @Test
    fun `every mode prompt carries the NOP sentinel contract`() {
        for (m in Mode.entries) {
            assertTrue("${m.name} prompt must declare NOP", m.prompt.contains(Mode.NOP_PREFIX))
        }
    }

    @Test
    fun `unknown id falls back to the default mode`() {
        assertEquals(Mode.AI_INTERACTION, Mode.byId("SQL"))
        assertEquals(Mode.AI_INTERACTION, Mode.byId(null))
        assertEquals(Mode.DOCUMENT_EDIT, Mode.byId("DOCUMENT_EDIT"))
    }

    @Test
    fun `document edit prompt preserves length, ai interaction compresses`() {
        assertTrue(Mode.DOCUMENT_EDIT.prompt.contains("ten-sentence"))
        assertTrue(Mode.DOCUMENT_EDIT.prompt.contains("NOT a summary"))
        assertTrue(Mode.AI_INTERACTION.prompt.contains("INSTRUCTIVE, not descriptive"))
        assertTrue(Mode.AI_INTERACTION.prompt.contains("acts immediately"))
    }
}