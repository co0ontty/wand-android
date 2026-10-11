package com.wand.app.ui.screens

import org.junit.Assert.*
import com.wand.app.ui.SendPhase
import com.wand.app.ui.SendActionVisual
import com.wand.app.ui.components.AvatarFileActivity
import org.junit.Test

class CompanionDockStateTest {
    @Test fun actionRequiresActualTextEvenWhenRunningOrAnAttachmentExists() {
        assertEquals(CompanionAction.Hidden, companionAction(false, false))
        assertEquals(CompanionAction.Hidden, companionAction(false, true))
        assertEquals(CompanionAction.Send, companionAction(true, false))
        assertEquals(CompanionAction.Stop, companionAction(true, true))
    }
    @Test fun receiptKeepsTheActionSlotThroughSendingAcceptedAndFailure() {
        for (running in listOf(false, true)) {
            for (hasText in listOf(false, true)) {
                assertEquals(SendActionVisual.Sending, companionActionVisual(hasText, running, SendPhase.Sending))
                assertEquals(SendActionVisual.Sent, companionActionVisual(hasText, running, SendPhase.Sent))
                assertEquals(SendActionVisual.Failed, companionActionVisual(hasText, running, SendPhase.Failed))
            }
        }
        assertNull(companionActionVisual(false, true, SendPhase.Idle))
        assertEquals(SendActionVisual.Stop, companionActionVisual(true, true, SendPhase.Idle))
        assertEquals(SendActionVisual.Send, companionActionVisual(true, false, SendPhase.Idle))
    }
    @Test fun activeTaskAndSubagentAlreadyDescribeWorkButFileActivityStillHasMeaning() {
        assertEquals("", companionWorkStatus(true, AvatarFileActivity.Idle, true))
        assertEquals("正在处理", companionWorkStatus(true, AvatarFileActivity.Idle, false))
        assertEquals("正在读文件", companionWorkStatus(true, AvatarFileActivity.Reading, true))
        assertEquals("正在写文件", companionWorkStatus(true, AvatarFileActivity.Writing, true))
        assertEquals("", companionWorkStatus(false, AvatarFileActivity.Reading, false))
    }
    @Test fun sceneStaysCompactAndReservesRoomForThreeTouchTargets() {
        for (width in listOf(280f, 320f, 360f, 393f, 600f, 840f)) {
            val size = companionSceneSizeDp(width)
            assertTrue(size in 48f..88f)
            assertTrue(size + 36f + 3 * 48f + 48f <= width) // caption/pause keeps its own touch width
        }
        assertEquals(88f, COMPANION_SCENE_SLOT_DP)
    }
    @Test fun shortTapAndLongHoldCannotBothFire() {
        val tap = CompanionGestureState(true)
        assertEquals(CompanionGestureEffect.Tap, tap.release(false))
        assertEquals(CompanionGestureEffect.None, tap.timeout())
        val hold = CompanionGestureState(true)
        assertEquals(CompanionGestureEffect.Hold, hold.timeout())
        assertEquals(CompanionGestureEffect.FinishVoice, hold.release(false))
        assertEquals(CompanionGestureEffect.None, hold.release(false))
    }
    @Test fun horizontalDragBeforeHoldCanNeverOpenMicrophone() {
        val drag = CompanionGestureState(true)
        assertEquals(CompanionGestureEffect.Drag, drag.move(35f, 4f, 12f, 60f))
        assertEquals(CompanionGestureEffect.None, drag.timeout())
        assertEquals(CompanionGestureEffect.Snap, drag.release(false))
    }
    @Test fun afterRecordingUpwardCancelCannotSwapSideEvenWithHorizontalMotion() {
        val hold = CompanionGestureState(true)
        hold.timeout()
        assertEquals(CompanionGestureEffect.None, hold.move(150f, -80f, 12f, 60f))
        assertTrue(hold.cancelVoice)
        assertEquals(CompanionGestureEffect.CancelVoice, hold.release(false))
    }
    @Test fun interruptedPointerDiscardsUtteranceAndVerticalScrollDoesNotRecord() {
        val hold = CompanionGestureState(true); hold.timeout()
        assertEquals(CompanionGestureEffect.CancelVoice, hold.release(true))
        val scroll = CompanionGestureState(true); scroll.move(2f, 45f, 12f, 60f)
        assertEquals(CompanionGestureEffect.None, scroll.timeout())
        assertEquals(CompanionGestureEffect.None, scroll.release(false))
        val mic = CompanionGestureState(false); mic.move(50f, 0f, 12f, 60f)
        assertEquals(CompanionGestureEffect.None, mic.timeout())
    }
    @Test fun dropClampsToSidesAndPreferenceHandlesOlderOrInvalidValues() {
        assertEquals(CompanionSide.Left, companionSnapSide(100f, 320f))
        assertEquals(CompanionSide.Right, companionSnapSide(240f, 320f))
        assertEquals(CompanionSide.Left, CompanionSidePreference.parse("unknown"))
        for (side in CompanionSide.entries) assertEquals(side, CompanionSidePreference.parse(side.name))
    }
}
