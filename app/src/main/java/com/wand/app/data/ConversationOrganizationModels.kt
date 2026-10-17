package com.wand.app.data

import org.json.JSONArray
import org.json.JSONObject

interface ConversationOrganizationPort {
    suspend fun previewConversationOrganization(target: ConversationOrganizationTarget, mode: ConversationOrganizationMode): ConversationOrganizationPreview
    suspend fun startConversationOrganization(token: String, force: Boolean): ConversationOrganizationJob
    suspend fun conversationOrganizationJobByToken(token: String): ConversationOrganizationJob
    suspend fun conversationOrganizationJob(id: String): ConversationOrganizationJob
    suspend fun cancelConversationOrganization(id: String): ConversationOrganizationJob
}

/** 服务端会话内容归组的目录范围；global 工作区仍使用 workspace，而不是 all。 */
sealed class ConversationOrganizationTarget {
    abstract fun toJson(): JSONObject

    data class Workspace(val workspaceId: String) : ConversationOrganizationTarget() {
        override fun toJson(): JSONObject = JSONObject()
            .put("kind", "workspace")
            .put("workspaceId", workspaceId)
    }

    data class Directory(val cwd: String) : ConversationOrganizationTarget() {
        override fun toJson(): JSONObject = JSONObject()
            .put("kind", "directory")
            .put("cwd", cwd)
    }
}

fun TaskDirectoryGroup.conversationOrganizationTarget(): ConversationOrganizationTarget? {
    return if (synthetic) {
        val rawCwd = workspaceCwd.trim()
        val cwd = normalizeWorkspacePath(rawCwd)
        rawCwd.takeIf { it.isNotBlank() }?.let { ConversationOrganizationTarget.Directory(cwd) }
    } else {
        // Includes wand-global: server resolves exact membership for this workspace.
        workspaceId.trim().takeIf { it.isNotBlank() }?.let(ConversationOrganizationTarget::Workspace)
    }
}

enum class ConversationOrganizationMode(val raw: String, val label: String, val detail: String) {
    Auto("auto", "默认范围", "保护人工命名任务中的会话"),
    All("all", "所有工作会话", "包含人工任务中的会话"),
}

data class ConversationOrganizationPreview(
    val token: String,
    val sessionCount: Int,
    val manualSessionCount: Int,
    val skippedSessionCount: Int,
    val existingTaskCount: Int,
    val contentCharacters: Int,
    val estimatedModelRequests: Int,
    val unchanged: Boolean,
    val activeJobId: String?,
) {
    companion object {
        fun parse(o: JSONObject): ConversationOrganizationPreview? {
            val token = o.str("token")?.takeIf { it.isNotBlank() } ?: return null
            return ConversationOrganizationPreview(
                token = token,
                sessionCount = o.int("sessionCount") ?: 0,
                manualSessionCount = o.int("manualSessionCount") ?: 0,
                skippedSessionCount = o.int("skippedSessionCount") ?: 0,
                existingTaskCount = o.int("existingTaskCount") ?: 0,
                contentCharacters = o.int("contentCharacters") ?: 0,
                estimatedModelRequests = o.int("estimatedModelRequests") ?: 0,
                unchanged = o.bool("unchanged") ?: false,
                activeJobId = o.str("activeJobId")?.takeIf { it.isNotBlank() },
            )
        }
    }
}

data class ConversationOrganizationCreatedTask(
    val taskId: String,
    val cardId: String,
    val title: String,
    val sessionCount: Int,
) {
    companion object {
        fun parse(o: JSONObject): ConversationOrganizationCreatedTask? {
            val taskId = o.str("taskId")?.takeIf { it.isNotBlank() } ?: return null
            return ConversationOrganizationCreatedTask(taskId, o.str("cardId").orEmpty(), o.str("title").orEmpty(), o.int("sessionCount") ?: 0)
        }
    }
}

data class ConversationOrganizationReusedTask(val taskId: String, val title: String, val sessionCount: Int) {
    companion object { fun parse(o: JSONObject): ConversationOrganizationReusedTask? =
        o.str("taskId")?.takeIf { it.isNotBlank() }?.let { ConversationOrganizationReusedTask(it, o.str("title").orEmpty(), o.int("sessionCount") ?: 0) } }
}

data class ConversationOrganizationUpdatedTask(val taskId: String, val previousTitle: String, val title: String) {
    companion object { fun parse(o: JSONObject): ConversationOrganizationUpdatedTask? =
        o.str("taskId")?.takeIf { it.isNotBlank() }?.let { ConversationOrganizationUpdatedTask(it, o.str("previousTitle").orEmpty(), o.str("title").orEmpty()) } }
}

