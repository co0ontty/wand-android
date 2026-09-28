package com.wand.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.WorkspaceTaskSummary
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandProviderMark
import com.wand.app.ui.components.WandProviderMarkVariant
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.ambientBackground

/** Pad 初始详情区使用现有会话数据，避免只留一整块空白。 */
internal data class PadLandingSession(
    val group: TaskDirectoryGroup,
    val task: WorkspaceTaskSummary?,
    val session: WorkspaceSessionSummary,
)

internal fun padLandingSessions(groups: List<TaskDirectoryGroup>): List<PadLandingSession> =
    directoryTreeGroups(groups)
        .flatMap { group ->
            group.tasks.flatMap { task ->
                task.sessions.map { PadLandingSession(group, task, it) }
            } + group.standaloneSessions.map { PadLandingSession(group, null, it) }
        }
        .sortedWith(
            compareByDescending<PadLandingSession> {
                when (sessionPulse(it.session)) {
                    HomeSessionPulse.NeedsYou -> 2
                    HomeSessionPulse.Running -> 1
                    HomeSessionPulse.Quiet -> 0
                }
            }.thenByDescending { it.session.startedAt.orEmpty() },
        )
        .take(4)

@Composable
internal fun PadLandingScreen(
    groups: List<TaskDirectoryGroup>,
    onOpenSession: (TaskSessionRoute) -> Unit,
    onNewTask: () -> Unit,
) {
    val overview = homeOverview(directoryTreeGroups(groups))
    val sessions = padLandingSessions(groups)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .ambientBackground()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "工作概览",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = WandColors.textPrimary,
                )
                Text(
                    "从左侧选择任务，或在这里继续会话",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WandColors.textSecondary,
                )
            }
            WandButton(label = "新建任务", onClick = onNewTask, compact = true)
        }
        WandCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PadLandingMetric("在跑", overview.running, WandColors.success, Modifier.weight(1f))
                PadLandingMetric("等你", overview.needsYou, WandColors.permission, Modifier.weight(1f))
                PadLandingMetric("全部会话", overview.sessions, WandColors.textPrimary, Modifier.weight(1f))
            }
        }
        Text(
            "继续工作",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = WandColors.textPrimary,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (sessions.isEmpty()) {
            Text(
                "还没有会话。从左侧新建任务开始。",
                style = MaterialTheme.typography.bodyMedium,
                color = WandColors.textSecondary,
            )
        }
        sessions.forEachIndexed { index, entry ->
            PadLandingSessionRow(entry, index, onOpenSession)
        }
    }
}

@Composable
private fun PadLandingMetric(label: String, value: Int, color: Color, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = WandColors.textMuted)
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

@Composable
private fun PadLandingSessionRow(
    entry: PadLandingSession,
    index: Int,
    onOpenSession: (TaskSessionRoute) -> Unit,
) {
    val session = entry.session
    val pulse = sessionPulse(session)
    val status = when (pulse) {
        HomeSessionPulse.NeedsYou -> "等你处理"
        HomeSessionPulse.Running -> "运行中"
        HomeSessionPulse.Quiet -> "空闲"
    }
    val statusColor = when (pulse) {
        HomeSessionPulse.NeedsYou -> WandColors.permission
        HomeSessionPulse.Running -> WandColors.success
        HomeSessionPulse.Quiet -> WandColors.textMuted
    }
    WandCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = { onOpenSession(taskSessionRoute(session, entry.group, entry.task)) },
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            WandProviderMark(provider = session.provider, variant = WandProviderMarkVariant.Tinted)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    listSessionLabel(
                        session,
                        index = index,
                        parentNames = listOfNotNull(entry.task?.name, entry.group.workspaceName),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = WandColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(entry.group.workspaceName, entry.task?.name).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = WandColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(status, style = MaterialTheme.typography.labelMedium, color = statusColor)
            Icon(
                WandIcons.chevronRight,
                contentDescription = null,
                tint = WandColors.textMuted,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
