package com.wand.app.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class ComposerAccessoryHeightTest {
    @Test fun expandedListReservesMeasuredInputHeadersAndReadingSpace() {
        val available = 500.dp
        val input = 150.dp
        val height = composerAccessoryHeight(available, input, 2)
        assertTrue(height + input + ComposerActionTouchSize * 2 + ChatFloatingControlsClearance + 64.dp <= available)
    }

    @Test fun keyboardAndGrowingInputReduceTheSameListBudget() {
        val roomy = composerAccessoryHeight(600.dp, 52.dp, 2)
        val keyboard = composerAccessoryHeight(400.dp, 52.dp, 2)
        val multiLine = composerAccessoryHeight(400.dp, 150.dp, 2)
        assertTrue(keyboard < roomy)
        assertTrue(multiLine < keyboard)
        assertTrue(multiLine >= 0.dp)
    }

    @Test fun largeWindowsStayBoundedAndShortKeyboardWindowsDoNotForceOverflow() {
        assertEquals(160.dp, composerAccessoryHeight(1000.dp, 52.dp, 2))
        assertEquals(0.dp, composerAccessoryHeight(220.dp, 100.dp, 2))
        assertEquals(0.dp, composerAccessoryHeight(200.dp, 150.dp, 2))
    }

    @Test fun shortExpansionDismissesKeyboardButRoomyExpansionPreservesIt() {
        assertTrue(composerAccessoryNeedsKeyboardDismiss(composerAccessoryHeight(220.dp, 100.dp, 2), true))
        assertTrue(composerAccessoryNeedsKeyboardDismiss(48.dp, true))
        assertFalse(composerAccessoryNeedsKeyboardDismiss(80.dp, true))
        assertFalse(composerAccessoryNeedsKeyboardDismiss(0.dp, false))
    }

    @Test fun rotatingAnOpenListIntoShortViewportDismissesOnlyAnExistingKeyboard() {
        assertEquals(ComposerAccessoryKeyboardAction.Keep, composerAccessoryKeyboardAction(true, true, true, 180.dp))
        assertEquals(ComposerAccessoryKeyboardAction.DismissInput, composerAccessoryKeyboardAction(true, true, true, 0.dp))
        assertEquals(ComposerAccessoryKeyboardAction.Keep, composerAccessoryKeyboardAction(true, false, true, 180.dp))
    }

    @Test fun returningToInputClosesDisclosureWithoutImmediatelyHidingNewKeyboard() {
        assertEquals(ComposerAccessoryKeyboardAction.CollapsePanel, composerAccessoryKeyboardAction(false, true, true, 0.dp))
        assertEquals(ComposerAccessoryKeyboardAction.Keep, composerAccessoryKeyboardAction(true, true, false, 0.dp))
    }

    @Test fun shortWindowRelaxesReadingReserveWithoutExceedingRealRoom() {
        assertEquals(48.dp, composerAccessoryHeight(280.dp, 52.dp, 2))
        assertEquals(ComposerAccessoryKeyboardAction.DismissInput,
            composerAccessoryKeyboardAction(false, false, true, composerAccessoryHeight(280.dp, 150.dp, 2)))
        assertEquals(ComposerAccessoryKeyboardAction.Keep,
            composerAccessoryKeyboardAction(false, false, true, composerAccessoryHeight(280.dp, 52.dp, 2)))
    }

    @Test fun duoOrientationsAndNarrowLargeTextNeverOverrunAvailableHeight() {
        // Same four window classes as WideLayoutMetricsTest, plus a 320dp narrow host.
        val windows = listOf(390 to 844, 844 to 390, 720 to 960, 960 to 720, 320 to 568)
        for ((width, height) in windows) for (keyboard in listOf(0, 240)) {
            val available = (height - 48 - keyboard).coerceAtLeast(0).dp
            for (input in listOf(52.dp, 150.dp, 220.dp)) {
                val budget = composerAccessoryHeight(available, input, 2)
                val remaining = (available - input - 96.dp - ChatFloatingControlsClearance - 8.dp).coerceAtLeast(0.dp)
                assertTrue("$width x $height / IME=$keyboard / input=$input", budget <= remaining)
                assertTrue(budget in 0.dp..160.dp)
                if (remaining >= 48.dp) assertTrue(budget >= 48.dp)
            }
        }
    }
}
