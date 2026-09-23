package com.wand.app.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalAppearanceTest {
    @Test
    fun ansiPaletteHasSixteenDistinctOpaqueColors() {
        assertEquals(16, TerminalPalette.ansi.size)
        assertEquals(16, TerminalPalette.ansi.toSet().size)
        TerminalPalette.ansi.forEach { color ->
            assertEquals(0xFF, color ushr 24)
        }
        assertTrue(TerminalPalette.foregroundArgb != TerminalPalette.backgroundArgb)
    }

    @Test
    fun softKeyboardStaysOffUntilTheTerminalCanTakeKeys() {
        assertFalse(terminalSoftKeyboardEnabled(ready = false, composerOpen = false, requested = true))
        assertFalse(terminalSoftKeyboardEnabled(ready = true, composerOpen = true, requested = true))
        assertFalse(terminalSoftKeyboardEnabled(ready = true, composerOpen = false, requested = false))
        assertTrue(terminalSoftKeyboardEnabled(ready = true, composerOpen = false, requested = true))
    }

    @Test
    fun directInputSendsCommittedTextAndHoldsComposition() {
        assertEquals(DirectPtyInput("ni", null), directPtyInput("ni", composing = true))
        assertEquals(DirectPtyInput("", "你"), directPtyInput("你", composing = false))
        assertEquals(DirectPtyInput("", "ls"), directPtyInput("ls", composing = false))
        assertEquals(DirectPtyInput("", null), directPtyInput("", composing = false))
    }

    @Test
    fun terminalScaleUsesQuarterStepsBetweenHalfAndDouble() {
        assertEquals(1f, stepTerminalScale(1.5f, 0f))
        assertEquals(1.25f, stepTerminalScale(1f, 0.25f))
        assertEquals(0.5f, stepTerminalScale(0.5f, -0.25f))
        assertEquals(2f, stepTerminalScale(2f, 0.25f))
        assertEquals(14, terminalFontSp(1f))
        assertEquals(28, terminalFontSp(2f))
        assertEquals(8, terminalFontSp(0.5f))
    }
}
