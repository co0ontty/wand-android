package com.wand.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wand.app.data.AiTeam
import com.wand.app.data.TaskBoardPort
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandDetailBackButton
import com.wand.app.ui.components.WandDetailTopBar
import com.wand.app.ui.components.WandIconButton
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.WandColors

/**
 * AI 团队列表页（§6.2 A2）：只读团队定义 + 进入详情「直接开工」。
 * 团队创建 / 编辑 / 多候选编辑都留在 Web 端（§11-Q6），这里不做搜索筛选。
 */
@Composable
fun AiTeamsScreen(
    api: TaskBoardPort,
    onBack: () -> Unit,
    onOpenTeam: (String) -> Unit,
) {
    var teams by remember { mutableStateOf<List<AiTeam>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshNonce by remember { mutableStateOf(0) }

    LaunchedEffect(api, refreshNonce) {
        loading = true
        try {
            teams = api.listAiTeams()
            error = null
        } catch (e: Exception) {
            error = e.message ?: "无法加载 AI 团队"
        } finally {
            loading = false
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            WandDetailTopBar(
                title = "AI 团队",
                subtitle = "团队定义在 Web 端创建与编辑，这里可查看并直接开工",
                leading = { WandDetailBackButton(onClick = onBack) },
                actions = {
                    WandIconButton(
                        icon = WandIcons.refresh,
                        contentDescription = "刷新",
                        onClick = { refreshNonce += 1 },
                    )
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                loading && teams.isEmpty() -> CircularProgressIndicator(
                    color = WandColors.brand,
                    modifier = Modifier.align(Alignment.Center).size(26.dp),
                )
                error != null && teams.isEmpty() -> AiTeamsLoadError(
                    message = error ?: "",
                    onRetry = { refreshNonce += 1 },
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = 720.dp)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(14.dp, 14.dp, 14.dp, 30.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (teams.isEmpty() && error == null) {
                        item {
                            Text(
                                "还没有团队，可在 Web 端创建。",
                                color = WandColors.textMuted,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(36.dp),
                            )
                        }
                    }
                    items(teams, key = { it.id }) { team ->
                        AiTeamCard(
                            team = team,
                            onClick = { onOpenTeam(team.id) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                    // 加载失败但手上有旧数据：保留列表，只在顶部原位插一条错误。
                    if (error != null) {
                        item(key = "ai-teams-error") {
                            Text(
                                error ?: "",
                                color = WandColors.danger,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiTeamsLoadError(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(message, color = WandColors.danger, style = MaterialTheme.typography.bodyMedium)
        WandButton(
            label = "重试",
            onClick = onRetry,
            variant = WandButtonVariant.Secondary,
            compact = true,
        )
    }
}

@Composable
private fun AiTeamCard(
    team: AiTeam,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WandCard(
        modifier = modifier,
        onClick = onClick,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                team.name,
                color = WandColors.textPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            val leader = aiTeamLeader(team)
            if (leader != null) {
                Text(
                    "负责人 ${leader.name}",
                    color = WandColors.brand,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Text(
            aiTeamSummaryLine(team),
            color = WandColors.textMuted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (team.description.isNotBlank()) {
            Text(
                team.description,
                color = WandColors.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            aiTeamOrderedMembers(team).forEach { member ->
                val provider = aiTeamPreferredAgent(member)?.provider
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        member.name,
                        color = WandColors.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (member.isLeader) {
                        Text(
                            "★",
                            color = WandColors.brand,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    if (!provider.isNullOrBlank()) {
                        Text(
                            provider,
                            color = WandColors.textMuted,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
