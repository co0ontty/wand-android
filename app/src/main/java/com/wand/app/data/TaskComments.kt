package com.wand.app.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

data class TaskCommentDispatch(val employeeId: String, val employeeName: String, val state: String, val sessionId: String?, val status: String?) {
    val deliveryUncertain: Boolean get() = state in setOf("pending", "unknown") || status in setOf("pending", "unknown")
    val label: String get() = when (status ?: state) {
        "running" -> "执行中"; "blocked" -> "等待审批"; "idle" -> "本轮已结束"; "exited" -> "会话已退出"
        "failed" -> "执行失败"; "stopped" -> "已停止"; "history" -> "历史会话"; "unavailable" -> "会话不可用"
        "accepted" -> "已创建执行"; else -> "送达未知 · 不会重放"
    }
}

data class TaskComment(
    val id: String,
    val taskId: String,
    val authorName: String,
    val body: String,
    val createdAt: String,
    val sourceCommentId: String? = null,
    val dispatch: TaskCommentDispatch? = null,
) {
    companion object {
        fun parseList(response: JSONObject, taskId: String): List<TaskComment> {
            val comments = response.optJSONArray("comments") ?: return emptyList()
            return (0 until comments.length()).mapNotNull { index ->
                val item = comments.optJSONObject(index) ?: return@mapNotNull null
                if (item.optString("taskId") != taskId || item.optString("id").isBlank() ||
                    item.optString("body").isBlank()) return@mapNotNull null
                TaskComment(item.getString("id"), taskId, item.optString("authorName"),
                    item.getString("body"), item.optString("createdAt"), item.optString("sourceCommentId").takeUnless { it.isBlank() || it == "null" },
                    item.optJSONObject("dispatch")?.let { d -> TaskCommentDispatch(d.optString("employeeId"), d.optString("employeeName"), d.optString("state"),
                        d.optString("sessionId").takeUnless { it.isBlank() || it == "null" }, d.optString("status").takeUnless { it.isBlank() || it == "null" }) })
            }
        }
    }
}

/** Recheck cancellation after even a non-cancellable transport; never apply another task's rows. */
internal suspend fun loadTaskComments(api: TaskBoardPort, taskId: String): List<TaskComment> {
    val comments = api.listTaskComments(taskId)
    currentCoroutineContext().ensureActive()
    return comments.filter { it.taskId == taskId && it.body.isNotBlank() }
}
