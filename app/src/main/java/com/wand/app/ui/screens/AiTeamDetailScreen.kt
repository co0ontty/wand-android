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
import androidx.compose.material3.Icon
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
import com.wand.app.data.ModelsResponse
import com.wand.app.data.TaskBoardPort
import com.wand.app.data.boardAgentModelName
import com.wand.app.data.boardTaskProviderLabel
import com.wand.app.data.Workspace
import com.wand.app.data.WorkspacePort
import com.wand.app.ui.components.WandBreadcrumb
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandCrumb
import com.wand.app.ui.components.WandDetailTopBar
import com.wand.app.ui.components.WandIconButton
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandTextField
import com.wand.app.ui.theme.WandColors
import kotlinx.coroutines.launch

/**
 * 团队详情（§6.2 A2/A3）：成员组织图 + 「直接开工」，顶栏进编辑器改成员。
 * 组织图本身只读（点卡片不改人设，避免与编辑器的展开手势冲突）；
 * 提交结果全部原位呈现，不用 Toast。
 */
@Composable
fun AiTeamDetailScreen(
    api: TaskBoardPort,
    workspaceApi: WorkspacePort,
    teamId: String,
    onBack: () -> Unit,
    onOpenTask: (String) -> Unit,
    /** 「直接开工」成功后进 IM 群聊页（参数是运行 id，不是任务 id）。 */
    onOpenGroupChat: (String) -> Unit = onOpenTask,
    /** 顶栏「编辑」：进团队编辑器改名字、成员与执行候选。 */
    onEditTeam: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var team by remember { mutableStateOf<AiTeam?>(null) }
    // 执行候选的模型名要把 `default` 哨兵换成一个具体的默认模型（同 Web 目录口径）。
    var models by remember { mutableStateOf<ModelsResponse?>(null) }
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
        // 模型目录失败只影响候选行的模型名，不挡团队详情。
        models = runCatching { api.boardModels() }.getOrDefault(models)
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
                // 成功 = 直接落到这一轮的群聊页：那里能看到派工、能立刻补一句要求。
                val runId = direct.detail.run.id
                if (runId.isNotBlank()) {
                    onOpenGroupChat(runId)
                } else {
                    val taskId = aiTeamDirectRunTaskId(direct)
                    if (taskId.isNotBlank()) {
                        onOpenTask(taskId)
                    } else {
                        submitError = "已开工，但响应里没带运行 id，请在任务看板里查找。"
                    }
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
                title = "",
                // 返回入口只留一个：面包屑首段。原来的箭头与它同功能、同一条栏，属重复。
                leading = null,
                // 单层标题：面包屑「AI 团队 › 团队名」，副行说明当前可做的事（不再是「只读」）。
                titleContent = {
                    Column(modifier = Modifier.weight(1f)) {
                        WandBreadcrumb(
                            crumbs = listOf(
                                // 返回入口只留一个：面包屑首段，且**永远可点**——它与任务详情不同：
                                // 宽屏左栏只有会话/任务列表（TaskListScreen 里没有团队列表），
                                // 本页在宽屏也是经 nav.push 进入，栈里下一层恒为「AI 团队」列表，
                                // 恒有可回的去处。所以这里不存在「宽屏右栏即顶层」那种态，
                                // showBack 门控是走不到的分支，按 AGENTS.md 删掉（第 22 步实测取证）。
                                WandCrumb("AI 团队", onClick = onBack),
                                WandCrumb(team?.name ?: "AI 团队"),
                            ),
                        )
                        Text(
                            "团队详情 · 可编辑成员与执行配置",
                            style = MaterialTheme.typography.labelSmall,
                            color = WandColors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    WandIconButton(
                        icon = WandIcons.edit,
                        contentDescription = "编辑团队",
                        onClick = onEditTeam,
                    )
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
                                    AiTeamMemberCard(member, models)
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
private fun AiTeamMemberCard(member: AiTeamMember, models: ModelsResponse?) {
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
                // §2.3 F1：名字原样保留，角色改用矢量星形标记，不在标题再写一遍「负责人」
                // （成员名恰为「负责人」时，文字角色会变成同义重复，且掩盖真实名字）。
                Icon(
                    WandIcons.leader,
                    contentDescription = "团队角色：负责人",
                    tint = WandColors.brand,
                    modifier = Modifier.padding(start = 8.dp).size(13.dp),
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
                        aiTeamAgentLabel(agent, models),
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

/**
 * 候选一行：provider · model · effort；off 这类缺省值不占篇幅。
 * `model` 是 `default` 哨兵时换成服务端配置的默认模型名（拿不到名字才省掉这一段，不写「默认模型」）。
 */
fun aiTeamAgentLabel(agent: BoardTaskAgent, models: ModelsResponse? = null): String = buildString {
    append(boardTaskProviderLabel(agent.provider))
    val model = boardAgentModelName(models, agent.provider, agent.model)
    if (model.isNotBlank()) append(" · ").append(model)
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
