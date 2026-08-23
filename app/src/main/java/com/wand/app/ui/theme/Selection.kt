package com.wand.app.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Tile / chip 选中态：品牌软底 + 品牌描边。
 * 对齐 iOS NewSession `cardBackground` 与 Web `.ask-user-option.selected`。
 * 单独软底在米色 / 深暖底上几乎看不见，描边才是选中 / 未选的主信号。
 */
@Composable
fun Modifier.wandSelectedSurface(
    selected: Boolean,
    shape: Shape = WandShapes.md,
    unselectedFill: Color = WandColors.surface,
    showUnselectedBorder: Boolean = true,
): Modifier {
    val fill by animateColorAsState(
        if (selected) WandColors.selectedFill else unselectedFill,
        WandMotion.tweenFast(),
        label = "wandSelectedFill",
    )
    val stroke by animateColorAsState(
        when {
            selected -> WandColors.brand
            showUnselectedBorder -> WandColors.border
            else -> Color.Transparent
        },
        WandMotion.tweenFast(),
        label = "wandSelectedStroke",
    )
    val width = when {
        selected -> 1.5.dp
        showUnselectedBorder -> 1.dp
        else -> 0.dp
    }
    return this
        .clip(shape)
        .background(fill)
        .then(if (width > 0.dp) Modifier.border(width, stroke, shape) else Modifier)
}

/**
 * 列表行选中态：品牌软底 + 左侧品牌条。
 * 对齐 Web `.session-item.selected::before`，避免每一行再套一圈描边。
 */
@Composable
fun Modifier.wandSelectedRow(
    selected: Boolean,
    shape: Shape = RoundedCornerShape(12.dp),
    unselectedFill: Color = Color.Transparent,
): Modifier {
    val fill by animateColorAsState(
        if (selected) WandColors.selectedFill else unselectedFill,
        WandMotion.tweenFast(),
        label = "wandSelectedRowFill",
    )
    val accent = WandColors.brand
    return this
        .clip(shape)
        .background(fill)
        .drawBehind {
            if (!selected) return@drawBehind
            val barWidth = 3.dp.toPx()
            val inset = size.height * 0.22f
            drawRoundRect(
                color = accent,
                topLeft = Offset(4.dp.toPx(), inset),
                size = Size(barWidth, (size.height - inset * 2).coerceAtLeast(0f)),
                cornerRadius = CornerRadius(barWidth),
            )
        }
}
