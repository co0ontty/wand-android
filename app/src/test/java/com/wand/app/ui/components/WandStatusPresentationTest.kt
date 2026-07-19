package com.wand.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WandStatusPresentationTest {
    @Test
    fun aliasesShareOnePresentation() {
        val dashed = wandStatusPresentation("waiting-input")
        val underscored = wandStatusPresentation("waiting_input")

        assertEquals(dashed, underscored)
        assertEquals("等待输入", dashed.label)
        assertEquals(WandStatusTone.Permission, dashed.tone)
        assertTrue(dashed.breathing)
    }

    @Test
    fun completedAndUnknownStatusesDoNotAnimate() {
        assertFalse(wandStatusPresentation("failed").breathing)
        assertFalse(wandStatusPresentation("custom-state").breathing)
        assertEquals("custom-state", wandStatusPresentation("custom-state").label)
        assertEquals("未知状态", wandStatusPresentation(null).label)
    }
}
