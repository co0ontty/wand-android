package com.wand.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.wand.app.data.ModelsResponse
import com.wand.app.data.WorkspaceSessionKind
import com.wand.app.data.WorkspaceSessionTarget
import com.wand.app.data.boardAgentModelOptions
import com.wand.app.ui.SendActionVisual
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandInlinePanelAction
import com.wand.app.ui.components.WandTextField
import com.wand.app.ui.theme.WandColors
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
    target: WorkspaceSessionTarget,
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
    val defaultModel = models?.defaultModelFor(target.raw).orEmpty()
    val selectedModelLabel = modelOptions.firstOrNull { it.id == model }?.label ?: model
    val effortOptions = thinkingEffortOptions(target.raw, model, defaultModel, models?.modelsFor(target.raw).orEmpty())
    val effortLabel = effortOptions.firstOrNull { it.id == thinkingEffort }?.label ?: "自动"
    var providerOpen by remember { mutableStateOf(false) }
    var modelOpen by remember { mutableStateOf(false) }
    var effortOpen by remember { mutableStateOf(false) }
    var kindOpen by remember { mutableStateOf(false) }
    var actionsOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = actionsOpen) { actionsOpen = false }

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
                    .padding(horizontal = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(58.dp))
                Box {
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(18.dp))
                            .clickable(enabled = !busy, role = Role.Button,
                                onClickLabel = "更换工具，当前 ${target.label}") {
                                actionsOpen = false
                                providerOpen = true
                            }
                            .padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ProviderBrandMark(target.raw.takeUnless { target.isShell }, size = 68)
                        Box(
                            modifier = Modifier.align(Alignment.BottomEnd).size(22.dp)
                                .clip(RoundedCornerShape(11.dp)).background(WandColors.brandSoft),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(WandIcons.expand, contentDescription = null, tint = WandColors.brand,
                                modifier = Modifier.size(16.dp))
                        }
                    }
                    DropdownMenu(expanded = providerOpen, onDismissRequest = { providerOpen = false }) {
                        WorkspaceSessionTarget.OPTIONS.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                leadingIcon = {
                                    Image(painter = BrandLogos.painterForProvider(option.raw.takeUnless { option.isShell }),
                                        contentDescription = null, modifier = Modifier.size(20.dp))
                                },
                                onClick = { providerOpen = false; onTargetChange(option) },
                            )
                        }
                    }
                }
                Text(target.label, style = MaterialTheme.typography.headlineSmall,
                    color = WandColors.textPrimary, fontWeight = FontWeight.SemiBold)
                Text("点 Logo 更换工具", style = MaterialTheme.typography.labelSmall,
                    color = WandColors.brand, modifier = Modifier.clickable(enabled = !busy) {
                        actionsOpen = false
                        providerOpen = true
                    })
                Text(
                    when {
                        !startFirstSession -> "写下任务内容，稍后再开始"
                        target.isShell -> "写下任务内容，在终端中开始工作"
                        else -> "输入消息，让它帮你完成任务"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = WandColors.textSecondary, modifier = Modifier.padding(top = 12.dp),
                )
                Spacer(Modifier.height(36.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(WandColors.surfaceSoft)
                        .clickable(enabled = !busy) { actionsOpen = false; onChooseDirectory() }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(WandIcons.folder, contentDescription = null, tint = WandColors.brand)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("工作目录", style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
                        Text(cwd.ifEmpty { "选择目录" }, style = MaterialTheme.typography.bodyMedium,
                            color = WandColors.textPrimary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                    Icon(WandIcons.chevronRight, contentDescription = null, tint = WandColors.textMuted)
                }
                WandTextField(
                    value = name, onValueChange = onNameChange,
                    placeholder = "任务名称（可选，留空自动命名）", singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Text(
                    (if (startFirstSession) "创建后启动会话" else "仅创建任务分组") +
                        (if (worktree) " · 独立工作树" else " · 共用工作区"),
                    style = MaterialTheme.typography.labelSmall,
                    color = WandColors.textMuted,
                    modifier = Modifier.align(Alignment.Start).padding(top = 12.dp),
                )
                Spacer(Modifier.height(24.dp))
            }
            if (error != null) {
                Text(error, color = WandColors.danger, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
            }
            NativeComposerSurface(
                backdrop = null,
                expanded = true,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                panelVisible = actionsOpen,
                panelContent = {
                    WandInlinePanelAction(
                        icon = WandIcons.todo,
                        label = if (startFirstSession) "仅建分组" else "启动会话",
                        enabled = !busy,
                        onClick = {
                            actionsOpen = false
                            onStartFirstSessionChange(!startFirstSession)
                        },
                    )
                    WandInlinePanelAction(
                        icon = WandIcons.commit,
                        label = if (worktree) "共用目录" else "独立工作树",
                        enabled = !busy,
                        onClick = {
                            actionsOpen = false
                            onWorktreeChange(!worktree)
                        },
                    )
                },
                inputContent = {
                    BasicTextField(
                        value = prompt,
                        onValueChange = { actionsOpen = false; onPromptChange(it) },
                        enabled = !busy,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = WandColors.textPrimary),
                        cursorBrush = SolidColor(WandColors.brand),
                        minLines = 2,
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        modifier = Modifier.weight(1f).heightIn(min = 58.dp, max = 132.dp)
                            .padding(start = 12.dp, top = 8.dp, bottom = 6.dp),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.TopStart) {
                                if (prompt.isEmpty()) Text(
                                    if (startFirstSession && !target.isShell) "输入消息" else "输入任务内容",
                                    color = WandColors.textMuted,
                                    style = MaterialTheme.typography.bodyLarge)
                                inner()
                            }
                        },
                    )
                    SubmitMorphButton(
                        visual = when {
                            busy -> SendActionVisual.Sending
                            error != null -> SendActionVisual.Failed
                            else -> SendActionVisual.Send
                        },
                        contentDescription = if (busy) "创建中" else "创建任务",
                        onClick = { actionsOpen = false; onSubmit() },
                        enabled = canCreate && !busy,
                        fillColor = when {
                            error != null -> WandColors.danger
                            canCreate -> WandColors.brand
                            else -> WandColors.surfaceSoft
                        },
                        contentTint = if (canCreate) androidx.compose.ui.graphics.Color.White else WandColors.textMuted,
                    )
                },
                expandedControls = { compact ->
                    ControlChip(
                        icon = WandIcons.add, text = "更多", tint = WandColors.textSecondary,
                        contentDescription = "更多任务选项", showText = false,
                    ) { if (!busy) actionsOpen = !actionsOpen }
                    if (startFirstSession) {
                        ControlChip(
                            icon = WandIcons.terminal, text = kind.label,
                            tint = WandColors.textSecondary,
                            contentDescription = "会话类型：${kind.label}", showText = !compact,
                        ) { if (!busy) { actionsOpen = false; kindOpen = true } }
                        if (!target.isShell) {
                            ControlChip(
                                icon = WandIcons.tune, text = selectedModelLabel,
                                tint = WandColors.brand, contentDescription = "模型：$selectedModelLabel",
                                showText = true, modifier = Modifier.weight(1f),
                            ) { if (!busy) { actionsOpen = false; modelOpen = true } }
                            ControlChip(
                                icon = WandIcons.thinking, text = effortLabel,
                                tint = WandColors.brand, contentDescription = "思考深度：$effortLabel",
                                showText = true,
                            ) { if (!busy) { actionsOpen = false; effortOpen = true } }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                },
            )
        }
        if (modelOpen) {
            ComposerChoiceSheet(
                title = "模型", options = modelOptions.map { it.id to it.label },
                selected = model, searchable = true,
                onSelect = { modelOpen = false; onModelChange(it) },
                onDismiss = { modelOpen = false },
            )
        }
        if (effortOpen) {
            ComposerChoiceSheet(
                title = "思考深度", options = effortOptions.map { it.id to it.menuLabel },
                selected = thinkingEffort,
                onSelect = { effortOpen = false; onThinkingEffortChange(it) },
                onDismiss = { effortOpen = false },
            )
        }
        if (kindOpen) {
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
