package com.wand.app.ui.screens

import com.wand.app.data.HistorySession
import com.wand.app.data.SessionDirectoryNode
import com.wand.app.data.SessionDirectoryTreeResponse
import com.wand.app.data.SessionListEntry
import com.wand.app.data.SessionListPage
import com.wand.app.data.SessionListPort
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.WandApiException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionListStateTest {
    @Test
    fun directoryLoadPublishesServerTreeAndCounts() = runBlocking {
        val root = SessionDirectoryNode(
            path = "/tmp/project",
            name = "project",
            synthetic = false,
            directCount = 1,
            totalCount = 1,
            latestTimestamp = 1,
            entries = listOf(managed("first")),
            children = emptyList(),
        )
        val response = SessionDirectoryTreeResponse(
            roots = listOf(root),
            totalSessions = 1,
            directoryCount = 1,
            revision = "directory-revision",
        )
        val state = SessionListState(FakeSessionListPort().apply { directoryResponse = response })

        assertTrue(state.loadDirectories())

        assertSame(response, state.directoryTree)
        assertEquals(1, state.directoryTree?.directoryCount)
        assertTrue(state.directoryTree?.roots?.single()?.containsSession("first") == true)
        assertNull(state.directoryError)
        assertFalse(state.directoryLoading)
    }

    @Test
    fun loadMoreAppendsTheNextPageWithoutDuplicates() = runBlocking {
        val first = managed("first")
        val second = recoverable("history-2")
        val port = FakeSessionListPort().apply {
            pages[0] = page(listOf(first), total = 2)
            pages[1] = page(listOf(first, second), offset = 1, total = 2)
        }
        val state = SessionListState(port)

        assertTrue(state.load())
        assertTrue(state.loadMore())

        assertEquals(listOf("session-first", "recoverable-claude-history-2"), state.entries.map { it.key })
        assertFalse(state.canLoadMore)
    }

    @Test
    fun initialLoadUsesTwentyEntriesAndKeepsUnchangedCache() = runBlocking {
        val first = managed("first")
        val port = FakeSessionListPort().apply {
            pages[0] = page(listOf(first), total = 1, revision = "revision-a")
        }
        val state = SessionListState(port)

        assertTrue(state.load())
        val cachedEntries = state.entries

        assertTrue(state.load(silent = true))

        assertEquals(Triple(0, 20, null), port.pageRequests[0])
        assertEquals(Triple(0, 20, null), port.pageRequests[1])
        assertSame(cachedEntries, state.entries)
    }

    @Test
    fun refreshingCachedPagesRequestsAndReplacesTheFullWindow() = runBlocking {
        val firstPage = (1..20).map { managed("first-$it") }
        val secondPage = (1..20).map { managed("second-$it") }
        val port = FakeSessionListPort().apply {
            pages[0] = page(firstPage, total = 40, revision = "revision-a")
            pages[20] = page(secondPage, offset = 20, total = 40, revision = "revision-a")
        }
        val state = SessionListState(port)

        assertTrue(state.load())
        assertTrue(state.loadMore())
        val cachedEntries = state.entries

        port.pages[0] = page(firstPage + secondPage, total = 40, revision = "revision-a")
        assertTrue(state.load(silent = true))

        assertEquals(Triple(0, 40, null), port.pageRequests.last())
        assertSame(cachedEntries, state.entries)
    }

    @Test
    fun refreshingCachedPagesUpdatesChangedEntries() = runBlocking {
        val first = managed("first")
        val updated = SessionListEntry.Managed(
            key = "session-first",
            sortTimestamp = 0,
            session = session("first").copy(summary = "Updated"),
        )
        val port = FakeSessionListPort().apply {
            pages[0] = page(listOf(first), total = 1, revision = "revision-a")
        }
        val state = SessionListState(port)
        assertTrue(state.load())
        val cachedEntries = state.entries

        port.pages[0] = page(listOf(updated), total = 1, revision = "revision-a")
        assertTrue(state.load(silent = true))

        assertNotSame(cachedEntries, state.entries)
        assertEquals("Updated", (state.entries.single() as SessionListEntry.Managed).session.summary)
    }

    @Test
    fun changedRevisionDropsCachedTailInsteadOfMixingPages() = runBlocking {
        val first = managed("first")
        val second = managed("second")
        val third = managed("third")
        val fourth = managed("fourth")
        val refreshedFirst = managed("refreshed-first")
        val refreshedSecond = managed("refreshed-second")
        val port = FakeSessionListPort().apply {
            pages[0] = page(listOf(first, second), total = 4, revision = "revision-a")
            pages[2] = page(listOf(third, fourth), offset = 2, total = 4, revision = "revision-a")
        }
        val state = SessionListState(port)
        assertTrue(state.load())
        assertTrue(state.loadMore())

        port.pages[0] = page(
            listOf(refreshedFirst, refreshedSecond),
            total = 2,
            revision = "revision-b",
        )
        assertTrue(state.load(silent = true))

        assertEquals(
            listOf("session-refreshed-first", "session-refreshed-second"),
            state.entries.map { it.key },
        )
    }

    @Test
    fun stalePageReloadsTheFirstPageInsteadOfAppending() = runBlocking {
        val first = managed("first")
        val refreshed = managed("refreshed")
        val port = FakeSessionListPort().apply {
            pages[0] = page(listOf(first), total = 2, revision = "revision-a")
            pages[1] = page(listOf(recoverable("history")), offset = 1, total = 2, revision = "revision-a")
        }
        val state = SessionListState(port)
        assertTrue(state.load())

        port.pages[0] = page(listOf(refreshed), total = 3, revision = "revision-b")
        port.pageFailures[1] = WandApiException(409, "会话列表已更新，请重新加载")

        assertTrue(state.loadMore())
        assertEquals(listOf("session-refreshed"), state.entries.map { it.key })
        assertEquals(Triple(1, 20, "revision-a"), port.pageRequests[1])
        assertNull(state.loadError)
    }

    @Test
    fun refreshingFirstPageReplacesLoadedEntries() = runBlocking {
        val older = managed("older")
        val newest = managed("newest")
        val port = FakeSessionListPort().apply {
            pages[0] = page(listOf(older), total = 2)
            pages[1] = page(listOf(recoverable("history")), offset = 1, total = 2)
        }
        val state = SessionListState(port)
        state.load()
        assertTrue(state.loadMore())

        port.pages[0] = page(listOf(newest), total = 3)
        assertTrue(state.load(silent = true))

        assertEquals(
            listOf("session-newest"),
            state.entries.map { it.key },
        )
    }

    @Test
    fun failedRefreshPreservesLoadedEntries() = runBlocking {
        val first = managed("first")
        val port = FakeSessionListPort().apply {
            pages[0] = page(listOf(first), total = 1, revision = "revision-a")
        }
        val state = SessionListState(port)
        assertTrue(state.load())

        port.pageFailures[0] = WandApiException(null, "响应解析失败")

        assertFalse(state.load(silent = true))
        assertEquals(listOf("session-first"), state.entries.map { it.key })
        assertNull(state.loadError)
    }

    @Test
    fun replacingLoadedSessionDoesNotAdvancePageCursor() = runBlocking {
        val first = managed("first")
        val second = recoverable("history-2")
        val port = FakeSessionListPort().apply {
            pages[0] = page(listOf(first), total = 2)
            pages[1] = page(listOf(second), offset = 1, total = 2)
        }
        val state = SessionListState(port)
        assertTrue(state.load())

        state.addCreated(first.session)
        assertTrue(state.loadMore())

        assertEquals(Triple(1, 20, null), port.pageRequests.last())
        assertEquals(listOf("session-first", "recoverable-claude-history-2"), state.entries.map { it.key })
    }

    @Test
    fun restoringHistoryReplacesItWithManagedSession() = runBlocking {
        val recoverable = HistorySession(
            claudeSessionId = "history-1",
            cwd = "/tmp/project",
            firstUserMessage = "Question",
            timestamp = null,
            mtimeMs = null,
            hasConversation = true,
            managedByWand = false,
            provider = "claude",
        )
        val resumed = session("wand-resumed", "history-1")
        val port = FakeSessionListPort().apply {
            pages[0] = page(listOf(SessionListEntry.Recoverable(
                key = "recoverable-claude-history-1",
                sortTimestamp = 0,
                history = recoverable,
            )), total = 1)
            resumeResults["claude:history-1"] = resumed
        }
        val state = SessionListState(port)
        state.load()
        port.pages[0] = page(listOf(SessionListEntry.Managed(
            key = "session-wand-resumed",
            sortTimestamp = 0,
            session = resumed,
        )), total = 1)

        assertEquals("wand-resumed", state.restore(recoverable)?.id)
        assertEquals(listOf("session-wand-resumed"), state.entries.map { it.key })
        assertFalse(state.isRestoring(recoverable))
        assertNull(state.loadError)
    }

    @Test
    fun batchDeleteGroupsHistoryByProvider() = runBlocking {
        val managed = managed("wand-1")
        val claude = recoverable("claude-1")
        val codex = recoverable("codex-1", provider = "codex")
        val port = FakeSessionListPort().apply {
            pages[0] = page(listOf(managed, claude, codex), total = 3)
        }
        val state = SessionListState(port)
        state.load()

        assertTrue(state.delete(state.entries))
        assertEquals(
            setOf("claude" to listOf("claude-1"), "codex" to listOf("codex-1")),
            port.historyDeleteCalls.toSet(),
        )
    }

    private class FakeSessionListPort : SessionListPort {
        val pages = mutableMapOf<Int, SessionListPage>()
        val pageFailures = mutableMapOf<Int, Exception>()
        val pageRequests = mutableListOf<Triple<Int, Int, String?>>()
        val resumeResults = mutableMapOf<String, SessionSnapshot>()
        val historyDeleteCalls = mutableListOf<Pair<String, List<String>>>()
        var directoryResponse = SessionDirectoryTreeResponse(emptyList(), 0, 0, "empty")

        override suspend fun fetchSessionList(
            offset: Int,
            limit: Int,
            revision: String?,
        ): SessionListPage {
            pageRequests += Triple(offset, limit, revision)
            pageFailures.remove(offset)?.let { throw it }
            return pages[offset] ?: page(emptyList(), offset, total = 0)
        }

        override suspend fun fetchSessionDirectories(): SessionDirectoryTreeResponse = directoryResponse

        override suspend fun resumeHistory(history: HistorySession): SessionSnapshot =
            resumeResults["${history.apiProvider}:${history.id}"] ?: error("missing resume result")

        override suspend fun deleteSession(id: String) = Unit

        override suspend fun deleteHistoryBatch(provider: String, ids: List<String>) {
            historyDeleteCalls += provider to ids
        }
    }

    private companion object {
        fun page(
            entries: List<SessionListEntry>,
            offset: Int = 0,
            total: Int,
            revision: String? = null,
        ) = SessionListPage(entries, offset, total, revision)

        fun managed(id: String) = SessionListEntry.Managed(
            key = "session-$id",
            sortTimestamp = 0,
            session = session(id),
        )

        fun recoverable(id: String, provider: String = "claude") = SessionListEntry.Recoverable(
            key = "recoverable-$provider-$id",
            sortTimestamp = 0,
            history = HistorySession(
                claudeSessionId = id,
                cwd = "/tmp/project",
                firstUserMessage = "Question",
                timestamp = null,
                mtimeMs = null,
                hasConversation = true,
                managedByWand = false,
                provider = provider,
            ),
        )

        fun session(id: String, providerSessionId: String? = null) = SessionSnapshot(
            id = id,
            sessionKind = "structured",
            provider = "claude",
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
