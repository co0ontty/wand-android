package com.wand.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.WorkspacePort
import com.wand.app.data.WorkspaceSessionKind
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.WorkspaceSessionTarget
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.components.WandBottomSheet
import com.wand.app.ui.components.WandDialog
import com.wand.app.ui.components.WandDialogAction
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.workspaces.WorkspaceTargetState
import com.wand.app.ui.workspaces.WorkspaceTaskState
import com.wand.app.ui.workspaces.WorkspaceWorkflow
import kotlinx.coroutines.launch

/**
 * 任务内「其他终端」快捷切换条（对齐 iOS WorkspaceTaskView 的 sessionStrip）：
 * 横向滚动的 Tab，展示当前任务下的全部工作窗口 —— provider 图标 + 短标签 +
 * 运行状态点；点击直接切到对应会话页，当前会话高亮。右侧固定的「+」直接打开
 * 当前任务的工作窗口选择器，创建成功后切换到新会话。
 *
 * 数据和创建流程由 [WorkspaceWorkflow] 统一管理。加载失败或任务下没有会话时整条
 * 隐藏，不影响原布局。切换由外层导航完成（replaceTop），返回键仍回到任务详情。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskSessionTabStrip(
    api: WorkspacePort,
    taskId: String,
    currentSessionId: String?,
    onSelect: (WorkspaceSessionSummary) -> Unit,
    onCreated: (SessionSnapshot) -> Unit,
    onDeleted: ((WorkspaceSessionSummary) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val workflow = remember(api, taskId) { WorkspaceWorkflow(api, scope) }
    val taskState by workflow.taskState.collectAsState()
    val targetState by workflow.targetState.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTarget by remember(taskId) { mutableStateOf(WorkspaceSessionTarget.Claude) }
    var selectedKind by remember(taskId) { mutableStateOf(WorkspaceSessionKind.Structured) }
    var deleteTarget by remember(taskId) { mutableStateOf<WorkspaceSessionSummary?>(null) }
    var deleteError by remember(taskId) { mutableStateOf<String?>(null) }
    var deleteBusy by remember(taskId) { mutableStateOf(false) }

    LaunchedEffect(taskId) {
        workflow.loadTask(taskId)
    }
    LaunchedEffect(currentSessionId) {
        currentSessionId?.let(workflow::selectSession)
    }

    fun openTargetSheet() {
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

    fun dismissTargetSheet() {
        workflow.closeTargetSheet()
        scope.launch { runCatching { sheetState.hide() } }
    }

    fun confirmCreate() {
        val cwd = workflow.currentTaskCwd() ?: return
        val workspaceId = workflow.currentWorkspaceId() ?: return
        workflow.createTaskWindow(
            target = selectedTarget,
            workspaceId = workspaceId,
            taskId = taskId,
            cwd = cwd,
            kind = selectedKind,
        ) { session ->
            scope.launch { runCatching { sheetState.hide() } }
            onCreated(session)
        }
    }

    val tabs = (taskState as? WorkspaceTaskState.Content)?.orderedSessions
    if (tabs.isNullOrEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, session ->
                TaskSessionTab(
                    session = session,
                    index = index,
                    isSelected = session.id == currentSessionId,
                    onClick = { onSelect(session) },
                    onDelete = if (onDeleted != null) {
                        { deleteError = null; deleteTarget = session }
                    } else {
                        null
                    },
                )
            }
        }
        TaskSessionAddButton(
            enabled = targetState is WorkspaceTargetState.Closed,
            onClick = { openTargetSheet() },
        )
    }

    if (targetState !is WorkspaceTargetState.Closed) {
        val creating = targetState is WorkspaceTargetState.Creating
        val error = (targetState as? WorkspaceTargetState.Error)?.message
        WandBottomSheet(
            onDismissRequest = { if (!creating) dismissTargetSheet() },
            sheetState = sheetState,
            gesturesEnabled = !creating,
        ) {
            WorkspaceTargetSheet(
                selected = selectedTarget,
                selectedKind = selectedKind,
                creating = creating,
                error = error,
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
                onDismiss = { if (!creating) dismissTargetSheet() },
            )
        }
    }

    deleteTarget?.let { session ->
        val label = listSessionLabel(session, tabs?.indexOf(session)?.coerceAtLeast(0) ?: 0)
        WandDialog(
            title = "删除终端？",
            onDismissRequest = { if (!deleteBusy) { deleteTarget = null; deleteError = null } },
            icon = WandIcons.delete,
            confirm = WandDialogAction(
                label = if (deleteBusy) "删除中…" else "删除",
                enabled = !deleteBusy,
                destructive = true,
                onClick = {
                    if (deleteBusy) return@WandDialogAction
                    deleteBusy = true
                    deleteError = null
                    workflow.deleteSession(
                        sessionId = session.id,
                        onDeleted = {
                            val deleted = session
                            deleteTarget = null
                            deleteError = null
                            deleteBusy = false
                            onDeleted?.invoke(deleted)
                        },
                        onError = { message ->
                            deleteError = message
                            deleteBusy = false
                        },
                    )
                },
            ),
            dismiss = WandDialogAction(
                "取消",
                enabled = !deleteBusy,
                onClick = { deleteTarget = null; deleteError = null },
            ),
        ) {
            Text(
                deleteError ?: "终端「$label」会结束并被删除，此操作无法撤销。",
                style = MaterialTheme.typography.bodyMedium,
                color = if (deleteError != null) WandColors.danger else WandColors.textSecondary,
            )
        }
    }
}

@Composable
private fun TaskSessionAddButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(shape)
            .background(WandColors.bgElevated.copy(alpha = 0.72f))
            .border(1.dp, WandColors.border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = "新建工作窗口" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = WandIcons.add,
            contentDescription = null,
            tint = if (enabled) WandColors.brand else WandColors.textMuted,
            modifier = Modifier.size(17.dp),
        )
    }
}

@Composable
private fun TaskSessionTab(
    session: WorkspaceSessionSummary,
    index: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val label = listSessionLabel(session, index)
    val accent = if (session.provider == "codex") WandColors.info else WandColors.brand
    val shape = RoundedCornerShape(8.dp)
    val isRunning = session.status in RUNNING_STATUSES
    val a11yLabel = if (isSelected) "当前工作窗口 $label" else "切换到 $label"

    Row(
        modifier = Modifier
            .height(34.dp)
            .clip(shape)
            .background(if (isSelected) WandColors.selectedFill else WandColors.bgElevated.copy(alpha = 0.72f))
            .border(
                width = 1.dp,
                color = if (isSelected) WandColors.brand.copy(alpha = 0.55f) else WandColors.border,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(
                start = if (isSelected) 10.dp else 8.dp,
                end = when {
                    isSelected && onDelete != null -> 2.dp
                    isSelected -> 10.dp
                    else -> 8.dp
                },
            )
            .semantics { contentDescription = a11yLabel },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = BrandLogos.painterForProvider(session.provider),
            contentDescription = null,
            tint = BrandLogos.tintForProvider(session.provider, if (isSelected) accent else WandColors.textSecondary),
            modifier = Modifier.size(14.dp),
        )
        if (isSelected) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = WandColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
        }
        if (isRunning) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(WandColors.success, CircleShape),
            )
        }
        if (isSelected && onDelete != null) {
            Icon(
                imageVector = WandIcons.close,
                contentDescription = "删除终端 $label",
                tint = WandColors.textMuted,
                modifier = Modifier
                    .size(22.dp)
                    .clickable(onClick = onDelete)
                    .padding(4.dp),
            )
        }
    }
}

private val RUNNING_STATUSES = setOf("initializing", "running", "thinking")
