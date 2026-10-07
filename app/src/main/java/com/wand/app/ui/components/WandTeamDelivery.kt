package com.wand.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wand.app.data.AiTeamDeliveryExpansion
import com.wand.app.data.AiTeamRunDetail
import com.wand.app.data.aiTeamDeliveryMemberName
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandShapes

/** Optional presentation only: no fetching, viewing acknowledgement or run actions. */
@Composable
fun WandTeamDeliveryPanel(detail: AiTeamRunDetail, baseUrl: String) {
    val delivery = detail.delivery ?: return
    var expansion by remember { mutableStateOf(AiTeamDeliveryExpansion()) }
    val expanded = expansion.expanded(detail.run.id)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().clip(WandShapes.sm).background(WandColors.surfaceSoft)
            .clickable(onClickLabel = if (expanded) "收起交付与接力" else "展开交付与接力") {
                expansion = expansion.toggle(detail.run.id)
            }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(delivery.headline, style = MaterialTheme.typography.bodySmall,
                color = WandColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Text(if (expanded) "收起" else "交付与接力", style = MaterialTheme.typography.labelSmall,
                color = WandColors.textMuted)
        }
        WandInlinePanel(visible = expanded, growFrom = Alignment.Top) {
            WandTeamDeliveryBody(detail, baseUrl)
        }
    }
}

/** Shared body for the existing group-context disclosure and task-run panel. */
@Composable
fun WandTeamDeliveryBody(detail: AiTeamRunDetail, baseUrl: String) {
    val delivery = detail.delivery?.takeIf { it.runId == detail.run.id } ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        delivery.conclusion?.takeIf { it.isNotBlank() }?.let {
            Text("负责人交付说明", style = MaterialTheme.typography.labelMedium, color = WandColors.brandText)
            Text(it, style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
        }
        delivery.attention?.let {
            Text(it.message, style = MaterialTheme.typography.bodySmall, color = WandColors.warningText)
        }
        Text("历史交付文件 · ${delivery.totalFiles}", style = MaterialTheme.typography.labelMedium,
            color = WandColors.textPrimary)
        if (delivery.files.isEmpty()) {
            Text("暂无可确认的交付文件", style = MaterialTheme.typography.bodySmall, color = WandColors.textMuted)
        } else {
            Text("冻结交付记录；当前文件是否可用以打开结果为准", style = MaterialTheme.typography.labelSmall,
                color = WandColors.textMuted)
            delivery.files.forEach { entry ->
                key(detail.run.id, entry.stepId, entry.file.path) {
                    Text("${aiTeamDeliveryMemberName(detail, entry.memberId, entry.memberName)} · ${entry.title}",
                        style = MaterialTheme.typography.labelSmall, color = WandColors.textSecondary)
                    WandTeamReportFileCard(entry.file, baseUrl)
                }
            }
            if (delivery.totalFiles > delivery.files.size) Text("仅展示前 ${delivery.files.size} 个文件",
                style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
        }
        if (delivery.totalHandoffs > 0) {
            Text("正在负责 / 等待接手 · ${delivery.totalHandoffs}", style = MaterialTheme.typography.labelMedium,
                color = WandColors.textPrimary)
            delivery.handoffs.forEach { entry ->
                val label = when {
                    entry.status == "queued" -> "等待接手"
                    entry.state == "needs_permission" -> "待批准"
                    entry.state == "needs_input" -> "待回复"
                    else -> "正在负责"
                }
                Text("${aiTeamDeliveryMemberName(detail, entry.memberId, entry.memberName)} · ${entry.title} · $label",
                    style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
                if (entry.waitingFor.isNotEmpty()) Text("等待：${entry.waitingFor.joinToString("、")}",
                    style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
            }
            if (delivery.totalHandoffs > delivery.handoffs.size) Text("仅展示前 ${delivery.handoffs.size} 项接力",
                style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
        }
    }
}
