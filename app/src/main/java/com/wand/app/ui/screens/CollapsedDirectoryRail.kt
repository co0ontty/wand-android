package com.wand.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.WorkspaceTaskSummary
import com.wand.app.data.activityStatus
import com.wand.app.data.workspaceProviderLabel
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.components.StatusDot
import com.wand.app.ui.components.WandIconButton
import com.wand.app.ui.components.WandIconButtonVariant
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.isWandDarkTheme
import com.wand.app.ui.theme.wandSelectedRow
import com.wand.app.ui.withLiveTitle

/**
 * 平板窄栏。展开和新建留在栏内；每个目录是一个文件夹，点开后由 [DirectoryPeekOverlay] 选择内容。
 */
@Composable
internal fun CollapsedDirectoryRail(
    groups: List<TaskDirectoryGroup>,
    selectedTaskId: String?,
    selectedSessionId: String?,
    peekDirectoryId: String?,
    rootWindowTop: () -> Float,
    onToggleDirectory: (TaskDirectoryGroup, Dp) -> Unit,
    onDirectoryTop: (Dp) -> Unit,
    onNewTask: () -> Unit,
    onExpandSidebar: () -> Unit,
) {
    val directories = collapsedRailDirectories(groups)
    val directoryTops = remember { mutableMapOf<String, Dp>() }
    val density = LocalDensity.current.density
    Column(
        modifier = Modifier
            .width(56.dp)
            .fillMaxHeight()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CollapsedRailTile(
            icon = rememberVectorPainter(WandIcons.panelExpand),
            iconTint = WandColors.textSecondary,
            accentTint = WandColors.brand,
            selected = false,
            selectionStateEnabled = false,
            contentDescription = "展开任务侧边栏",
            onClickLabel = "展开任务侧边栏",
            outlined = true,
            onClick = onExpandSidebar,
        )
        Spacer(modifier = Modifier.height(10.dp))
        CollapsedRailDivider()
        Spacer(modifier = Modifier.height(10.dp))
        CollapsedRailTile(
            icon = rememberVectorPainter(WandIcons.add),
            iconTint = WandColors.brand,
            accentTint = WandColors.brand,
            selected = false,
            selectionStateEnabled = false,
            contentDescription = "新建任务",
            onClickLabel = "新建任务",
            emphasized = true,
            onClick = onNewTask,
        )
        if (directories.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            CollapsedRailDivider()
            Spacer(modifier = Modifier.height(8.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            directories.forEach { item ->
                val directoryId = item.group.workspaceId
                val open = peekDirectoryId == directoryId
                val current = directoryContainsSelection(item.group, selectedTaskId, selectedSessionId)
                val name = item.group.workspaceName.ifBlank { "任务目录" }
                Box(
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        val top = coordinates.topInRoot(rootWindowTop, density)
                        directoryTops[directoryId] = top
                        if (open) onDirectoryTop(top)
                    },
                ) {
                    CollapsedRailTile(
                        icon = rememberVectorPainter(WandIcons.folder),
                        iconTint = if (open || current) WandColors.brand else WandColors.textSecondary,
                        accentTint = WandColors.brand,
                        selected = open || current,
                        selectionStateEnabled = current,
                        statusText = if (open) "已展开" else null,
                        contentDescription = directoryContentDescription(name, item.activity),
                        onClickLabel = if (open) "关闭目录" else "打开目录",
                        onClick = {
                            onToggleDirectory(item.group, directoryTops[directoryId] ?: 0.dp)
                        },
                    )
                    if (item.activity != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .size(7.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    if (item.activity == "attention") WandColors.warning else WandColors.success,
                                ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 盖在详情区上的目录选择窗。遮罩不到窄栏，再点另一个文件夹会直接切换。
 */
@Composable
internal fun DirectoryPeekOverlay(
    anchorTop: Dp,
    group: TaskDirectoryGroup,
    selectedTaskId: String?,
    selectedSessionId: String?,
    onOpenTask: (WorkspaceTaskSummary) -> Unit,
    onOpenSession: (WorkspaceSessionSummary, WorkspaceTaskSummary?) -> Unit,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val margin = 8.dp
        val headerHeight = 52.dp
        val minBody = 120.dp
        val preferredTop = (anchorTop - 6.dp).coerceAtLeast(statusTop + margin)
        val bottomLimit = maxHeight - navBottom - margin
        val spaceBelow = bottomLimit - preferredTop
        val top = if (spaceBelow < minBody + headerHeight) {
            (bottomLimit - minBody - headerHeight).coerceAtLeast(statusTop + margin)
        } else {
            preferredTop
        }
        val bodyMax = (bottomLimit - top - headerHeight).coerceAtLeast(minBody)
        val panelWidth = 300.dp.coerceAtMost((maxWidth - margin * 2).coerceAtLeast(220.dp))
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .semantics { contentDescription = "关闭目录预览" },
        )
        DirectoryPeekPanel(
            modifier = Modifier.offset(x = margin, y = top),
            width = panelWidth,
            bodyMax = bodyMax,
            group = group,
            selectedTaskId = selectedTaskId,
            selectedSessionId = selectedSessionId,
            onOpenTask = onOpenTask,
            onOpenSession = onOpenSession,
            onExpand = onExpand,
        )
    }
}

@Composable
private fun DirectoryPeekPanel(
    modifier: Modifier,
    width: Dp,
    bodyMax: Dp,
    group: TaskDirectoryGroup,
    selectedTaskId: String?,
    selectedSessionId: String?,
    onOpenTask: (WorkspaceTaskSummary) -> Unit,
    onOpenSession: (WorkspaceSessionSummary, WorkspaceTaskSummary?) -> Unit,
    onExpand: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val dark = isWandDarkTheme()
    val title = group.workspaceName.ifBlank { "任务目录" }
    val scroll = rememberScrollState()
    LaunchedEffect(group.workspaceId) {
        scroll.scrollTo(0)
    }
    Column(
        modifier = modifier
            .width(width)
            .shadow(
                elevation = 18.dp,
                shape = shape,
                ambientColor = if (dark) Color.Black.copy(alpha = 0.45f) else Color(0xFF593A20).copy(alpha = 0.18f),
                spotColor = if (dark) Color.Black.copy(alpha = 0.55f) else Color(0xFF593A20).copy(alpha = 0.24f),
            )
            .clip(shape)
            .background(WandColors.bgElevated)
            .border(1.dp, WandColors.border, shape),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures(onTap = {}) },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = WandColors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                WandIconButton(
                    icon = WandIcons.panelExpand,
                    contentDescription = "展开成完整侧栏",
                    onClick = onExpand,
                    variant = WandIconButtonVariant.Quiet,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(WandColors.border.copy(alpha = 0.72f)),
            )
        }
        Column(
            modifier = Modifier
                .heightIn(max = bodyMax)
                .verticalScroll(scroll)
                .padding(horizontal = 6.dp, vertical = 6.dp),
        ) {
            if (group.tasks.isEmpty() && group.standaloneSessions.isEmpty()) {
                Text(
                    "这个目录还没有任务或终端。",
                    style = MaterialTheme.typography.bodySmall,
                    color = WandColors.textMuted,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                )
            }
            orderedTaskSummaries(group.tasks).forEach { task ->
                PeekTaskBlock(
                    task = task,
                    parentNames = listOf(group.workspaceName),
                    selectedTaskId = selectedTaskId,
                    selectedSessionId = selectedSessionId,
                    onOpenTask = { onOpenTask(task) },
                    onOpenSession = { onOpenSession(it, task) },
                )
            }
            if (group.standaloneSessions.isNotEmpty()) {
                Text(
                    "${group.standaloneSessions.size} 个未分组终端",
                    style = MaterialTheme.typography.labelSmall,
                    color = WandColors.textMuted,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
                group.standaloneSessions.forEachIndexed { index, session ->
                    PeekSessionRow(
                        session = session,
                        label = listSessionLabel(session.withLiveTitle(), index, listOf(group.workspaceName)),
                        selected = session.id == selectedSessionId,
                        onClick = { onOpenSession(session, null) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PeekTaskBlock(
    task: WorkspaceTaskSummary,
    parentNames: Collection<String>,
    selectedTaskId: String?,
    selectedSessionId: String?,
    onOpenTask: () -> Unit,
    onOpenSession: (WorkspaceSessionSummary) -> Unit,
) {
    val selected = isTaskRowSelected(
        taskId = task.id,
        visibleSessionIds = task.sessions.map { it.id },
        selectedTaskId = selectedTaskId,
        selectedSessionId = selectedSessionId,
    )
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .wandSelectedRow(selected = selected, shape = RoundedCornerShape(8.dp))
                .clickable(onClick = onOpenTask)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                task.name,
                style = MaterialTheme.typography.bodyMedium,
                color = WandColors.textPrimary,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            if (task.isIsolated) {
                Icon(
                    WandIcons.commit,
                    contentDescription = "隔离 worktree",
                    tint = WandColors.success,
                    modifier = Modifier.padding(start = 6.dp).size(14.dp),
                )
            }
        }
        if (task.sessions.isNotEmpty()) {
            Column(modifier = Modifier.padding(start = 10.dp)) {
                task.sessions.forEachIndexed { index, session ->
                    PeekSessionRow(
                        session = session,
                        label = listSessionLabel(
                            session.withLiveTitle(),
                            index,
                            parentNames + task.name,
                        ),
                        selected = session.id == selectedSessionId,
                        onClick = { onOpenSession(session) },
                    )
                }
            }
        }
        if (task.totalSessions > task.sessions.size) {
            Text(
                "还有 ${task.totalSessions - task.sessions.size} 个会话，打开任务查看",
                style = MaterialTheme.typography.labelSmall,
                color = WandColors.textMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenTask)
                    .padding(start = 18.dp, end = 8.dp, top = 2.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun PeekSessionRow(
    session: WorkspaceSessionSummary,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .wandSelectedRow(selected = selected, shape = RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(session.withLiveTitle().activityStatus(), modifier = Modifier.size(7.dp))
        Column(
            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = WandColors.textPrimary,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                sessionKindLabel(session),
                style = MaterialTheme.typography.labelSmall,
                color = WandColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun sessionKindLabel(session: WorkspaceSessionSummary): String = buildString {
    append(workspaceProviderLabel(session.provider))
    if (session.sessionKind == "pty" && !session.provider.isNullOrBlank() && session.provider != "shell") {
        append(" · 终端")
    }
}

private fun directoryContentDescription(name: String, activity: String?): String = buildString {
    append("目录 ")
    append(name)
    when (activity) {
        "attention" -> append("，需要处理")
        "running" -> append("，正在运行")
    }
}

private fun LayoutCoordinates.topInRoot(rootWindowTop: () -> Float, density: Float): Dp {
    val topPx = positionInWindow().y - rootWindowTop()
    return Dp(topPx / density)
}

@Composable
private fun CollapsedRailDivider() {
    Box(
        modifier = Modifier
            .width(24.dp)
            .height(1.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(WandColors.border.copy(alpha = 0.48f)),
    )
}

@Composable
private fun CollapsedRailTile(
    icon: Painter,
    iconTint: Color,
    iconScale: Float = 1f,
    selected: Boolean,
    contentDescription: String,
    onClickLabel: String = "打开",
    accentTint: Color = iconTint,
    selectionStateEnabled: Boolean = false,
    outlined: Boolean = false,
    emphasized: Boolean = false,
    loading: Boolean = false,
    statusText: String? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = WandMotion.tweenPress(),
        label = "collapsedRailPress",
    )
    val showContainer = outlined || emphasized || selected
    val background by animateColorAsState(
        targetValue = when {
            selected -> accentTint.copy(alpha = 0.12f)
            emphasized -> accentTint.copy(alpha = 0.09f)
            outlined -> WandColors.surfaceSoft.copy(alpha = 0.58f)
            else -> Color.Transparent
        },
        animationSpec = WandMotion.tweenFast(),
        label = "collapsedRailBackground",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            selected -> accentTint.copy(alpha = 0.22f)
            emphasized -> accentTint.copy(alpha = 0.28f)
            outlined -> WandColors.border.copy(alpha = 0.48f)
            else -> Color.Transparent
        },
        animationSpec = WandMotion.tweenFast(),
        label = "collapsedRailBorder",
    )
    Box(
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(shape)
            .clickable(
                enabled = !loading,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClickLabel = onClickLabel,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                this.contentDescription = contentDescription
                if (selectionStateEnabled) this.selected = selected
                val status = if (loading) "正在恢复会话" else statusText
                if (status != null) stateDescription = status
            },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(accentTint.copy(alpha = 0.92f)),
            )
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .then(
                    if (showContainer) {
                        Modifier
                            .clip(shape)
                            .background(background)
                            .border(1.dp, borderColor, shape)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    color = accentTint,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Icon(
                    painter = icon,
                    contentDescription = null,
                    tint = BrandLogos.tintWithAlpha(
                        iconTint,
                        alpha = if (outlined && !emphasized && !selected) 0.82f else 0.96f,
                    ),
                    modifier = Modifier.size(
                        (when {
                            emphasized -> 20.dp
                            outlined -> 19.dp
                            else -> 23.dp
                        }) * iconScale,
                    ),
                )
            }
        }
    }
}
