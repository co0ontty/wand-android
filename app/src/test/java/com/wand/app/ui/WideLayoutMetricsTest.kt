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
    fun foldingAndRotationKeepNarrowLandscapeOutOfSplitView() {
        assertFalse(usesWideListDetail(390.dp, 844.dp))
        assertFalse(usesWideListDetail(844.dp, 390.dp))
        assertTrue(usesWideListDetail(720.dp, 960.dp))
        assertTrue(usesWideListDetail(960.dp, 720.dp))
        for (width in listOf(720.dp, 960.dp)) {
            assertTrue(width - wideListPaneWidth(width) >= 360.dp)
        }
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
        assertEquals(400.dp, wideListPaneWidth(1_000.dp))
        assertEquals(400.dp, wideListPaneWidth(1_200.dp))
    }
}
