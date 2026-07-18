package com.wand.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wand.app.ui.theme.GlassBackdrop
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandGlass
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.glassSurface
import com.wand.app.ui.theme.isWandDarkTheme

/** 输入区操作按钮统一规格：36dp 视觉区、18dp 图标、48dp Material 触控区。 */
internal val ComposerActionVisualSize = 36.dp
internal val ComposerActionIconSize = 18.dp
internal val ComposerActionTouchSize = 48.dp
// 48dp 触控盒在 36dp 视觉区两侧各留 6dp，不再叠加额外空隙。
internal val ComposerActionSpacing = 0.dp

@Composable
fun NativeComposerSurface(
    backdrop: GlassBackdrop,
    expanded: Boolean,
    focused: Boolean,
    modifier: Modifier = Modifier,
    collapsedLeading: @Composable RowScope.() -> Unit = {},
    inputContent: @Composable RowScope.() -> Unit,
    collapsedTrailing: @Composable RowScope.() -> Unit = {},
    expandedControls: @Composable RowScope.(controlsCompact: Boolean) -> Unit = {},
) {
    // 聚焦是高频操作：移动端仍会展开控制行，但容器不再同时改变圆角、阴影
    // 和材质厚度。这样键盘升起时只有一次清晰的结构变化，没有“整块缩放”错觉。
    val composerShape = RoundedCornerShape(20.dp)
    val outlineColor by animateColorAsState(
        targetValue = if (focused) WandColors.focusRing else WandColors.border.copy(alpha = 0.42f),
        animationSpec = WandMotion.tweenFast(),
        label = "composerOutlineColor",
    )
    val darkGlass = isWandDarkTheme()
    val composerGlass = WandGlass.regular.copy(
        tintAlpha = if (darkGlass) 0.68f else 0.80f,
        fallbackAlpha = if (darkGlass) 0.93f else 0.95f,
        blurRadius = 11.dp,
        refractionHeight = 0.dp,
        refractionAmount = 0.dp,
        shadowElevation = 2.dp,
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        val controlsCompact = maxWidth < 360.dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(
                    backdrop = backdrop,
                    shape = composerShape,
                    style = composerGlass,
                    drawRim = false,
                )
                .border(
                    width = if (focused) 1.25.dp else 0.75.dp,
                    color = outlineColor,
                    shape = composerShape,
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(if (expanded) 6.dp else 0.dp),
        ) {
            Row(
                verticalAlignment = if (expanded) Alignment.Bottom else Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ComposerActionSpacing),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (!expanded) {
                    collapsedLeading()
                }
                inputContent()
                if (!expanded) {
                    collapsedTrailing()
                }
            }
            if (expanded) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ComposerActionSpacing),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    expandedControls(controlsCompact)
                }
            }
        }
    }
}
