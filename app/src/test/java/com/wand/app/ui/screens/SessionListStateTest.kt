package com.wand.app.ui.screens

import com.wand.app.data.HistorySession
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.WandApi
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionListStateTest {
    @Test
    fun managedHistoryFilteringKeepsProvidersIndependent() {
        val state = SessionListState(WandApi("http://localhost", token = null))
        state.sessions = listOf(session(provider = "codex", providerSessionId = "shared-id"))
        state.historySessions = listOf(
            history(provider = "claude", id = "shared-id"),
            history(provider = "codex", id = "shared-id"),
        )

        assertEquals(listOf("claude"), state.visibleHistorySessions.map { it.apiProvider })
    }

    @Test
    fun removingManagedSessionOnlyRemovesMatchingProviderHistory() {
        val state = SessionListState(WandApi("http://localhost", token = null))
        val managed = session(provider = "claude", providerSessionId = "shared-id")
        state.sessions = listOf(managed)
        state.historySessions = listOf(
            history(provider = "claude", id = "shared-id"),
            history(provider = "codex", id = "shared-id"),
        )

        state.removeLocally(managed)

        assertEquals(listOf("codex"), state.historySessions.map { it.apiProvider })
    }

    private fun history(provider: String, id: String) = HistorySession(
        claudeSessionId = id,
        cwd = "/tmp/project",
        firstUserMessage = "Question",
        timestamp = null,
        mtimeMs = null,
        hasConversation = true,
        managedByWand = false,
        provider = provider,
    )

    private fun session(provider: String, providerSessionId: String) = SessionSnapshot(
        id = "wand-$provider",
        sessionKind = "structured",
        provider = provider,
        runner = null,
        command = null,
        cwd = "/tmp/project",
        mode = null,
        status = "idle",
        exitCode = null,
        startedAt = null,
        endedAt = null,
        archived = false,
        summary = null,
        currentTaskTitle = null,
        selectedModel = null,
        thinkingEffort = null,
        claudeSessionId = providerSessionId,
        messages = emptyList(),
        queuedMessages = emptyList(),
        structuredState = null,
        pendingEscalation = null,
        permissionBlocked = false,
        autoApprovePermissions = null,
    )
}
