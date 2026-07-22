package com.wand.app.data

/** Wand server seam used by the new-session workflow. */
interface NewSessionPort {
    suspend fun serverConfig(): ServerConfigInfo
    suspend fun models(): ModelsResponse
    suspend fun recentPaths(): List<RecentPath>
    suspend fun updateNewSessionDefaults(
        mode: String? = null,
        model: String? = null,
        modelProvider: String = "claude",
        thinkingEffort: String? = null,
        defaultProvider: String? = null,
        defaultSessionKind: String? = null,
    )
    suspend fun createStructuredSession(
        cwd: String,
        mode: String?,
        prompt: String?,
        provider: String = "claude",
        model: String? = null,
        thinkingEffort: String? = null,
    ): SessionSnapshot
    suspend fun createPtySession(
        cwd: String,
        mode: String?,
        initialInput: String?,
        provider: String = "claude",
        model: String? = null,
        thinkingEffort: String? = null,
    ): SessionSnapshot
}
