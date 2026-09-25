package com.wand.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 标签指示条几何（`docs/motion-design.md` 规则 5）的单测：拉伸靠前后缘错开，不靠目测。 */
class WandMotionKitTest {
    @Test
    fun segmentsTileTheTrackWhenThereIsNoGap() {
        assertEquals(0f to 0.5f, slideEdgeFractions(index = 0, count = 2, gapFraction = 0f))
        assertEquals(0.5f to 1f, slideEdgeFractions(index = 1, count = 2, gapFraction = 0f))
        assertEquals(0f to 1f / 3f, slideEdgeFractions(index = 0, count = 3, gapFraction = 0f))
    }

    @Test
    fun gapShrinksEachSegmentAndShiftsTheFollowingOnes() {
        val (left, right) = slideEdgeFractions(index = 1, count = 2, gapFraction = 0.02f)

        // 两段各占 0.49，第二段从 0.51 开始，末尾正好落在轨道右缘。
        assertEquals(0.51f, left, 0.0001f)
        assertEquals(1f, right, 0.0001f)
    }

    @Test
    fun indicatorEdgesCrossDuringTheMoveWhichIsWhatMakesItStretch() {
        val from = slideEdgeFractions(index = 0, count = 3, gapFraction = 0f)
        val to = slideEdgeFractions(index = 2, count = 3, gapFraction = 0f)

        // 前缘（右）先到、后缘（左）后到：中间任何一帧的宽度都大于一段，这就是「拉长再收回」。
        val stretched = (to.second - from.first)
        assertTrue(stretched > (from.second - from.first))
        assertTrue(stretched > (to.second - to.first))
    }

    @Test
    fun outOfRangeIndexClampsInsteadOfDrawingOffTrack() {
        assertEquals(0f to 0.5f, slideEdgeFractions(index = -3, count = 2, gapFraction = 0f))
        assertEquals(0.5f to 1f, slideEdgeFractions(index = 9, count = 2, gapFraction = 0f))
    }

    @Test
    fun emptyTrackHasZeroGeometryInsteadOfDividingByZero() {
        assertEquals(0f to 0f, slideEdgeFractions(index = 0, count = 0, gapFraction = 0f))
    }
}
