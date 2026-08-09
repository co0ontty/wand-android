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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.graphicsLayer
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
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.WorkspaceSessionTarget
import com.wand.app.data.workspaceProviderLabel
import com.wand.app.data.workspaceSessionLabel
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandBottomSheet
import com.wand.app.ui.theme.WandColors
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
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenPty: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val workflow = remember(taskId) { WorkspaceWorkflow(api, scope) }
    val taskState by workflow.taskState.collectAsState()
    val targetState by workflow.targetState.collectAsState()
    var selectedTarget by remember { mutableStateOf(WorkspaceSessionTarget.Claude) }
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
        scope.launch { sheetState.show() }
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
        ) { session ->
            scope.launch { runCatching { sheetState.hide() } }
            // 创建成功后路由到对应会话页。
            if (session.isStructured) {
                onOpenSession(session.id)
            } else {
                onOpenPty(session.id)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WandColors.bgPrimary),
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .heightIn(min = 56.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                WandIcons.chevronRight,
                contentDescription = "返回",
                tint = WandColors.textSecondary,
                modifier = Modifier
                    .size(40.dp)
                    .graphicsLayerRotate180()
                    .clickable(onClick = onBack)
                    .padding(8.dp),
            )
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    taskName.ifEmpty { "任务" },
                    style = MaterialTheme.typography.titleMedium,
                    color = WandColors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    workspaceName.ifEmpty { "项目" },
                    style = MaterialTheme.typography.labelSmall,
                    color = WandColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                WandIcons.refresh,
                contentDescription = "刷新任务",
                tint = WandColors.textSecondary,
                modifier = Modifier
                    .size(40.dp)
                    .clickable { workflow.loadTask(taskId) }
                    .padding(8.dp),
            )
        }

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
                creating = creating,
                error = sheetError,
                onSelect = { selectedTarget = it },
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                workspaceName.ifEmpty { "项目" },
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
                    .padding(horizontal = 12.dp, vertical = 8.dp)
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
    onAddWindow: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
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

@Composable
private fun SessionSummaryRow(
    session: WorkspaceSessionSummary,
    index: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val provider = session.provider
    val icon = BrandLogos.painterForProvider(provider)
    val accent = if (provider == "codex") WandColors.info else WandColors.brand
    val iconTint = BrandLogos.tintForProvider(provider, accent)
    val borderColor = if (isSelected) accent else WandColors.border.copy(alpha = 0.5f)
    val background = if (isSelected) accent.copy(alpha = 0.08f) else WandColors.bgElevated.copy(alpha = 0.5f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics { contentDescription = workspaceSessionLabel(session, index) },
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
                workspaceSessionLabel(session, index),
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
        Icon(
            WandIcons.chevronRight,
            contentDescription = "打开",
            tint = WandColors.textMuted,
            modifier = Modifier.size(16.dp),
        )
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

private fun Modifier.graphicsLayerRotate180(): Modifier =
    this.graphicsLayer { rotationZ = 180f }
