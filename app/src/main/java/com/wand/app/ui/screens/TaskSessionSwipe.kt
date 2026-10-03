package com.wand.app.ui.screens

import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.orderWorkspaceSessions
import com.wand.app.ui.Screen

/**
 * 列表点选转场使用的「兄弟会话」列表：
 * - 任务会话 → 同任务下的全部工作窗口；
 * - 未分组会话 → 同目录下的未分组会话。未命名任务仍保留自身边界。
 *
 * 顺序与侧栏展示一致，转场方向据此判定。
 */
internal fun siblingSessionsFor(
    groups: List<TaskDirectoryGroup>,
    taskId: String?,
    sessionId: String?,
): List<WorkspaceSessionSummary> {
    // The live hierarchy wins over a route restored before a session was moved.
    if (!sessionId.isNullOrBlank()) {
        groups.asSequence().flatMap { it.tasks.asSequence() }
            .firstOrNull { task -> task.sessions.any { it.id == sessionId } }
            ?.let { return orderWorkspaceSessions(it.sessions) }
        groups.firstOrNull { group -> group.standaloneSessions.any { it.id == sessionId } }
            ?.let { return orderWorkspaceSessions(it.standaloneSessions) }
    }
    if (!taskId.isNullOrBlank()) {
        return groups.asSequence()
            .flatMap { it.tasks.asSequence() }
            .firstOrNull { it.id == taskId }
            ?.sessions
            ?.let(::orderWorkspaceSessions)
            .orEmpty()
    }
    if (sessionId.isNullOrBlank()) return emptyList()
    return emptyList()
}

internal fun taskSessionTransitionDirection(
    initial: Screen,
    target: Screen,
    sessions: List<WorkspaceSessionSummary>,
): Int? {
    val initialSession = taskSessionScreenIdentity(initial) ?: return null
    val targetSession = taskSessionScreenIdentity(target) ?: return null
    // 未分组会话的 taskId 同为 null，靠 sessionId + 兄弟列表下标判定；跨目录的会话
    // 不会同时出现在传入的列表里，因此不会误判为同一组。
    if (initialSession.taskId != targetSession.taskId || initialSession.sessionId == targetSession.sessionId) {
        return null
    }
    val initialIndex = sessions.indexOfFirst { it.id == initialSession.sessionId }
    val targetIndex = sessions.indexOfFirst { it.id == targetSession.sessionId }
    if (initialIndex < 0 || targetIndex < 0 || initialIndex == targetIndex) return null
    return if (targetIndex > initialIndex) 1 else -1
}

private data class TaskSessionScreenIdentity(
    /** 未分组会话为 null；任务内会话为所属 taskId。 */
    val taskId: String?,
    val sessionId: String,
)

private fun taskSessionScreenIdentity(screen: Screen): TaskSessionScreenIdentity? = when (screen) {
    is Screen.Chat -> TaskSessionScreenIdentity(screen.taskId, screen.sessionId)
    is Screen.PtyTerminal -> TaskSessionScreenIdentity(screen.taskId, screen.sessionId)
    else -> null
}
