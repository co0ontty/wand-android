package com.wand.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.wand.app.data.AiTeam
import com.wand.app.data.ModelsResponse
import com.wand.app.data.WorkspaceSessionKind
import com.wand.app.data.WorkspaceSessionTarget
import com.wand.app.data.boardAgentModelOptions
import com.wand.app.ui.components.BrandLogos
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
    teamId: String? = null,
    teamAllowed: Boolean = false,
    onTeamChange: (String?) -> Unit = {},
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
    val teamDisabledReason = newTaskTeamDisabledReason(teamAllowed, teams.isNotEmpty())
    val teamError = newTaskTeamSubmitError(prompt, teamSelected, teamAllowed)
    val defaultModel = models?.defaultModelFor(target.raw).orEmpty()
    val selectedModelLabel = modelOptions.firstOrNull { it.id == model }?.label ?: model
    val effortOptions = thinkingEffortOptions(target.raw, model, defaultModel, models?.modelsFor(target.raw).orEmpty())
    val effortLabel = effortOptions.firstOrNull { it.id == thinkingEffort }?.label ?: "自动"
    // 会话参数行与选择器可开性共用这份判定。
    val controlChips = newTaskComposerControlChips(teamSelected, startFirstSession, target.isShell)
    var displayedControlChips by remember { mutableStateOf(controlChips) }
    LaunchedEffect(controlChips) {
        if (controlChips.isNotEmpty()) displayedControlChips = controlChips
    }
    var providerOpen by remember { mutableStateOf(false) }
    var modelOpen by remember { mutableStateOf(false) }
    var effortOpen by remember { mutableStateOf(false) }
    var kindOpen by remember { mutableStateOf(false) }
    var parentOpen by remember { mutableStateOf(false) }

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
                .statusBarsPadding().navigationBarsPadding().imePadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("新建任务", style = MaterialTheme.typography.titleMedium,
                    color = WandColors.textPrimary, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("任务内容", style = MaterialTheme.typography.titleSmall,
                    color = WandColors.textPrimary, modifier = Modifier.padding(top = 12.dp))
                WandTextField(
                    value = prompt, onValueChange = onPromptChange,
                    label = "描述要做的事",
                    placeholder = if (teamSelected) "告诉团队要做什么" else "输入任务内容",
                    minLines = 4, maxLines = 8, enabled = !busy && !fieldsLocked,
                    modifier = Modifier.fillMaxWidth(),
                )
                WandTextField(
                    value = name, onValueChange = onNameChange,
                    label = "任务名称（可选）", placeholder = "留空自动命名",
                    singleLine = true, enabled = !busy && !fieldsLocked,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("执行方式", style = MaterialTheme.typography.titleSmall,
                    color = WandColors.textPrimary, modifier = Modifier.padding(top = 8.dp))
                Box {
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(WandColors.surfaceSoft)
                            .clickable(enabled = !busy && !fieldsLocked, role = Role.Button,
                                onClickLabel = "选择派发对象") { providerOpen = true }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (teamSelected) {
                            Icon(WandIcons.agent, contentDescription = null, tint = WandColors.brand,
                                modifier = Modifier.size(28.dp))
                        } else {
                            Image(
                                painter = BrandLogos.painterForProvider(
                                    target.raw.takeUnless { target.isShell },
                                ),
                                contentDescription = null, modifier = Modifier.size(28.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("派发对象", style = MaterialTheme.typography.labelSmall,
                                color = WandColors.textMuted)
                            Text(teamTarget?.let { newTaskTeamOptionLabel(it) } ?: target.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = WandColors.textPrimary, fontWeight = FontWeight.SemiBold)
                        }
                        Icon(WandIcons.expand, contentDescription = null, tint = WandColors.textMuted)
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
                                    onTargetChange(option)
                                },
                            )
                        }
                        val teamOptions = newTaskTeamOptions(teams, teamAllowed)
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
                if (!teamSelected) {
                    NewTaskToggleRow(
                        icon = WandIcons.todo, label = "创建后启动会话",
                        checked = startFirstSession,
                        onLabel = "启动会话", offLabel = "仅建分组",
                        description = newTaskStartSessionDescription(startFirstSession),
                        enabled = !busy && !fieldsLocked,
                        onToggle = {
                            kindOpen = false
                            modelOpen = false
                            effortOpen = false
                            onStartFirstSessionChange(it)
                        },
                    )
                }
                NewTaskToggleRow(
                    icon = WandIcons.commit, label = "工作目录方式",
                    checked = worktree,
                    onLabel = "独立工作树", offLabel = "共用目录",
                    description = newTaskWorktreeDescription(worktree),
                    enabled = !busy && !fieldsLocked, onToggle = onWorktreeChange,
                )
                AnimatedVisibility(
                    visible = controlChips.isNotEmpty(),
                    enter = if (motionEnabled) {
                        fadeIn(WandMotion.tweenFast()) + expandVertically(WandMotion.tweenNormal())
                    } else EnterTransition.None,
                    exit = if (motionEnabled) {
                        fadeOut(WandMotion.tweenFast()) + shrinkVertically(WandMotion.tweenNormal())
                    } else ExitTransition.None,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                Text("工作区", style = MaterialTheme.typography.titleSmall,
                    color = WandColors.textPrimary, modifier = Modifier.padding(top = 8.dp))
                NewTaskSettingRow(
                    icon = WandIcons.folder, label = "工作目录",
                    value = cwd.ifEmpty { "选择目录" },
                    enabled = !busy && !fieldsLocked, onClick = onChooseDirectory,
                )
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
                Spacer(Modifier.height(12.dp))
            }
            Column(
                modifier = Modifier.fillMaxWidth().background(WandColors.bgPrimary)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    val visibleError = error ?: teamError.takeIf { teamSelected }
                    if (visibleError != null) {
                        Text(
                            visibleError, color = WandColors.danger,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        )
                    } else {
                        Text(
                            newTaskComposerStatusLine(teamSelected, startFirstSession, worktree),
                            style = MaterialTheme.typography.labelSmall,
                            color = WandColors.textSecondary,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                WandButton(
                    label = if (teamSelected) {
                        newTaskTeamActionLabel(busy, teamRunRetry, prompt.isNotBlank())
                    } else if (busy) "创建中…" else if (startFirstSession) "创建并启动会话"
                    else "创建任务分组",
                    onClick = onSubmit, enabled = canCreate && !busy,
                    loading = busy, modifier = Modifier.fillMaxWidth(),
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
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(WandColors.surfaceSoft)
            .clickable(enabled = enabled, role = Role.Button,
                onClickLabel = "更改$label") { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = WandColors.brand,
            modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
            Text(value, style = MaterialTheme.typography.bodyMedium,
                color = WandColors.textPrimary, maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis)
        }
        Icon(WandIcons.chevronRight, contentDescription = null, tint = WandColors.textMuted)
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
    val motionEnabled = !reduceMotionEnabled()
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(WandColors.surfaceSoft)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch,
                onValueChange = onToggle)
            .semantics { stateDescription = if (checked) onLabel else offLabel }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = WandColors.brand,
            modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium,
                color = WandColors.textPrimary, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.labelSmall,
                color = WandColors.textSecondary, maxLines = 2)
        }
        Box(modifier = Modifier.widthIn(min = 80.dp), contentAlignment = Alignment.CenterEnd) {
            Crossfade(
                targetState = checked,
                animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenFast()),
                label = "newTaskSettingState",
            ) {
                Text(if (it) onLabel else offLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (it) WandColors.brand else WandColors.textSecondary,
                    maxLines = 1)
            }
        }
    }
}
