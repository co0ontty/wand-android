package com.wand.app.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wand.app.data.HistorySession
import com.wand.app.data.SessionListPort
import com.wand.app.data.SessionSnapshot
import com.wand.app.ui.ScopedStore
import com.wand.app.ui.parseIsoMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SessionListEntry {
    val key: String
    val sortTimestamp: Long

    data class Managed(val session: SessionSnapshot) : SessionListEntry {
        override val key: String = "session-${session.id}"
        override val sortTimestamp: Long = parseIsoMillis(session.startedAt) ?: 0L
    }

    data class Recoverable(val session: HistorySession) : SessionListEntry {
        // provider 必须进入 key：Claude / Codex 的历史 ID 分属不同接口，不能在多选时混淆。
        override val key: String = "recoverable-${session.apiProvider}-${session.id}"
        override val sortTimestamp: Long = session.mtimeMs?.toLong() ?: parseIsoMillis(session.timestamp) ?: 0L
    }
}

/**
 * 会话列表 module。拥有加载、轮询、恢复、删除和本地/远端一致性；
 * Compose 调用方只消费状态并发送意图，不接触 SessionListPort。
 */
class SessionListState(private val port: SessionListPort) : ScopedStore() {
    var sessions by mutableStateOf<List<SessionSnapshot>>(emptyList())
        private set
    var historySessions by mutableStateOf<List<HistorySession>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    /** 状态与列表同生命周期；重新进入列表时由页面主动回到最新条目。 */
    val scrollState = LazyListState()
    var scrollToLatestRequest by mutableLongStateOf(0L)
        private set
    private var restoringHistoryKeys by mutableStateOf<Set<String>>(emptySet())

    private val operationMutex = Mutex()
    private var syncing = false

    val visibleSessions: List<SessionSnapshot>
        get() = sessions
    val isRestoringHistory: Boolean
        get() = restoringHistoryKeys.isNotEmpty()

    /** 本机可恢复会话：过滤空记录 / 已被 wand 纳管的记录。 */
    val visibleHistorySessions: List<HistorySession>
        get() {
            val managedKeys = sessions.mapNotNull { session ->
                session.claudeSessionId?.let { providerSessionId ->
                    historyApiProvider(session.provider)?.let { provider ->
                        provider to providerSessionId
                    }
                }
            }.toSet()
            return historySessions
                .filter {
                    (it.hasConversation ?: true) &&
                        !(it.managedByWand ?: false) &&
                        (it.apiProvider to it.claudeSessionId) !in managedKeys
                }
                .sortedByDescending { it.mtimeMs ?: 0.0 }
        }

    val visibleEntries: List<SessionListEntry>
        get() = buildList {
            visibleSessions.forEach { add(SessionListEntry.Managed(it)) }
            visibleHistorySessions.forEach { add(SessionListEntry.Recoverable(it)) }
        }.sortedByDescending { it.sortTimestamp }

    fun startSync() {
        if (syncing) return
        syncing = true
        scope.launch {
            load(silent = sessions.isNotEmpty())
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
            val previousClaude = historySessions.filter { it.apiProvider == "claude" }
            val previousCodex = historySessions.filter { it.apiProvider == "codex" }
            coroutineScope {
                val active = async { port.listSessions() }
                // 历史扫描端点单独容错：失败不拖垮会话列表，也不让一次瞬时
                // 故障把上一轮成功加载的 provider 历史整批清空。
                val claude = async { runCatching { port.listClaudeHistory() } }
                val codex = async { runCatching { port.listCodexHistory() } }
                sessions = active.await()
                historySessions =
                    claude.await().getOrElse { previousClaude } +
                        codex.await().getOrElse { previousCodex }
            }
            loadError = null
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (!silent || sessions.isEmpty()) {
                loadError = e.message ?: "加载失败"
            }
            false
        } finally {
            loading = false
        }
    }

    fun addCreated(snapshot: SessionSnapshot) {
        prepend(snapshot)
        scrollToLatestRequest += 1
    }

    private fun prepend(snapshot: SessionSnapshot) {
        sessions = listOf(snapshot) + sessions.filter { it.id != snapshot.id }
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

    suspend fun delete(entries: Collection<SessionListEntry>): Boolean = operationMutex.withLock {
        if (entries.isEmpty()) return@withLock true
        val previousSessions = sessions
        val previousHistory = historySessions
        val managed = entries.filterIsInstance<SessionListEntry.Managed>().map { it.session }
        val history = entries.filterIsInstance<SessionListEntry.Recoverable>().map { it.session }

        managed.forEach(::removeManagedLocally)
        removeHistoryLocally(history)

        val failed = coroutineScope {
            val managedDeletes = managed.map { session ->
                async { runCatching { port.deleteSession(session.id) }.isFailure }
            }
            val historyDeletes = history.groupBy { it.apiProvider }.map { (provider, targets) ->
                async {
                    runCatching {
                        port.deleteHistoryBatch(provider, targets.map { it.claudeSessionId })
                    }.isFailure
                }
            }
            (managedDeletes + historyDeletes).any { it.await() }
        }
        if (!failed) return@withLock true

        // 部分删除可能已经成功；优先用服务端真相重建列表。连重载也失败时恢复本地快照。
        if (!loadUnlocked(silent = true)) {
            sessions = previousSessions
            historySessions = previousHistory
            loadError = "删除失败，已恢复本地列表"
        }
        false
    }

    private fun removeManagedLocally(session: SessionSnapshot) {
        sessions = sessions.filter { it.id != session.id }
        session.claudeSessionId?.let { providerSessionId ->
            historyApiProvider(session.provider)?.let { provider ->
                historySessions = historySessions.filter {
                    it.claudeSessionId != providerSessionId || it.apiProvider != provider
                }
            }
        }
    }

    private fun removeHistoryLocally(history: HistorySession) {
        historySessions = historySessions.filter {
            it.id != history.id || it.apiProvider != history.apiProvider
        }
    }

    private fun removeHistoryLocally(targets: Collection<HistorySession>) {
        val keys = targets.mapTo(mutableSetOf()) { it.apiProvider to it.id }
        historySessions = historySessions.filter { (it.apiProvider to it.id) !in keys }
    }
}

private val HistorySession.key: String get() = "$apiProvider:$id"

private fun historyApiProvider(provider: String?): String? = when (provider) {
    "codex" -> "codex"
    null, "", "claude" -> "claude"
    else -> null
}
