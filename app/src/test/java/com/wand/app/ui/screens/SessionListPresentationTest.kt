package com.wand.app.ui.screens

import org.junit.Assert.assertEquals
import com.wand.app.ui.singleUnitDurationLabel
import org.junit.Assert.assertNull
import org.junit.Test

class SessionListPresentationTest {
    @Test
    fun directoryBrowserBackGoesToParentThenStopsAtRoot() {
        assertEquals("/Users/me", directoryParentPath("/Users/me/project"))
        assertEquals("/Users", directoryParentPath("/Users/me"))
        assertEquals("/", directoryParentPath("/Users"))
        assertNull(directoryParentPath("/"))
        assertNull(directoryParentPath(""))
    }

    @Test
    fun relativeTimeUsesSingleUnitWithoutSuffix() {
        assertEquals("刚刚", singleUnitDurationLabel(30_000L))
        assertEquals("12分钟", singleUnitDurationLabel(12 * 60_000L))
        assertEquals("3小时", singleUnitDurationLabel(3 * 60 * 60_000L))
        assertEquals("2天", singleUnitDurationLabel(2 * 24 * 60 * 60_000L))
    }
}
