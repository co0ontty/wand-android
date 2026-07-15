package com.wand.app.data

/** Wand server seam used by the session-list module. */
interface SessionListPort {
    suspend fun listSessions(): List<SessionSnapshot>
    suspend fun listClaudeHistory(): List<HistorySession>
    suspend fun listCodexHistory(): List<HistorySession>
    suspend fun resumeHistory(history: HistorySession): SessionSnapshot
    suspend fun deleteSession(id: String)
    suspend fun deleteHistoryBatch(provider: String, ids: List<String>)
}
