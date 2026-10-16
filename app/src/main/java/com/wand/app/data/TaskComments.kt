package com.wand.app.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

data class TaskComment(
    val id: String,
    val taskId: String,
    val authorName: String,
    val body: String,
    val createdAt: String,
) {
    companion object {
        fun parseList(response: JSONObject, taskId: String): List<TaskComment> {
            val comments = response.optJSONArray("comments") ?: return emptyList()
            return (0 until comments.length()).mapNotNull { index ->
                val item = comments.optJSONObject(index) ?: return@mapNotNull null
                if (item.optString("taskId") != taskId || item.optString("id").isBlank() ||
                    item.optString("body").isBlank()) return@mapNotNull null
                TaskComment(item.getString("id"), taskId, item.optString("authorName"),
                    item.getString("body"), item.optString("createdAt"))
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
