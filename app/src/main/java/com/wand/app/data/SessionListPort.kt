package com.wand.app.data

/** Wand server seam used by the session-list module. */
interface SessionListPort {
    suspend fun fetchSessionList(
        offset: Int,
        limit: Int,
        revision: String? = null,
    ): SessionListPage
    suspend fun fetchSessionDirectories(): SessionDirectoryTreeResponse {
        throw UnsupportedOperationException("会话目录接口不可用")
    }
    suspend fun renameSessionDirectory(path: String, name: String) {
        throw UnsupportedOperationException("工作区重命名接口不可用")
    }
    suspend fun resumeHistory(history: HistorySession): SessionSnapshot
    suspend fun deleteSession(id: String)
    suspend fun deleteHistoryBatch(provider: String, ids: List<String>)
}
