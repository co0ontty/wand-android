package com.wand.app.ui.screens

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.ui.Screen
import kotlin.math.abs

private val TaskSessionSwipeMinDistance = 72.dp

/**
 * Returns the adjacent task session for a completed horizontal swipe.
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
    if (initialSession.taskId != targetSession.taskId || initialSession.sessionId == targetSession.sessionId) {
        return null
    }
    val initialIndex = sessions.indexOfFirst { it.id == initialSession.sessionId }
    val targetIndex = sessions.indexOfFirst { it.id == targetSession.sessionId }
    if (initialIndex < 0 || targetIndex < 0 || initialIndex == targetIndex) return null
    return if (targetIndex > initialIndex) 1 else -1
}

private data class TaskSessionScreenIdentity(
    val taskId: String,
    val sessionId: String,
)

private fun taskSessionScreenIdentity(screen: Screen): TaskSessionScreenIdentity? = when (screen) {
    is Screen.Chat -> screen.taskId?.let { TaskSessionScreenIdentity(it, screen.sessionId) }
    is Screen.PtyTerminal -> screen.taskId?.let { TaskSessionScreenIdentity(it, screen.sessionId) }
    else -> null
}

/** Adds left/right navigation to the task session tabs without interfering with vertical scroll. */
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
