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
