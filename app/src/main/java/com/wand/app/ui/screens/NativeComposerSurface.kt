package com.wand.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import com.wand.app.ui.components.WandInlinePanel
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.reduceMotionEnabled
import androidx.compose.material3.ripple
import com.wand.app.ui.theme.GlassBackdrop
import com.wand.app.ui.theme.WandGlass
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.glassSurface
import com.wand.app.ui.theme.isWandDarkTheme

/** 紧凑视觉尺寸配合独立触控区，图标在浅色和深色底上都清晰可辨。 */
internal val ComposerActionVisualSize = 34.dp
internal val ComposerActionIconSize = 20.dp
internal val ComposerActionTouchSize = 48.dp
// 触控盒本身提供按钮间距，不再叠加额外空隙。
internal val ComposerActionSpacing = 0.dp

@Composable
fun NativeComposerSurface(
    backdrop: GlassBackdrop?,
    modifier: Modifier = Modifier,
    drawSurface: Boolean = true,
    focused: Boolean = false,
    inputContent: @Composable RowScope.() -> Unit,
    controls: @Composable RowScope.() -> Unit,
    inlineControls: Boolean = false,
    leadingControl: @Composable RowScope.() -> Unit = {},
    /** 就地展开的工具面板（＋ 展开的相册/文件行）：从输入行上方长出来、收回时缩回去。 */
    panelVisible: Boolean = false,
    panelModifier: Modifier = Modifier,
    panelContent: @Composable RowScope.() -> Unit = {},
    /** Optional session-scoped resources, expanding upwards with the same bottom toolbar anchor. */
    resourcePanel: @Composable () -> Unit = {},
) {
    val motionEnabled = !reduceMotionEnabled()
    // 同一条底部操作行持续挂载，输入和附件只向上生长。
    val composerShape = if (inlineControls) androidx.compose.foundation.shape.RoundedCornerShape(24.dp) else WandShapes.lg
    val darkGlass = isWandDarkTheme()
    val composerGlass = WandGlass.regular.copy(
        tintAlpha = if (darkGlass) 0.68f else 0.80f,
        fallbackAlpha = if (darkGlass) 0.93f else 0.95f,
        blurRadius = 11.dp,
        refractionHeight = 0.dp,
        refractionAmount = 0.dp,
        shadowElevation = 2.dp,
    )

    val animatedBorderColor by animateColorAsState(
        targetValue = if (focused) WandColors.focusRing else WandColors.border,
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenFast()),
        label = "composerSurfaceBorder",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (drawSurface) Modifier.padding(horizontal = if (inlineControls) 0.dp else 6.dp, vertical = 4.dp) else Modifier),
    ) {
        val surfaceModifier = if (drawSurface && inlineControls) {
            Modifier.fillMaxWidth().clip(composerShape).background(WandColors.surface)
                .border(0.6.dp, animatedBorderColor, composerShape).padding(horizontal = 4.dp)
        } else if (drawSurface) {
            Modifier
                .fillMaxWidth()
                .glassSurface(
                    backdrop = backdrop,
                    shape = composerShape,
                    style = composerGlass,
                    drawRim = false,
                )
                .border(1.dp, animatedBorderColor, composerShape)
                .padding(horizontal = 6.dp, vertical = 6.dp)
        } else {
            Modifier.fillMaxWidth()
        }
        Column(
            modifier = surfaceModifier,
            verticalArrangement = Arrangement.spacedBy(if (inlineControls) 0.dp else 4.dp),
        ) {
            resourcePanel()
            // 面板在输入行**上方**：输入行位置不动，面板从它上方长出来、原路缩回去，
            // 视觉上就是「加号原地展开」，符合动效规范规则 2。
            WandInlinePanel(visible = panelVisible, growFrom = Alignment.Bottom) {
                Row(
                    modifier = panelModifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = panelContent,
                )
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(ComposerActionSpacing),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (inlineControls) 48.dp else 0.dp)
                    .animateContentSize(
                        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenNormal()),
                        alignment = Alignment.BottomCenter,
                    ),
            ) {
                if (inlineControls) leadingControl()
                inputContent()
                if (inlineControls) controls()
            }
            if (!inlineControls) Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ComposerActionSpacing),
                modifier = Modifier.fillMaxWidth(),
                content = controls,
            )
        }
    }
}

@Composable
internal fun FilledComposerAction(
    enabled: Boolean,
    fillColor: Color,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.92f else 1f,
        WandMotion.respectMotion(!reduceMotionEnabled(), WandMotion.tweenPress()),
        label = "filledComposerScale",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(ComposerActionTouchSize)
            .clip(CircleShape)
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = ripple(),
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
