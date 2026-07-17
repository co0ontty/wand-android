package com.wand.app.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
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
    val cornerRadius by animateDpAsState(
        targetValue = if (expanded) 18.dp else 24.dp,
        animationSpec = WandMotion.tweenFast(),
        label = "composerCornerRadius",
    )
    val composerShape = RoundedCornerShape(cornerRadius)
    val darkGlass = isWandDarkTheme()
    val composerGlass = if (expanded) {
        WandGlass.regular.copy(
            tintAlpha = if (darkGlass) 0.70f else 0.82f,
            fallbackAlpha = if (darkGlass) 0.94f else 0.96f,
            blurRadius = 12.dp,
            refractionHeight = 0.dp,
            refractionAmount = 0.dp,
            shadowElevation = 0.dp,
        )
    } else {
        WandGlass.regular.copy(
            tintAlpha = if (darkGlass) 0.62f else 0.76f,
            fallbackAlpha = if (darkGlass) 0.90f else 0.92f,
            blurRadius = 10.dp,
            refractionHeight = 0.dp,
            refractionAmount = 0.dp,
            shadowElevation = 0.dp,
        )
    }

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
                    width = if (focused) 1.dp else 0.5.dp,
                    color = if (focused) {
                        WandColors.brand.copy(alpha = 0.42f)
                    } else {
                        WandColors.border.copy(alpha = 0.32f)
                    },
                    shape = composerShape,
                )
                .animateContentSize(animationSpec = WandMotion.tweenNormal())
                .padding(horizontal = if (expanded) 8.dp else 9.dp, vertical = if (expanded) 6.dp else 4.dp),
            verticalArrangement = Arrangement.spacedBy(if (expanded) 8.dp else 0.dp),
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
