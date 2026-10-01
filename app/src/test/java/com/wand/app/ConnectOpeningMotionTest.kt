package com.wand.app

import com.wand.app.ui.components.WORKSPACE_REVEAL_END
import com.wand.app.ui.components.WORKSPACE_REVEAL_START
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冷启动开屏的飞入几何。
 *
 * 这些值决定「系统 splash 上那只猫」和「开屏里那只猫」是不是同一个位置、同一段时间：
 * 起点必须在屏幕中心停够一拍（否则和 splash 的接续看不出是同一只），落点必须与工作区
 * 成形同一拍（否则会出现两只猫，或者猫先到、底板后到）。
 */
class ConnectOpeningMotionTest {

    @Test
    fun markWaitsAtScreenCenterBeforeTakingOff() {
        assertEquals(0f, openingMarkTravel(0f), 0f)

        // 起飞点之前一直停在屏幕中心，尺寸也不动。
        assertEquals(0f, openingMarkTravel(OPENING_MARK_HOLD), 0f)
        assertTrue(OPENING_MARK_HOLD < WORKSPACE_REVEAL_START)
        assertEquals(162f, openingMarkSize(162f, 22f, openingMarkTravel(OPENING_MARK_HOLD)), 0.001f)
    }

    @Test
    fun markLandsWhenWorkspaceFinishesRevealing() {
        assertEquals(1f, openingMarkTravel(WORKSPACE_REVEAL_END), 0f)
        assertEquals(1f, openingMarkTravel(1f), 0f)

        // 落点尺寸 = 场景里工作区标的实际尺寸（场景侧按 22dp 缩过，这里只要求收到 22）。
        assertEquals(22f, openingMarkSize(162f, 22f, openingMarkTravel(WORKSPACE_REVEAL_END)), 0.001f)
    }

    @Test
    fun travelIsMonotonicBetweenCenterAndLanding() {
        var previous = -1f
        var checked = 0
        var step = 0
        while (step <= 100) {
            val value = openingMarkTravel(step / 100f)
            assertTrue("travel 必须单调不减（第 $step 步）", value >= previous)
            previous = value
            checked += 1
            step += 1
        }
        assertEquals(101, checked)
    }

    @Test
    fun travelClampsOutsideJourney() {
        assertEquals(0f, openingMarkTravel(-0.5f), 0f)
        assertEquals(1f, openingMarkTravel(1.5f), 0f)
    }

    @Test
    fun markShrinksFromSplashSizeToWorkspaceSize() {
        // 起点是系统 splash 那只猫的实测尺寸，终点是工作区标；中途必须严格收窄。
        assertEquals(162f, openingMarkSize(162f, 22f, 0f), 0.001f)
        assertEquals(92f, openingMarkSize(162f, 22f, 0.5f), 0.001f)
        assertEquals(22f, openingMarkSize(162f, 22f, 1f), 0.001f)

        // 越界的 travel 不能把标放大到起点以外，也不能缩成 0。
        assertEquals(162f, openingMarkSize(162f, 22f, -1f), 0.001f)
        assertEquals(22f, openingMarkSize(162f, 22f, 2f), 0.001f)
    }
}
