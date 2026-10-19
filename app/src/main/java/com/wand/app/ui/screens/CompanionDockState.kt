package com.wand.app.ui.screens

import android.content.Context
import com.wand.app.ui.SendPhase
import com.wand.app.ui.SendActionVisual
import com.wand.app.ui.components.AvatarFileActivity
import kotlin.math.abs

internal enum class CompanionSide { Left, Right }
internal enum class CompanionAction { Hidden, Send, Stop }

internal fun companionAction(hasText: Boolean, running: Boolean): CompanionAction = when {
    !hasText -> CompanionAction.Hidden
    running -> CompanionAction.Stop
    else -> CompanionAction.Send
}

internal const val COMPANION_SCENE_SLOT_DP = 88f

/** Reserve for the largest action group; text/receipt state never changes the scene's geometry. */
internal fun companionSceneSizeDp(availableWidth: Float): Float =
    (availableWidth - 232f).coerceIn(48f, COMPANION_SCENE_SLOT_DP)

internal fun companionWorkStatus(running: Boolean, activity: AvatarFileActivity, hasDetailedProgress: Boolean): String = when {
    !running -> ""
    activity == AvatarFileActivity.Reading -> "正在读文件"
    activity == AvatarFileActivity.Writing -> "正在写文件"
    !hasDetailedProgress -> "正在处理"
    else -> ""
}

/** A receipt owns the same slot until its existing dwell ends, even after ACK clears the text. */
internal fun companionActionVisual(hasText: Boolean, running: Boolean, phase: SendPhase): SendActionVisual? = when (phase) {
    SendPhase.Sending -> SendActionVisual.Sending
    SendPhase.Sent -> SendActionVisual.Sent
    SendPhase.Failed -> SendActionVisual.Failed
    SendPhase.Idle -> when (companionAction(hasText, running)) {
        CompanionAction.Hidden -> null
        CompanionAction.Send -> SendActionVisual.Send
        CompanionAction.Stop -> SendActionVisual.Stop
    }
}

/** Persist one local preference, independent of session/server and with no remote write. */
internal class CompanionSidePreference(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("wand_chat_companion", Context.MODE_PRIVATE)
    fun read(): CompanionSide = parse(prefs.getString("side", null))
    fun write(side: CompanionSide) { prefs.edit().putString("side", side.name).apply() }
    fun observe(changed: () -> Unit): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "side") changed() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    companion object {
        fun parse(value: String?): CompanionSide = CompanionSide.entries.firstOrNull { it.name == value } ?: CompanionSide.Left
    }
}

internal enum class CompanionGesturePhase { Pending, Dragging, Ended }
internal enum class CompanionGestureEffect { None, Tap, Drag, Snap }

/** Monotonic modes: a gesture is a tap or a horizontal drag, never both. */
internal class CompanionGestureState {
    var phase = CompanionGesturePhase.Pending
        private set
    fun move(x: Float, y: Float, slop: Float): CompanionGestureEffect = when (phase) {
        CompanionGesturePhase.Pending -> when {
            abs(x) <= slop && abs(y) <= slop -> CompanionGestureEffect.None
            abs(x) > abs(y) -> { phase = CompanionGesturePhase.Dragging; CompanionGestureEffect.Drag }
            else -> { phase = CompanionGesturePhase.Ended; CompanionGestureEffect.None }
        }
        CompanionGesturePhase.Dragging -> CompanionGestureEffect.Drag
        CompanionGesturePhase.Ended -> CompanionGestureEffect.None
    }
    fun release(canceled: Boolean): CompanionGestureEffect {
        val effect = when (phase) {
            CompanionGesturePhase.Pending -> if (canceled) CompanionGestureEffect.None else CompanionGestureEffect.Tap
            CompanionGesturePhase.Dragging -> if (canceled) CompanionGestureEffect.None else CompanionGestureEffect.Snap
            CompanionGesturePhase.Ended -> CompanionGestureEffect.None
        }
        phase = CompanionGesturePhase.Ended
        return effect
    }
}

internal fun companionSnapSide(centerX: Float, availableWidth: Float): CompanionSide =
    if (centerX > availableWidth / 2f) CompanionSide.Right else CompanionSide.Left
