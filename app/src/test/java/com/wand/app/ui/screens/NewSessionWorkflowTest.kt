package com.wand.app.ui.screens

import com.wand.app.data.CardExpandDefaults
import com.wand.app.data.ModelInfo
import com.wand.app.data.ModelsResponse
import com.wand.app.data.NewSessionPort
import com.wand.app.data.ProviderDefaultModels
import com.wand.app.data.RecentPath
import com.wand.app.data.ReasoningEffortInfo
import com.wand.app.data.ServerConfigInfo
import com.wand.app.data.SessionSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewSessionWorkflowTest {
    @Test
    fun bootstrapNormalizesProviderModeAndPrefersDetectedDefaults() = runBlocking {
        val port = FakeNewSessionPort().apply {
            config = config(defaultProvider = "codex", defaultMode = "managed", defaultCwd = "/config")
            modelResponse = modelFixture(defaults = ProviderDefaultModels("claude-new", "codex-new", null))
            paths = listOf(RecentPath("/recent", "recent", null))
        }

        val initial = NewSessionWorkflow(port).bootstrap()

        assertEquals("codex", initial.provider)
        assertEquals("full-access", initial.mode)
        assertEquals("/recent", initial.cwd)
        assertEquals("codex-new", initial.defaultModels.codex)
    }

    @Test
    fun bootstrapFallsBackToConfigWhenModelDiscoveryFails() = runBlocking {
        val port = FakeNewSessionPort().apply {
            config = config(defaultProvider = "unknown", defaultMode = "native", defaultCwd = "/config")
            failModels = true
        }

        val initial = NewSessionWorkflow(port).bootstrap()

        assertEquals("claude", initial.provider)
        assertEquals("native", initial.mode)
        assertEquals("/config", initial.cwd)
        assertEquals("claude-config", initial.defaultModels.claude)
    }

    @Test
    fun structuredCreatePersistsFinalNormalizedDraftBeforeCreation() = runBlocking {
        val port = FakeNewSessionPort()
        val workflow = NewSessionWorkflow(port)

        val result = workflow.create(
            NewSessionDraft(
                cwd = "  /project  ",
                provider = "codex",
                structured = true,
                mode = "managed",
                model = "",
                thinkingEffort = "deep",
                firstMessage = "  hello  ",
            ),
        )

        assertEquals("created", result.id)
        assertEquals(listOf("persist", "structured"), port.calls)
        assertEquals("full-access", port.lastMode)
        assertEquals("codex", port.lastProvider)
        assertEquals("/project", port.lastCwd)
        assertEquals("hello", port.lastPrompt)
        assertNull(port.lastModel)
    }

    @Test
    fun ptyCreateUsesInitialInputAndProviderSupportedMode() = runBlocking {
        val port = FakeNewSessionPort()

        NewSessionWorkflow(port).create(
            NewSessionDraft("/project", "opencode", false, "native", "model-x", "off", "run"),
        )

        assertEquals(listOf("persist", "pty"), port.calls)
        assertEquals("managed", port.lastMode)
        assertEquals("run", port.lastPrompt)
    }

    @Test
    fun unsupportedThinkingEffortNormalizesToAutomatic() {
        val workflow = NewSessionWorkflow(FakeNewSessionPort())
        val normalized = workflow.normalizeThinkingEffort(
            provider = "codex",
            selectedModel = "codex-model",
            currentEffort = "max",
            defaultModels = ProviderDefaultModels(null, "codex-model", null),
            claudeModels = emptyList(),
            codexModels = listOf(
                ModelInfo(
                    "codex-model",
                    "Codex",
                    false,
                    listOf(ReasoningEffortInfo("low", null)),
                    "low",
                ),
            ),
            opencodeModels = emptyList(),
        )

        assertEquals("off", normalized)
    }

    private class FakeNewSessionPort : NewSessionPort {
        var config = config()
        var modelResponse = modelFixture()
        var paths = emptyList<RecentPath>()
        var failModels = false
        val calls = mutableListOf<String>()
        var lastMode: String? = null
        var lastProvider: String? = null
        var lastCwd: String? = null
        var lastPrompt: String? = null
        var lastModel: String? = null

        override suspend fun serverConfig(): ServerConfigInfo = config
        override suspend fun models(): ModelsResponse {
            if (failModels) error("models failed")
            return modelResponse
        }
        override suspend fun recentPaths(): List<RecentPath> = paths

        override suspend fun updateNewSessionDefaults(
            mode: String?, model: String?, modelProvider: String, thinkingEffort: String?,
            defaultProvider: String?, defaultSessionKind: String?,
        ) {
            calls += "persist"
            lastMode = mode
            lastProvider = modelProvider
            lastModel = model
        }

        override suspend fun createStructuredSession(
            cwd: String, mode: String?, prompt: String?, provider: String,
            model: String?, thinkingEffort: String?,
        ): SessionSnapshot {
            calls += "structured"
            capture(cwd, mode, prompt, provider, model)
            return snapshot()
        }

        override suspend fun createPtySession(
            cwd: String, mode: String?, initialInput: String?, provider: String,
            model: String?, thinkingEffort: String?,
        ): SessionSnapshot {
            calls += "pty"
            capture(cwd, mode, initialInput, provider, model)
            return snapshot()
        }

        private fun capture(cwd: String, mode: String?, prompt: String?, provider: String, model: String?) {
            lastCwd = cwd
            lastMode = mode
            lastPrompt = prompt
            lastProvider = provider
            lastModel = model
        }
    }

    private companion object {
        fun config(
            defaultProvider: String? = "claude",
            defaultMode: String? = "managed",
            defaultCwd: String? = "/default",
        ) = ServerConfigInfo(
            defaultCwd = defaultCwd,
            defaultProvider = defaultProvider,
            defaultSessionKind = "structured",
            defaultMode = defaultMode,
            defaultModel = "claude-config",
            defaultCodexModel = "codex-config",
            defaultOpenCodeModel = null,
            defaultModels = null,
            defaultThinkingEffort = "off",
            cardDefaults = CardExpandDefaults(),
            currentVersion = null,
        )

        fun modelFixture(defaults: ProviderDefaultModels? = null) = ModelsResponse(
            models = emptyList(),
            codexModels = emptyList(),
            opencodeModels = emptyList(),
            defaultModel = "claude-models",
            defaultCodexModel = "codex-models",
            defaultOpenCodeModel = null,
            defaultModels = defaults,
        )

        fun snapshot() = SessionSnapshot(
            id = "created", sessionKind = "structured", provider = "claude", runner = null,
            command = null, cwd = "/project", mode = "managed", status = "running",
            exitCode = null, startedAt = null, endedAt = null, archived = false, summary = null,
            currentTaskTitle = null, selectedModel = null, thinkingEffort = null,
            claudeSessionId = null, messages = emptyList(), queuedMessages = emptyList(),
            structuredState = null, pendingEscalation = null, permissionBlocked = false,
            autoApprovePermissions = null,
        )
    }
}
