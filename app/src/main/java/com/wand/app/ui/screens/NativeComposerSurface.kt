package com.wand.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wand.app.ui.theme.GlassBackdrop
import com.wand.app.ui.theme.WandGlass
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.glassSurface
import com.wand.app.ui.theme.isWandDarkTheme

/** 输入区操作按钮统一规格：32dp 视觉区、16dp 图标、44dp 紧凑触控区。 */
internal val ComposerActionVisualSize = 32.dp
internal val ComposerActionIconSize = 16.dp
internal val ComposerActionTouchSize = 44.dp
// 触控盒本身提供按钮间距，不再叠加额外空隙。
internal val ComposerActionSpacing = 0.dp

@Composable
fun NativeComposerSurface(
    backdrop: GlassBackdrop,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    collapsedLeading: @Composable RowScope.() -> Unit = {},
    inputContent: @Composable RowScope.() -> Unit,
    collapsedTrailing: @Composable RowScope.() -> Unit = {},
    expandedControls: @Composable RowScope.(controlsCompact: Boolean) -> Unit = {},
) {
    // 输入底栏只保留低对比度玻璃底，不再叠加聚焦描边和宽外边距。
    val composerShape = WandShapes.lg
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
            .padding(horizontal = 6.dp, vertical = 4.dp),
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
                .padding(horizontal = 6.dp, vertical = 5.dp),
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
