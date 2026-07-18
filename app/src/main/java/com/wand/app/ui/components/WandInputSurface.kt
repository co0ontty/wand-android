package com.wand.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.wand.app.ui.theme.WandColors

/**
 * 全局文本输入表面：静态时保持安静，聚焦/错误时提供即时且无过冲的反馈。
 * 输入控件继续保留平台原生文本编辑行为；这里只统一容器、描边和层级。
 */
@Composable
fun Modifier.wandInputSurface(
    focused: Boolean,
    invalid: Boolean = false,
    shape: Shape = RoundedCornerShape(14.dp),
): Modifier {
    val outlineColor = when {
        invalid -> WandColors.danger
        focused -> WandColors.focusRing
        else -> WandColors.borderStrong.copy(alpha = 0.72f)
    }
    val containerColor = if (focused) WandColors.surface else WandColors.surface.copy(alpha = 0.90f)

    return this
        .shadow(elevation = 1.dp, shape = shape, clip = false)
        .background(containerColor, shape)
        .border(if (focused || invalid) 1.5.dp else 1.dp, outlineColor, shape)
}
