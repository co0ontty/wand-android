package com.wand.app.speech

internal enum class VoiceSessionPhase {
    IDLE,
    RECORDING,
    CANCELING,
    AWAITING_FINAL,
}

internal data class VoiceSessionState(
    val phase: VoiceSessionPhase = VoiceSessionPhase.IDLE,
    val transcript: String = "",
) {
    val pressed: Boolean get() = phase == VoiceSessionPhase.RECORDING || phase == VoiceSessionPhase.CANCELING
    val canceling: Boolean get() = phase == VoiceSessionPhase.CANCELING
}

internal sealed interface VoiceSessionEvent {
    data object Begin : VoiceSessionEvent
    data class Partial(val text: String) : VoiceSessionEvent
    data class CancelChanged(val canceling: Boolean) : VoiceSessionEvent
    data object Release : VoiceSessionEvent
    /** null 表示 final 超时，使用当前 partial；空串 final 也回退到当前 partial。 */
    data class Complete(val finalText: String?) : VoiceSessionEvent
    data object Abort : VoiceSessionEvent
}

internal sealed interface VoiceSessionEffect {
    data object None : VoiceSessionEffect
    data object FinishEngine : VoiceSessionEffect
    data object CancelEngine : VoiceSessionEffect
    data class Commit(val text: String?) : VoiceSessionEffect
}

internal data class VoiceSessionTransition(
    val state: VoiceSessionState,
    val effect: VoiceSessionEffect = VoiceSessionEffect.None,
)

/** Android 无关的按住说话状态机。非法或迟到事件保持当前状态且不产生副作用。 */
internal object VoiceSessionStateMachine {
    fun reduce(state: VoiceSessionState, event: VoiceSessionEvent): VoiceSessionTransition = when (event) {
        VoiceSessionEvent.Begin -> if (state.phase == VoiceSessionPhase.IDLE) {
            VoiceSessionTransition(VoiceSessionState(VoiceSessionPhase.RECORDING))
        } else {
            VoiceSessionTransition(state)
        }

        is VoiceSessionEvent.Partial -> if (state.phase != VoiceSessionPhase.IDLE) {
            VoiceSessionTransition(state.copy(transcript = event.text))
        } else {
            VoiceSessionTransition(state)
        }

        is VoiceSessionEvent.CancelChanged -> when {
            state.phase == VoiceSessionPhase.RECORDING && event.canceling ->
                VoiceSessionTransition(state.copy(phase = VoiceSessionPhase.CANCELING))
            state.phase == VoiceSessionPhase.CANCELING && !event.canceling ->
                VoiceSessionTransition(state.copy(phase = VoiceSessionPhase.RECORDING))
            else -> VoiceSessionTransition(state)
        }

        VoiceSessionEvent.Release -> when (state.phase) {
            VoiceSessionPhase.RECORDING -> VoiceSessionTransition(
                state.copy(phase = VoiceSessionPhase.AWAITING_FINAL),
                VoiceSessionEffect.FinishEngine,
            )
            VoiceSessionPhase.CANCELING -> VoiceSessionTransition(
                VoiceSessionState(),
                VoiceSessionEffect.CancelEngine,
            )
            else -> VoiceSessionTransition(state)
        }

        is VoiceSessionEvent.Complete -> if (state.phase == VoiceSessionPhase.AWAITING_FINAL) {
            val text = event.finalText?.takeIf { it.isNotBlank() } ?: state.transcript
            VoiceSessionTransition(
                VoiceSessionState(),
                VoiceSessionEffect.Commit(text.trim().takeIf { it.isNotEmpty() }),
            )
        } else {
            VoiceSessionTransition(state)
        }

        VoiceSessionEvent.Abort -> VoiceSessionTransition(VoiceSessionState())
    }
}
