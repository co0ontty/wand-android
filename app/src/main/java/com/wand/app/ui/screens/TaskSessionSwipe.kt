package com.wand.app.ui.screens

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
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
 * - 未分组会话 → 同目录下的未分组会话。未命名任务仍保留自身边界。
 *
 * 顺序与侧栏展示一致，滑动方向据此判定。
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

/**
 * Adds left/right navigation to the sibling sessions without interfering with vertical scroll.
 *
 * 探测器自己实现而非用 detectHorizontalDragGestures：后者一越过 touch slop 就 consume，
 * 一旦 consume，Compose 会把这条手势从内嵌 WebView（终端）上收回（WebView 收到 cancel），
 * 于是「悬浮球拖到一半不动了」。这里只在横向位移够切换会话时才接管，其余时间不碰指针，
 * 终端里的拖动（悬浮球 / 文本选择 / 滚动）保持完整。
 *
 * [isSuppressed] 为真时整条手势交还给网页：Android 外壳的网页端在悬浮球按下时
 * 通过 WandTerminal 桥把它翻成 true，抬手复位。
 */
@Composable
internal fun Modifier.taskSessionSwipe(
    sessions: List<WorkspaceSessionSummary>,
    currentSessionId: String,
    onSelect: (WorkspaceSessionSummary) -> Unit,
    isSuppressed: () -> Boolean = { false },
): Modifier {
    if (sessions.size < 2) return this
    val latestOnSelect = rememberUpdatedState(onSelect)
    val latestSuppressed = rememberUpdatedState(isSuppressed)
    val density = LocalDensity.current
    val minDistancePx = with(density) { TaskSessionSwipeMinDistance.toPx() }
    val sessionIds = remember(sessions) { sessions.map { it.id } }
    return pointerInput(sessionIds, currentSessionId, minDistancePx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var horizontal = 0f
            var vertical = 0f
            var claimed = false
            var released = false
            while (!released) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.changedToUpIgnoreConsumed()) {
                    released = true
                    continue
                }
                val delta = change.positionChange()
                horizontal += delta.x
                vertical += delta.y
                if (claimed) {
                    change.consume()
                    continue
                }
                // 只有明确要切会话时才接管；斜向拖动（纵向分量更大）不接管。
                if (abs(horizontal) >= minDistancePx && abs(horizontal) > abs(vertical)) {
                    if (latestSuppressed.value()) {
                        // 悬浮球正在被拖动：整条手势留给网页，抬手也不切会话。
                        break
                    }
                    claimed = true
                    change.consume()
                }
            }
            if (claimed) {
                taskSessionSwipeTarget(
                    sessions = sessions,
                    currentSessionId = currentSessionId,
                    horizontalDrag = horizontal,
                    minDistance = minDistancePx,
                )?.let(latestOnSelect.value)
            }
        }
    }
}
