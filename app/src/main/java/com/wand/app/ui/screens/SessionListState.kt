package com.wand.app.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wand.app.data.HistorySession
import com.wand.app.data.SessionListEntry
import com.wand.app.data.SessionListPort
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.WandApiException
import com.wand.app.ui.ScopedStore
import com.wand.app.ui.parseIsoMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SessionListState(private val port: SessionListPort) : ScopedStore() {
    var entries by mutableStateOf<List<SessionListEntry>>(emptyList())
        private set
    var total by mutableStateOf(0)
        private set
    var loading by mutableStateOf(true)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    val scrollState = LazyListState()
    var scrollToLatestRequest by mutableLongStateOf(0L)
        private set
    private var restoringHistoryKeys by mutableStateOf<Set<String>>(emptySet())
    private var nextOffset = 0
    private var revision: String? = null

    private val operationMutex = Mutex()
    private var syncing = false

    val sessions: List<SessionSnapshot>
        get() = entries.mapNotNull { (it as? SessionListEntry.Managed)?.session }
    val isRestoringHistory: Boolean
        get() = restoringHistoryKeys.isNotEmpty()
    val canLoadMore: Boolean
        get() = nextOffset < total

    fun startSync() {
        if (syncing) return
        syncing = true
        scope.launch {
            load(silent = entries.isNotEmpty())
            while (true) {
                delay(10_000)
                load(silent = true)
            }
        }
    }

    suspend fun load(silent: Boolean = false): Boolean = operationMutex.withLock {
        loadUnlocked(silent)
    }

    private suspend fun loadUnlocked(silent: Boolean): Boolean {
        if (!silent) loading = true
        return try {
            val page = port.fetchSessionList(offset = 0, limit = PAGE_SIZE)
            entries = page.entries
            total = page.total
            nextOffset = page.offset + page.entries.size
            revision = page.revision
            loadError = null
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (!silent || entries.isEmpty()) loadError = e.message ?: "加载失败"
            false
        } finally {
            loading = false
        }
    }

    suspend fun loadMore(): Boolean = operationMutex.withLock {
        if (!canLoadMore || loadingMore) return@withLock false
        loadingMore = true
        val requestOffset = nextOffset
        val requestRevision = revision
        try {
            val page = port.fetchSessionList(
                offset = requestOffset,
                limit = PAGE_SIZE,
                revision = requestRevision,
            )
            if (nextOffset != requestOffset || revision != requestRevision) return@withLock false
            val existingKeys = entries.mapTo(mutableSetOf()) { it.key }
            entries += page.entries.filter { existingKeys.add(it.key) }
            total = page.total
            nextOffset = page.offset + page.entries.size
            revision = page.revision
            loadError = null
            true
        } catch (e: WandApiException) {
            if (e.status == 409) loadUnlocked(silent = true) else {
                loadError = e.message ?: "加载更多失败"
                false
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            loadError = e.message ?: "加载更多失败"
            false
        } finally {
            loadingMore = false
        }
    }

    fun addCreated(snapshot: SessionSnapshot) {
        prepend(snapshot)
        scrollToLatestRequest += 1
    }

    private fun prepend(snapshot: SessionSnapshot) {
        val entry = SessionListEntry.Managed(
            key = "session-${snapshot.id}",
            sortTimestamp = parseIsoMillis(snapshot.startedAt) ?: 0L,
            session = snapshot,
        )
        val exists = entries.any { it.key == entry.key }
        entries = listOf(entry) + entries.filter { it.key != entry.key }
        if (!exists) {
            total += 1
            nextOffset += 1
        }
    }

    fun clearError(message: String) {
        if (loadError == message) loadError = null
    }

    fun isRestoring(history: HistorySession): Boolean = history.key in restoringHistoryKeys

    suspend fun restore(history: HistorySession): SessionSnapshot? {
        val key = history.key
        if (key in restoringHistoryKeys) return null
        restoringHistoryKeys = restoringHistoryKeys + key
        return try {
            operationMutex.withLock {
                try {
                    val resumed = port.resumeHistory(history)
                    removeHistoryLocally(history)
                    prepend(resumed)
                    loadUnlocked(silent = true)
                    loadError = null
                    resumed
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    loadError = e.message ?: "恢复失败"
                    null
                }
            }
        } finally {
            restoringHistoryKeys = restoringHistoryKeys - key
        }
    }

    suspend fun delete(entry: SessionListEntry): Boolean = delete(listOf(entry))

    suspend fun delete(targets: Collection<SessionListEntry>): Boolean = operationMutex.withLock {
        if (targets.isEmpty()) return@withLock true
        val previousEntries = entries
        val previousTotal = total
        val managed = targets.filterIsInstance<SessionListEntry.Managed>().map { it.session }
        val history = targets.filterIsInstance<SessionListEntry.Recoverable>().map { it.history }
        val targetKeys = targets.mapTo(mutableSetOf()) { it.key }
        entries = entries.filter { it.key !in targetKeys }
        total = (total - targetKeys.size).coerceAtLeast(entries.size)

        val failed = coroutineScope {
            val managedDeletes = managed.map { session ->
                async { runCatching { port.deleteSession(session.id) }.isFailure }
            }
            val historyDeletes = history.groupBy { it.apiProvider }.map { (provider, sessions) ->
                async {
                    runCatching {
                        port.deleteHistoryBatch(provider, sessions.map { it.claudeSessionId })
                    }.isFailure
                }
            }
            (managedDeletes + historyDeletes).any { it.await() }
        }
        if (!failed) {
            loadUnlocked(silent = true)
            return@withLock true
        }

        if (!loadUnlocked(silent = true)) {
            entries = previousEntries
            total = previousTotal
            loadError = "删除失败，已恢复本地列表"
        }
        false
    }

    private fun removeHistoryLocally(history: HistorySession) {
        entries = entries.filter {
            it !is SessionListEntry.Recoverable ||
                it.history.id != history.id || it.history.apiProvider != history.apiProvider
        }
    }

    private companion object {
        const val PAGE_SIZE = 6
        val HistorySession.key: String get() = "$apiProvider:$id"
    }
}
