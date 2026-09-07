package com.wand.app.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
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
 * Legacy spacing tokens kept for source compatibility with older callers.
 * Row selection no longer uses a leading accent bar.
 */
val WandSelectedRowBarStart: Dp = 5.dp

/** @see WandSelectedRowBarStart */
val WandSelectedRowBarWidth: Dp = 2.dp

/** @see WandSelectedRowBarStart */
val WandSelectedRowLeadingInset: Dp = 14.dp

/**
 * 列表行选中态：只铺一层品牌软底。
 *
 * 侧栏行不要再叠描边或左侧竖条。软底已经是完整的选中矩形；再加描边/竖条会看起来像
 * 两个高亮长方形，在窄侧栏里尤其明显。
 * 选中色必须先合成到页面底上：半透明 brand 叠 graphicsLayer 时，部分 Android GPU
 * 会把离屏层的白色矩形透出来，看起来像圆角高亮里再套一个长方形。
 * [contentInset] 为 true 时始终留出左槽，选中/未选不会左右跳动。
 */
@Composable
fun Modifier.wandSelectedRow(
    selected: Boolean,
    shape: Shape = RoundedCornerShape(12.dp),
    unselectedFill: Color = Color.Transparent,
    contentInset: Boolean = false,
): Modifier {
    val fill by animateColorAsState(
        if (selected) WandColors.selectedFill.compositeOver(WandColors.bgPrimary) else unselectedFill,
        WandMotion.tweenFast(),
        label = "wandSelectedRowFill",
    )
    return this
        .clip(shape)
        .background(fill)
        .then(
            if (contentInset) Modifier.padding(start = WandSelectedRowLeadingInset) else Modifier,
        )
}