data class ConversationOrganizationMovedSession(val sessionId: String, val taskId: String, val taskName: String) {
    companion object { fun parse(o: JSONObject): ConversationOrganizationMovedSession? =
        o.str("sessionId")?.takeIf { it.isNotBlank() }?.let { ConversationOrganizationMovedSession(it, o.str("taskId").orEmpty(), o.str("taskName").orEmpty()) } }
}

data class ConversationOrganizationUnassignedSession(val sessionId: String, val reason: String) {
    companion object { fun parse(o: JSONObject): ConversationOrganizationUnassignedSession? =
        o.str("sessionId")?.takeIf { it.isNotBlank() }?.let { ConversationOrganizationUnassignedSession(it, o.str("reason").orEmpty()) } }
}

data class ConversationOrganizationRefusal(val id: String, val kind: String, val reason: String) {
    companion object { fun parse(o: JSONObject): ConversationOrganizationRefusal? =
        o.str("id")?.takeIf { it.isNotBlank() }?.let { ConversationOrganizationRefusal(it, o.str("kind").orEmpty(), o.str("reason").orEmpty()) } }
}

data class ConversationOrganizationResult(
    val ok: Boolean,
    val foundSessions: Int,
    val eligibleSessions: Int,
    val skippedManualSessions: Int,
    val skippedSessions: Int,
    val analyzedSessions: Int,
    val createdTasks: List<ConversationOrganizationCreatedTask>,
    val reusedTasks: List<ConversationOrganizationReusedTask>,
    val updatedTasks: List<ConversationOrganizationUpdatedTask>,
    val movedSessions: List<ConversationOrganizationMovedSession>,
    val unassignedSessions: List<ConversationOrganizationUnassignedSession>,
    val refused: List<ConversationOrganizationRefusal>,
    val unchanged: Boolean?,
    val cancelled: Boolean?,
    val error: String?,
) {
    companion object {
        fun parse(o: JSONObject): ConversationOrganizationResult = ConversationOrganizationResult(
            ok = o.bool("ok") ?: true,
            foundSessions = o.int("foundSessions") ?: 0,
            eligibleSessions = o.int("eligibleSessions") ?: 0,
            skippedManualSessions = o.int("skippedManualSessions") ?: 0,
            skippedSessions = o.int("skippedSessions") ?: 0,
            analyzedSessions = o.int("analyzedSessions") ?: 0,
            createdTasks = o.arr("createdTasks").parseEach(ConversationOrganizationCreatedTask::parse),
            reusedTasks = o.arr("reusedTasks").parseEach(ConversationOrganizationReusedTask::parse),
            updatedTasks = o.arr("updatedTasks").parseEach(ConversationOrganizationUpdatedTask::parse),
            movedSessions = o.arr("movedSessions").parseEach(ConversationOrganizationMovedSession::parse),
            unassignedSessions = o.arr("unassignedSessions").parseEach(ConversationOrganizationUnassignedSession::parse),
            refused = o.arr("refused").parseEach(ConversationOrganizationRefusal::parse),
            unchanged = o.bool("unchanged"),
            cancelled = o.bool("cancelled"),
            error = o.str("error"),
        )
    }
}

data class ConversationOrganizationJob(
    val id: String,
    val status: String,
    val stage: String,
    val processedSessions: Int,
    val totalSessions: Int,
    val modelRequests: Int,
    val result: ConversationOrganizationResult,
    val logSessionId: String?,
    val error: String?,
) {
    val isRunning: Boolean get() = status == "running"
    val isFailure: Boolean get() = status == "failed" || status == "interrupted"

    companion object {
        fun parse(o: JSONObject): ConversationOrganizationJob? {
            val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return null
            val status = o.str("status")?.takeIf { it in setOf("running", "completed", "failed", "cancelled", "interrupted") } ?: return null
            val result = o.obj("result") ?: return null
            return ConversationOrganizationJob(
                id = id,
                status = status,
                stage = o.str("stage").orEmpty(),
                processedSessions = o.int("processedSessions") ?: 0,
                totalSessions = o.int("totalSessions") ?: 0,
                modelRequests = o.int("modelRequests") ?: 0,
                result = ConversationOrganizationResult.parse(result),
                logSessionId = o.str("logSessionId")?.takeIf { it.isNotBlank() },
                error = o.str("error")?.takeIf { it.isNotBlank() },
            )
        }
    }
}

private fun <T> JSONArray?.parseEach(parse: (JSONObject) -> T?): List<T> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) optJSONObject(index)?.let { parse(it)?.let(::add) }
    }
}
