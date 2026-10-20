package com.wand.app.ui.screens

import com.wand.app.ServerStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具时间线自动展开偏好的两个维度：开闭轴（自动展开 / 一直折叠）× 网络口径轴（所有网络 / 仅 Wi-Fi）。
 * 对话内手动收放只翻开闭轴，不能顺手把用户选过的网络范围改掉。
 */
class ActivityTimelinePreferenceTest {

    @Test
    fun scopeKeepsNetworkCondition() {
        assertEquals(ServerStore.TRAFFIC_TIMELINE_MODE_ALL, ServerStore.trafficTimelineScope("all"))
        assertEquals(ServerStore.TRAFFIC_TIMELINE_MODE_WIFI, ServerStore.trafficTimelineScope("wifi"))
        assertEquals(ServerStore.TRAFFIC_TIMELINE_MODE_ALL, ServerStore.trafficTimelineScope("never"))
        assertEquals(ServerStore.TRAFFIC_TIMELINE_MODE_WIFI, ServerStore.trafficTimelineScope("never_wifi"))
        // 未知值走保守口径，不会凭空变成「所有网络自动展开」。
        assertEquals(ServerStore.TRAFFIC_TIMELINE_MODE_WIFI, ServerStore.trafficTimelineScope("unknown"))
    }

    @Test
    fun collapsedAxisIsIndependentFromScope() {
        assertFalse(ServerStore.trafficTimelineCollapsed("all"))
        assertFalse(ServerStore.trafficTimelineCollapsed("wifi"))
        assertTrue(ServerStore.trafficTimelineCollapsed("never"))
        assertTrue(ServerStore.trafficTimelineCollapsed("never_wifi"))
    }

    @Test
    fun manualToggleFlipsOpenCloseWithoutTouchingNetworkScope() {
        // 仅 Wi-Fi 下手动收起：折叠，但仍记着「仅 Wi-Fi」。
        val collapsed = ServerStore.trafficTimelineModeAfterToggle("wifi", false)
        assertEquals("never_wifi", collapsed)
        // 再手动展开：回到「仅 Wi-Fi 自动展开」，不会被改写成「所有网络」。
        assertEquals("wifi", ServerStore.trafficTimelineModeAfterToggle(collapsed, true))

        // 所有网络口径同理。
        val collapsedAll = ServerStore.trafficTimelineModeAfterToggle("all", false)
        assertEquals("never", collapsedAll)
        assertEquals("all", ServerStore.trafficTimelineModeAfterToggle(collapsedAll, true))
    }

    @Test
    fun collapsedModeKeepsCurrentScope() {
        assertEquals("never_wifi", ServerStore.trafficTimelineCollapsedMode("wifi"))
        assertEquals("never", ServerStore.trafficTimelineCollapsedMode("all"))
        assertEquals("never_wifi", ServerStore.trafficTimelineCollapsedMode("never_wifi"))
        assertEquals("never", ServerStore.trafficTimelineCollapsedMode("never"))
    }

    @Test
    fun unknownModeIsNotAutoExpanded() {
        assertFalse(ServerStore.isKnownTrafficTimelineMode("unknown"))
        assertFalse(activityTimelineEnabled("unknown", wifiConnected = true))
        assertTrue(ServerStore.isKnownTrafficTimelineMode("never_wifi"))
    }
}
