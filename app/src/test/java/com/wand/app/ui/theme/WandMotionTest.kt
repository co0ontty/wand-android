package com.wand.app.ui.theme

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WandMotionTest {
    /**
     * animateXxxAsState 没有「不动画」开关，规格必须由 respectMotion 收口：
     * 系统关闭动画时退化为 snap，否则沿用传入的有限规格。
     */
    @Test
    fun respectMotionDegradesToSnapWhenDisabled() {
        val animated = WandMotion.respectMotion(true, WandMotion.tweenFast<Float>())
        val still = WandMotion.respectMotion(false, WandMotion.tweenFast<Float>())

        assertTrue(animated is TweenSpec)
        assertTrue(still is SnapSpec)
    }

    @Test
    fun respectMotionKeepsDurationWhenEnabled() {
        val spec = WandMotion.respectMotion(true, WandMotion.tweenNormal<Float>()) as TweenSpec

        assertEquals(WandMotion.normal, spec.durationMillis)
    }
}
