package com.wand.app.ui.screens

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun PointerInputScope.companionGesture(
    canDrag: Boolean,
    onTap: () -> Unit, onHold: () -> Unit, onVoiceMove: (Boolean) -> Unit,
    onVoiceEnd: () -> Unit, onVoiceCancel: () -> Unit,
    onDrag: (Float) -> Unit, onSnap: () -> Unit, onDragCancel: () -> Unit,
) {
    val cancelDistance = 60.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        down.consume()
        val state = CompanionGestureState(canDrag)
        var elapsed = 0L
        var total = Offset.Zero
        var released = false
        try {
            while (state.phase != CompanionGesturePhase.Ended) {
                val event = if (state.phase == CompanionGesturePhase.Pending)
                    withTimeoutOrNull((VOICE_HOLD_THRESHOLD_MS - elapsed).coerceAtLeast(0)) { awaitPointerEvent(PointerEventPass.Initial) }
                else awaitPointerEvent(PointerEventPass.Initial)
                if (event == null) { if (state.timeout() == CompanionGestureEffect.Hold) onHold(); continue }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                elapsed = change.uptimeMillis - down.uptimeMillis
                if (!change.pressed) { released = !change.isConsumed; change.consume(); break }
                // Accumulate movement; local coordinates shift with the dragged node itself.
                total += change.position - change.previousPosition
                val delta = total
                val effect = state.move(delta.x, delta.y, viewConfiguration.touchSlop, cancelDistance)
                if (effect == CompanionGestureEffect.Drag) onDrag(delta.x)
                if (state.phase == CompanionGesturePhase.Recording) onVoiceMove(state.cancelVoice)
                change.consume()
            }
        } finally {
            when (state.release(!released)) {
                CompanionGestureEffect.Tap -> onTap()
                CompanionGestureEffect.Snap -> onSnap()
                CompanionGestureEffect.FinishVoice -> onVoiceEnd()
                CompanionGestureEffect.CancelVoice -> onVoiceCancel()
                else -> Unit
            }
            onDragCancel()
        }
    }
}
