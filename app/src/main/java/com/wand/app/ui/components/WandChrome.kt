package com.wand.app.ui.components

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wand.app.ui.theme.GlassBackdrop
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandGlass
import com.wand.app.ui.theme.glassSurface

/**
 * 详情页统一顶栏。手机和 Pad 共用同一套玻璃栏，只靠 leading 是否出现来换呼吸感：
 * 有返回时标题跟在按钮后；分栏根详情没有返回时，标题从 16dp 起排，不再留空槽。
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
    contentHeight: Dp = 56.dp,
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .heightIn(min = contentHeight)
                .padding(
                    start = if (leading != null) 4.dp else 16.dp,
                    end = 10.dp,
                    top = 6.dp,
                    bottom = 6.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (leading != null) leading()
            if (titleContent != null) {
                titleContent()
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = WandColors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = WandColors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            actions()
        }
    }
}

@Composable
fun WandDetailBackButton(
    onClick: () -> Unit,
    contentDescription: String = "返回",
    icon: ImageVector = WandIcons.close,
) {
    ToolbarIconButton(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        iconSize = 22.dp,
    )
}
