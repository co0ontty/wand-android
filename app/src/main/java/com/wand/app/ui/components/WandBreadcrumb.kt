package com.wand.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.ui.theme.WandColors

/**
 * 单层页面标题：用「来源 › 当前」替代「页面标题 + 正文里再来一条同名标题」的双层堆叠。
 * 末段是当前页（不可点、带 aria 语义），前面每段可点且只负责往回走一步 ——
 * 点面包屑不跳页、不移动触发元素（动效总则）。
 */
data class WandCrumb(
    val label: String,
    /** null = 这一层没有可回的去处（例如直接深链进来）。 */
    val onClick: (() -> Unit)? = null,
)

@Composable
fun WandBreadcrumb(
    crumbs: List<WandCrumb>,
    modifier: Modifier = Modifier,
) {
    if (crumbs.isEmpty()) return
    Row(
        modifier = modifier.semantics { contentDescription = crumbs.last().label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 末段吃掉剩余宽度并省略号，窄屏也不会换成两行标题。
        val lastIndex = crumbs.lastIndex
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                Text(
                    "›",
                    fontSize = 12.sp,
                    color = WandColors.textMuted.copy(alpha = 0.7f),
                )
            }
            val current = index == lastIndex
            val label = if (current) crumb.label else "返回${crumb.label}"
            val base = Modifier.semantics { contentDescription = label }
            when {
                current -> Text(
                    crumb.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = WandColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = base.weight(1f),
                )
                crumb.onClick != null -> Text(
                    crumb.label,
                    fontSize = 13.sp,
                    color = WandColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = base.clickable(onClickLabel = label, onClick = crumb.onClick),
                )
                else -> Text(
                    crumb.label,
                    fontSize = 13.sp,
                    color = WandColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = base,
                )
            }
        }
    }
}
