package com.wand.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.data.GLOBAL_WORKSPACE_ID
import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.WorkspaceTaskStatus
import com.wand.app.data.WorkspaceTaskSummary
import com.wand.app.data.activityStatus
import com.wand.app.ui.SessionTitleStore
import com.wand.app.ui.components.StatusDot
import com.wand.app.ui.components.WandBrandMark
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandIconButton
import com.wand.app.ui.components.WandIconButtonVariant
import com.wand.app.ui.components.WandInlineSearchField
import com.wand.app.ui.components.WandMorphIconButton
import com.wand.app.ui.components.WandSegmentedTrack
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandProviderMark
import com.wand.app.ui.components.WandStatusPresentation
import com.wand.app.ui.components.WandStatusTone
import com.wand.app.ui.components.wandStatusPresentation
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.reduceMotionEnabled
import com.wand.app.ui.theme.wandSelectedRow
import com.wand.app.ui.withLiveTitle

/**
 * 首页（工作台）的成品级外壳。
 *
 * 设计取舍（对齐 Cursor for iOS / Claude Code mobile / Codex mobile 的首页共识）：
 * - 顶部是品牌 + 服务器 + 唯一溢出菜单，不再把「会话模式」做成一个像下拉的胶囊；
 * - 模式切换用真正的分段控件；
 * - 状态优先：先告诉你「几个在跑、几个等你」，再给列表；列表是卡片不是文件树；
 * - 需要动手的会话有明确的状态胶囊，而不是只有一个小圆点；
 * - 主操作固定在底部（输入式启动条），随时可以「开口」。
 * 这里只做展示，所有状态计算走 HomePresentation 的纯函数。
 */

// MARK: - 顶部栏

@Composable
internal fun HomeTopBar(
    serverDisplayName: String,
    interactionEnabled: Boolean,
    searchOpen: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearchToggle: () -> Unit,
    onRefresh: () -> Unit,
    onStartSelection: (() -> Unit)?,
    onOpenTaskBoard: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchServer: () -> Unit,
    onCollapseSidebar: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val motionEnabled = !reduceMotionEnabled()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 品牌标是身份，也是「这是一款客户端」而不是「一个后台工具」的第一眼信号。
        WandBrandMark(size = 30)
        Spacer(Modifier.width(10.dp))
        // 搜索就地展开：服务器胶囊的位置变成输入框，右侧那枚放大镜原地变形成 ✕，
        // 不跳页、不弹新层，收起时同一段动画倒放回去。
        AnimatedContent(
            targetState = searchOpen,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                if (motionEnabled) {
                    (fadeIn(WandMotion.tweenFast()) + slideInHorizontally(
                        animationSpec = WandMotion.tweenEnter(),
                        initialOffsetX = { it / 3 },
                    )) togetherWith (fadeOut(WandMotion.tweenExit()) + slideOutHorizontally(
                        animationSpec = WandMotion.tweenExit(),
                        targetOffsetX = { -it / 4 },
                    ))
                } else {
                    EnterTransition.None togetherWith ExitTransition.None
                }
            },
            label = "homeTopBarSearch",
        ) { open ->
            if (open) {
                WandInlineSearchField(
                    expanded = true,
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    onCollapse = null,
                    placeholder = "搜索工作区 / 任务 / 会话",
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(WandShapes.full)
                        .background(WandColors.surfaceSoft.copy(alpha = 0.55f))
                        .border(0.5.dp, WandColors.border.copy(alpha = 0.6f), WandShapes.full)
                        .clickable(
                            enabled = interactionEnabled,
                            role = Role.Button,
                            onClickLabel = "切换服务器",
                            onClick = onSwitchServer,
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        WandIcons.server,
                        contentDescription = null,
                        tint = WandColors.textMuted,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        serverDisplayName.ifBlank { "当前服务器" },
                        style = MaterialTheme.typography.labelLarge,
                        color = WandColors.textPrimary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(start = 6.dp),
                    )
                    Icon(
                        WandIcons.expand,
                        contentDescription = null,
                        tint = WandColors.textMuted,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(14.dp),
                    )
                }
            }
        }
        // 同一个按钮实例承载两种状态：放大镜原地变形成 ✕（规则 4）。
        // 拆成两个分支会让图标瞬切，变形就没了。
        WandMorphIconButton(
            expanded = searchOpen,
            collapsedIcon = WandIcons.search,
            expandedIcon = WandIcons.close,
            contentDescription = if (searchOpen) "关闭搜索" else "搜索",
            onClick = onSearchToggle,
            enabled = interactionEnabled,
            tint = WandColors.textMuted,
            expandedTint = WandColors.brand,
            touchSize = 40.dp,
            iconSize = 19.dp,
            rotationDegrees = 0f,
        )
        if (!searchOpen && onCollapseSidebar != null) {
            WandIconButton(
                icon = WandIcons.panelCollapse,
                contentDescription = "收起任务侧边栏",
                onClick = onCollapseSidebar,
                variant = WandIconButtonVariant.Toolbar,
            )
        }
        if (!searchOpen) {
            Box {
                WandIconButton(
                    icon = WandIcons.more,
                    contentDescription = "更多选项",
                    onClick = { menuOpen = true },
                    variant = WandIconButtonVariant.Toolbar,
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = WandColors.bgElevated,
                ) {
                    if (onStartSelection != null) {
                        DropdownMenuItem(
                            text = { Text("选择多项") },
                            leadingIcon = { Icon(WandIcons.todo, contentDescription = null) },
                            onClick = { menuOpen = false; onStartSelection() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("刷新") },
                        leadingIcon = { Icon(WandIcons.refresh, contentDescription = null) },
                        onClick = { menuOpen = false; onRefresh() },
                    )
                    DropdownMenuItem(
                        text = { Text("任务管理") },
                        leadingIcon = { Icon(WandIcons.todo, contentDescription = null) },
                        onClick = { menuOpen = false; onOpenTaskBoard() },
                    )
                    DropdownMenuItem(
                        text = { Text("设置") },
                        leadingIcon = { Icon(WandIcons.settings, contentDescription = null) },
                        onClick = { menuOpen = false; onOpenSettings() },
                    )
                    DropdownMenuItem(
                        text = { Text("切换服务器") },
                        leadingIcon = { Icon(WandIcons.swapServer, contentDescription = null) },
                        onClick = { menuOpen = false; onSwitchServer() },
                    )
                }
            }
        }
    }
}

