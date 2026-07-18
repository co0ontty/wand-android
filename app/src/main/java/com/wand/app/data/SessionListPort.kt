package com.wand.app.data

/** Wand server seam used by the session-list module. */
interface SessionListPort {
    suspend fun fetchSessionList(
        offset: Int,
        limit: Int,
        revision: String? = null,
    ): SessionListPage
    suspend fun resumeHistory(history: HistorySession): SessionSnapshot
    suspend fun deleteSession(id: String)
    suspend fun deleteHistoryBatch(provider: String, ids: List<String>)
}
