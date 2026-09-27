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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wand.app.data.AiTeam
import com.wand.app.data.AiTeamMember
import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.TaskBoardPort
import com.wand.app.data.Workspace
import com.wand.app.data.WorkspacePort
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandDetailBackButton
import com.wand.app.ui.components.WandDetailTopBar
import com.wand.app.ui.components.WandIconButton
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandTextField
import com.wand.app.ui.theme.WandColors
import kotlinx.coroutines.launch

/**
 * 团队详情（§6.2 A2/A3）：成员组织图只读 + 「直接开工」。
 * 不提供成员 / 候选编辑（§11-Q6），不提供增删；提交结果全部原位呈现，不用 Toast。
 */
@Composable
fun AiTeamDetailScreen(
    api: TaskBoardPort,
    workspaceApi: WorkspacePort,
    teamId: String,
    onBack: () -> Unit,
    onOpenTask: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var team by remember { mutableStateOf<AiTeam?>(null) }
    var projects by remember { mutableStateOf<List<Workspace>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshNonce by remember { mutableStateOf(0) }

    var note by remember { mutableStateOf("") }
    // "" = 还没选，实际提交用 defaultTeamStartProjectId 的缺省（列表首个 = 最近一个）。
    var pickedWorkspaceId by remember { mutableStateOf("") }
    var projectMenuOpen by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(api, teamId, refreshNonce) {
        loading = true
        try {
            team = api.listAiTeams().firstOrNull { it.id == teamId }
            error = null
        } catch (e: Exception) {
            error = e.message ?: "无法加载团队详情"
        } finally {
            loading = false
        }
        // 项目列表失败不挡组织图展示：候选为空时开工区自己原位说明。
        projects = runCatching { workspaceApi.listWorkspaces() }.getOrDefault(projects)
    }

    val candidates = teamStartProjectCandidates(projects)
    val effectiveWorkspaceId = pickedWorkspaceId.ifBlank { defaultTeamStartProjectId(projects) }
    val selectedProjectName = candidates.firstOrNull { it.id == effectiveWorkspaceId }
        ?.let { it.name.ifBlank { it.id } }
    // 与服务端 boundedText 同口径：trim 后判长度，首尾空白不吃配额。
    val noteError = note.takeIf { it.trim().length > TEAM_DIRECT_NOTE_MAX }?.let { teamDirectNoteError(note) }
    val canStart = candidates.isNotEmpty() && noteError == null && note.isNotBlank() && !submitting

    fun submit() {
        val invalid = teamDirectSubmitError(projects, note)
        if (invalid != null) {
            submitError = invalid
            return
        }
        submitting = true
        submitError = null
        scope.launch {
            try {
                val direct = api.startDirectTeamRun(teamId, effectiveWorkspaceId, note.trim())
                val taskId = aiTeamDirectRunTaskId(direct)
                if (taskId.isNotBlank()) {
                    // 成功 = 跳看板详情页，那里有团队运行面板与「打开群聊」。
                    onOpenTask(taskId)
                } else {
                    submitError = "已开工，但响应里没带任务卡，请在任务看板里查找。"
                }
            } catch (e: Exception) {
                // 服务端 400/404 文案原样显示（如「AI 团队不能在全局暂存工作区运行…」）。
                submitError = e.message ?: "开工失败"
            } finally {
                submitting = false
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            WandDetailTopBar(
                title = team?.name ?: "AI 团队",
                subtitle = "团队详情 · 只读",
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
                loading && team == null -> CircularProgressIndicator(
                    color = WandColors.brand,
                    modifier = Modifier.align(Alignment.Center).size(26.dp),
                )
                error != null && team == null -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(error ?: "", color = WandColors.danger, style = MaterialTheme.typography.bodyMedium)
                    WandButton(
                        label = "重试",
                        onClick = { refreshNonce += 1 },
                        variant = WandButtonVariant.Secondary,
                        compact = true,
                    )
                }
                team == null -> Text(
                    "团队不存在或已被删除。",
                    color = WandColors.textMuted,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                else -> {
                    val currentTeam = team
                    if (currentTeam != null) LazyColumn(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .widthIn(max = 720.dp)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(14.dp, 14.dp, 14.dp, 30.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // 已有旧数据时刷新失败：顶部原位错误行（照 AiTeamsScreen），重试走顶栏刷新按钮。
                        if (error != null) {
                            item(key = "ai-team-detail-error") {
                                Text(
                                    error ?: "",
                                    color = WandColors.danger,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                )
                            }
                        }
                        item(key = "ai-team-members") {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                // 组织图：负责人在最前、成员在后（aiTeamOrderedMembers）。
                                aiTeamOrderedMembers(currentTeam).forEach { member ->
                                    AiTeamMemberCard(member)
                                }
                            }
                        }
                        item(key = "ai-team-start") {
                            AiTeamStartCard(
                                candidates = candidates,
                                selectedProjectName = selectedProjectName,
                                projectMenuOpen = projectMenuOpen,
                                onToggleProjectMenu = { projectMenuOpen = !projectMenuOpen },
                                onPickProject = {
                                    pickedWorkspaceId = it
                                    projectMenuOpen = false
                                    submitError = null
                                },
                                note = note,
                                onNoteChange = {
                                    note = it
                                    submitError = null
                                },
                                noteError = noteError,
                                submitting = submitting,
                                submitError = submitError,
                                canStart = canStart,
                                onSubmit = { submit() },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiTeamMemberCard(member: AiTeamMember) {
    WandCard(contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                member.name,
                color = WandColors.textPrimary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (member.isLeader) {
                Text(
                    "负责人",
                    color = WandColors.brand,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (member.duty.isNotBlank()) {
            Text(
                member.duty,
                color = WandColors.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (member.agents.isEmpty()) {
                Text("未配置执行候选", color = WandColors.textMuted, style = MaterialTheme.typography.labelSmall)
            }
            member.agents.forEachIndexed { index, agent ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        aiTeamCandidateRoleLabel(index),
                        color = if (index == 0) WandColors.brand else WandColors.textMuted,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.widthIn(min = 40.dp),
                    )
                    Text(
                        aiTeamAgentLabel(agent),
                        color = WandColors.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 候选一行：provider · model · effort；default/off 这类缺省值不占篇幅。 */
fun aiTeamAgentLabel(agent: BoardTaskAgent): String = buildString {
    append(agent.provider)
    if (agent.model.isNotBlank() && agent.model != "default") append(" · ").append(agent.model)
    if (agent.thinkingEffort.isNotBlank() && agent.thinkingEffort != "off") {
        append(" · ").append(agent.thinkingEffort)
    }
}

@Composable
private fun AiTeamStartCard(
    candidates: List<Workspace>,
    selectedProjectName: String?,
    projectMenuOpen: Boolean,
    onToggleProjectMenu: () -> Unit,
    onPickProject: (String) -> Unit,
    note: String,
    onNoteChange: (String) -> Unit,
    noteError: String?,
    submitting: Boolean,
    submitError: String?,
    canStart: Boolean,
    onSubmit: () -> Unit,
) {
    WandCard(contentPadding = PaddingValues(14.dp)) {
        Text(
            "直接开工",
            color = WandColors.textPrimary,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (candidates.isEmpty()) {
            // 原位提示替代禁用按钮的瞎点击（§4.2：必须有非 global 已有项目）。
            Text(
                "AI 团队需要先选择一个已有项目",
                color = WandColors.textMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                WandButton(
                    label = "项目：" + (selectedProjectName ?: "选择项目"),
                    onClick = onToggleProjectMenu,
                    variant = WandButtonVariant.Secondary,
                    compact = true,
                    trailingIcon = WandIcons.expand,
                    enabled = !submitting,
                )
                DropdownMenu(
                    expanded = projectMenuOpen,
                    onDismissRequest = { onToggleProjectMenu() },
                    containerColor = WandColors.bgElevated,
                ) {
                    candidates.forEach { project ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        project.name.ifBlank { project.id },
                                        color = WandColors.textPrimary,
                                    )
                                    Text(
                                        project.cwd,
                                        color = WandColors.textMuted,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                            onClick = { onPickProject(project.id) },
                        )
                    }
                }
            }
        }
        WandTextField(
            value = note,
            onValueChange = onNoteChange,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            label = "开工说明",
            placeholder = "要做什么？成员会按职责分工执行",
            isError = noteError != null,
            minLines = 3,
            maxLines = 8,
            enabled = !submitting,
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(
                noteError ?: "上限 $TEAM_DIRECT_NOTE_MAX 字",
                color = if (noteError != null) WandColors.danger else WandColors.textMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${note.length}",
                color = if (noteError != null) WandColors.danger else WandColors.textMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        submitError?.let { message ->
            Text(
                message,
                color = WandColors.danger,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        WandButton(
            label = if (submitting) "开工中…" else "开工",
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            enabled = canStart,
            loading = submitting,
            icon = if (submitting) null else WandIcons.send,
        )
    }
}
