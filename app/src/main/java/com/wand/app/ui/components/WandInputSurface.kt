package com.wand.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
        else -> Color.Transparent
    }
    val containerColor = if (focused) WandColors.surface else WandColors.surfaceSoft.copy(alpha = 0.72f)

    return this
        .shadow(elevation = if (focused) 1.dp else 0.dp, shape = shape, clip = false)
        .background(containerColor, shape)
        .then(
            if (focused || invalid) Modifier.border(1.dp, outlineColor, shape)
            else Modifier
        )
}
