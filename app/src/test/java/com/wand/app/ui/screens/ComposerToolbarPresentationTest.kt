package com.wand.app.ui.screens

import com.wand.app.ui.SendActionVisual
import com.wand.app.ui.SendPhase
import com.wand.app.ui.sendActionVisual
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerToolbarPresentationTest {
    @Test
    fun independentActionsCoverTheFourDraftAndExecutionStates() {
        val empty = composerActionPresentation(SendPhase.Idle, false, false, false)
        assertEquals(0, empty.slotCount)
        assertEquals(null, empty.send)
        assertFalse(empty.stop)
        val draft = composerActionPresentation(SendPhase.Idle, false, true, true)
        assertEquals(1, draft.slotCount)
        assertEquals(SendActionVisual.Send, draft.send)
        assertFalse(draft.stop)
        val running = composerActionPresentation(SendPhase.Idle, true, false, false)
        assertEquals(1, running.slotCount)
        assertEquals(null, running.send)
        assertTrue(running.stop)
        val both = composerActionPresentation(SendPhase.Idle, true, true, true)
        assertEquals(2, both.slotCount)
        assertEquals(SendActionVisual.Send, both.send)
        assertTrue(both.stop)
    }

    @Test
    fun sendLocksAndReceiptFeedbackCannotConsumeTheIndependentStop() {
        val blocked = composerActionPresentation(SendPhase.Idle, true, true, false)
        assertEquals(SendActionVisual.Blocked, blocked.send)
        assertTrue(blocked.stop)
        for (phase in listOf(SendPhase.Sending, SendPhase.Sent, SendPhase.Failed)) {
            for (hasDraft in listOf(false, true)) {
                val actions = composerActionPresentation(phase, true, hasDraft, false)
                assertEquals(2, actions.slotCount)
                assertEquals(sendActionVisual(phase, false, hasDraft), actions.send)
                assertTrue(actions.stop)
            }
        }
        assertEquals(SendActionVisual.Failed, composerActionPresentation(SendPhase.Idle, true, false, false,
            feedback = SendActionVisual.Failed).send)
        assertEquals(0, composerActionPresentation(SendPhase.Idle, false, false, false).slotCount)
    }
    @Test
    fun actualChatProjectionLetsRunningTextAndAttachmentDraftsSendWithoutBypassingLocks() {
        assertEquals(SendActionVisual.Stop, composerPrimaryVisual(SendPhase.Idle, true, false, false))
        assertEquals(SendActionVisual.Send, composerPrimaryVisual(SendPhase.Idle, true, true, true))
        assertEquals(SendActionVisual.Blocked, composerPrimaryVisual(SendPhase.Idle, true, true, false))
        for (phase in listOf(SendPhase.Sending, SendPhase.Sent, SendPhase.Failed)) {
            assertEquals(sendActionVisual(phase, true, true), composerPrimaryVisual(phase, true, true, false))
        }
    }
    @Test
    fun runningTurnAlwaysHasExactlyOneStopEntryAcrossDraftAndFeedbackStates() {
        for (phase in SendPhase.entries) {
            for (hasDraft in listOf(false, true)) {
                val primary = sendActionVisual(phase, turnRunning = true, hasDraft = hasDraft)
                val stopEntries = (if (primary == SendActionVisual.Stop) 1 else 0) +
                    (if (composerMenuHasStop(true, primary)) 1 else 0)
                assertEquals("phase=$phase, hasDraft=$hasDraft", 1, stopEntries)
            }
        }
    }

    @Test
    fun typingWhileRunningKeepsSendPrimaryAndMovesStopIntoMenu() {
        val empty = sendActionVisual(SendPhase.Idle, turnRunning = true, hasDraft = false)
        assertEquals(SendActionVisual.Stop, empty)
        assertFalse(composerMenuHasStop(true, empty))
        val drafting = sendActionVisual(SendPhase.Idle, turnRunning = true, hasDraft = true)
        assertEquals(SendActionVisual.Send, drafting)
        assertTrue(composerMenuHasStop(true, drafting))
        // Saving settings can temporarily block submission without removing stop access.
        assertTrue(composerMenuHasStop(true, SendActionVisual.Blocked))
    }

    @Test
    fun finishedTurnRemovesStopEntryEvenWhileSendFeedbackDwells() {
        for (phase in SendPhase.entries) {
            val primary = sendActionVisual(phase, turnRunning = false, hasDraft = true)
            assertFalse(composerMenuHasStop(false, primary))
        }
    }
}
