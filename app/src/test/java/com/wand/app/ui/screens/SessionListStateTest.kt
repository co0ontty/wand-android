package com.wand.app.ui.screens

import com.wand.app.data.HistorySession
import com.wand.app.data.SessionListPort
import com.wand.app.data.SessionSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionListStateTest {
    @Test
    fun managedHistoryFilteringKeepsProvidersIndependent() = runBlocking {
        val port = FakeSessionListPort().apply {
            sessions = mutableListOf(session(provider = "codex", providerSessionId = "shared-id"))
            claudeHistory = mutableListOf(history(provider = "claude", id = "shared-id"))
            codexHistory = mutableListOf(history(provider = "codex", id = "shared-id"))
        }
        val state = SessionListState(port)

        assertTrue(state.load())

        assertEquals(listOf("claude"), state.visibleHistorySessions.map { it.apiProvider })
    }

    @Test
    fun providerHistoryFailurePreservesTheLastSuccessfulProviderSnapshot() = runBlocking {
        val port = FakeSessionListPort().apply {
            claudeHistory = mutableListOf(history("claude", "claude-old"))
            codexHistory = mutableListOf(history("codex", "codex-old"))
        }
        val state = SessionListState(port)
        assertTrue(state.load())

        port.claudeHistory = mutableListOf(history("claude", "claude-new"))
        port.failCodexHistory = true
        assertTrue(state.load(silent = true))

        assertEquals(
            setOf("claude-new", "codex-old"),
            state.visibleHistorySessions.map { it.id }.toSet(),
        )
    }

    @Test
    fun restoringHistoryRemovesItAndPrependsTheManagedSession() = runBlocking {
        val recoverable = history("claude", "history-1")
        val resumed = session("claude", "history-1", id = "wand-resumed")
        val port = FakeSessionListPort().apply {
            claudeHistory = mutableListOf(recoverable)
            resumeResults[recoverable.key] = resumed
        }
        val state = SessionListState(port)
        state.load()

        assertEquals(resumed, state.restore(recoverable))

        assertEquals(listOf("wand-resumed"), state.sessions.map { it.id })
        assertTrue(state.visibleHistorySessions.isEmpty())
        assertFalse(state.isRestoring(recoverable))
        assertNull(state.loadError)
    }

    @Test
    fun restoreFailureKeepsHistoryAndExposesOneError() = runBlocking {
        val recoverable = history("codex", "history-1")
        val port = FakeSessionListPort().apply {
            codexHistory = mutableListOf(recoverable)
            failingResumes += recoverable.key
        }
        val state = SessionListState(port)
        state.load()

        assertNull(state.restore(recoverable))

        assertEquals(listOf("history-1"), state.visibleHistorySessions.map { it.id })
        assertEquals("restore failed", state.loadError)
        assertFalse(state.isRestoring(recoverable))
    }

    @Test
    fun batchDeleteGroupsHistoryByProvider() = runBlocking {
        val managed = session("claude", "managed-history", id = "wand-1")
        val claude = history("claude", "claude-1")
        val codex = history("codex", "codex-1")
        val port = FakeSessionListPort().apply {
            sessions = mutableListOf(managed)
            claudeHistory = mutableListOf(claude)
            codexHistory = mutableListOf(codex)
        }
        val state = SessionListState(port)
        state.load()

        val entries = listOf(
            SessionListEntry.Managed(managed),
            SessionListEntry.Recoverable(claude),
            SessionListEntry.Recoverable(codex),
        )
        assertTrue(state.delete(entries))

        assertTrue(state.sessions.isEmpty())
        assertTrue(state.visibleHistorySessions.isEmpty())
        assertEquals(
            setOf("claude" to listOf("claude-1"), "codex" to listOf("codex-1")),
            port.historyDeleteCalls.toSet(),
        )
    }

    @Test
    fun partialDeleteFailureReloadsServerTruth() = runBlocking {
        val managed = session("claude", "managed-history", id = "wand-1")
        val recoverable = history("codex", "codex-1")
        val port = FakeSessionListPort().apply {
            sessions = mutableListOf(managed)
            codexHistory = mutableListOf(recoverable)
            failingHistoryDeletes += "codex"
        }
        val state = SessionListState(port)
        state.load()

        assertFalse(
            state.delete(
                listOf(
                    SessionListEntry.Managed(managed),
                    SessionListEntry.Recoverable(recoverable),
                ),
            ),
        )

        // Managed delete succeeded remotely; Codex delete failed and is restored by reload.
        assertTrue(state.sessions.isEmpty())
        assertEquals(listOf("codex-1"), state.visibleHistorySessions.map { it.id })
    }

    private class FakeSessionListPort : SessionListPort {
        var sessions = mutableListOf<SessionSnapshot>()
        var claudeHistory = mutableListOf<HistorySession>()
        var codexHistory = mutableListOf<HistorySession>()
        var failCodexHistory = false
        val resumeResults = mutableMapOf<String, SessionSnapshot>()
        val failingResumes = mutableSetOf<String>()
        val failingHistoryDeletes = mutableSetOf<String>()
        val historyDeleteCalls = mutableListOf<Pair<String, List<String>>>()

        override suspend fun listSessions(): List<SessionSnapshot> = sessions.toList()

        override suspend fun listClaudeHistory(): List<HistorySession> = claudeHistory.toList()

        override suspend fun listCodexHistory(): List<HistorySession> {
            if (failCodexHistory) error("codex history failed")
            return codexHistory.toList()
        }

        override suspend fun resumeHistory(history: HistorySession): SessionSnapshot {
            if (history.key in failingResumes) error("restore failed")
            return resumeResults[history.key] ?: error("missing resume result")
        }

        override suspend fun deleteSession(id: String) {
            sessions.removeAll { it.id == id }
        }

        override suspend fun deleteHistoryBatch(provider: String, ids: List<String>) {
            historyDeleteCalls += provider to ids
            if (provider in failingHistoryDeletes) error("history delete failed")
            val target = if (provider == "codex") codexHistory else claudeHistory
            target.removeAll { it.claudeSessionId in ids }
        }
    }

    private companion object {
        val HistorySession.key: String get() = "$apiProvider:$id"

        fun history(provider: String, id: String) = HistorySession(
            claudeSessionId = id,
            cwd = "/tmp/project",
            firstUserMessage = "Question",
            timestamp = null,
            mtimeMs = null,
            hasConversation = true,
            managedByWand = false,
            provider = provider,
        )

        fun session(
            provider: String,
            providerSessionId: String,
            id: String = "wand-$provider",
        ) = SessionSnapshot(
            id = id,
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
}
