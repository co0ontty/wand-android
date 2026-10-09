package com.wand.app.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerHoldToTalkTest {
    @Test fun `only empty unfocused input with closed IME owns hold to talk`() {
        assertTrue(composerHoldToTalkEligible("", false, false))
        assertFalse(composerHoldToTalkEligible("", true, false))
        assertFalse(composerHoldToTalkEligible("", false, true))
        assertFalse(composerHoldToTalkEligible("草稿", false, false))
        assertFalse(composerHoldToTalkEligible(" ", false, false))
    }
}
