package com.wand.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wand.app.ui.theme.GlassBackdrop
import com.wand.app.ui.theme.WandGlass
import com.wand.app.ui.theme.WandMotion
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
    backdrop: GlassBackdrop?,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    drawSurface: Boolean = true,
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
            .then(if (drawSurface) Modifier.padding(horizontal = 6.dp, vertical = 4.dp) else Modifier),
    ) {
        val controlsCompact = maxWidth < 360.dp
        val surfaceModifier = if (drawSurface) {
            Modifier
                .fillMaxWidth()
                .glassSurface(
                    backdrop = backdrop,
                    shape = composerShape,
                    style = composerGlass,
                    drawRim = false,
                )
                .padding(horizontal = 6.dp, vertical = 5.dp)
        } else {
            Modifier.fillMaxWidth()
        }
        Column(
            modifier = surfaceModifier,
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

@Composable
internal fun FilledComposerAction(
    enabled: Boolean,
    fillColor: Color,
    contentDescription: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.92f else 1f,
        WandMotion.tweenFast(),
        label = "filledComposerScale",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(ComposerActionTouchSize)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(ComposerActionVisualSize)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(fillColor),
        ) {
            content()
        }
    }
}
