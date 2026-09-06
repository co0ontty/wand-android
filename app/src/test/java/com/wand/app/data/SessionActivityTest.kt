package com.wand.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionActivityTest {
    @Test
    fun providerCliAtPromptIsIdleNotRunning() {
        assertFalse(
            ptyTurnActive(
                sessionKind = "pty",
                status = "running",
                provider = "claude",
                ptyBusy = false,
            ),
        )
        assertEquals(
            "idle",
            effectiveSessionStatus(
                sessionKind = "pty",
                status = "running",
                provider = "claude",
                ptyBusy = false,
                providerCliActive = true,
                inFlight = false,
            ),
        )
    }

    @Test
    fun providerCliTurnLightsUpRunning() {
        assertTrue(
            sessionIsResponding(
                sessionKind = "pty",
                status = "running",
                provider = "claude",
                ptyBusy = true,
                providerCliActive = true,
                inFlight = false,
            ),
        )
        assertEquals(
            "running",
            effectiveSessionStatus(
                sessionKind = "pty",
                status = "running",
                provider = "claude",
                ptyBusy = true,
                providerCliActive = true,
                inFlight = false,
            ),
        )
    }

    @Test
    fun structuredUsesInFlightAndBareShellStaysBusyWhileRunning() {
        assertTrue(
            sessionIsResponding(
                sessionKind = "structured",
                status = "running",
                provider = "claude",
                ptyBusy = false,
                providerCliActive = true,
                inFlight = true,
            ),
        )
        assertTrue(
            ptyTurnActive(
                sessionKind = "pty",
                status = "running",
                provider = null,
                ptyBusy = false,
            ),
        )
    }
}
