package com.wand.app.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PtyTerminalResumePolicyTest {
    @Test
    fun stoppedProviderPtyResumesBeforeOpeningTheTerminal() {
        assertTrue(shouldResumePtyTerminal("exited", "pty", "provider-session-id"))
        assertTrue(shouldResumePtyTerminal("idle", null, "provider-session-id"))
    }

    @Test
    fun runningStructuredAndBareShellSessionsDoNotResume() {
        assertFalse(shouldResumePtyTerminal("running", "pty", "provider-session-id"))
        assertFalse(shouldResumePtyTerminal("exited", "structured", "provider-session-id"))
        assertFalse(shouldResumePtyTerminal("exited", "pty", null))
    }
}
