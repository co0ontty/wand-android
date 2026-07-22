package com.wand.app.data

import org.junit.Assert.assertEquals
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
        assertEquals("full-access", clampSessionMode("managed", "codex"))
        assertEquals("managed", clampSessionMode("native", "opencode"))
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
            modelsForProvider(
                provider = "qoder",
                claude = response.models,
                codex = response.codexModels,
                opencode = response.opencodeModels,
                qoder = response.qoderModels,
            ).map { it.id },
        )
        assertEquals(
            listOf("grok-3"),
            modelsForProvider(
                provider = "grok",
                claude = response.models,
                codex = response.codexModels,
                opencode = response.opencodeModels,
                qoder = response.qoderModels,
                grok = response.grokModels,
            ).map { it.id },
        )
    }

}
