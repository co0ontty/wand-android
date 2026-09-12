package com.wand.app.ui.screens

import com.wand.app.data.BOARD_TASK_STATUSES
import com.wand.app.data.BoardTask
import com.wand.app.data.BoardTaskSession
import com.wand.app.data.boardTaskAgentLabels

internal data class BoardTaskStats(
    val total: Int,
    val todo: Int,
    val doing: Int,
    val done: Int,
    val remaining: Int,
    val high: Int,
)

internal data class BoardTaskProgress(
    val completed: Int,
    val total: Int,
)

internal fun boardTaskStats(tasks: List<BoardTask>): BoardTaskStats {
    var todo = 0
    var doing = 0
    var done = 0
    var high = 0
    for (task in tasks) {
        when (task.status) {
            "todo" -> todo += 1
            "doing" -> doing += 1
            else -> done += 1
        }
        if (task.priority == "high" || task.priority == "urgent") high += 1
    }
    return BoardTaskStats(
        total = tasks.size,
        todo = todo,
        doing = doing,
        done = done,
        remaining = todo + doing,
        high = high,
    )
}

internal fun boardTaskMatches(task: BoardTask, query: String, workspaceId: String): Boolean {
    val haystack = "${task.title} ${task.description} ${task.identifier} ${task.workspace?.name.orEmpty()}"
    val matchesQuery = query.isBlank() || haystack.contains(query, ignoreCase = true)
    val matchesWorkspace = workspaceId.isBlank() || task.workspaceId == workspaceId
    return matchesQuery && matchesWorkspace
}

internal fun filterBoardTasks(
    tasks: List<BoardTask>,
    query: String,
    workspaceId: String,
    status: String = "",
): List<BoardTask> = tasks.filter { task ->
    boardTaskMatches(task, query, workspaceId) && (status.isBlank() || task.status == status)
}

internal fun sortBoardTasks(tasks: List<BoardTask>): List<BoardTask> =
    tasks.sortedWith(
        compareBy<BoardTask> { task ->
            BOARD_TASK_STATUSES.indexOf(task.status).takeIf { it >= 0 } ?: 99
        }
            .thenBy { it.sortOrder }
            .thenByDescending { it.updatedAt },
    )

internal fun groupedBoardTasks(tasks: List<BoardTask>): List<Pair<String, List<BoardTask>>> =
    BOARD_TASK_STATUSES.map { status -> status to tasks.filter { it.status == status } }

internal fun boardTaskToggledStatus(status: String): String =
    if (status == "done") "todo" else "done"

internal fun boardTaskDisplayId(task: BoardTask): String =
    task.identifier.ifBlank { task.id.take(8) }

internal fun boardTaskCardTitle(task: BoardTask): String {
    val title = task.title.trim()
    if (title.isNotEmpty()) return title
    val fromDescription = task.description.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !isSyncedWorkspaceDescriptionLine(it) }
    return fromDescription ?: UNNAMED_TASK_NAME
}

internal data class BoardTaskCardModel(
    val title: String,
    val workspaceName: String?,
    val priority: String?,
    val agentLabel: String?,
    val labels: List<String>,
    val processingLabel: String?,
    val sessions: List<BoardTaskSession>,
)

internal fun boardTaskCardModel(task: BoardTask, showWorkspace: Boolean): BoardTaskCardModel {
    val workspaceName = task.workspace?.name?.trim().orEmpty()
    return BoardTaskCardModel(
        title = boardTaskCardTitle(task),
        workspaceName = workspaceName.takeIf { showWorkspace && it.isNotEmpty() },
        priority = task.priority.takeIf { it.isNotBlank() && it != "none" },
        agentLabel = boardTaskAgentLabels(task.sessions, task.agent),
        labels = task.labels.filter { it.isNotBlank() }.take(2),
        processingLabel = boardTaskProcessingLabel(task),
        sessions = task.sessions.take(3),
    )
}

internal fun isSyncedWorkspaceDescription(description: String): Boolean {
    val lines = description.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    if (lines.isEmpty()) return true
    return lines.all(::isSyncedWorkspaceDescriptionLine)
}

internal fun isPlaceholderBoardTask(task: BoardTask): Boolean {
    if (task.sessions.isNotEmpty()) return false
    if (task.priority != "none" && task.priority.isNotBlank()) return false
    if (task.agent != null) return false
    if (task.labels.any { it.isNotBlank() }) return false
    if (!isUnnamedTaskName(task.title)) return false
    return isSyncedWorkspaceDescription(task.description)
}

private fun isSyncedWorkspaceDescriptionLine(line: String): Boolean =
    line.startsWith("项目：") || line.startsWith("目录：") || line.startsWith("分支：")

internal fun boardSessionRunning(status: String): Boolean = status == "running"

internal fun boardSessionFinished(status: String): Boolean =
    status == "exited" || status == "idle"

internal fun boardTaskProcessingLabel(task: BoardTask): String? {
    if (task.status != "doing") return null
    val running = task.sessions.any { boardSessionRunning(it.status) }
    val finished = task.sessions.isNotEmpty() && task.sessions.all { boardSessionFinished(it.status) }
    return when {
        running -> "正在处理..."
        finished -> "等待验收"
        task.sessions.isNotEmpty() -> "暂停处理"
        else -> "等待派发"
    }
}

internal fun boardTaskProgress(task: BoardTask): BoardTaskProgress? {
    if (task.status != "doing") return null
    val total = maxOf(4, task.sessions.size + 2)
    val waitingAcceptance = task.sessions.isNotEmpty() &&
        task.sessions.all { boardSessionFinished(it.status) }
    val completed = if (waitingAcceptance) {
        (total - 1).coerceAtLeast(0)
    } else {
        task.sessions.count { boardSessionFinished(it.status) }.coerceAtMost(total)
    }
    return BoardTaskProgress(completed = completed, total = total)
}

