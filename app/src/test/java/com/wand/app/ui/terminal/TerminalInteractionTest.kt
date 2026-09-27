package com.wand.app.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalInteractionTest {
    @Test
    fun scrollDistanceIsMeasuredFromTheFingerDownPoint() {
        val gesture = Any()
        PtyScrollSlop.noteDown(gesture, pack(10f, 20f))

        assertEquals(0f, PtyScrollSlop.displacementSquared(gesture, pack(10f, 20f)))
        assertEquals(25f, PtyScrollSlop.displacementSquared(gesture, pack(13f, 24f)))
        // A later gesture must not keep the previous anchor.
        val next = Any()
        assertEquals(Float.MAX_VALUE, PtyScrollSlop.displacementSquared(next, pack(13f, 24f)))
        PtyScrollSlop.noteDown(next, pack(0f, 0f))
        assertEquals(4f, PtyScrollSlop.displacementSquared(next, pack(-2f, 0f)))
    }

    @Test
    fun pasteMatchesTheWebTerminalSequence() {
        assertEquals("", buildTerminalPasteSequence("", bracketed = true))
        assertEquals("\u001b[200~first\rsecond\u001b[201~", buildTerminalPasteSequence("first\nsecond", true))
        assertEquals("first\rsecond", buildTerminalPasteSequence("first\r\nsecond", false))
        assertEquals(
            "\u001b[200~safe\u241b[201~tail\u001b[201~",
            buildTerminalPasteSequence("safe\u001b[201~tail", true),
        )
    }

    @Test
    fun bracketedPasteFollowsTheTerminalModeAndCodex() {
        assertTrue(shouldBracketTerminalPaste("claude", bracketedPasteMode = true))
        assertTrue(shouldBracketTerminalPaste(null, bracketedPasteMode = true))
        assertTrue(shouldBracketTerminalPaste(" Codex ", bracketedPasteMode = false))
        assertFalse(shouldBracketTerminalPaste("claude", bracketedPasteMode = false))
        assertFalse(shouldBracketTerminalPaste(null, bracketedPasteMode = false))
    }

    @Test
    fun bracketedPasteModeSurvivesASplitEscape() {
        val tracker = BracketedPasteModeTracker()
        tracker.observe("ready\u001b[?20")
        assertFalse(tracker.enabled)
        tracker.observe("04h")
        assertTrue(tracker.enabled)
        tracker.observe("still\u001b[?2004lmore")
        assertFalse(tracker.enabled)
        tracker.observe("\u001b[31m")
        assertFalse(tracker.enabled)
    }

    @Test
    fun menuStaysHiddenWhileTheFingerIsSelecting() {
        val dragging = TerminalSelectionSnapshot(active = true, dragging = true, lineMode = false, singleCell = true)
        val resting = dragging.copy(dragging = false)
        assertFalse(shouldShowTerminalMenu(dragging))
        assertTrue(shouldShowTerminalMenu(resting))
        assertTrue(shouldExpandTerminalSelection(resting, alreadyExpanded = false))
        assertFalse(shouldExpandTerminalSelection(resting, alreadyExpanded = true))
        assertFalse(shouldExpandTerminalSelection(resting.copy(singleCell = false), alreadyExpanded = false))
        assertFalse(shouldExpandTerminalSelection(TerminalSelectionSnapshot.Inactive, alreadyExpanded = false))
    }

    @Test
    fun bufferCopyKeepsScrollbackBeforeTheVisibleScreen() {
        assertEquals("", joinTerminalBuffer(emptyList(), emptyList()))
        assertEquals("old\nvisible", joinTerminalBuffer(listOf("old   "), listOf("visible  ", "   ")))
    }
}

private fun pack(x: Float, y: Float): Long {
    val high = x.toRawBits().toLong() and 0xFFFFFFFFL
    val low = y.toRawBits().toLong() and 0xFFFFFFFFL
    return (high shl 32) or low
}
