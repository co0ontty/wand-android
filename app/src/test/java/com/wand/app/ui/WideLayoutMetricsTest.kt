package com.wand.app.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WideLayoutMetricsTest {
    @Test
    fun wideLayoutRequiresEnoughWidthAndHeight() {
        assertFalse(usesWideListDetail(width = 639.dp, height = 900.dp))
        assertFalse(usesWideListDetail(width = 900.dp, height = 479.dp))
        assertTrue(usesWideListDetail(width = 640.dp, height = 480.dp))
    }

    @Test
    fun mediumWindowsUseACompactProportionalSidebar() {
        assertEquals(232.dp, wideListPaneWidth(640.dp))
        assertEquals(252.dp, wideListPaneWidth(700.dp))
        assertEquals(280.dp, wideListPaneWidth(800.dp))
    }

    @Test
    fun expandedWindowsPreserveDetailWidthAndCapSidebarGrowth() {
        assertEquals(280.dp, wideListPaneWidth(840.dp))
        assertEquals(340.dp, wideListPaneWidth(900.dp))
        assertEquals(360.dp, wideListPaneWidth(1_200.dp))
    }
}
