package com.wand.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import com.wand.app.ui.components.WandInlinePanel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.wand.app.data.AiTeamDispatchRun
import com.wand.app.data.TaskBoardPort
import com.wand.app.data.dispatchStartBlockedReason
import com.wand.app.data.AiTeam
import com.wand.app.data.SiliconEmployee
import com.wand.app.data.ModelsResponse
import com.wand.app.data.WorkspaceSessionKind
import com.wand.app.data.WorkspaceSessionTarget
import com.wand.app.data.boardAgentModelOptions
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandDetailTopBar
import com.wand.app.ui.components.WandDetailBackButton
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandTextField
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.reduceMotionEnabled
import com.wand.app.ui.thinkingEffortOptions

/** 单独任务的新建窗口：保留任务创建链路，不进入任务看板的新建/指派表单。 */
@Composable
internal fun NewTaskComposerDialog(
    name: String,
    onNameChange: (String) -> Unit,
    prompt: String,
    onPromptChange: (String) -> Unit,
    cwd: String,
    onChooseDirectory: () -> Unit,
    parentOptions: List<Pair<String, String>>,
    parentTaskId: String,
    fieldsLocked: Boolean = false,
    onParentTaskChange: (String) -> Unit,
    parentsLoading: Boolean,
    parentError: String?,
    onReloadParents: () -> Unit,
    target: WorkspaceSessionTarget,
    teams: List<AiTeam> = emptyList(),
    employees: List<SiliconEmployee> = emptyList(),
    teamId: String? = null,
    employeeId: String? = null,
    teamAllowed: Boolean = false,
    onTeamChange: (String?) -> Unit = {},
    onEmployeeChange: (String?) -> Unit = {},
    /** 派工要的接口与目标项目：目标项目由宿主决定（首页列表按当前工作区传入）。 */
    api: TaskBoardPort,
    dispatchWorkspaceId: String = "",
    dispatchWorkspaceName: String? = null,
    onDispatchStarted: (AiTeamDispatchRun) -> Unit = {},
    teamRunRetry: Boolean = false,
    onTargetChange: (WorkspaceSessionTarget) -> Unit,
    kind: WorkspaceSessionKind,
    onKindChange: (WorkspaceSessionKind) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
    thinkingEffort: String,
    onThinkingEffortChange: (String) -> Unit,
    models: ModelsResponse?,
    startFirstSession: Boolean,
    onStartFirstSessionChange: (Boolean) -> Unit,
    /** 任务名留空又要起会话：这一轮不建卡，会话先进「未分组任务」。 */
    ungroupedStart: Boolean = false,
    worktree: Boolean,
    onWorktreeChange: (Boolean) -> Unit,
    canCreate: Boolean,
    busy: Boolean,
    error: String?,
    onSubmit: () -> Unit,
    directoryPickerContent: @Composable () -> Unit,
    onDismiss: () -> Unit,
) {
    val modelOptions = boardAgentModelOptions(models, target.raw)
    // 团队与 CLI 目标互斥：teamId 命中列表才算「选中团队」，选中后隐藏 CLI 参数行。
    val teamTarget = teams.firstOrNull { it.id == teamId }
    val teamSelected = teamTarget != null
    val employeeTarget = employees.firstOrNull { it.id == employeeId }
    val employeeSelected = employeeTarget != null
    // 临时派工：不指派员工，由本机决策模型给建议名单；服务端自己建卡 + 建临时团队 + 起 run。
    var dispatchMode by remember { mutableStateOf(false) }
    val dispatchFlow = remember { TeamDispatchFlowState() }
    val dispatchScope = rememberCoroutineScope()
    val dispatchReason = dispatchWorkspaceBlockedReason(dispatchWorkspaceId)
    val dispatchBlocked = dispatchStartBlockedReason(
        dispatchFlow.selection, dispatchWorkspaceId, prompt, dispatchFlow.busy,
    )
    // 派工也属于「指名对象」：它同样要收起 CLI 那组参数（模型/深度/会话）。
    val namedSubjectSelected = teamSelected || employeeSelected || dispatchMode
    val teamDisabledReason = newTaskTeamDisabledReason(teamAllowed, teams.isNotEmpty())
    val teamError = newTaskTeamSubmitError(prompt, teamSelected, teamAllowed)
    val defaultModel = models?.defaultModelFor(target.raw).orEmpty()
    val selectedModelLabel = modelOptions.firstOrNull { it.id == model }?.label ?: model
    val effortOptions = thinkingEffortOptions(target.raw, model, defaultModel, models?.modelsFor(target.raw).orEmpty())
    val effortLabel = effortOptions.firstOrNull { it.id == thinkingEffort }?.label ?: "自动"
    // 会话参数行与选择器可开性共用这份判定。
    val controlChips = newTaskComposerControlChips(namedSubjectSelected, startFirstSession, target.isShell)
    var displayedControlChips by remember { mutableStateOf(controlChips) }
    LaunchedEffect(controlChips) {
        if (controlChips.isNotEmpty()) displayedControlChips = controlChips
    }
    var providerOpen by remember { mutableStateOf(false) }
    var modelOpen by remember { mutableStateOf(false) }
    var effortOpen by remember { mutableStateOf(false) }
    var kindOpen by remember { mutableStateOf(false) }
    var parentOpen by remember { mutableStateOf(false) }
    var advancedOpen by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val dialogView = LocalView.current
        val window = (dialogView.parent as? DialogWindowProvider)?.window
        val lightBackground = WandColors.bgPrimary.luminance() > 0.5f
        DisposableEffect(window, lightBackground) {
            val controller = window?.let { WindowCompat.getInsetsController(it, dialogView) }
            val previous = controller?.isAppearanceLightStatusBars
            controller?.isAppearanceLightStatusBars = lightBackground
            onDispose { if (previous != null) controller.isAppearanceLightStatusBars = previous }
        }
        val motionEnabled = !reduceMotionEnabled()
        Column(
            modifier = Modifier.fillMaxSize().background(WandColors.bgPrimary)
                .navigationBarsPadding().imePadding(),
        ) {
            WandDetailTopBar(
                title = "新建任务",
                leading = { WandDetailBackButton(onClick = onDismiss, contentDescription = "取消新建任务", enabled = !busy) },
            )
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                WandTextField(
                    value = prompt, onValueChange = onPromptChange,
                    label = "任务内容",
                    placeholder = when {
                        dispatchMode -> "将作为决策判断与团队执行的唯一目标"
                        teamSelected -> "告诉团队要做什么"
                        employeeSelected -> "告诉员工要做什么"
                        else -> "输入任务内容"
                    },
                    minLines = 4, maxLines = 8, enabled = !busy && !fieldsLocked,
                    modifier = Modifier.fillMaxWidth(),
                )
                WandCard(contentPadding = PaddingValues(horizontal = 16.dp)) {
                    Box {
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .clickable(enabled = !busy && !fieldsLocked, role = Role.Button,
                                    onClickLabel = "选择派发对象") { providerOpen = true }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (dispatchMode) {
                                Icon(WandIcons.thinking, contentDescription = null, tint = WandColors.brand,
                                    modifier = Modifier.size(22.dp))
                            } else if (namedSubjectSelected) {
                                Icon(WandIcons.agent, contentDescription = null, tint = WandColors.brand,
                                    modifier = Modifier.size(22.dp))
                            } else {
                                Image(
                                    painter = BrandLogos.painterForProvider(
                                        target.raw.takeUnless { target.isShell },
                                    ),
                                    contentDescription = null, modifier = Modifier.size(22.dp),
                                )
                            }
                            Text("执行对象", style = MaterialTheme.typography.bodyMedium,
                                color = WandColors.textPrimary, modifier = Modifier.weight(0.8f))
                            Text(
                                when {
                                    dispatchMode -> "临时派工"
                                    else -> employeeTarget?.name
                                        ?: teamTarget?.let { newTaskTeamOptionLabel(it) }
                                        ?: target.label
                                },
                                style = MaterialTheme.typography.bodyMedium, color = WandColors.textSecondary,
                                modifier = Modifier.weight(1f), textAlign = TextAlign.End,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Icon(WandIcons.chevronRight, contentDescription = null, tint = WandColors.textMuted, modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded = providerOpen, onDismissRequest = { providerOpen = false }) {
                            WorkspaceSessionTarget.OPTIONS.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    leadingIcon = {
                                        Image(
                                            painter = BrandLogos.painterForProvider(
                                                option.raw.takeUnless { option.isShell },
                                            ),
                                            contentDescription = null, modifier = Modifier.size(20.dp),
                                        )
                                    },
                                    onClick = {
                                        providerOpen = false
                                        modelOpen = false
                                        effortOpen = false
                                        kindOpen = false
                                        dispatchMode = false
                                        dispatchFlow.resetResults()
                                        onTargetChange(option)
                                    },
                                )
                            }
                            if (kind == WorkspaceSessionKind.Structured) {
                                DropdownMenuItem(
                                    text = { Text("不指派员工", style = MaterialTheme.typography.labelSmall,
                                        color = WandColors.textMuted) },
                                    enabled = false, onClick = {},
                                )
                                DropdownMenuItem(
                                    text = { Text("临时派工（决策选人）") },
                                    leadingIcon = { Icon(WandIcons.thinking, contentDescription = null,
                                        tint = WandColors.textSecondary, modifier = Modifier.size(20.dp)) },
                                    onClick = {
                                        providerOpen = false
                                        modelOpen = false
                                        effortOpen = false
                                        kindOpen = false
                                        onEmployeeChange(null)
                                        onTeamChange(null)
                                        dispatchFlow.resetResults()
                                        dispatchMode = true
                                    },
                                )
                            }
                            if (kind == WorkspaceSessionKind.Structured && employees.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("硅基员工", style = MaterialTheme.typography.labelSmall,
                                        color = WandColors.textMuted) },
                                    enabled = false, onClick = {},
                                )
                                employees.forEach { employee ->
                                    DropdownMenuItem(
                                        text = { Text(employee.name) },
                                        leadingIcon = { Icon(WandIcons.agent, contentDescription = null,
                                            modifier = Modifier.size(20.dp)) },
                                        onClick = {
                                            providerOpen = false
                                            dispatchMode = false
                                            dispatchFlow.resetResults()
                                            onEmployeeChange(employee.id)
                                        },
                                    )
                                }
                            }
                            val teamOptions = if (kind == WorkspaceSessionKind.Structured)
                                newTaskTeamOptions(teams, teamAllowed) else emptyList()
                            if (teamOptions.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("AI 团队", style = MaterialTheme.typography.labelSmall,
                                        color = WandColors.textMuted) },
                                    enabled = false, onClick = {},
                                )
                                teamOptions.forEach { (id, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        leadingIcon = {
                                            Icon(WandIcons.agent, contentDescription = null,
                                                tint = WandColors.textSecondary,
                                                modifier = Modifier.size(20.dp))
                                        },
                                        onClick = {
                                            providerOpen = false
                                            modelOpen = false
                                            effortOpen = false
                                            kindOpen = false
                                            dispatchMode = false
                                            dispatchFlow.resetResults()
                                            onTeamChange(id)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (teamDisabledReason != null && !teamSelected) {
                        Text(teamDisabledReason, style = MaterialTheme.typography.bodySmall,
                            color = WandColors.textMuted)
                    }
                    if (teamTarget != null) {
                        Text(teamTarget.name + " · " + teamTarget.members.size + " 人 · 由负责人拆解分派",
                            style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
                    }
                    if (employeeTarget != null) {
                        Text(employeeTarget.name + " · " + employeeTarget.duty.ifBlank { "按角色设定工作" },
                            style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
                    }
                    if (dispatchMode) {
                        Text(
                            dispatchReason ?: ("目标项目 · " + (dispatchWorkspaceName ?: "未选择")
                                + "。不指派员工：写清描述，本机决策模型按职责与标签给出建议名单，确认后才开工。"),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (dispatchReason != null) WandColors.danger else WandColors.textMuted,
                        )
                        TeamDispatchRoster(dispatchFlow, maxRosterHeight = 200.dp)
                        dispatchFlow.message?.let { message ->
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (dispatchFlow.phase == TeamDispatchPhase.Failed) WandColors.danger
                                else WandColors.textMuted,
                            )
                        }
                    }
                    HorizontalDivider(color = WandColors.border.copy(alpha = 0.5f))
                    NewTaskSettingRow(
                        icon = WandIcons.folder, label = "工作目录",
                        value = cwd.ifEmpty { "选择目录" },
                        enabled = !busy && !fieldsLocked, onClick = onChooseDirectory,
                    )
                    HorizontalDivider(color = WandColors.border.copy(alpha = 0.5f))
                    // 临时派工只读取描述与项目；不呈现不会提交的名称、工作树和 CLI 参数。
                    if (!dispatchMode) {
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .clickable(enabled = !busy, role = Role.Button) { advancedOpen = !advancedOpen }
                                .semantics { stateDescription = if (advancedOpen) "已展开" else "已收起" }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("更多设置", style = MaterialTheme.typography.bodyMedium,
                                color = WandColors.textPrimary, modifier = Modifier.weight(0.65f))
                            Text(
                                newTaskMoreSettingsSummary(
                                    name = name, ungroupedStart = ungroupedStart, worktree = worktree,
                                    startFirstSession = startFirstSession, namedSubjectSelected = namedSubjectSelected,
                                    kindLabel = kind.label, modelLabel = selectedModelLabel,
                                    effortLabel = effortLabel, shellTarget = target.isShell,
                                    parentLabel = parentOptions.firstOrNull { it.first == parentTaskId && it.first.isNotBlank() }?.second,
                                ),
                                style = MaterialTheme.typography.bodySmall, color = WandColors.textMuted,
                                modifier = Modifier.weight(1f), textAlign = TextAlign.End,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            Icon(if (advancedOpen) WandIcons.expand else WandIcons.chevronRight,
                                contentDescription = null, tint = WandColors.textMuted, modifier = Modifier.size(20.dp))
                        }
                        WandInlinePanel(visible = advancedOpen) {
                            Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                WandTextField(
                                    value = name, onValueChange = onNameChange,
                                    label = "任务名称（可选）", placeholder = if (ungroupedStart) "填写名称后创建任务卡" else "留空自动命名",
                                    singleLine = true, enabled = !busy && !fieldsLocked,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (!namedSubjectSelected) {
                                    NewTaskToggleRow(
                                        icon = WandIcons.todo,
                                        label = if (ungroupedStart) newTaskUngroupedStartLabel() else "创建后启动会话",
                                        checked = startFirstSession,
                                        onLabel = "启动会话", offLabel = "仅建分组",
                                        description = if (ungroupedStart) newTaskUngroupedStartDescription()
                                        else newTaskStartSessionDescription(startFirstSession),
                                        enabled = !busy && !fieldsLocked,
                                        onToggle = {
                                            kindOpen = false
                                            modelOpen = false
                                            effortOpen = false
                                            onStartFirstSessionChange(it)
                                        },
                                    )
                                }
                                if (!ungroupedStart) {
                                    NewTaskToggleRow(
                                        icon = WandIcons.commit, label = "工作目录方式",
                                        checked = worktree,
                                        onLabel = "独立工作树", offLabel = "共用目录",
                                        description = newTaskWorktreeDescription(worktree),
                                        enabled = !busy && !fieldsLocked, onToggle = onWorktreeChange,
                                    )
                                }
                                AnimatedVisibility(
                                    visible = controlChips.isNotEmpty(),
                                    enter = if (motionEnabled) {
                                        fadeIn(WandMotion.tweenFast()) + expandVertically(WandMotion.tweenNormal())
                                    } else EnterTransition.None,
                                    exit = if (motionEnabled) {
                                        fadeOut(WandMotion.tweenFast()) + shrinkVertically(WandMotion.tweenNormal())
                                    } else ExitTransition.None,
                                ) {
                                    Column {
                                        val chipsForAnimation = controlChips.ifEmpty { displayedControlChips }
                                        chipsForAnimation.forEach { chip ->
                                            val controlsEnabled = !busy && !fieldsLocked && chip in controlChips
                                            when (chip) {
                                                NewTaskComposerControlChip.SessionKind -> NewTaskSettingRow(
                                                    icon = WandIcons.terminal, label = "会话类型", value = kind.label,
                                                    enabled = controlsEnabled, onClick = { kindOpen = true },
                                                )
                                                NewTaskComposerControlChip.Model -> NewTaskSettingRow(
                                                    icon = WandIcons.tune, label = "模型", value = selectedModelLabel,
                                                    enabled = controlsEnabled, onClick = { modelOpen = true },
                                                )
                                                NewTaskComposerControlChip.ThinkingEffort -> NewTaskSettingRow(
                                                    icon = WandIcons.thinking, label = "思考深度", value = effortLabel,
                                                    enabled = controlsEnabled, onClick = { effortOpen = true },
                                                )
                                            }
                                        }
                                    }
                                }
                                NewTaskSettingRow(
                                    icon = WandIcons.todo, label = "归属父任务",
                                    value = when {
                                        parentError != null -> "加载失败，点此重试"
                                        parentsLoading -> "正在加载任务…"
                                        else -> parentOptions.firstOrNull { it.first == parentTaskId }?.second
                                            ?: "不关联父任务"
                                    },
                                    enabled = !busy && !fieldsLocked,
                                    onClick = {
                                        if (parentError != null) onReloadParents() else parentOpen = true
                                    },
                                )
                                if (!parentsLoading && parentError == null && parentOptions.size == 1) {
                                    Text("当前目录没有进行中的任务",
                                        style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Column(
                modifier = Modifier.fillMaxWidth().background(WandColors.bgPrimary)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 96.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    val visibleError = error
                        ?: teamError.takeIf { teamSelected }
                        ?: dispatchFlow.message?.takeIf { dispatchFlow.phase == TeamDispatchPhase.Failed }
                        ?: dispatchReason?.takeIf { dispatchMode }
                    if (visibleError != null) {
                        Text(
                            visibleError, color = WandColors.danger,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        )
                    } else {
                        Text(
                            if (dispatchMode) {
                                if (dispatchFlow.hasPlan) dispatchBlocked.ifEmpty { "已选出名单：确认后开工" }
                                else "决策选人后再确认开工"
                            } else if (ungroupedStart) newTaskUngroupedStatusLine()
                            else if (employeeSelected) "建卡后立即交给员工开工 · " +
                                if (worktree) "独立工作树" else "共用工作区"
                            else newTaskComposerStatusLine(teamSelected, startFirstSession, worktree),
                            style = MaterialTheme.typography.labelSmall,
                            color = WandColors.textSecondary,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                WandButton(
                    label = if (dispatchMode) dispatchPrimaryActionLabel(dispatchFlow.phase, dispatchFlow.hasPlan)
                    else if (ungroupedStart) newTaskUngroupedActionLabel(busy)
                    else if (employeeSelected) {
                        if (busy) "正在交给员工…" else "创建并交给员工"
                    } else if (teamSelected) {
                        newTaskTeamActionLabel(busy, teamRunRetry, prompt.isNotBlank())
                    } else if (busy) "创建中…" else if (startFirstSession) "创建并启动会话"
                    else "创建任务分组",
                    onClick = {
                        if (!dispatchMode) {
                            onSubmit()
                        } else {
                            // 同一个按钮依次承担：选人 → 开工；结果与失败都留在原位。
                            dispatchScope.launch {
                                if (!dispatchFlow.hasPlan) {
                                    dispatchFlow.loadPlan(api, prompt)
                                } else {
                                    dispatchFlow.submit(api, dispatchWorkspaceId, prompt)?.let(onDispatchStarted)
                                }
                            }
                        }
                    },
                    enabled = if (dispatchMode) {
                        !busy && !dispatchFlow.busy && dispatchReason == null &&
                            if (dispatchFlow.hasPlan) dispatchBlocked.isEmpty() else prompt.isNotBlank()
                    } else canCreate && !busy,
                    loading = busy || (dispatchMode && dispatchFlow.busy), modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (parentOpen) {
            ComposerChoiceSheet(
                title = "归属父任务", options = parentOptions, selected = parentTaskId,
                searchable = true, searchPlaceholder = "搜索任务",
                onSelect = { parentOpen = false; onParentTaskChange(it) },
                onDismiss = { parentOpen = false },
            )
        }
        // 会话类型 / 模型 / 思考深度是 CLI 专属参数，团队分支不读：选择器
        // 与设置行同源（controlChips），切回 CLI 后已选值原样恢复。
        if (modelOpen && NewTaskComposerControlChip.Model in controlChips) {
            ComposerChoiceSheet(
                title = "模型", options = modelOptions.map { it.id to it.label },
                selected = model, searchable = true,
                onSelect = { modelOpen = false; onModelChange(it) },
                onDismiss = { modelOpen = false },
            )
        }
        if (effortOpen && NewTaskComposerControlChip.ThinkingEffort in controlChips) {
            ComposerChoiceSheet(
                title = "思考深度", options = effortOptions.map { it.id to it.menuLabel },
                selected = thinkingEffort,
                onSelect = { effortOpen = false; onThinkingEffortChange(it) },
                onDismiss = { effortOpen = false },
            )
        }
        if (kindOpen && NewTaskComposerControlChip.SessionKind in controlChips) {
            ComposerChoiceSheet(
                title = "会话类型", options = WorkspaceSessionKind.entries.map { it.raw to it.label },
                selected = kind.raw,
                onSelect = { raw -> kindOpen = false; onKindChange(WorkspaceSessionKind.fromRaw(raw)) },
                onDismiss = { kindOpen = false },
            )
        }
        directoryPickerContent()
    }
}

@Composable
private fun NewTaskSettingRow(
    icon: ImageVector,
    label: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button,
                onClickLabel = "更改$label") { onClick() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = WandColors.textSecondary,
            modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = WandColors.textPrimary,
            modifier = Modifier.weight(0.8f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary,
            modifier = Modifier.weight(1f), textAlign = TextAlign.End, maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis)
        Icon(WandIcons.chevronRight, contentDescription = null, tint = WandColors.textMuted,
            modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun NewTaskToggleRow(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onLabel: String,
    offLabel: String,
    description: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch,
                onValueChange = onToggle)
            .semantics { stateDescription = if (checked) onLabel else offLabel }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = WandColors.textSecondary,
            modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium,
                color = WandColors.textPrimary)
            Text(description, style = MaterialTheme.typography.labelSmall,
                color = WandColors.textSecondary, maxLines = 2)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
