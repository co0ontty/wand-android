package com.wand.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wand.app.ui.theme.GlassBackdrop
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandGlass
import com.wand.app.ui.theme.glassSurface

/**
 * 详情页统一顶栏。默认标题按屏幕居中，与 IM 参考图保持同一层级；
 * 自定义 titleContent 仍使用 RowScope，以容纳会话头像等专属内容。
 */
@Composable
fun WandDetailTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    backdrop: GlassBackdrop? = null,
    leading: (@Composable () -> Unit)? = null,
    titleContent: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    contentHeight: Dp = 52.dp,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassSurface(
                backdrop,
                RoundedCornerShape(0.dp),
                WandGlass.regular.copy(refractionHeight = 0.dp, shadowElevation = 0.dp),
                edgeToEdge = true,
            ),
    ) {
        val rowModifier = Modifier.fillMaxWidth().statusBarsPadding()
            .heightIn(min = contentHeight).padding(horizontal = 8.dp, vertical = 2.dp)
        if (titleContent != null) {
            Row(rowModifier, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (leading != null) leading()
                titleContent()
                actions()
            }
        } else {
            // Measure both action edges first, then reserve their maximum symmetrically.
            // The title stays at the screen centre without overlapping a long action label.
            Layout(
                modifier = rowModifier,
                content = {
                    Box { if (leading != null) leading() }
                    Row(verticalAlignment = Alignment.CenterVertically, content = actions)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(title, style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            textAlign = TextAlign.Center, color = WandColors.textPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!subtitle.isNullOrBlank()) {
                            Text(subtitle, style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center, color = WandColors.textMuted,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
            ) { measurables, constraints ->
                val edgeConstraints = constraints.copy(minWidth = 0, minHeight = 0, maxWidth = constraints.maxWidth / 2)
                val start = measurables[0].measure(edgeConstraints)
                val end = measurables[1].measure(edgeConstraints)
                val side = maxOf(start.width, end.width) + 8.dp.roundToPx()
                val heading = measurables[2].measure(constraints.copy(
                    minWidth = 0, minHeight = 0, maxWidth = (constraints.maxWidth - side * 2).coerceAtLeast(0),
                ))
                val height = constraints.constrainHeight(maxOf(start.height, end.height, heading.height))
                layout(constraints.maxWidth, height) {
                    start.placeRelative(0, (height - start.height) / 2)
                    end.placeRelative(constraints.maxWidth - end.width, (height - end.height) / 2)
                    heading.placeRelative((constraints.maxWidth - heading.width) / 2, (height - heading.height) / 2)
                }
            }
        }
    }
}

@Composable
fun WandDetailBackButton(
    onClick: () -> Unit,
    contentDescription: String = "返回",
    icon: ImageVector = WandIcons.back,
    enabled: Boolean = true,
) {
    WandIconButton(
        variant = WandIconButtonVariant.Chrome,
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        iconSize = 22.dp,
        enabled = enabled,
    )
}
