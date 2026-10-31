package com.wand.app.ui

import android.content.Context
import java.security.MessageDigest

/** Persisted safety state for one stable Pending-conversation entry scope. */
enum class PendingCreationOperation { TASK, SESSION }

data class PendingCreationState(
    val taskId: String? = null,
    val sessionId: String? = null,
    val unconfirmed: Boolean = false,
    val operation: PendingCreationOperation? = null,
    val taskCwd: String? = null,
    val taskWorkspaceId: String? = null,
    val conversationOnly: Boolean = false,
) {
    fun blocksCreation(volatileUnconfirmed: Boolean, hasKnownSession: Boolean): Boolean =
        (unconfirmed || volatileUnconfirmed) && !hasKnownSession
}

class PendingCreationPersistenceException(message: String) : IllegalStateException(message)

interface PendingCreationPersistence {
    fun read(key: String): PendingCreationState
    fun write(key: String, state: PendingCreationState): Boolean
    fun remove(key: String): Boolean
}

class PendingCreationStore(private val persistence: PendingCreationPersistence) {
    private fun requireSaved(saved: Boolean, message: String) {
        if (!saved) throw PendingCreationPersistenceException(message)
    }
    fun state(key: String): PendingCreationState = persistence.read(key)

    /** Must be durably recorded before issuing a create request. */
    fun markUnconfirmed(key: String, operation: PendingCreationOperation) {
        val current = state(key)
        check(operation != PendingCreationOperation.TASK || !current.conversationOnly) { "已选择直接对话，不会重发任务创建。" }
        requireSaved(
            persistence.write(key, current.copy(unconfirmed = true, operation = operation)),
            "无法安全保存创建状态；未发送创建请求。",
        )
    }

    fun recordTaskAccepted(
        key: String,
        taskId: String,
        taskCwd: String? = null,
        taskWorkspaceId: String? = null,
    ) {
        val current = state(key)
        requireSaved(
            persistence.write(key, current.copy(
                taskId = taskId,
                taskCwd = taskCwd,
                taskWorkspaceId = taskWorkspaceId,
                unconfirmed = false,
                operation = null,
            )),
            "任务已创建，但无法保存续接标识；请勿重复创建。",
        )
    }

    /** Explicit user selection may continue a task POST with unknown delivery, never a session POST. */
    fun chooseExistingTask(key: String, taskId: String, taskCwd: String, taskWorkspaceId: String) {
        val current = state(key)
        check(current.unconfirmed && current.operation == PendingCreationOperation.TASK && current.sessionId == null) {
            "当前创建状态不能通过选择任务继续。"
        }
        check(taskId.isNotBlank() && taskCwd.isNotBlank() && taskWorkspaceId.isNotBlank()) {
            "所选任务的运行目录或归属不完整。"
        }
        recordTaskAccepted(key, taskId, taskCwd, taskWorkspaceId)
    }

    /** A task-only POST cannot have sent a message. Preserve it and explicitly continue without a task. */
    fun continueWithoutTask(key: String): String {
        val current = state(key)
        check(current.unconfirmed && current.operation == PendingCreationOperation.TASK && current.sessionId == null && current.taskId == null) {
            "当前创建状态不能跳过任务。"
        }
        val unresolvedKey = "$key.unresolved-task.${java.util.UUID.randomUUID()}"
        requireSaved(persistence.write(unresolvedKey, current), "无法保留上次任务创建记录。")
        requireSaved(persistence.write(key, PendingCreationState(conversationOnly = true)), "无法保存直接对话状态。")
        return unresolvedKey
    }

    fun sessionAccepted(key: String, sessionId: String) {
        val current = state(key)
        requireSaved(
            persistence.write(key, current.copy(
                sessionId = sessionId,
                taskCwd = null,
                taskWorkspaceId = null,
                unconfirmed = false,
                operation = null,
            )),
            "会话已创建，但无法保存续接标识；请勿重复创建。",
        )
    }

    /** A definite rejection only clears the in-flight marker; accepted task/session IDs remain resumable. */
    fun definiteRejection(key: String) {
        val current = state(key)
        if (current.operation == PendingCreationOperation.TASK && current.taskId == null) {
            requireSaved(persistence.remove(key), "无法保存创建拒绝状态；请勿重复创建。")
            return
        }
        val next = current.copy(unconfirmed = false, operation = null)
        val saved = if (next.taskId == null && next.sessionId == null && !next.conversationOnly) persistence.remove(key)
        else persistence.write(key, next)
        requireSaved(saved, "无法保存创建拒绝状态；请勿重复创建。")
    }

    fun clear(key: String) {
        requireSaved(persistence.remove(key), "无法清除创建续接状态。")
    }

    companion object {
        fun stableKey(serverId: String, entryScope: String): String {
            val source = "$serverId\u0000$entryScope"
            val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

class SharedPreferencesPendingCreationPersistence(context: Context) : PendingCreationPersistence {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(key: String): PendingCreationState = PendingCreationState(
        taskId = prefs.getString("$key.task", null),
        sessionId = prefs.getString("$key.session", null),
        unconfirmed = prefs.getBoolean("$key.unknown", false),
        operation = prefs.getString("$key.operation", null)?.let { runCatching { PendingCreationOperation.valueOf(it) }.getOrNull() },
        taskCwd = prefs.getString("$key.taskCwd", null),
        taskWorkspaceId = prefs.getString("$key.taskWorkspaceId", null),
        conversationOnly = prefs.getBoolean("$key.conversationOnly", false),
    )

    override fun write(key: String, state: PendingCreationState): Boolean = prefs.edit().apply {
        if (state.taskId == null) remove("$key.task") else putString("$key.task", state.taskId)
        if (state.sessionId == null) remove("$key.session") else putString("$key.session", state.sessionId)
        if (state.taskCwd == null) remove("$key.taskCwd") else putString("$key.taskCwd", state.taskCwd)
        if (state.taskWorkspaceId == null) remove("$key.taskWorkspaceId") else putString("$key.taskWorkspaceId", state.taskWorkspaceId)
        putBoolean("$key.unknown", state.unconfirmed)
        putBoolean("$key.conversationOnly", state.conversationOnly)
        if (state.operation == null) remove("$key.operation") else putString("$key.operation", state.operation.name)
    }.commit()

    override fun remove(key: String): Boolean = prefs.edit()
        .remove("$key.task")
        .remove("$key.session")
        .remove("$key.taskCwd")
        .remove("$key.taskWorkspaceId")
        .remove("$key.unknown")
        .remove("$key.conversationOnly")
        .remove("$key.operation")
        .commit()

    private companion object {
        const val PREFS_NAME = "wand_pending_creation"
    }
}
