package com.wand.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wand.app.data.AiTeam
import com.wand.app.data.boardTaskProviderLabel
import com.wand.app.data.TaskBoardPort
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandDetailBackButton
import com.wand.app.ui.components.WandDetailTopBar
import com.wand.app.ui.components.WandIconButton
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandInlinePanel
import com.wand.app.ui.components.WandMorphIconButton
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandShapes

/**
 * AI 团队列表页（§6.2 A2）：团队定义列表 + 「＋」新建入口。
 * ＋ 从原位展开模板面板（动效硬要求 2）：选完模板直接进编辑器，不弹底部弹层也不换页；
 * 编辑既有团队走卡片上的「编辑」或详情页顶栏，两处都进同一个 AiTeamEditorScreen。
 */
@Composable
fun AiTeamsScreen(
    api: TaskBoardPort,
    onBack: () -> Unit,
    onOpenTeam: (String) -> Unit,
    /** 新建：参数是起步模板 id（与 Web 的模板列表同源）。 */
    onCreateTeam: (String) -> Unit = {},
    /** 卡片上的「编辑」快捷入口：直接进编辑器改成员，不用先进详情。 */
    onEditTeam: (String) -> Unit = {},
) {
    var teams by remember { mutableStateOf<List<AiTeam>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshNonce by remember { mutableStateOf(0) }
    var templatesOpen by remember { mutableStateOf(false) }

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
                subtitle = "团队可以在这里新建、改成员，也可以在任务上直接交给团队",
                leading = { WandDetailBackButton(onClick = onBack) },
                actions = {
                    WandIconButton(
                        icon = WandIcons.refresh,
                        contentDescription = "刷新",
                        onClick = { refreshNonce += 1 },
                    )
                    // ＋ 与 ✕ 是同一个图标按钮的两个形态（硬要求 4）：位置、尺寸不变。
                    WandMorphIconButton(
                        expanded = templatesOpen,
                        collapsedIcon = WandIcons.add,
                        expandedIcon = WandIcons.close,
                        contentDescription = if (templatesOpen) "收起新建团队" else "新建团队",
                        onClick = { templatesOpen = !templatesOpen },
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
            Column(modifier = Modifier.fillMaxSize()) {
                // 模板面板从顶栏下方长出来（就地展开），关掉时原路收回 ＋。
                WandInlinePanel(visible = templatesOpen, growFrom = Alignment.Top) {
                    AiTeamTemplatePanel(
                        onPick = { templateId ->
                            templatesOpen = false
                            onCreateTeam(templateId)
                        },
                    )
                }
                when {
                    loading && teams.isEmpty() -> Box(modifier = Modifier.fillMaxSize()) {
                        CircularProgressIndicator(
                            color = WandColors.brand,
                            modifier = Modifier.align(Alignment.Center).size(26.dp),
                        )
                    }
                    error != null && teams.isEmpty() -> Box(modifier = Modifier.fillMaxSize()) {
                        AiTeamsLoadError(
                            message = error ?: "",
                            onRetry = { refreshNonce += 1 },
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    else -> LazyColumn(
                        modifier = Modifier
                            .widthIn(max = 720.dp)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(14.dp, 14.dp, 14.dp, 30.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (teams.isEmpty() && error == null) {
                            item {
                                Text(
                                    "还没有团队。点右上角 ＋ 选一个模板就能开始。",
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
                                onEdit = { onEditTeam(team.id) },
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
}

/** 新建团队的模板面板：一行一个模板（名字 + 能干什么），点即进编辑器。 */
@Composable
private fun AiTeamTemplatePanel(onPick: (String) -> Unit) {
    WandCard(
        modifier = Modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentPadding = PaddingValues(12.dp),
    ) {
        Text(
            "先选一个起步模板，进去还能改",
            color = WandColors.textSecondary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        AI_TEAM_TEMPLATES.forEach { template ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(WandShapes.sm)
                    .clickable { onPick(template.id) }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            ) {
                Text(
                    template.name,
                    color = WandColors.textPrimary,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    template.summary,
                    color = WandColors.textMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
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
    onEdit: () -> Unit,
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
                // 徽标只说「谁是负责人角色」，不再拼成员名（§2.3：`负责人 负责人` 是同义重复）。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Icon(
                        WandIcons.leader,
                        contentDescription = "负责人",
                        tint = WandColors.brand,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        "负责人",
                        color = WandColors.brand,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
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
                        Icon(
                            WandIcons.leader,
                            contentDescription = "负责人",
                            tint = WandColors.brand,
                            modifier = Modifier.padding(start = 4.dp).size(13.dp),
                        )
                    }
                    if (!provider.isNullOrBlank()) {
                        Text(
                            boardTaskProviderLabel(provider),
                            color = WandColors.textMuted,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        }
        // 快捷入口：不必先进详情才能改成员。按钮只在卡片内部占位，不影响整卡可点。
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            WandButton(
                label = "编辑",
                onClick = onEdit,
                variant = WandButtonVariant.Text,
                compact = true,
                icon = WandIcons.edit,
            )
        }
    }
}
