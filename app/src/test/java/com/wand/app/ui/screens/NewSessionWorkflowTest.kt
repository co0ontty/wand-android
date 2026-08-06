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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
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
    fun bootstrapSurfacesRequiredServerConfigFailure() = runBlocking {
        val port = FakeNewSessionPort().apply { failConfig = true }

        try {
            NewSessionWorkflow(port).bootstrap()
            fail("bootstrap should fail when the selected server is unavailable")
        } catch (error: IllegalStateException) {
            assertEquals("config failed", error.message)
        }
    }

    @Test
    fun bootstrapWaitsForAnOlderDefaultsWriteOnTheSameServer() = runBlocking {
        val persistStarted = CompletableDeferred<Unit>()
        val releasePersist = CompletableDeferred<Unit>()
        val port = FakeNewSessionPort().apply {
            this.persistStarted = persistStarted
            this.releasePersist = releasePersist
        }
        val workflow = NewSessionWorkflow(port)

        coroutineScope {
            val persist = async { workflow.persistDefaults(mode = "native") }
            persistStarted.await()
            val bootstrap = async { workflow.bootstrap() }
            yield()
            assertFalse(bootstrap.isCompleted)
            releasePersist.complete(Unit)
            persist.await()
            bootstrap.await()
        }

        assertEquals(listOf("persist", "config"), port.calls.take(2))
    }

    @Test
    fun bootstrapKeepsTheServerGrokCatalogAndDefault() = runBlocking {
        val port = FakeNewSessionPort().apply {
            modelResponse = modelFixture(
                defaults = ProviderDefaultModels(
                    claude = null,
                    codex = null,
                    opencode = null,
                    grok = "grok-4.5",
                ),
            ).copy(
                grokModels = listOf(ModelInfo("grok-4.5", "Grok 4.5", false, emptyList(), null)),
                qoderModels = listOf(ModelInfo("zhipu/glm5.2-cp", "GLM-5.2", false, emptyList(), null)),
            )
        }

        val bootstrap = NewSessionWorkflow(port).bootstrap()

        assertEquals("grok-4.5", bootstrap.defaultModels.grok)
        assertEquals(listOf("grok-4.5"), bootstrap.models?.grokModels?.map { it.id })
    }

    @Test
    fun structuredCreatePersistsFinalNormalizedDraftBeforeCreation() = runBlocking {
        val port = FakeNewSessionPort()
        val workflow = NewSessionWorkflow(port)

        val result = workflow.create(
            NewSessionDraft(
                cwd = "  /project  ",
                provider = "codex",
                kind = NewSessionKind.Structured,
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
            NewSessionDraft("/project", "opencode", NewSessionKind.Pty, "native", "model-x", "off", "run"),
        )

        assertEquals(listOf("persist", "pty"), port.calls)
        assertEquals("managed", port.lastMode)
        assertEquals("run", port.lastPrompt)
    }

    @Test
    fun grokSupportsStructuredAndPtyManagedModes() = runBlocking {
        val port = FakeNewSessionPort()
        val workflow = NewSessionWorkflow(port)
        assertEquals("grok", NewSessionWorkflow.normalizeProvider("grok"))
        assertEquals(setOf("default", "full-access", "managed"), workflow.supportedModes("grok"))

        workflow.create(NewSessionDraft("/project", "grok", NewSessionKind.Structured, "native", "grok-4.5", "deep", "hello"))
        assertEquals("managed", port.lastMode)
        assertEquals("grok", port.lastProvider)
        assertEquals("grok-4.5", port.lastModel)
        assertEquals(listOf("persist", "structured"), port.calls)
    }

    @Test
    fun qoderSupportsStructuredAndPtyPermissionModes() = runBlocking {
        val port = FakeNewSessionPort()
        val workflow = NewSessionWorkflow(port)
        assertEquals("qoder", NewSessionWorkflow.normalizeProvider("qoder"))
        assertEquals(
            setOf("default", "full-access", "auto-edit", "managed"),
            workflow.supportedModes("qoder"),
        )

        workflow.create(NewSessionDraft("/project", "qoder", NewSessionKind.Structured, "native", "performance", "off", "hello"))
        assertEquals("qoder", port.lastProvider)
        assertEquals("managed", port.lastMode)
        assertEquals("performance", port.lastModel)
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

    @Test
    fun shellCreateSkipsProviderDefaultsAndStartsBareShell() = runBlocking {
        val port = FakeNewSessionPort()

        NewSessionWorkflow(port).create(
            NewSessionDraft(
                "/project",
                "codex",
                NewSessionKind.Shell,
                "full-access",
                "gpt-5",
                "deep",
                "ignored",
            ),
        )

        assertEquals(listOf("shell"), port.calls)
        assertEquals("/project", port.lastCwd)
    }

    private class FakeNewSessionPort : NewSessionPort {
        var config = config()
        var modelResponse = modelFixture()
        var paths = emptyList<RecentPath>()
        var failConfig = false
        var failModels = false
        var persistStarted: CompletableDeferred<Unit>? = null
        var releasePersist: CompletableDeferred<Unit>? = null
        val calls = mutableListOf<String>()
        var lastMode: String? = null
        var lastProvider: String? = null
        var lastCwd: String? = null
        var lastPrompt: String? = null
        var lastModel: String? = null

        override suspend fun serverConfig(): ServerConfigInfo {
            calls += "config"
            if (failConfig) error("config failed")
            return config
        }
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
            persistStarted?.complete(Unit)
            releasePersist?.await()
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

        override suspend fun createShellSession(cwd: String): SessionSnapshot {
            calls += "shell"
            lastCwd = cwd
            return snapshot().copy(sessionKind = "pty", provider = null)
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
