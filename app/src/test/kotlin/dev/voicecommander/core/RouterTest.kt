package dev.voicecommander.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouterTest {
    @Test
    fun `termux frontmost routes to termux`() {
        assertEquals(Route.TERMUX, Router.routeFor("com.termux", false, true, "dev.voicecommander"))
    }

    @Test
    fun `other app with editable node routes to focused field`() {
        assertEquals(Route.FOCUSED_FIELD, Router.routeFor("com.whatsapp", true, true, "dev.voicecommander"))
    }

    @Test
    fun `our own overlay is never a delivery target`() {
        assertEquals(Route.CLIPBOARD, Router.routeFor("dev.voicecommander", true, true, "dev.voicecommander"))
    }

    @Test
    fun `no accessibility means clipboard`() {
        assertEquals(Route.CLIPBOARD, Router.routeFor("com.termux", false, false, "dev.voicecommander"))
    }

    @Test
    fun `no frontmost app means clipboard`() {
        assertEquals(Route.CLIPBOARD, Router.routeFor(null, false, true, "dev.voicecommander"))
    }

    // --- effectiveFrontmost: live root is truth; chrome & own package ignored

    @Test
    fun `live package beats a stale systemui cache`() {
        assertEquals("com.termux", Router.effectiveFrontmost("com.android.systemui", "com.termux", "dev.voicecommander"))
    }

    @Test
    fun `live package beats a stale ime cache`() {
        assertEquals("com.whatsapp", Router.effectiveFrontmost("com.google.android.inputmethod.latin", "com.whatsapp", "dev.voicecommander"))
    }

    @Test
    fun `cleaned cache used when live is unavailable`() {
        assertEquals("com.termux", Router.effectiveFrontmost("com.termux", null, "dev.voicecommander"))
    }

    @Test
    fun `own package and chrome never survive`() {
        assertNull(Router.effectiveFrontmost("dev.voicecommander", "com.android.systemui", "dev.voicecommander"))
        assertNull(Router.effectiveFrontmost("com.android.systemui", null, "dev.voicecommander"))
        assertEquals("com.termux", Router.effectiveFrontmost("com.termux", "dev.voicecommander", "dev.voicecommander"))
    }
}