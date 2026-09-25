package com.wand.app.ui.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePtyTerminalResizeTest {
    @Test fun resyncAtTheSameSizeDoesNotTriggerAnotherServerResize() {
        val view = 48 to 20
        assertFalse(shouldResizePtyAfterSnapshot(view, view, view))
        assertFalse(shouldResizePtyAfterSnapshot(view, view, null))
    }

    @Test fun initialLayoutAndRemoteResizeStillCorrectThePtySize() {
        val view = 48 to 20
        assertTrue(shouldResizePtyAfterSnapshot(view, null, view))
        assertTrue(shouldResizePtyAfterSnapshot(view, view, 80 to 24))
        assertTrue(shouldResizePtyAfterSnapshot(view, 50 to 22, view))
        assertFalse(shouldResizePtyAfterSnapshot(0 to 0, null, view))
    }
}
