package com.wand.app.ui.screens

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.orderWorkspaceSessions
import com.wand.app.ui.Screen
import kotlin.math.abs

private val TaskSessionSwipeMinDistance = 72.dp

/**
 * 可被左右滑动切换的「兄弟会话」列表：
 * - 任务会话 → 同任务下的全部工作窗口；
 * - 未分组会话 → 同目录下的未分组会话（含侧栏合并展示的未命名任务会话）。
 *
 * 顺序与侧栏展示一致，滑动方向据此判定。
 */
internal fun siblingSessionsFor(
    groups: List<TaskDirectoryGroup>,
    taskId: String?,
    sessionId: String?,
): List<WorkspaceSessionSummary> {
    if (!taskId.isNullOrBlank()) {
        return groups.asSequence()
            .flatMap { it.tasks.asSequence() }
            .firstOrNull { it.id == taskId }
            ?.sessions
            ?.let(::orderWorkspaceSessions)
            .orEmpty()
    }
    if (sessionId.isNullOrBlank()) return emptyList()
    // 侧栏把未命名任务的会话并入「未分组终端」，这里必须用同一份扁平化结果，
    // 否则未命名任务里的会话拿不到兄弟列表，滑动失效。
    groups.map(::flattenUnnamedTasksIntoStandalone).forEach { candidate ->
        if (candidate.standaloneSessions.any { it.id == sessionId }) {
            // 与任务工作窗口一致：按 startedAt 升序，左滑前进到更新的会话。
            return orderWorkspaceSessions(candidate.standaloneSessions)
        }
    }
    return emptyList()
}

/**
 * Returns the adjacent session for a completed horizontal swipe.
 * A left swipe advances to the next tab; a right swipe goes back to the previous tab.
 */
internal fun taskSessionSwipeTarget(
    sessions: List<WorkspaceSessionSummary>,
    currentSessionId: String,
    horizontalDrag: Float,
    minDistance: Float = 72f,
): WorkspaceSessionSummary? {
    if (abs(horizontalDrag) < minDistance) return null
    val currentIndex = sessions.indexOfFirst { it.id == currentSessionId }
    if (currentIndex < 0) return null
    val targetIndex = currentIndex + if (horizontalDrag < 0f) 1 else -1
    return sessions.getOrNull(targetIndex)
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

/** Adds left/right navigation to the sibling sessions without interfering with vertical scroll. */
@Composable
internal fun Modifier.taskSessionSwipe(
    sessions: List<WorkspaceSessionSummary>,
    currentSessionId: String,
    onSelect: (WorkspaceSessionSummary) -> Unit,
): Modifier {
    if (sessions.size < 2) return this
    val latestOnSelect = rememberUpdatedState(onSelect)
    val density = LocalDensity.current
    val minDistancePx = with(density) { TaskSessionSwipeMinDistance.toPx() }
    val sessionIds = remember(sessions) { sessions.map { it.id } }
    return pointerInput(sessionIds, currentSessionId, minDistancePx) {
        var horizontalDrag = 0f
        detectHorizontalDragGestures(
            onHorizontalDrag = { _, dragAmount -> horizontalDrag += dragAmount },
            onDragEnd = {
                taskSessionSwipeTarget(
                    sessions = sessions,
                    currentSessionId = currentSessionId,
                    horizontalDrag = horizontalDrag,
                    minDistance = minDistancePx,
                )?.let(latestOnSelect.value)
                horizontalDrag = 0f
            },
            onDragCancel = { horizontalDrag = 0f },
        )
    }
}
