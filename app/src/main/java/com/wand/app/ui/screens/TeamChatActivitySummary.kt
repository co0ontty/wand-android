package com.wand.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandShapes

/** 进度留在公告下面，不进入消息流；原始输出只在成员会话里查看。 */
@Composable
internal fun TeamChatActivitySummary(
    activities: List<TeamChatActivity>,
    onOpenDetails: () -> Unit,
    onOpenMemberSession: (String) -> Unit,
) {
    if (activities.isEmpty()) return
    val attention = activities.filter { it.state in setOf("needs_permission", "needs_input", "failed") }
    val working = activities.filterNot { it in attention }
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (working.isNotEmpty()) {
            val text = if (working.size == 1) {
                val member = working.single()
                val action = if (member.state == "done") "已结束本轮处理" else "正在处理"
                "${member.memberName}$action · ${member.title.ifBlank { "当前任务" }}"
            } else {
                val members = working.map { it.memberName }.distinct()
                val names = members.take(2).joinToString("、")
                "$names${if (members.size > 2) "等" else ""} · ${working.size} 项任务进行中"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(WandShapes.sm)
                    .clickable(role = Role.Button, onClickLabel = "查看成员进度", onClick = onOpenDetails)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.labelSmall,
                    color = WandColors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("进度", style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
            }
        }
        if (attention.isNotEmpty()) {
            // 并行成员都可到达；提醒多时独立滚动，给聊天与输入保留空间。
            Column(
                modifier = Modifier.heightIn(max = 144.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                attention.forEach { activity ->
                    val action = when (activity.state) {
                        "needs_permission" -> "需要你授权"
                        "needs_input" -> "需要你回答"
                        else -> "执行遇到问题"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(WandShapes.sm)
                            .background(WandColors.warning.copy(alpha = 0.08f))
                            .clickable(role = Role.Button, onClickLabel = if (activity.sessionId != null) {
                                "查看${activity.memberName}的会话"
                            } else "查看团队进度") {
                                activity.sessionId?.let(onOpenMemberSession) ?: onOpenDetails()
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${activity.memberName}$action",
                                style = MaterialTheme.typography.labelMedium,
                                color = WandColors.warning,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                activity.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = WandColors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text("查看", style = MaterialTheme.typography.labelSmall, color = WandColors.warning)
                    }
                }
            }
        }
    }
}
