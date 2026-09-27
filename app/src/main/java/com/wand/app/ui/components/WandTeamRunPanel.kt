package com.wand.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wand.app.data.AiTeamRunDetail
import com.wand.app.data.TeamRunAction
import com.wand.app.data.aiTeamRunActive
import com.wand.app.data.aiTeamRunStatusLabel
import com.wand.app.data.aiTeamStepOwnerLabel
import com.wand.app.data.aiTeamStepStatusLabel
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandShapes

/**
 * 任务详情里的团队运行摘要（对齐 Web team-run-panel.tsx 的最小集）：
 * 状态、步数、当前步骤/成员，以及 approve / reject / reply / continue / stop 动作。
 * 数据由调用方拉取并在动作成功后重传，本组件不发请求、不轮询。
 */
@Composable
fun WandTeamRunPanel(
    detail: AiTeamRunDetail,
    busy: Boolean,
    onAction: (TeamRunAction) -> Unit,
    onOpenGroupChat: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val run = detail.run
    // 换一轮运行或进入新的等待态时清掉上一轮输入（同 Web TeamRunView 的 effect）。
    var respondText by remember(run.id, run.status) { mutableStateOf("") }
    val status = run.status
    val statusColor = when (status) {
        "running" -> WandColors.info
        "awaiting_approval", "waiting_user" -> WandColors.warning
        "done" -> WandColors.success
        "failed" -> WandColors.danger
        else -> WandColors.textMuted
    }
    val active = aiTeamRunActive(status)
    val currentStep = detail.steps.firstOrNull { it.status == "running" }
        ?: detail.steps.lastOrNull { it.status != "queued" && it.status != "skipped" }
    val needsYou = status == "awaiting_approval" || status == "waiting_user"
    val budgetSpent = run.stepsUsed >= run.stepLimit

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(WandShapes.md)
            .background(statusColor.copy(alpha = 0.08f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor),
            )
            Text(
                run.team?.name?.takeIf { it.isNotBlank() } ?: "AI 团队",
                color = WandColors.textPrimary,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                aiTeamRunStatusLabel(status),
                color = statusColor,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                "步数 ${run.stepsUsed}/${run.stepLimit}",
                color = WandColors.textMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (currentStep != null) {
            Text(
                "${aiTeamStepOwnerLabel(currentStep, detail)} · ${currentStep.title.ifBlank { "步骤 #${currentStep.seq}" }}" +
                    " · ${aiTeamStepStatusLabel(currentStep.status)}",
                color = WandColors.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (run.statusDetail.isNotBlank()) {
            Text(run.statusDetail, color = WandColors.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
        if (needsYou) {
            WandTextField(
                value = respondText,
                onValueChange = { respondText = it },
                label = if (status == "awaiting_approval") "退回意见" else "回复负责人",
                placeholder = if (status == "awaiting_approval") "退回时写下修改意见" else "回复负责人的问题或补充要求",
                minLines = 3,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status == "awaiting_approval") {
                    WandButton(
                        label = "退回重做",
                        onClick = { onAction(TeamRunAction.Reject(respondText.trim())) },
                        enabled = !busy && respondText.isNotBlank(),
                        variant = WandButtonVariant.Secondary,
                        compact = true,
                    )
                    WandButton(
                        label = "批准计划",
                        onClick = { onAction(TeamRunAction.Approve) },
                        enabled = !busy,
                        variant = WandButtonVariant.Success,
                        compact = true,
                    )
                } else {
                    if (budgetSpent) {
                        WandButton(
                            label = "追加 10 步继续",
                            onClick = { onAction(TeamRunAction.Continue(10)) },
                            enabled = !busy,
                            variant = WandButtonVariant.Secondary,
                            compact = true,
                        )
                    }
                    WandButton(
                        label = "发送回复",
                        onClick = { onAction(TeamRunAction.Reply(respondText.trim())) },
                        enabled = !busy && respondText.isNotBlank(),
                        variant = WandButtonVariant.Success,
                        compact = true,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            run.chatSessionId?.let { sessionId ->
                WandButton(
                    label = "打开群聊",
                    onClick = { onOpenGroupChat(sessionId) },
                    variant = WandButtonVariant.Secondary,
                    compact = true,
                )
            }
            if (active) {
                WandButton(
                    label = "停止",
                    onClick = { onAction(TeamRunAction.Stop) },
                    enabled = !busy,
                    variant = WandButtonVariant.Danger,
                    compact = true,
                )
            }
        }
    }
}
