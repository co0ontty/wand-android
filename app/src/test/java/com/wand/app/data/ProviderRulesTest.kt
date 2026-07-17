package com.wand.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderRulesTest {
    @Test
    fun providerMetadataHasOneConsistentMapping() {
        assertEquals("Claude", providerDisplayName("claude"))
        assertEquals("Codex", providerDisplayName("codex"))
        assertEquals("OpenCode", providerDisplayName("opencode"))
        assertEquals("Grok", providerDisplayName("grok"))
        assertEquals("Qoder", providerDisplayName("qoder"))
        assertEquals(setOf("full-access"), supportedSessionModeIds("codex"))
        assertEquals(setOf("default", "full-access", "managed"), supportedSessionModeIds("grok"))
        assertEquals(setOf("default", "full-access", "auto-edit", "managed"), supportedSessionModeIds("qoder"))
    }

    @Test
    fun defaultsAndModesUseProviderRules() {
        val defaults = ProviderDefaultModels("claude-model", "codex-model", "opencode-model")

        assertEquals("codex-model", defaults.defaultFor("codex"))
        assertNull(defaults.defaultFor("grok"))
        assertNull(defaults.defaultFor("qoder"))
        assertEquals("full-access", clampSessionMode("managed", "codex"))
        assertEquals("managed", clampSessionMode("native", "opencode"))
    }
}