// MARK: - 模式分段

/** 真正的分段控件：会话 / 任务。旧的「会话模式 ⌄」看着像下拉，其实是切换，误导性太强。 */
@Composable
internal fun HomeModeTabs(
    mode: HomeListMode,
    enabled: Boolean,
    onChange: (HomeListMode) -> Unit,
) {
    val motionEnabled = !reduceMotionEnabled()
    val modes = HomeListMode.entries
    WandSegmentedTrack(
        itemCount = modes.size,
        selectedIndex = modes.indexOf(mode).coerceAtLeast(0),
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        modes.forEach { entry ->
            val selected = entry == mode
            val textColor by animateColorAsState(
                targetValue = if (selected) WandColors.brand else WandColors.textSecondary,
                animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenFast()),
                label = "homeTabText",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 38.dp)
                    .clip(WandShapes.sm)
                    .clickable(
                        enabled = enabled && !selected,
                        role = Role.Tab,
                        onClick = { onChange(entry) },
                    )
                    .semantics {
                        contentDescription = if (selected) {
                            "当前${entry.segmentLabel}"
                        } else {
                            "切换到${entry.segmentLabel}"
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    entry.segmentLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

// MARK: - 状态总览

/**
 * 一行状态胶囊：几个在跑、几个等你。安静时整条不渲染，
 * 让首页在没有活动的时候也保持干净（不是留一条空壳）。
 */
@Composable
internal fun HomeActivityStrip(
    overview: HomeOverview,
    attentionOnly: Boolean,
    enabled: Boolean,
    onToggleAttention: () -> Unit,
) {
    val showNeedsYou = overview.needsYou > 0 || attentionOnly
    if (overview.running == 0 && !showNeedsYou) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (overview.running > 0) {
            HomeStatPill(
                label = "${overview.running} 个在跑",
                tone = WandStatusTone.Success,
                selected = false,
                onClick = null,
            )
        }
        if (showNeedsYou) {
            HomeStatPill(
                label = if (overview.needsYou > 0) "${overview.needsYou} 个等你" else "只看等你",
                tone = WandStatusTone.Permission,
                selected = attentionOnly,
                onClick = { if (enabled) onToggleAttention() },
                contentDescription = if (attentionOnly) "取消只看需要处理的会话" else "只看需要处理的会话",
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            "${overview.sessions} 个会话",
            style = MaterialTheme.typography.labelMedium,
            color = WandColors.textMuted,
        )
    }
}

@Composable
private fun HomeStatPill(
    label: String,
    tone: WandStatusTone,
    selected: Boolean,
    onClick: (() -> Unit)?,
    contentDescription: String? = null,
) {
    val color = when (tone) {
        WandStatusTone.Success -> WandColors.success
        WandStatusTone.Permission -> WandColors.permission
        WandStatusTone.Danger -> WandColors.danger
        WandStatusTone.Warning -> WandColors.warning
        WandStatusTone.Neutral -> WandColors.textMuted
    }
    val motionEnabled = !reduceMotionEnabled()
    val fill by animateColorAsState(
        targetValue = if (selected) color.copy(alpha = 0.18f) else WandColors.surfaceSoft.copy(alpha = 0.5f),
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenFast()),
        label = "homeStatPillFill",
    )
    val stroke by animateColorAsState(
        targetValue = if (selected) color.copy(alpha = 0.55f) else WandColors.border.copy(alpha = 0.5f),
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenFast()),
        label = "homeStatPillStroke",
    )
    val base = Modifier
        .clip(WandShapes.full)
        .background(fill)
        .border(0.8.dp, stroke, WandShapes.full)
    val clickable = if (onClick != null) {
        base.clickable(role = Role.Button, onClick = onClick)
    } else {
        base
    }
    Row(
        modifier = clickable
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(WandShapes.full)
                .background(color),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

// MARK: - 工作区卡片

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeWorkspaceCard(
    group: TaskDirectoryGroup,
    modifier: Modifier = Modifier,
    expanded: Boolean,
    standaloneCollapsed: Boolean,
    taskCollapsed: (String) -> Boolean,
    forceExpandTasks: Boolean = false,
    /** 标题行上的长按拖动手势（由列表侧注入，卡片本身不知道排序实现）。 */
    headerDragModifier: Modifier = Modifier,
    selectedTaskId: String?,
    selectedSessionId: String?,
    nowMillis: Long,
    selecting: Boolean = false,
    selectedTaskIds: Set<String> = emptySet(),
    selectedSessionIds: Set<String> = emptySet(),
    onToggleManagedTask: (String) -> Unit = {},
    onToggleManagedSession: (String) -> Unit = {},
    onEnterSelection: (taskId: String?, sessionId: String?) -> Unit = { _, _ -> },
    onToggleGroup: () -> Unit,
    onToggleTask: (String) -> Unit,
    onToggleStandalone: () -> Unit,
    onOpenTask: (WorkspaceTaskSummary) -> Unit,
    onOpenSession: (WorkspaceSessionSummary, WorkspaceTaskSummary?) -> Unit,
    onMoveSession: (WorkspaceSessionSummary) -> Unit,
    onNewTask: () -> Unit,
    onRenameDirectory: () -> Unit,
    onNewWindow: (WorkspaceTaskSummary) -> Unit,
    onRename: (WorkspaceTaskSummary) -> Unit,
    onClear: (WorkspaceTaskSummary) -> Unit,
    onArchive: (WorkspaceTaskSummary) -> Unit,
    onDelete: (WorkspaceTaskSummary) -> Unit,
    onDeleteSession: (WorkspaceSessionSummary) -> Unit,
    onDeleteDirectory: () -> Unit,
    onReview: () -> Unit,
) {
    val sessions = group.tasks.flatMap { it.sessions } + group.standaloneSessions
    val counts = homeSessionCounts(sessions)
    val reduceMotion = reduceMotionEnabled()
    var menuOpen by remember { mutableStateOf(false) }
    // 空工作区没有可折叠的内容：渲染成一行紧凑条目，点它直接去建任务，
    // 而不是留一张带箭头、点了没反应的卡片。
    val isEmpty = group.tasks.isEmpty() && group.standaloneSessions.isEmpty()

    WandCard(
        modifier = modifier.fillMaxWidth(),
        shape = WandShapes.lg,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(WandShapes.md)
                .clickable(
                    onClickLabel = when {
                        isEmpty -> "在 ${group.workspaceName} 新建任务"
                        expanded -> "收起工作区"
                        else -> "展开工作区"
                    },
                    onClick = { if (isEmpty) onNewTask() else onToggleGroup() },
                )
                // 长按标题行 = 拿起这张卡片排序；卡片内部的行保留自己的长按多选。
                .then(headerDragModifier)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(WandShapes.sm)
                    .background(WandColors.brandSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    WandIcons.folder,
                    contentDescription = null,
                    tint = WandColors.brand,
                    modifier = Modifier.size(16.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp, end = 6.dp),
            ) {
                Text(
                    group.workspaceName.ifEmpty { "任务目录" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = WandColors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (isEmpty) {
                        // 空目录的摘要是「它还没有内容」，不是「0 会话」这种机械计数。
                        androidx.compose.ui.text.AnnotatedString(
                            if (group.isGlobal) "未归属 · 还没有会话" else "还没有会话",
                        )
                    } else {
                        summaryText(group.isGlobal, workspaceSummarySegments(sessions.size, counts))
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = WandColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!isEmpty) {
                TreeDisclosureCaret(
                    expanded = expanded,
                    contentDescription = if (expanded) {
                        "收起工作区 ${group.workspaceName}"
                    } else {
                        "展开工作区 ${group.workspaceName}"
                    },
                    onClick = onToggleGroup,
                )
            }
            Box {
                WandIconButton(
                    icon = WandIcons.more,
                    contentDescription = "工作区操作 ${group.workspaceName}",
                    onClick = { menuOpen = true },
                    variant = WandIconButtonVariant.Quiet,
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = WandColors.bgElevated,
                ) {
                    if (!group.isGlobal) {
                        DropdownMenuItem(
                            text = { Text("在这里新建任务") },
                            leadingIcon = { Icon(WandIcons.add, contentDescription = null) },
                            onClick = { menuOpen = false; onNewTask() },
                        )
                    }
                    if (group.tasks.any { it.worktree != null }) {
                        DropdownMenuItem(
                            text = { Text("审查 Worktree") },
                            leadingIcon = { Icon(WandIcons.commit, contentDescription = null) },
                            onClick = { menuOpen = false; onReview() },
                        )
                    }
                    if (group.workspaceId != GLOBAL_WORKSPACE_ID) {
                        DropdownMenuItem(
                            text = { Text("重命名工作区") },
                            leadingIcon = { Icon(WandIcons.rename, contentDescription = null) },
                            onClick = { menuOpen = false; onRenameDirectory() },
                        )
                    }
                    // 合成目录没有项目实体，删除它等于删掉这里所有会话，不给这个入口。
                    if (!group.synthetic && !group.isGlobal) {
                        DropdownMenuItem(
                            text = { Text("删除工作区…", color = WandColors.danger) },
                            leadingIcon = { Icon(WandIcons.delete, contentDescription = null) },
                            onClick = { menuOpen = false; onDeleteDirectory() },
                        )
                    }
                }
            }
        }
        if (isEmpty) return@WandCard
        AnimatedVisibility(
            visible = expanded,
            enter = if (reduceMotion) {
                EnterTransition.None
            } else {
                expandVertically(animationSpec = WandMotion.tweenEnter(), expandFrom = Alignment.Top) +
                    fadeIn(WandMotion.tweenEnter())
            },
            exit = if (reduceMotion) {
                ExitTransition.None
            } else {
                shrinkVertically(animationSpec = WandMotion.tweenExit(), shrinkTowards = Alignment.Top) +
                    fadeOut(WandMotion.tweenExit())
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                orderedTaskSummaries(group.tasks).forEachIndexed { index, task ->
                    // 任务块不再有底色，兄弟任务之间用一条发丝线分隔，边界才看得清。
                    if (index > 0) HomeHairline()
                    HomeTaskBlock(
                        task = task,
                        parentNames = listOf(group.workspaceName),
                        nowMillis = nowMillis,
                        expanded = forceExpandTasks || isTaskSessionsExpanded(
                            userCollapsed = taskCollapsed(task.id),
                            sessionCount = task.totalSessions,
                        ),
                        selected = isTaskRowSelected(
                            taskId = task.id,
                            visibleSessionIds = task.sessions.map { it.id },
                            selectedTaskId = selectedTaskId,
                            selectedSessionId = selectedSessionId,
                        ),
                        selectedSessionId = selectedSessionId,
                        selecting = selecting,
                        managedSelected = task.id in selectedTaskIds,
                        selectedSessionIds = selectedSessionIds,
                        onToggleManaged = { onToggleManagedTask(task.id) },
                        onToggleManagedSession = onToggleManagedSession,
                        onEnterSelection = { onEnterSelection(task.id, null) },
                        onToggle = { onToggleTask(task.id) },
                        onOpen = { onOpenTask(task) },
                        onOpenSession = { onOpenSession(it, task) },
                        onNewWindow = { onNewWindow(task) },
                        onRename = { onRename(task) },
                        onClear = { onClear(task) },
                        onArchive = { onArchive(task) },
                        onDelete = { onDelete(task) },
                        onDeleteSession = onDeleteSession,
                        onMoveSession = onMoveSession,
                    )
                }
                if (group.standaloneSessions.isNotEmpty()) {
                    if (group.tasks.isNotEmpty()) HomeHairline()
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(WandShapes.sm)
                                .clickable(onClick = onToggleStandalone)
                                .padding(start = 6.dp, top = 6.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${group.standaloneSessions.size} 个未分组终端",
                                style = MaterialTheme.typography.labelMedium,
                                color = WandColors.textMuted,
                                modifier = Modifier.weight(1f),
                            )
                            TreeDisclosureCaret(
                                expanded = !standaloneCollapsed,
                                contentDescription = if (standaloneCollapsed) {
                                    "展开未分组终端"
                                } else {
                                    "收起未分组终端"
                                },
                                onClick = onToggleStandalone,
                            )
                        }
                        if (!standaloneCollapsed) {
                            group.standaloneSessions.forEachIndexed { index, session ->
                                HomeSessionRow(
                                    session = session,
                                    label = listSessionLabel(
                                        session.withLiveTitle(),
                                        index,
                                        listOf(group.workspaceName),
                                    ),
                                    nowMillis = nowMillis,
                                    selected = session.id == selectedSessionId,
                                    selecting = selecting,
                                    managedSelected = session.id in selectedSessionIds,
                                    onToggleManaged = { onToggleManagedSession(session.id) },
                                    onEnterSelection = { onEnterSelection(null, session.id) },
                                    onClick = { onOpenSession(session, null) },
                                    onDelete = { onDeleteSession(session) },
                                    onMove = { onMoveSession(session) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 摘要行：数字自己带语义色（运行中绿、待处理金），比在标题右侧再挂一个活动胶囊省地方，
 * 也不会把同一个数字说两遍。
 */
@Composable
private fun summaryText(global: Boolean, segments: List<HomeSummarySegment>): AnnotatedString {
    val separator = " · "
    return buildAnnotatedString {
        if (global) {
            withStyle(SpanStyle(color = WandColors.textMuted)) { append("未归属") }
            append(separator)
        }
        segments.forEachIndexed { index, segment ->
            if (index > 0) append(separator)
            withStyle(
                SpanStyle(
                    color = when (segment.tone) {
                        HomeSummaryTone.Muted -> WandColors.textMuted
                        HomeSummaryTone.Running -> WandColors.success
                        HomeSummaryTone.NeedsYou -> WandColors.permission
                    },
                    fontWeight = if (segment.tone == HomeSummaryTone.Muted) FontWeight.Normal else FontWeight.Medium,
                ),
            ) {
                append(segment.text)
            }
        }
    }
}

/** 卡片内部的发丝分隔线：比整块底色轻，只负责划清兄弟区块的边界。 */
@Composable
private fun HomeHairline() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .height(1.dp)
            .background(WandColors.border.copy(alpha = 0.38f)),
    )
}

// MARK: - 任务块

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeTaskBlock(
    task: WorkspaceTaskSummary,
    parentNames: Collection<String>,
    nowMillis: Long,
    expanded: Boolean,
    selected: Boolean,
    selectedSessionId: String?,
    selecting: Boolean = false,
    managedSelected: Boolean = false,
    selectedSessionIds: Set<String> = emptySet(),
    onToggleManaged: () -> Unit = {},
    onToggleManagedSession: (String) -> Unit = {},
    onEnterSelection: () -> Unit = {},
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onOpenSession: (WorkspaceSessionSummary) -> Unit,
    onMoveSession: (WorkspaceSessionSummary) -> Unit,
    onNewWindow: () -> Unit,
    onRename: () -> Unit,
    onClear: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onDeleteSession: (WorkspaceSessionSummary) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val reduceMotion = reduceMotionEnabled()
    val done = task.status == WorkspaceTaskStatus.Done
    val counts = homeSessionCounts(task.sessions)
    // 任务块只做「小标题 + 状态引导条」：不再套一层带底色的盒子，
    // 否则工作区卡片里再嵌一张同色卡片，整页看起来像文件树。
    // 引导条的颜色就是这条任务的健康度：等你 > 在跑 > 普通。
    val railColor = when {
        counts.needsYou > 0 -> WandColors.permission.copy(alpha = 0.6f)
        counts.running > 0 -> WandColors.success.copy(alpha = 0.5f)
        else -> WandColors.border.copy(alpha = 0.6f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .then(if (done) Modifier.graphicsLayer { alpha = 0.76f } else Modifier),
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .clip(WandShapes.full)
                .background(railColor),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .wandSelectedRow(
                    selected = if (selecting) managedSelected else selected,
                    shape = WandShapes.sm,
                )
                .combinedClickable(
                    onClick = { if (selecting) onToggleManaged() else onOpen() },
                    onLongClick = { if (!selecting) onEnterSelection() else onToggleManaged() },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                ManageCheck(checked = managedSelected)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        task.name,
                        // 三级层级：工作区（15 半粗）> 任务（13 半粗·次级色）> 会话标题（14 正文）。
                        // 任务名是这一层的小标题，不能和会话标题长得一样大，
                        // 否则整列文字看起来是平铺的一堆句子。
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) WandColors.textPrimary else WandColors.textSecondary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (task.isIsolated) {
                        Icon(
                            WandIcons.commit,
                            contentDescription = "隔离 worktree",
                            tint = WandColors.success,
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .size(13.dp),
                        )
                    }
                }
                Text(
                    summaryText(global = false, segments = taskSummarySegments(task.sessions.size, done, counts)),
                    style = MaterialTheme.typography.labelMedium,
                    color = WandColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showsTaskSessionDisclosure(task.totalSessions)) {
                TreeDisclosureCaret(
                    expanded = expanded,
                    contentDescription = if (expanded) "收起会话" else "展开会话",
                    onClick = onToggle,
                )
            }
            Box {
                WandIconButton(
                    icon = WandIcons.more,
                    contentDescription = "任务操作 ${task.name}",
                    onClick = { menuOpen = true },
                    variant = WandIconButtonVariant.Quiet,
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = WandColors.bgElevated,
                ) {
                    DropdownMenuItem(
                        text = { Text("新建会话") },
                        leadingIcon = { Icon(WandIcons.add, contentDescription = null) },
                        onClick = { menuOpen = false; onNewWindow() },
                    )
                    DropdownMenuItem(
                        text = { Text("打开任务") },
                        onClick = { menuOpen = false; onOpen() },
                    )
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = { menuOpen = false; onRename() },
                    )
                    if (task.totalSessions > 0) {
                        DropdownMenuItem(
                            text = { Text("清空会话", color = WandColors.danger) },
                            onClick = { menuOpen = false; onClear() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("归档任务") },
                        leadingIcon = { Icon(WandIcons.archive, contentDescription = null) },
                        onClick = { menuOpen = false; onArchive() },
                    )
                    // 归档是软删除；只有隔离任务才在归档之外再提供真删（清理 Worktree）。
                    if (task.isIsolated) {
                        DropdownMenuItem(
                            text = { Text("删除任务并清理 Worktree", color = WandColors.danger) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = if (reduceMotion) {
                EnterTransition.None
            } else {
                expandVertically(animationSpec = WandMotion.tweenEnter(), expandFrom = Alignment.Top) +
                    fadeIn(WandMotion.tweenEnter())
            },
            exit = if (reduceMotion) {
                ExitTransition.None
            } else {
                shrinkVertically(animationSpec = WandMotion.tweenExit(), shrinkTowards = Alignment.Top) +
                    fadeOut(WandMotion.tweenExit())
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 2.dp, bottom = 2.dp),
            ) {
                if (task.sessions.isEmpty()) {
                    Text(
                        "＋ 添加第一个会话",
                        style = MaterialTheme.typography.labelMedium,
                        color = WandColors.brand,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(WandShapes.sm)
                            .clickable(onClick = onNewWindow)
                            .padding(vertical = 12.dp, horizontal = 6.dp),
                    )
                } else {
                    task.sessions.forEachIndexed { index, session ->
                        HomeSessionRow(
                            session = session,
                            label = listSessionLabel(session.withLiveTitle(), index, parentNames + task.name),
                            nowMillis = nowMillis,
                            selected = session.id == selectedSessionId,
                            selecting = selecting,
                            managedSelected = session.id in selectedSessionIds,
                            onToggleManaged = { onToggleManagedSession(session.id) },
                            onEnterSelection = onEnterSelection,
                            onClick = { onOpenSession(session) },
                            onDelete = { onDeleteSession(session) },
                            onMove = { onMoveSession(session) },
                        )
                    }
                    if (task.totalSessions > task.sessions.size) {
                        Text(
                            "列表仅显示 ${task.sessions.size}/${task.totalSessions} 个会话，打开任务可查看全部。",
                            style = MaterialTheme.typography.labelSmall,
                            color = WandColors.textMuted,
                            modifier = Modifier
                                .clickable(onClick = onOpen)
                                .padding(start = 6.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
                        )
                    }
                }
            }
        }
        }
    }
}

// MARK: - 会话行

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeSessionRow(
    session: WorkspaceSessionSummary,
    label: String,
    nowMillis: Long,
    selected: Boolean,
    selecting: Boolean = false,
    managedSelected: Boolean = false,
    onToggleManaged: () -> Unit = {},
    onEnterSelection: () -> Unit = {},
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val live = session.withLiveTitle()
    val status = live.activityStatus()
    // 权限态只在 WS 事件里，轮询摘要没有；首页要显示它必须先看实时 overlay。
    val permissionBlocked = SessionTitleStore.permissionBlockedOf(session.id) == true
    val presentation = wandStatusPresentation(if (permissionBlocked) "permission" else status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .wandSelectedRow(
                selected = if (selecting) managedSelected else selected,
                shape = WandShapes.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .combinedClickable(
                    onClick = { if (selecting) onToggleManaged() else onClick() },
                    onLongClick = { if (!selecting) onEnterSelection() else onToggleManaged() },
                )
                .padding(start = 4.dp, top = 7.dp, bottom = 7.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                ManageCheck(checked = managedSelected)
            }
            // provider 标是列表里最省字的身份信息：一眼分清 Claude / Codex / Grok。
            Box(
                // 只给淡底、不加描边：Pi / Claude 这些 logo 自带外框，
                // 再套一层圆角描边会变成「双框」，远看像个禁止符号。
                modifier = Modifier
                    .size(30.dp)
                    .clip(WandShapes.sm)
                    .background(WandColors.surface.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center,
            ) {
                WandProviderMark(
                    provider = session.provider,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = WandColors.textPrimary,
                    lineHeight = 19.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.padding(top = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SessionStatusPill(presentation = presentation)
                    Text(
                        sessionMetaLine(live, nowMillis),
                        style = MaterialTheme.typography.labelMedium,
                        color = WandColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Box {
            WandIconButton(
                icon = WandIcons.more,
                contentDescription = "终端操作 $label",
                onClick = { menuOpen = true },
                variant = WandIconButtonVariant.Quiet,
                // 会话行是列表里最密的一层，操作入口收一点，别和工作区/任务的菜单抢注意力。
                tint = WandColors.textMuted.copy(alpha = 0.78f),
                iconSize = 18.dp,
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
                    text = { Text("移动到任务") },
                    leadingIcon = { Icon(WandIcons.folder, contentDescription = null) },
                    onClick = { menuOpen = false; onMove() },
                )
                DropdownMenuItem(
                    text = { Text("删除终端", color = WandColors.danger) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

/**
 * 会话状态胶囊：文字 + 语义色。列表里只有一个小圆点时，
 * 「在跑 / 在等我 / 空闲」的差别要靠颜色去猜；写出来才是产品该有的信息密度。
 */
@Composable
private fun SessionStatusPill(presentation: WandStatusPresentation) {
    val color = when (presentation.tone) {
        WandStatusTone.Success -> WandColors.success
        WandStatusTone.Permission -> WandColors.permission
        WandStatusTone.Danger -> WandColors.danger
        WandStatusTone.Warning -> WandColors.warning
        WandStatusTone.Neutral -> WandColors.textMuted
    }
    // 「空闲」是绝大多数会话的常态：它只留一个安静的灰点，
    // 不写文字、不占胶囊，让真正需要看的「运行中 / 等待授权」跳出来。
    if (presentation.normalized == "idle") {
        Box(
            modifier = Modifier
                .padding(vertical = 2.dp)
                .semantics { contentDescription = "空闲" },
            contentAlignment = Alignment.Center,
        ) {
            StatusDot(presentation.normalized, modifier = Modifier.size(6.dp))
        }
        return
    }
    Row(
        modifier = Modifier
            .clip(WandShapes.full)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            presentation.label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

// MARK: - 底部启动条

/**
 * 底部固定的启动条：产品的「开口」应该永远在手边。
 * 输入后回车等于把这段意图带进新建任务对话框（目录 / provider 仍走原有流程确认），
 * 左侧「＋」保留一键直达原对话框的老路径。
 */
@Composable
internal fun HomeComposerBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSubmit: (String) -> Unit,
    onOpenFullDialog: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val trimmed = value.trim()
    Column(modifier = Modifier.fillMaxWidth().imePadding()) {
        // 上与列表的分界线：列表被滑动条压住时，边界要看起来是「故意切的」而不是被截断。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(WandColors.border.copy(alpha = 0.45f)),
        )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // edge-to-edge 下窗口不会因键盘而缩，输入条必须自己让开 IME 高度。
            .background(WandColors.bgElevated.copy(alpha = 0.94f))
            .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        WandIconButton(
            icon = WandIcons.add,
            contentDescription = "新建任务",
            onClick = onOpenFullDialog,
            enabled = enabled,
            variant = WandIconButtonVariant.Quiet,
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 40.dp)
                .clip(WandShapes.full)
                .background(WandColors.surface.copy(alpha = 0.92f))
                .border(0.8.dp, WandColors.border.copy(alpha = 0.7f), WandShapes.full)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = WandColors.textPrimary),
                cursorBrush = SolidColor(WandColors.brand),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (trimmed.isEmpty()) return@KeyboardActions
                        focusManager.clearFocus()
                        onSubmit(trimmed)
                    },
                ),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                "描述你想让 AI 做什么…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = WandColors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
        }
        WandIconButton(
            icon = WandIcons.send,
            contentDescription = "用这个提示词新建任务",
            onClick = {
                if (trimmed.isEmpty()) return@WandIconButton
                onSubmit(trimmed)
            },
            enabled = enabled && trimmed.isNotEmpty(),
            variant = WandIconButtonVariant.Accent,
        )
    }
    }
}

// MARK: - 共享微件

/** 首页里的展开箭头：和卡片展开语义一致（收起时箭头转向，不硬切）。 */
@Composable
private fun TreeDisclosureCaret(
    expanded: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    label: String? = null,
) {
    val motionEnabled = !reduceMotionEnabled()
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenNormal()),
        label = "homeDisclosureCaret",
    )
    Row(
        modifier = Modifier
            .clip(WandShapes.sm)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = WandColors.textMuted)
        }
        Icon(
            WandIcons.expand,
            contentDescription = contentDescription,
            tint = WandColors.textMuted,
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer { rotationZ = rotation },
        )
    }
}

/** 多选模式下的勾选框：底与描边跟着选中过渡，避免快速多选时整列硬闪。 */
@Composable
private fun ManageCheck(checked: Boolean) {
    val motionEnabled = !reduceMotionEnabled()
    val fill by animateColorAsState(
        targetValue = if (checked) WandColors.brand else WandColors.surfaceSoft,
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenFast()),
        label = "manageCheckFill",
    )
    val stroke by animateColorAsState(
        targetValue = if (checked) WandColors.brand else WandColors.border.copy(alpha = 0.7f),
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenFast()),
        label = "manageCheckStroke",
    )
    Box(
        modifier = Modifier
            .padding(end = 8.dp)
            .size(18.dp)
            .clip(WandShapes.xs)
            .background(fill)
            .border(1.dp, stroke, WandShapes.xs),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                WandIcons.statusDone,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}
