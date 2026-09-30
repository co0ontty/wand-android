package com.wand.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRulesTest {
    @Test
    fun providerMetadataHasOneConsistentMapping() {
        assertEquals("Claude", providerDisplayName("claude"))
        assertEquals("Codex", providerDisplayName("codex"))
        assertEquals("OpenCode", providerDisplayName("opencode"))
        assertEquals("Grok", providerDisplayName("grok"))
        assertEquals("Qoder", providerDisplayName("qoder"))
        assertEquals("Gemini", providerDisplayName("gemini"))
        assertEquals("终端", providerDisplayName(null))
        assertEquals(setOf("full-access"), supportedSessionModeIds("codex"))
        assertEquals(setOf("default", "full-access", "managed"), supportedSessionModeIds("grok"))
        assertEquals(setOf("default", "full-access", "auto-edit", "managed"), supportedSessionModeIds("qoder"))
        assertEquals(setOf("default", "full-access", "auto-edit", "managed"), supportedSessionModeIds("gemini"))
    }

    @Test
    fun defaultsAndModesUseProviderRules() {
        val defaults = ProviderDefaultModels(
            claude = "claude-model",
            codex = "codex-model",
            opencode = "opencode-model",
            grok = "grok-model",
            qoder = "qoder-model",
        )

        assertEquals("codex-model", defaults.defaultFor("codex"))
        assertEquals("grok-model", defaults.defaultFor("grok"))
        assertEquals("qoder-model", defaults.defaultFor("qoder"))
        assertEquals("gemini-model", defaults.copy(gemini = "gemini-model").defaultFor("gemini"))
        assertEquals("托管", sessionModeLabel("managed"))
        assertEquals("标准", sessionModeLabel("unknown"))
        assertEquals(
            listOf("managed", "full-access", "auto-edit", "default", "native"),
            SESSION_MODE_OPTIONS.map { it.id },
        )
    }

    @Test
    fun structuredSessionRoutingPrefersKindThenRunner() {
        assertTrue(isStructuredSession("structured", "pty"))
        assertFalse(isStructuredSession("pty", "structured"))
        assertTrue(isStructuredSession(null, "structured"))
        assertTrue(isStructuredSession(null, "codex-cli-exec"))
        assertTrue(isStructuredSession(null, "pi-cli-json"))
        assertTrue(isStructuredSession(null, "gemini-cli-json"))
        assertFalse(isStructuredSession(null, "pty"))
        assertFalse(isStructuredSession(null, null))
    }

    @Test
    fun grokAndQoderModelsAndLegacyDefaultsAreAvailableToTheProviderPicker() {
        val response = ModelsResponse(
            models = emptyList(),
            codexModels = emptyList(),
            opencodeModels = emptyList(),
            defaultModel = null,
            defaultCodexModel = null,
            defaultOpenCodeModel = null,
            defaultModels = null,
            qoderModels = listOf(
                ModelInfo("zhipu/glm5.2-cp", "GLM-5.2 (Z.ai)", false, emptyList(), null),
            ),
            defaultQoderModel = "performance",
            grokModels = listOf(
                ModelInfo("grok-3", "Grok 3", false, emptyList(), null),
            ),
            defaultGrokModel = "grok-3",
        )

        assertEquals("performance", response.defaultModelFor("qoder"))
        assertEquals("grok-3", response.defaultModelFor("grok"))
        assertEquals(
            listOf("zhipu/glm5.2-cp"),
            response.modelsFor("qoder").map { it.id },
        )
        assertEquals(
            listOf("grok-3"),
            response.modelsFor("grok").map { it.id },
        )
        assertEquals(response.models, response.modelsFor("claude"))
        assertEquals(response.models, response.modelsFor(null))

        val gemini = response.copy(
            geminiModels = listOf(ModelInfo("gemini-2.5-pro", "Gemini 2.5 Pro", false, emptyList(), null)),
            defaultGeminiModel = "gemini-2.5-pro",
        )
        assertEquals("gemini-2.5-pro", gemini.defaultModelFor("gemini"))
        assertEquals(listOf("gemini-2.5-pro"), gemini.modelsFor("gemini").map { it.id })
    }

    @Test
    fun providerTableIsTheSingleSourceForCliAndRunnerNames() {
        assertEquals("qodercli", WandProvider.cliCommandFor("qoder"))
        assertEquals("gemini", WandProvider.cliCommandFor("gemini"))
        assertEquals("gemini-cli-json", structuredRunnerFor("gemini"))
        assertEquals("codex", WandProvider.cliCommandFor("codex"))
        assertEquals("custom", WandProvider.cliCommandFor("custom"))
        assertEquals("codex-cli-exec", structuredRunnerFor("codex"))
        assertEquals("claude-cli-print", structuredRunnerFor("unknown"))
    }

    @Test
    fun modelSearchMatchesIdAndLabelKeywords() {
        assertEquals(true, matchesModelSearch("opus", "claude-opus-4-6", "Opus 4.6"))
        assertEquals(true, matchesModelSearch("GPT 5.4", "openai/gpt-5.4", "GPT-5.4"))
        assertEquals(true, matchesModelSearch("默认", "", "默认 · Claude Sonnet 4.6"))
        assertEquals(false, matchesModelSearch("kimi xyz", "openai/gpt-5.4", "GPT-5.4"))
        assertEquals(true, matchesModelSearch("  ", "anything", "label"))
    }

}
