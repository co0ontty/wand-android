package com.wand.app.ui.screens

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.geometry.Offset

internal suspend fun PointerInputScope.companionGesture(
    onTap: () -> Unit,
    onDrag: (Float) -> Unit, onSnap: () -> Unit, onDragCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        down.consume()
        val state = CompanionGestureState()
        var total = Offset.Zero
        var released = false
        try {
            while (state.phase != CompanionGesturePhase.Ended) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) { released = !change.isConsumed; change.consume(); break }
                // Accumulate movement; local coordinates shift with the dragged node itself.
                total += change.position - change.previousPosition
                val delta = total
                val effect = state.move(delta.x, delta.y, viewConfiguration.touchSlop)
                if (effect == CompanionGestureEffect.Drag) onDrag(delta.x)
                change.consume()
            }
        } finally {
            when (state.release(!released)) {
                CompanionGestureEffect.Tap -> onTap()
                CompanionGestureEffect.Snap -> onSnap()
                else -> Unit
            }
            onDragCancel()
        }
    }
}
