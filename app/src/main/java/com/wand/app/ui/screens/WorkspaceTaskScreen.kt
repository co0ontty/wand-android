package com.wand.app.ui.screens

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.data.WorkspacePort
import com.wand.app.data.WorkspaceSessionKind
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.WorkspaceSessionTarget
import com.wand.app.data.workspaceProviderLabel
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.components.ToolbarIconButton
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandDetailBackButton
import com.wand.app.ui.components.WandDetailTopBar
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandBottomSheet
import com.wand.app.ui.components.WandDialog
import com.wand.app.ui.components.WandDialogAction
import com.wand.app.ui.components.WandIconButton
import com.wand.app.ui.components.WandIconButtonVariant
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.wandSelectedSurface
import com.wand.app.ui.workspaces.WorkspaceTargetState
import com.wand.app.ui.workspaces.WorkspaceTaskState
import com.wand.app.ui.workspaces.WorkspaceWorkflow
import kotlinx.coroutines.launch

/**
 * 任务详情宿主页。空任务显示欢迎态（项目名 / 任务名 / cwd / 唯一主操作「选择 Agent 或空白终端」），
 * 不自动创建会话。已有会话按创建顺序稳定排序，正文一次只承载一个 Chat 或 PTY。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceTaskScreen(
    api: WorkspacePort,
    workspaceId: String,
    taskId: String,
    workspaceName: String,
    taskName: String,
    showBack: Boolean = true,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenPty: (String) -> Unit,
    onOpenMissions: (String?) -> Unit = {},
    onTaskChanged: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val workflow = remember(taskId) { WorkspaceWorkflow(api, scope) }
    val taskState by workflow.taskState.collectAsState()
    val targetState by workflow.targetState.collectAsState()
    var selectedTarget by remember { mutableStateOf(WorkspaceSessionTarget.Claude) }
    var selectedKind by remember { mutableStateOf(WorkspaceSessionKind.Structured) }
    var deleteSessionTarget by remember { mutableStateOf<WorkspaceSessionSummary?>(null) }
    var deleteSessionError by remember { mutableStateOf<String?>(null) }
    var deleteSessionBusy by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current

    // 进入任务立即加载详情；切任务（taskId 变化）自动取消上一请求。
    LaunchedEffect(taskId) { workflow.loadTask(taskId) }
    // 离开页面时关闭 Sheet。
    DisposableEffect(taskId) {
        onDispose { workflow.closeTargetSheet() }
    }

    fun openSheet() {
        workflow.openTargetSheet()
        scope.launch {
            runCatching { api.serverConfig() }.getOrNull()?.let { config ->
                WorkspaceSessionTarget.fromRaw(config.defaultProvider)?.let { selectedTarget = it }
                selectedKind = if (config.defaultSessionKind == "pty") {
                    WorkspaceSessionKind.Pty
                } else {
                    WorkspaceSessionKind.Structured
                }
            }
            sheetState.show()
        }
    }

    fun dismissSheet() {
        workflow.closeTargetSheet()
        scope.launch { runCatching { sheetState.hide() } }
    }

    fun confirmCreate() {
        val cwd = workflow.currentTaskCwd()
        val wsId = workflow.currentWorkspaceId()
        if (cwd == null || wsId == null) return
        workflow.createTaskWindow(
            target = selectedTarget,
            workspaceId = wsId,
            taskId = taskId,
            cwd = cwd,
            kind = selectedKind,
        ) { session ->
            scope.launch { runCatching { sheetState.hide() } }
            onTaskChanged()
            // 创建成功后路由到对应会话页。
            if (session.isStructured) {
                onOpenSession(session.id)
            } else {
                onOpenPty(session.id)
            }
        }
    }

    val missionCwd = workflow.currentTaskCwd()

    deleteSessionTarget?.let { session ->
        val label = listSessionLabel(session, 0)
        WandDialog(
            title = "删除终端？",
            onDismissRequest = { if (!deleteSessionBusy) { deleteSessionTarget = null; deleteSessionError = null } },
            icon = WandIcons.delete,
            confirm = WandDialogAction(
                label = if (deleteSessionBusy) "删除中…" else "删除",
                enabled = !deleteSessionBusy,
                destructive = true,
                onClick = {
                    if (deleteSessionBusy) return@WandDialogAction
                    deleteSessionBusy = true
                    deleteSessionError = null
                    workflow.deleteSession(
                        sessionId = session.id,
                        onDeleted = {
                            onTaskChanged()
                            deleteSessionTarget = null
                            deleteSessionError = null
                            deleteSessionBusy = false
                        },
                        onError = { message ->
                            deleteSessionError = message
                            deleteSessionBusy = false
                        },
                    )
                },
            ),
            dismiss = WandDialogAction(
                "取消",
                enabled = !deleteSessionBusy,
                onClick = { deleteSessionTarget = null; deleteSessionError = null },
            ),
        ) {
            Text(
                deleteSessionError ?: "终端「$label」会结束并被删除，此操作无法撤销。",
                style = MaterialTheme.typography.bodyMedium,
                color = if (deleteSessionError != null) WandColors.danger else WandColors.textSecondary,
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        WandDetailTopBar(
            title = taskName.ifEmpty { "任务" },
            subtitle = workspaceName.ifEmpty { "任务目录" },
            leading = if (showBack) {
                {
                    WandDetailBackButton(
                        onClick = onBack,
                        icon = WandIcons.back,
                    )
                }
            } else {
                null
            },
            actions = {
                ToolbarIconButton(
                    icon = WandIcons.agent,
                    contentDescription = "并行任务",
                    enabled = missionCwd != null,
                    onClick = { onOpenMissions(missionCwd) },
                )
                ToolbarIconButton(
                    icon = WandIcons.refresh,
                    contentDescription = "刷新任务",
                    onClick = { workflow.loadTask(taskId) },
                )
            },
        )

        when (val state = taskState) {
            is WorkspaceTaskState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = WandColors.brand,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            is WorkspaceTaskState.Error -> {
                TaskErrorState(
                    message = state.message,
                    onRetry = { workflow.loadTask(taskId) },
                )
            }
            is WorkspaceTaskState.EmptySessions -> {
                EmptyTaskWelcome(
                    workspaceName = workspaceName,
                    taskName = taskName,
                    cwd = state.cwd,
                    onCopyCwd = { clipboard.setText(AnnotatedString(state.cwd)) },
                    onChooseAgent = { openSheet() },
                )
            }
            is WorkspaceTaskState.Content -> {
                TaskSessionList(
                    state = state,
                    onSelectSession = { session ->
                        workflow.selectSession(session.id)
                        if (session.isStructuredSession()) {
                            onOpenSession(session.id)
                        } else {
                            onOpenPty(session.id)
                        }
                    },
                    onDeleteSession = { deleteSessionError = null; deleteSessionTarget = it },
                    onAddWindow = { openSheet() },
                )
            }
        }
    }

    // 共用七选一 Bottom Sheet（空态和「+」复用同一入口）。
    if (targetState !is WorkspaceTargetState.Closed) {
        val creating = targetState is WorkspaceTargetState.Creating
        val sheetError = (targetState as? WorkspaceTargetState.Error)?.message
        WandBottomSheet(
            onDismissRequest = { if (!creating) dismissSheet() },
            sheetState = sheetState,
            gesturesEnabled = !creating,
        ) {
            WorkspaceTargetSheet(
                selected = selectedTarget,
                selectedKind = selectedKind,
                creating = creating,
                error = sheetError,
                onSelect = {
                    selectedTarget = it
                    if (!it.isShell) {
                        scope.launch {
                            runCatching { api.updateCreationDefaults(defaultProvider = it.raw) }
                        }
                    }
                },
                onSelectKind = {
                    selectedKind = it
                    scope.launch {
                        runCatching { api.updateCreationDefaults(defaultSessionKind = it.raw) }
                    }
                },
                onConfirm = { confirmCreate() },
                onDismiss = { if (!creating) dismissSheet() },
            )
        }
    }
}

// MARK: - 空任务欢迎态

@Composable
private fun EmptyTaskWelcome(
    workspaceName: String,
    taskName: String,
    cwd: String,
    onCopyCwd: () -> Unit,
    onChooseAgent: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                workspaceName.ifEmpty { "任务目录" },
                style = MaterialTheme.typography.labelMedium,
                color = WandColors.textSecondary,
            )
            Text(
                taskName.ifEmpty { "任务" },
                style = MaterialTheme.typography.headlineSmall,
                color = WandColors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Text(
                "这个任务还没有工作窗口。选择一个 Agent，或直接打开空白终端。",
                style = MaterialTheme.typography.bodyMedium,
                color = WandColors.textSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            WandButton(
                label = "选择 Agent 或空白终端",
                onClick = onChooseAgent,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            // 任务实际 cwd：单行省略，可长按复制。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(WandColors.surfaceSoft.copy(alpha = 0.5f))
                    .clickable(onClick = onCopyCwd)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .semantics { contentDescription = "任务目录 $cwd，点击复制" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    WandIcons.folder,
                    contentDescription = null,
                    tint = WandColors.textMuted,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    cwd,
                    style = MaterialTheme.typography.labelSmall,
                    color = WandColors.textSecondary,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// MARK: - 任务会话列表

@Composable
private fun TaskSessionList(
    state: WorkspaceTaskState.Content,
    onSelectSession: (WorkspaceSessionSummary) -> Unit,
    onDeleteSession: (WorkspaceSessionSummary) -> Unit,
    onAddWindow: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
    Column(
        modifier = Modifier
            .widthIn(max = 640.dp)
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "工作窗口",
            style = MaterialTheme.typography.labelMedium,
            color = WandColors.textSecondary,
            modifier = Modifier.padding(start = 4.dp),
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(
                items = state.orderedSessions,
                key = { it.id },
            ) { session ->
                SessionSummaryRow(
                    session = session,
                    index = state.orderedSessions.indexOf(session),
                    isSelected = session.id == state.selectedSessionId,
                    onClick = { onSelectSession(session) },
                    onDelete = { onDeleteSession(session) },
                )
            }
        }
        WandButton(
            label = "新建工作窗口",
            onClick = onAddWindow,
            modifier = Modifier.fillMaxWidth(),
            variant = WandButtonVariant.Secondary,
        )
    }
    }
}

@Composable
private fun SessionSummaryRow(
    session: WorkspaceSessionSummary,
    index: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val provider = session.provider
    val icon = BrandLogos.painterForProvider(provider)
    val accent = if (provider == "codex") WandColors.info else WandColors.brand
    val iconTint = BrandLogos.tintForProvider(provider, accent)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .wandSelectedSurface(
                selected = isSelected,
                shape = RoundedCornerShape(14.dp),
                unselectedFill = WandColors.bgElevated.copy(alpha = 0.5f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics { contentDescription = listSessionLabel(session, index) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                listSessionLabel(session, index),
                style = MaterialTheme.typography.titleSmall,
                color = WandColors.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                workspaceProviderLabel(provider),
                style = MaterialTheme.typography.labelSmall,
                color = WandColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            var menuOpen by remember { mutableStateOf(false) }
            WandIconButton(
                icon = WandIcons.more,
                contentDescription = "终端操作",
                onClick = { menuOpen = true },
                variant = WandIconButtonVariant.Quiet,
            )
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = WandColors.bgElevated,
            ) {
                DropdownMenuItem(
                    text = { Text("打开") },
                    onClick = { menuOpen = false; onClick() },
                )
                DropdownMenuItem(
                    text = { Text("删除终端", color = WandColors.danger) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun TaskErrorState(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(WandIcons.error, contentDescription = null, tint = WandColors.danger, modifier = Modifier.size(28.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = WandColors.textSecondary)
            WandButton(label = "重试", onClick = onRetry)
        }
    }
}

// MARK: - 辅助

/** 判断会话摘要是否为结构化（非 PTY）会话，决定路由到 Chat 还是 PTY 页。 */
private fun WorkspaceSessionSummary.isStructuredSession(): Boolean =
    sessionKind == "structured"
