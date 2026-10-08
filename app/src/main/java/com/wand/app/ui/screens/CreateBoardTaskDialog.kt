package com.wand.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wand.app.data.*
import com.wand.app.ui.components.*
import com.wand.app.ui.theme.WandColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 与工作区新建相同的详情式表单，不再使用容纳不下完整配置的确认弹窗。 */
@Composable
internal fun CreateBoardTaskDialog(
    workspaces: List<Workspace>,
    tasks: List<BoardTask>,
    teams: List<AiTeam>,
    employees: List<SiliconEmployee>,
    models: ModelsResponse?,
    lastAgent: BoardTaskAgent,
    defaultWorkspaceId: String,
    initialStatus: String,
    busy: Boolean,
    error: String?,
    teamRunRetry: Boolean = false,
    api: TaskBoardPort,
    workspaceApi: WorkspacePort,
    onDismiss: () -> Unit,
    onCreate: (String, String, String, String, WorkspaceDirectorySelection, BoardTaskAgent, String?, String?, String?) -> Unit,
    onDispatchStarted: (AiTeamDispatchRun) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(initialStatus) }
    var priority by remember { mutableStateOf("none") }
    var directory by remember { mutableStateOf(workspaces.firstOrNull { it.id == defaultWorkspaceId }
        ?.let { WorkspaceDirectorySelection(it.cwd, it.id) } ?: WorkspaceDirectorySelection("")) }
    var directoryOpen by remember { mutableStateOf(false) }
    var parentTaskId by remember { mutableStateOf("") }
    var agent by remember { mutableStateOf(lastAgent) }
    var dispatchSubjectKey by remember { mutableStateOf("cli") }
    var showAdvanced by remember { mutableStateOf(false) }
    var resolvingDirectory by remember { mutableStateOf(false) }
    var directoryError by remember { mutableStateOf<String?>(null) }
    val dispatchFlow = remember { TeamDispatchFlowState() }
    val scope = rememberCoroutineScope()
    val dispatchMode = dispatchSubjectKey == "dispatch"
    val formBusy = busy || resolvingDirectory || dispatchFlow.busy
    // 建卡后失败的重试保留原目标，避免让旧卡在新目录/新对象下继续执行。
    val fieldsEnabled = !formBusy && !teamRunRetry
    val parentOptions = if (directory.workspaceId != null || directory.cwd.isBlank())
        boardParentTaskOptions(tasks, directory.workspaceId) else listOf("" to "不关联父任务")
    val dispatches = boardCreateDispatches(status)
    LaunchedEffect(dispatches, teams, employees, dispatchSubjectKey) {
        if (dispatchSubjectKey == "dispatch" || teamRunRetry) return@LaunchedEffect
        val selected = ExecutionSubject.fromKey(dispatchSubjectKey, agent.provider)
        if (!dispatches || (selected.type == "team" && teams.none { it.id == selected.id }) ||
            (selected.type == "employee" && employees.none { it.id == selected.id })) dispatchSubjectKey = "cli"
    }
    val selectedSubject = ExecutionSubject.fromKey(dispatchSubjectKey, agent.provider)
    val teamTarget = teams.firstOrNull { selectedSubject.type == "team" && it.id == selectedSubject.id }
    val employeeTarget = employees.firstOrNull { selectedSubject.type == "employee" && it.id == selectedSubject.id }
    val dispatchReason = if (directory.cwd.isBlank()) dispatchWorkspaceBlockedReason(directory.workspaceId.orEmpty()) else null
    val dispatchBlocked = dispatchStartBlockedReason(dispatchFlow.selection,
        directory.workspaceId ?: directory.cwd.takeIf { it.isNotBlank() }.orEmpty(), description, false)
    val actionLabel = if (dispatchMode) dispatchPrimaryActionLabel(dispatchFlow.phase, dispatchFlow.hasPlan)
    else boardCreateActionLabel(teamTarget != null, dispatches, description.trim().isNotEmpty(),
        formBusy, teamRunRetry, employeeTarget != null)
    val visibleError = directoryError ?: error ?: dispatchFlow.message?.takeIf { dispatchFlow.phase == TeamDispatchPhase.Failed }
        ?: "团队已不可用，请关闭后重新选择；已创建的任务不会重复建卡".takeIf { teamRunRetry && teamTarget == null }
    WandFormDialog(
        title = "新建任务", busy = formBusy, onDismiss = onDismiss,
        footer = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
                Text(visibleError ?: if (teamRunRetry) "任务已创建，本次仅重试交给团队，不会重复建卡"
                    else if (dispatchMode) dispatchReason ?: "决策选人后再确认开工"
                    else if (dispatches) "有任务内容时创建后立即开工" else "只创建任务，稍后再开始",
                    color = if (visibleError != null) WandColors.danger else WandColors.textSecondary,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
                }
                WandButton(actionLabel, onClick = {
                    directoryError = null
                    if (!dispatchMode) {
                        onCreate(title.trim(), description.trim(), status, priority, directory, agent,
                            parentTaskId.takeIf { id -> id.isNotBlank() && parentOptions.any { it.first == id } },
                            teamTarget?.id, employeeTarget?.id)
                    } else scope.launch {
                        if (!dispatchFlow.hasPlan) dispatchFlow.loadPlan(api, description)
                        else {
                            resolvingDirectory = true
                            try {
                                val workspace = resolveCreationWorkspace(workspaceApi, directory)
                                    ?: throw IllegalStateException("请选择工作区或运行目录")
                                directory = WorkspaceDirectorySelection(workspace.cwd, workspace.id)
                                dispatchFlow.submit(api, workspace.id, description)?.let(onDispatchStarted)
                            } catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                directoryError = failure.message ?: "无法选择运行目录"
                            } finally { resolvingDirectory = false }
                        }
                    }
                }, enabled = !formBusy && (!teamRunRetry || teamTarget != null) && if (dispatchMode) {
                    if (dispatchFlow.hasPlan) dispatchBlocked.isEmpty() else description.isNotBlank()
                } else title.isNotBlank() || description.isNotBlank(),
                    loading = formBusy, modifier = Modifier.fillMaxWidth().height(48.dp))
            }
        },
        overlays = {
            if (directoryOpen) WorkspaceDirectoryPicker(workspaceApi, directory.cwd, allowUnassigned = true,
                onSelect = { directory = it; parentTaskId = ""; directoryError = null; directoryOpen = false },
                onDismiss = { directoryOpen = false })
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.height(4.dp))
            WandTextField(description, { description = it }, label = "任务内容", placeholder = when {
                dispatchMode -> "将作为决策判断与团队执行的唯一目标"
                teamTarget != null -> "告诉团队要做什么"
                employeeTarget != null -> "告诉员工要做什么"
                dispatches -> "输入任务内容，创建后开始执行"
                else -> "输入任务内容，稍后再开始"
            }, minLines = 4, maxLines = 8, enabled = fieldsEnabled, modifier = Modifier.fillMaxWidth())
            WandCard(contentPadding = PaddingValues(horizontal = 16.dp)) {
                WandChoice("状态 · ${boardTaskStatusLabel(status)}",
                    BOARD_TASK_STATUSES.map { it to boardTaskStatusLabel(it) }, { status = it }, enabled = fieldsEnabled,
                    leadingIcon = WandIcons.todo)
                HorizontalDivider(color = WandColors.border.copy(alpha = 0.5f))
                WorkspaceDirectoryField(directory.cwd, fieldsEnabled) { directoryOpen = true }
                if (dispatches) {
                    HorizontalDivider(color = WandColors.border.copy(alpha = 0.5f))
                    WandChoice("执行对象 · " + when {
                        dispatchMode -> "临时派工（决策选人）"
                        else -> employeeTarget?.name ?: teamTarget?.name ?: "CLI 工具"
                    }, buildList {
                        add("cli" to "CLI 工具")
                        employees.forEach { add("employee:${it.id}" to "员工 · ${it.name}") }
                        teams.forEach { add("team:${it.id}" to "团队 · ${it.name}（${it.members.size} 人）") }
                        add("dispatch" to "临时派工 · 决策选人")
                    }, { dispatchSubjectKey = it }, enabled = fieldsEnabled, leadingIcon = WandIcons.agent)
                }
                if (dispatchMode) {
                    Text(dispatchReason ?: "写清目标，决策选人后确认开工。",
                        color = WandColors.textSecondary, style = MaterialTheme.typography.bodySmall)
                    TeamDispatchRoster(dispatchFlow, maxRosterHeight = 220.dp)
                }
                HorizontalDivider(color = WandColors.border.copy(alpha = 0.5f))
                BoardTaskSettingsSummary(title, priority, parentTaskId, parentOptions, agent,
                    dispatches && teamTarget == null && employeeTarget == null && !dispatchMode)
                WandButton("更多设置", { showAdvanced = !showAdvanced }, enabled = !formBusy,
                    variant = WandButtonVariant.Text, modifier = Modifier.fillMaxWidth().semantics {
                        stateDescription = if (showAdvanced) "已展开" else "已收起"
                    })
                WandInlinePanel(visible = showAdvanced) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WandTextField(title, { title = it.replace("\n", "") }, label = "任务名称（可选）",
                            placeholder = "留空按任务内容自动命名", singleLine = true,
                            enabled = fieldsEnabled, modifier = Modifier.fillMaxWidth())
                        if (dispatches && teamTarget == null && employeeTarget == null && !dispatchMode) {
                            WandAgentFields(models, agent, "CLI 工具", { agent = it }, enabled = fieldsEnabled)
                        }
                        WandChoice("归属父任务 · ${parentOptions.firstOrNull { it.first == parentTaskId }?.second ?: "不关联父任务"}",
                            parentOptions, { parentTaskId = it }, enabled = fieldsEnabled)
                        WandChoice("优先级 · ${boardTaskPriorityLabel(priority)}",
                            BOARD_TASK_PRIORITIES.map { it to boardTaskPriorityLabel(it) }, { priority = it }, enabled = fieldsEnabled)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun BoardTaskSettingsSummary(
    title: String, priority: String, parentTaskId: String, parents: List<Pair<String, String>>,
    agent: BoardTaskAgent, showAgent: Boolean,
) {
    Text(listOfNotNull(title.trim().takeIf { it.isNotEmpty() } ?: "自动命名", boardTaskPriorityLabel(priority),
        parents.firstOrNull { it.first == parentTaskId && it.first.isNotBlank() }?.second,
        if (showAgent) "${boardTaskProviderLabel(agent.provider)} · ${boardTaskKindLabel(agent.kind)} · ${boardTaskModeLabel(agent.mode)}" else null,
    ).joinToString(" · "), color = WandColors.textMuted, style = MaterialTheme.typography.bodySmall,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
}
