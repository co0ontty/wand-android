package com.wand.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.data.HistorySession
import com.wand.app.data.SessionListEntry
import com.wand.app.data.SessionSnapshot
import com.wand.app.ui.parseIsoMillis
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.components.EmptyState
import com.wand.app.ui.components.ErrorState
import com.wand.app.ui.components.LoadingState
import com.wand.app.ui.components.StatusBadge
import com.wand.app.ui.components.StatusDot
import com.wand.app.ui.components.TailMarqueePathText
import com.wand.app.ui.components.ToolbarIconButton
import com.wand.app.ui.components.wandStatusPresentation
import com.wand.app.ui.components.WandStatusTone
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandIconButton
import com.wand.app.ui.components.WandIconButtonVariant
import com.wand.app.ui.theme.AmbientBackground
import com.wand.app.ui.theme.GlassBackdrop
import com.wand.app.ui.theme.GlassStyle
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandGlass
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.glassBackdropSource
import com.wand.app.ui.theme.glassSurface
import com.wand.app.ui.theme.isWandDarkTheme
import com.wand.app.ui.theme.rememberGlassBackdrop
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 统一会话列表：普通、已归档和本机可恢复会话按时间混排。
 * 支持下拉刷新、10s 轮询、滑动删除；全部条目可长按多选并拖动连续选择。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    state: SessionListState,
    modifier: Modifier = Modifier,
    selectedSessionId: String? = null,
    topBarContentHeight: Dp = 64.dp,
    compactLayout: Boolean = false,
    onOpenSession: (SessionSnapshot) -> Unit,
    onNewSession: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWeb: () -> Unit,
    onSwitchServer: () -> Unit,
    onCollapseSidebar: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var isSelecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 同一时间只保留一条侧滑操作，避免多个红色操作区悬在列表里造成状态混乱。
    var revealedEntryKey by remember { mutableStateOf<String?>(null) }
    var dragAnchorId by remember { mutableStateOf<String?>(null) }
    var dragBaseIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 单栏布局每次从详情返回都会重新进入组合；宽屏列表常驻时，新建会话会递增请求值。
    // 两种路径都回到第 0 项，保证用户立即看到按时间排序后的最新会话。
    LaunchedEffect(state.scrollToLatestRequest) {
        state.scrollState.scrollToItem(0)
    }

    fun clearDragSelection() {
        dragAnchorId = null
        dragBaseIds = emptySet()
    }

    fun endSelection() {
        isSelecting = false
        selectedIds = emptySet()
        clearDragSelection()
    }

    val visibleEntries = state.entries
    fun nearestVisibleEntryIndex(pointerY: Float): Int? {
        return state.scrollState.layoutInfo.visibleItemsInfo
            .filter { it.index in visibleEntries.indices }
            .minByOrNull { item ->
                kotlin.math.abs(item.offset + item.size / 2f - pointerY)
            }
            ?.index
    }
    val entryIndexByKey = remember(visibleEntries) {
        visibleEntries.mapIndexed { index, entry -> entry.key to index }.toMap()
    }
    val selectableKeys = visibleEntries.map { it.key }.toSet()
    LaunchedEffect(selectableKeys, isSelecting) {
        if (isSelecting) selectedIds = selectedIds.intersect(selectableKeys)
        if (isSelecting || revealedEntryKey !in selectableKeys) revealedEntryKey = null
    }
    LaunchedEffect(state.scrollState.isScrollInProgress) {
        // 开始纵向浏览时收起操作区，避免删除按钮跟着列表长距离滚动。
        if (state.scrollState.isScrollInProgress) revealedEntryKey = null
    }
    LaunchedEffect(state.scrollState, visibleEntries.size, state.canLoadMore) {
        snapshotFlow {
            val lastVisibleIndex = state.scrollState.layoutInfo.visibleItemsInfo
                .maxOfOrNull { it.index } ?: -1
            state.scrollState.isScrollInProgress &&
                state.canLoadMore &&
                lastVisibleIndex >= visibleEntries.lastIndex - AUTO_LOAD_REMAINING_ITEMS
        }.collect { shouldLoadMore ->
            if (shouldLoadMore) state.loadMore()
        }
    }
    LaunchedEffect(state.loadError, visibleEntries.isNotEmpty()) {
        val message = state.loadError ?: return@LaunchedEffect
        if (visibleEntries.isNotEmpty()) {
            snackbarHostState.showSnackbar(message)
            state.clearError(message)
        }
    }

    // 液态玻璃：列表是 backdrop 捕获源，顶栏/多选栏悬浮其上采样模糊。
    val glassBackdrop = rememberGlassBackdrop()
    // 顶栏玻璃去掉厚重投影：全幅栏的大软影只在底缘可见，糊成一道脏脏的「接缝」。
    // 改用一道发丝分隔线收边，顶栏读作干净的玻璃表面而非浮在内容上的色块。
    val barGlass = WandGlass.regular.copy(refractionHeight = 0.dp, shadowElevation = 0.dp)
    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        topBar = {
            SessionListTopBar(
                backdrop = glassBackdrop,
                barGlass = barGlass,
                menuOpen = menuOpen,
                onMenuOpenChange = { menuOpen = it },
                isSelecting = isSelecting,
                selectedCount = selectedIds.size,
                entryCount = visibleEntries.size,
                contentHeight = topBarContentHeight,
                compact = compactLayout,
                onNewSession = onNewSession,
                onExitSelection = { endSelection() },
                onOpenSettings = {
                    menuOpen = false
                    scope.launch {
                        delay(170)
                        onOpenSettings()
                    }
                },
                onOpenWeb = onOpenWeb,
                onSwitchServer = onSwitchServer,
                onCollapseSidebar = onCollapseSidebar,
            )
        },
        bottomBar = {
            if (isSelecting) {
                SelectionBar(
                    backdrop = glassBackdrop,
                    selectedCount = selectedIds.size,
                    totalCount = selectableKeys.size,
                    onToggleAll = {
                        selectedIds = if (selectableKeys.isNotEmpty() && selectedIds.containsAll(selectableKeys)) {
                            emptySet()
                        } else {
                            selectableKeys
                        }
                    },
                    onDelete = {
                        val targets = visibleEntries.filter { it.key in selectedIds }
                        endSelection()
                        scope.launch { state.delete(targets) }
                    },
                    onDone = { endSelection() },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // 捕获层：环境渐变背景 + 列表，整体作为玻璃顶栏/多选栏的采样源。
        // 全幅布局，innerPadding 移到各 LazyColumn 的 contentPadding —— 卡片从玻璃栏下滚过。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .glassBackdropSource(glassBackdrop),
        ) {
            AmbientBackground(Modifier.fillMaxSize())
            when {
                state.loading && visibleEntries.isEmpty() -> {
                    LoadingState(
                        modifier = Modifier.padding(padding),
                        text = "正在加载会话…",
                    )
                }
                state.loadError != null && visibleEntries.isEmpty() -> {
                    ErrorState(
                        message = state.loadError ?: "加载失败",
                        onRetry = { scope.launch { state.load() } },
                        modifier = Modifier.padding(padding),
                    )
                }
                visibleEntries.isEmpty() -> {
                    EmptyState(
                        icon = WandIcons.sparkle,
                        title = "还没有会话",
                        subtitle = "新建一个会话，开始与 AI 协作",
                        actionText = "创建第一个会话",
                        onAction = onNewSession,
                        modifier = Modifier.padding(padding),
                    )
                }
                else -> {
                    val pullState = rememberPullToRefreshState()
                    val direction = LocalLayoutDirection.current
                    PullToRefreshBox(
                        isRefreshing = refreshing,
                        state = pullState,
                        onRefresh = {
                            scope.launch {
                                refreshing = true
                                state.load(silent = true)
                                refreshing = false
                            }
                        },
                        // 自定义指示器：列表全幅垫到玻璃顶栏下后，spinner 要从顶栏下方探出。
                        indicator = {
                            PullToRefreshDefaults.Indicator(
                                state = pullState,
                                isRefreshing = refreshing,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = padding.calculateTopPadding()),
                            )
                        },
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = state.scrollState,
                            contentPadding = PaddingValues(
                                start = 16.dp + padding.calculateStartPadding(direction),
                                end = 16.dp + padding.calculateEndPadding(direction),
                                top = 12.dp + padding.calculateTopPadding(),
                                bottom = 24.dp + padding.calculateBottomPadding(),
                            ),
                            verticalArrangement = Arrangement.spacedBy(if (compactLayout) 6.dp else 8.dp),
                        ) {
                            items(
                                items = visibleEntries,
                                key = { it.key },
                                contentType = { entry ->
                                    when (entry) {
                                        is SessionListEntry.Managed -> "managed"
                                        is SessionListEntry.Recoverable -> "recoverable"
                                    }
                                },
                            ) { entry ->
                                val rowModifier = Modifier
                                    .pointerInput(entry.key, entryIndexByKey) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                if (dragAnchorId == null) {
                                                    isSelecting = true
                                                    dragAnchorId = entry.key
                                                    dragBaseIds = selectedIds
                                                    selectedIds = selectedIds + entry.key
                                                }
                                            },
                                            onDrag = { change, _ ->
                                                val anchor = dragAnchorId
                                                    ?: return@detectDragGesturesAfterLongPress
                                                val anchorIndex = entryIndexByKey[anchor]
                                                val sourceIndex = entryIndexByKey[entry.key]
                                                val sourceOffset = sourceIndex?.let { index ->
                                                    state.scrollState.layoutInfo.visibleItemsInfo
                                                        .firstOrNull { it.index == index }
                                                        ?.offset
                                                }
                                                val targetIndex = sourceOffset?.let { offset ->
                                                    nearestVisibleEntryIndex(offset + change.position.y)
                                                }
                                                if (anchorIndex != null && targetIndex != null) {
                                                    selectedIds = dragBaseIds +
                                                        visibleEntries.subList(
                                                            minOf(anchorIndex, targetIndex),
                                                            maxOf(anchorIndex, targetIndex) + 1,
                                                        ).map { it.key }
                                                }
                                            },
                                            onDragEnd = ::clearDragSelection,
                                            onDragCancel = ::clearDragSelection,
                                        )
                                    }
                                when (entry) {
                                    is SessionListEntry.Managed -> {
                                        val session = entry.session
                                        if (isSelecting) {
                                            Box(modifier = rowModifier) {
                                                SessionCard(
                                                    session = session,
                                                    selecting = true,
                                                    selected = entry.key in selectedIds,
                                                    compact = compactLayout,
                                                    onClick = {
                                                        selectedIds = if (entry.key in selectedIds) {
                                                            selectedIds - entry.key
                                                        } else {
                                                            selectedIds + entry.key
                                                        }
                                                    },
                                                )
                                            }
                                        } else {
                                            SwipeRevealRow(
                                                modifier = rowModifier,
                                                expanded = revealedEntryKey == entry.key,
                                                onExpandedChange = { expanded ->
                                                    revealedEntryKey = when {
                                                        expanded -> entry.key
                                                        revealedEntryKey == entry.key -> null
                                                        else -> revealedEntryKey
                                                    }
                                                },
                                                onSwipeStart = {
                                                    if (revealedEntryKey != entry.key) revealedEntryKey = null
                                                },
                                                onDelete = {
                                                    scope.launch { state.delete(entry) }
                                                },
                                            ) { revealed, closeReveal ->
                                                SessionCard(
                                                    session = session,
                                                    selecting = false,
                                                    selected = session.id == selectedSessionId,
                                                    compact = compactLayout,
                                                    onClick = {
                                                        if (revealed || revealedEntryKey != null) {
                                                            revealedEntryKey = null
                                                            closeReveal()
                                                        } else {
                                                            onOpenSession(session)
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                    is SessionListEntry.Recoverable -> {
                                        val session = entry.history
                                        if (isSelecting) {
                                            Box(modifier = rowModifier) {
                                                HistorySessionCard(
                                                    history = session,
                                                    enabled = true,
                                                    selecting = true,
                                                    selected = entry.key in selectedIds,
                                                    compact = compactLayout,
                                                    restoring = false,
                                                    onClick = {
                                                        selectedIds = if (entry.key in selectedIds) {
                                                            selectedIds - entry.key
                                                        } else {
                                                            selectedIds + entry.key
                                                        }
                                                    },
                                                )
                                            }
                                        } else {
                                            SwipeRevealRow(
                                                modifier = rowModifier,
                                                expanded = revealedEntryKey == entry.key,
                                                onExpandedChange = { expanded ->
                                                    revealedEntryKey = when {
                                                        expanded -> entry.key
                                                        revealedEntryKey == entry.key -> null
                                                        else -> revealedEntryKey
                                                    }
                                                },
                                                onSwipeStart = {
                                                    if (revealedEntryKey != entry.key) revealedEntryKey = null
                                                },
                                                onDelete = {
                                                    scope.launch { state.delete(entry) }
                                                },
                                            ) { revealed, closeReveal ->
                                                HistorySessionCard(
                                                    history = session,
                                                    enabled = !state.isRestoringHistory,
                                                    selecting = false,
                                                    selected = false,
                                                    compact = compactLayout,
                                                    restoring = state.isRestoring(session),
                                                    onClick = {
                                                        if (revealed || revealedEntryKey != null) {
                                                            revealedEntryKey = null
                                                            closeReveal()
                                                        } else if (!state.isRestoringHistory) {
                                                            scope.launch {
                                                                state.restore(session)?.let(onOpenSession)
                                                            }
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            if (state.loadingMore) {
                                item(key = "session-list-loading-more", contentType = "loading-more") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = WandColors.brand,
                                        )
                                        Spacer(Modifier.size(8.dp))
                                        Text(
                                            "正在加载更多会话…",
                                            fontSize = 12.sp,
                                            color = WandColors.textSecondary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionListTopBar(
    backdrop: GlassBackdrop,
    barGlass: GlassStyle,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    isSelecting: Boolean,
    selectedCount: Int,
    entryCount: Int,
    contentHeight: Dp,
    compact: Boolean,
    onNewSession: () -> Unit,
    onExitSelection: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWeb: () -> Unit,
    onSwitchServer: () -> Unit,
    onCollapseSidebar: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(backdrop, RoundedCornerShape(0.dp), barGlass, edgeToEdge = true),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .heightIn(min = contentHeight)
                .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isSelecting) {
                ToolbarIconButton(
                    icon = WandIcons.close,
                    contentDescription = "退出多选",
                    tint = WandColors.textSecondary,
                    onClick = onExitSelection,
                )
                Text(
                    "已选择 $selectedCount 项",
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.titleLarge,
                    color = WandColors.textPrimary,
                )
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp, end = 8.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "会话",
                        style = if (compact) {
                            MaterialTheme.typography.titleLarge
                        } else {
                            MaterialTheme.typography.headlineSmall
                        },
                        color = WandColors.textPrimary,
                    )
                    Text(
                        "$entryCount 个会话",
                        style = MaterialTheme.typography.labelSmall,
                        color = WandColors.textMuted,
                    )
                }
                if (onCollapseSidebar != null) {
                    ToolbarIconButton(
                        icon = WandIcons.chevronRight,
                        contentDescription = "收起会话栏",
                        onClick = onCollapseSidebar,
                        modifier = Modifier.graphicsLayer { scaleX = -1f },
                    )
                }
                TopBarPrimaryAction(onClick = onNewSession)
                Box {
                    ToolbarIconButton(
                        icon = WandIcons.more,
                        contentDescription = "更多选项",
                        onClick = { onMenuOpenChange(true) },
                        tint = WandColors.textSecondary,
                    )
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { onMenuOpenChange(false) },
                    ) {
                        DropdownMenuItem(
                            text = { Text("设置", color = WandColors.textPrimary) },
                            leadingIcon = {
                                Icon(WandIcons.settings, contentDescription = null, tint = WandColors.textSecondary)
                            },
                            onClick = { onMenuOpenChange(false); onOpenSettings() },
                        )
                        DropdownMenuItem(
                            text = { Text("打开网页版", color = WandColors.textPrimary) },
                            leadingIcon = {
                                Icon(WandIcons.web, contentDescription = null, tint = WandColors.textSecondary)
                            },
                            onClick = { onMenuOpenChange(false); onOpenWeb() },
                        )
                        DropdownMenuItem(
                            text = { Text("切换服务器", color = WandColors.textPrimary) },
                            leadingIcon = {
                                Icon(WandIcons.swapServer, contentDescription = null, tint = WandColors.textSecondary)
                            },
                            onClick = { onMenuOpenChange(false); onSwitchServer() },
                        )
                    }
                }
            }
        }
    }
}

/** 顶栏主动作：48dp 触控区内放 36dp 品牌弱底，突出新增又不抢标题层级。 */
@Composable
private fun TopBarPrimaryAction(onClick: () -> Unit) {
    WandIconButton(
        icon = WandIcons.add,
        contentDescription = "新建会话",
        onClick = onClick,
        variant = WandIconButtonVariant.Accent,
    )
}

/** 多选底部工具栏：[全选] [删除 N] [完成]（对齐 iOS selectionBar）。 */
@Composable
private fun SelectionBar(
    backdrop: GlassBackdrop,
    selectedCount: Int,
    totalCount: Int,
    onToggleAll: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(
                backdrop,
                RoundedCornerShape(0.dp),
                WandGlass.regular.copy(refractionHeight = 0.dp),
                edgeToEdge = true,
            )
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(thickness = 1.dp, color = WandColors.border)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            TextButton(onClick = onToggleAll) {
                Text(
                    if (selectedCount == totalCount) "取消全选" else "全选",
                    style = MaterialTheme.typography.labelLarge,
                    color = WandColors.brand,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onDelete, enabled = selectedCount > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        WandIcons.delete,
                        contentDescription = null,
                        tint = if (selectedCount > 0) WandColors.danger else WandColors.textMuted,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        "删除 $selectedCount",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selectedCount > 0) WandColors.danger else WandColors.textMuted,
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onDone) {
                Text(
                    "完成",
                    style = MaterialTheme.typography.labelLarge,
                    color = WandColors.brand,
                )
            }
        }
    }
}

/**
 * 可恢复会话卡：左列为 provider Logo + 相对时间，右列为消息标题 + 一行上下文。
 * 时间与右侧状态共用同一基线；上下文不再重复具体 CLI 名称。
 */
@Composable
private fun HistorySessionCard(
    history: HistorySession,
    enabled: Boolean,
    selecting: Boolean,
    selected: Boolean,
    compact: Boolean,
    restoring: Boolean,
    onClick: () -> Unit,
) {
    val isCodex = history.provider == "codex"
    val tint = if (isCodex) WandColors.info else WandColors.brand
    val shape = RoundedCornerShape(if (compact) 14.dp else 16.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (enabled || restoring) 1f else 0.48f },
    ) {
        WandCard(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    stateDescription = "聊天模式，${if (restoring) "正在恢复" else "可恢复"}"
                },
            onClick = if (enabled) onClick else null,
            selected = selected,
            shape = shape,
            containerColor = if (selected) {
                lerp(WandColors.surface, WandColors.brand, 0.12f)
            } else {
                WandColors.surface.copy(alpha = 0.88f)
            },
            contentPadding = PaddingValues(
                horizontal = if (compact) 11.dp else 14.dp,
                vertical = if (compact) 10.dp else 12.dp,
            ),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 7.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    SessionLeadingSlot(compact = compact) {
                        if (selecting) {
                            SelectionMark(selected = selected, compact = compact)
                        } else {
                            ProviderMark(
                                provider = history.provider,
                                compact = compact,
                            )
                        }
                    }
                    Text(
                        history.firstUserMessage.ifEmpty { "空会话" },
                        modifier = Modifier.weight(1f),
                        fontSize = if (compact) 14.5.sp else 16.sp,
                        lineHeight = if (compact) 19.sp else 21.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = WandColors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val relative = relativeTimeLabel(history.timestamp)
                    SessionLeadingSlot(compact = compact) {
                        if (relative.isNotEmpty() || restoring) {
                            ConversationTimeLabel(
                                text = if (restoring) "恢复中" else relative,
                                compact = compact,
                                loading = restoring,
                                tint = if (restoring) tint else WandColors.textMuted,
                            )
                        }
                    }
                    ConversationContextLine(
                        icon = WandIcons.history,
                        label = "可恢复",
                        path = history.cwd,
                        compact = compact,
                        tint = tint,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** ISO8601 时间 → 相对时间（单单位：刚刚 / N分钟 / N小时 / N天），解析失败返回空。 */
private fun relativeTimeLabel(timestamp: String?): String {
    val millis = parseIsoMillis(timestamp) ?: return ""
    return when (val relative = singleUnitDurationLabel(System.currentTimeMillis() - millis)) {
        "刚刚" -> relative
        else -> "${relative}前"
    }
}

/**
 * 单位相对时间文案（列表时间只显示一个单位）。
 * - 刚刚 / N分钟 / N小时 / N天
 * 会话持续时长单独在 sessionDurationLabel 中处理，避免出现“持续 刚刚”。
 */
private fun singleUnitDurationLabel(deltaMillis: Long): String {
    val minutes = (deltaMillis.coerceAtLeast(0L) / 60_000L)
    if (minutes < 1) return "刚刚"
    val hours = minutes / 60
    if (hours < 1) return "${minutes}分钟"
    val days = hours / 24
    if (days < 1) return "${hours}小时"
    return "${days}天"
}

/**
 * 滑动揭示删除按钮（对齐 iOS swipeActions(allowsFullSwipe: false)）：
 * 左滑露出右侧红色「删除」按钮，点按钮才真正删除；不会一滑到底直接删。
 * content 收到 revealed/closeReveal，便于揭示态下点卡片先收起而非进入会话。
 */
@Composable
private fun SwipeRevealRow(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
    onSwipeStart: () -> Unit = {},
    content: @Composable (revealed: Boolean, closeReveal: () -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // 操作键与卡片之间、操作键与列表末端之间各留 8dp，边界靠留白而非重描边建立。
    val buttonWidth = 56.dp
    val gap = 8.dp
    val revealWidth = buttonWidth + gap * 2
    val revealPx = with(density) { revealWidth.toPx() }
    val offsetX = remember { Animatable(0f) }
    val revealed = offsetX.value <= -revealPx + 1f
    val snapSpec = WandMotion.springSpec<Float>()

    LaunchedEffect(expanded, revealPx) {
        val target = if (expanded) -revealPx else 0f
        if (kotlin.math.abs(offsetX.value - target) > 1f) {
            offsetX.animateTo(target, snapSpec)
        }
    }

    fun settle(shouldReveal: Boolean) {
        if (shouldReveal != expanded) {
            onExpandedChange(shouldReveal)
        } else {
            scope.launch { offsetX.animateTo(if (shouldReveal) -revealPx else 0f, snapSpec) }
        }
    }

    val closeReveal: () -> Unit = { settle(false) }

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = WandMotion.springSpec(),
        label = "deletePress",
    )
    // 暗色主题的 danger token 偏亮；压暗按钮底可维持白字对比，同时仍清楚表达危险动作。
    val actionContainerColor = if (isWandDarkTheme()) {
        lerp(WandColors.danger, Color.Black, 0.28f)
    } else {
        WandColors.danger
    }

    Box(
        modifier = modifier
            .semantics {
                // TalkBack 不依赖精细的横向手势，也能从“操作”菜单执行同一动作。
                customActions = listOf(
                    CustomAccessibilityAction("删除会话") {
                        onDelete()
                        true
                    },
                )
            },
    ) {
        // 操作键和卡片以相同行程从右向左移动，始终保持 8dp 空隙；不会再压在半透明卡片之上。
        if (offsetX.value < -1f) {
            Box(
                // 只裁剪滑入的操作层，保留卡片原有的 1dp 轻投影。
                modifier = Modifier
                    .matchParentSize()
                    .clipToBounds(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = gap, vertical = 4.dp)
                        .fillMaxHeight()
                        .width(buttonWidth)
                        .graphicsLayer {
                            val progress = (-offsetX.value / revealPx).coerceIn(0f, 1f)
                            // 闭合时整个操作键位于裁剪区外；随手指进入，和卡片边缘保持稳定间距。
                            translationX = revealPx + offsetX.value
                            val enter = 0.92f + 0.08f * progress
                            val s = enter * pressScale
                            scaleX = s
                            scaleY = s
                            alpha = ((progress - 0.08f) / 0.92f).coerceIn(0f, 1f)
                        }
                        .clip(WandShapes.md)
                        .background(actionContainerColor)
                        .border(0.8.dp, Color.White.copy(alpha = 0.20f), WandShapes.md)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = LocalIndication.current,
                            enabled = revealed,
                            role = Role.Button,
                        ) {
                            closeReveal()
                            onDelete()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(
                            WandIcons.delete,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            "删除",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(offsetX.value.roundToInt(), 0)
                }
                .pointerInput(expanded, revealPx) {
                    detectHorizontalDragGestures(
                        onDragStart = { onSwipeStart() },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val next = (offsetX.value + dragAmount).coerceIn(-revealPx, 0f)
                            scope.launch { offsetX.snapTo(next) }
                        },
                        onDragEnd = {
                            settle(offsetX.value < -revealPx / 2)
                        },
                        onDragCancel = {
                            settle(offsetX.value < -revealPx / 2)
                        },
                    )
                },
        ) {
            content(revealed, closeReveal)
        }
    }
}

/**
 * 单条会话卡片：左列为 provider Logo + 时间，右列为标题 + 状态上下文。
 * 时间与状态共用同一基线；上下文不再重复具体 CLI 名称。
 */
@Composable
private fun SessionCard(
    session: SessionSnapshot,
    selecting: Boolean,
    selected: Boolean,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val status = derivedStatus(session)
    val statusPresentation = wandStatusPresentation(status)
    val prominentStatus = statusPresentation.breathing
    val statusTint = sessionStatusTint(statusPresentation.tone)
    val shape = RoundedCornerShape(if (compact) 14.dp else 16.dp)
    Box(
        modifier = Modifier.fillMaxWidth(),
    ) {
        WandCard(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    val mode = if (session.isStructured) "聊天模式" else "终端模式"
                    stateDescription = "$mode，${sessionStatusLabel(status)}"
                },
            onClick = onClick,
            selected = selected,
            shape = shape,
            containerColor = if (selected) {
                lerp(WandColors.surface, WandColors.brand, 0.12f)
            } else if (prominentStatus) {
                lerp(WandColors.surface, statusTint, 0.065f)
            } else {
                WandColors.surface.copy(alpha = 0.88f)
            },
            contentPadding = PaddingValues(
                horizontal = if (compact) 11.dp else 14.dp,
                vertical = if (compact) 10.dp else 12.dp,
            ),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 7.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    SessionLeadingSlot(compact = compact) {
                        if (selecting) {
                            SelectionMark(selected = selected, compact = compact)
                        } else {
                            ProviderMark(
                                provider = session.provider,
                                compact = compact,
                            )
                        }
                    }
                    Text(
                        sessionListTitle(session),
                        modifier = Modifier.weight(1f),
                        fontSize = if (compact) 14.5.sp else 16.sp,
                        lineHeight = if (compact) 19.sp else 21.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = WandColors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val timeLabel = sessionListTimeLabel(session, status)
                    SessionLeadingSlot(compact = compact) {
                        if (timeLabel.isNotEmpty()) {
                            ConversationTimeLabel(text = timeLabel, compact = compact)
                        }
                    }
                    ConversationContextLine(
                        status = status,
                        label = "",
                        path = session.cwd.orEmpty(),
                        compact = compact,
                        prominentStatus = prominentStatus,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (prominentStatus && !selecting) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(if (compact) 3.dp else 4.dp)
                        .padding(vertical = if (compact) 9.dp else 10.dp)
                        .clip(WandShapes.full)
                        .background(statusTint.copy(alpha = 0.90f)),
                )
            }
        }
    }
}

private const val AUTO_LOAD_REMAINING_ITEMS = 2

private fun sessionListTitle(session: SessionSnapshot): String {
    return session.displayTitle.ifBlank {
        if (session.isStructured) "聊天会话" else "终端会话"
    }
}

/** 固定列表左列宽度，让 Logo、时间以及右侧标题/状态在所有卡片中稳定对齐。 */
@Composable
private fun SessionLeadingSlot(
    compact: Boolean,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.width(if (compact) 46.dp else 50.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** 左侧助手标识只保留品牌 Logo；会话模式不再占用列表视觉空间。 */
@Composable
private fun ProviderMark(
    provider: String?,
    compact: Boolean = false,
) {
    val isCodex = provider == "codex"
    val tint = if (isCodex) WandColors.info else WandColors.brand
    Box(
        modifier = Modifier.size(if (compact) 30.dp else 34.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = BrandLogos.painterForProvider(provider),
            // Logo 仅作视觉识别；会话标题与状态已覆盖列表项的主要可访问语义。
            contentDescription = null,
            tint = BrandLogos.tintForProvider(provider, tint.copy(alpha = 0.94f)),
            modifier = Modifier.size(if (compact) 21.dp else 23.dp),
        )
    }
}

/** 多选图标复用 Logo 的固定槽位，进入选择模式时标题不会左右跳动。 */
@Composable
private fun SelectionMark(selected: Boolean, compact: Boolean) {
    Box(
        modifier = Modifier.size(if (compact) 30.dp else 34.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (selected) WandIcons.statusDone else WandIcons.statusPending,
            contentDescription = if (selected) "已选中" else "未选中",
            tint = if (selected) WandColors.brand else WandColors.textSecondary,
            modifier = Modifier.size(if (compact) 20.dp else 21.dp),
        )
    }
}

/** 无底色、无重复时钟图标的时间；恢复时原位切成进度反馈。 */
@Composable
private fun ConversationTimeLabel(
    text: String,
    compact: Boolean,
    loading: Boolean = false,
    tint: Color = lerp(WandColors.textSecondary, WandColors.textMuted, 0.46f),
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = tint,
                strokeWidth = 1.5.dp,
                modifier = Modifier.size(if (compact) 11.dp else 12.dp),
            )
        }
        Text(
            text,
            fontSize = if (compact) 10.sp else 11.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Medium,
            color = tint,
            maxLines = 1,
        )
    }
}

/**
 * 统一第二层信息：运行状态用圆点 + 文字，历史记录用场景图标；路径占用剩余空间。
 * 只有一条视觉基线，不为每个元数据套胶囊，弱文字也保持浅色模式可读对比度。
 */
@Composable
private fun ConversationContextLine(
    icon: ImageVector? = null,
    status: String? = null,
    label: String,
    path: String,
    compact: Boolean,
    prominentStatus: Boolean = false,
    tint: Color = WandColors.textSecondary,
    modifier: Modifier = Modifier,
) {
    val metadataColor = lerp(WandColors.textSecondary, WandColors.textMuted, 0.42f)
    val pathColor = lerp(WandColors.textSecondary, WandColors.textMuted, 0.70f)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            status != null && prominentStatus -> StatusBadge(status = status)
            status != null -> StatusDot(
                status = status,
                modifier = Modifier.size(if (compact) 5.dp else 6.dp),
            )
            icon != null -> Icon(
                icon,
                contentDescription = null,
                tint = tint.copy(alpha = 0.92f),
                modifier = Modifier.size(if (compact) 12.dp else 13.dp),
            )
        }
        if (status != null || icon != null) Spacer(modifier = Modifier.width(5.dp))
        if (label.isNotBlank()) {
            Text(
                label,
                fontSize = if (compact) 10.5.sp else 11.5.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
                color = metadataColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (path.isNotBlank()) {
            if (label.isNotBlank()) {
                Spacer(modifier = Modifier.width(7.dp))
                Box(
                    modifier = Modifier
                        .size(3.dp)
                        .clip(CircleShape)
                        .background(pathColor.copy(alpha = 0.52f)),
                )
                Spacer(modifier = Modifier.width(7.dp))
            } else if (status != null || icon != null) {
                Spacer(modifier = Modifier.width(7.dp))
            }
            TailMarqueePathText(
                path = normalizedWorkingPath(path),
                modifier = Modifier.weight(1f),
                fontSize = if (compact) 9.8.sp else 10.8.sp,
                fontFamily = FontFamily.Monospace,
                color = pathColor,
                fallback = "",
                initialDelayMillis = 2_500L,
                pauseMillis = 3_500L,
                staggerWindowMillis = 1_200L,
                velocity = 28.dp,
                repeatTailReveal = true,
            )
        }
    }
}

/** 保留完整工作目录，仅统一分隔符并移除末尾斜杠。 */
private fun normalizedWorkingPath(path: String): String =
    path.trim().replace('\\', '/').trimEnd('/')

/** 派生状态的可见文字，同时供 TalkBack stateDescription 使用。 */
private fun sessionStatusLabel(status: String): String = wandStatusPresentation(status).label

@Composable
private fun sessionStatusTint(tone: WandStatusTone): Color = when (tone) {
    WandStatusTone.Success -> WandColors.success
    WandStatusTone.Permission -> WandColors.permission
    WandStatusTone.Warning -> WandColors.warning
    WandStatusTone.Danger -> WandColors.danger
    WandStatusTone.Neutral -> WandColors.textMuted
}

/**
 * 服务端 status（running/idle/exited/failed/stopped）+ 客户端派生态折算：
 * 待授权 > 思考中 > 原始状态，喂给公共 StatusDot/StatusBadge。
 */
private fun derivedStatus(session: SessionSnapshot): String = when {
    session.hasPendingPermission -> "permission"
    session.isResponding -> "thinking"
    else -> session.status ?: "idle"
}

/** 会话从启动到当前（或结束）的持续时间。列表每 10 秒刷新，运行态会自然更新。 */
private fun sessionDurationLabel(session: SessionSnapshot): String {
    val started = parseIsoMillis(session.startedAt) ?: return ""
    val ended = parseIsoMillis(session.endedAt)
    val deltaMillis = ((ended ?: System.currentTimeMillis()) - started).coerceAtLeast(0L)
    val minutes = deltaMillis / 60_000L
    if (minutes < 1) return "不足1分钟"
    val hours = minutes / 60
    if (hours < 1) return "${minutes}分钟"
    val days = hours / 24
    if (days < 1) return "${hours}小时"
    return "${days}天"
}

/**
 * 列表左下时间服务于“快速找到那条会话”：活跃会话显示已运行多久，
 * 已结束会话显示最近时间。详情页仍可展示精确起止时间。
 */
private fun sessionListTimeLabel(session: SessionSnapshot, status: String): String {
    return when (status.trim().lowercase()) {
        "running", "thinking", "waiting-input", "waiting_input", "permission", "reconnecting" ->
            sessionDurationLabel(session)
        else -> relativeTimeLabel(session.endedAt ?: session.startedAt)
    }
}
