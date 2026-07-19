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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.wand.app.ui.components.StatusDot
import com.wand.app.ui.components.ToolbarIconButton
import com.wand.app.ui.components.wandStatusPresentation
import com.wand.app.ui.components.WandStatusTone
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
                                start = 12.dp + padding.calculateStartPadding(direction),
                                end = 12.dp + padding.calculateEndPadding(direction),
                                top = 6.dp + padding.calculateTopPadding(),
                                bottom = 16.dp + padding.calculateBottomPadding(),
                            ),
                            verticalArrangement = Arrangement.spacedBy(0.dp),
                        ) {
                            itemsIndexed(
                                items = visibleEntries,
                                key = { _, entry -> entry.key },
                                contentType = { _, entry ->
                                    when (entry) {
                                        is SessionListEntry.Managed -> "managed"
                                        is SessionListEntry.Recoverable -> "recoverable"
                                    }
                                },
                            ) { index, entry ->
                                Column {
                                    if (index > 0) {
                                        SessionListDivider(compact = compactLayout)
                                    }
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

/** 多选底部工具栏：三项操作有稳定的等宽点击区，删除数目始终可见。 */
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
            TextButton(
                modifier = Modifier.weight(1f),
                onClick = onToggleAll,
            ) {
                Text(
                    if (selectedCount == totalCount) "取消全选" else "全选",
                    style = MaterialTheme.typography.labelLarge,
                    color = WandColors.brand,
                )
            }
            TextButton(
                modifier = Modifier.weight(1f),
                onClick = onDelete,
                enabled = selectedCount > 0,
            ) {
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
            TextButton(
                modifier = Modifier.weight(1f),
                onClick = onDone,
            ) {
                Text(
                    "完成",
                    style = MaterialTheme.typography.labelLarge,
                    color = WandColors.brand,
                )
            }
        }
    }
}

/** 可恢复会话以与托管会话同一套紧凑行呈现，只通过上下文标明可恢复来源。 */
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
    SessionListRow(
        title = history.firstUserMessage.ifEmpty { "空会话" },
        provider = history.provider,
        timeLabel = if (restoring) "恢复中" else relativeTimeLabel(history.timestamp),
        status = null,
        contextIcon = WandIcons.history,
        contextLabel = "可恢复",
        path = history.cwd,
        selecting = selecting,
        selected = selected,
        compact = compact,
        enabled = enabled,
        trailingLoading = restoring,
        stateDescription = "聊天模式，${if (restoring) "正在恢复" else "可恢复"}",
        onClick = onClick,
    )
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

/** 托管会话与历史会话共享扁平列表结构，只有活跃和选中态获得表面强调。 */
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
    val mode = if (session.isStructured) "聊天模式" else "终端模式"
    SessionListRow(
        title = sessionListTitle(session),
        provider = session.provider,
        timeLabel = sessionListTimeLabel(session, status),
        status = status,
        contextIcon = null,
        contextLabel = "",
        path = session.cwd.orEmpty(),
        selecting = selecting,
        selected = selected,
        compact = compact,
        enabled = true,
        trailingLoading = false,
        stateDescription = "$mode，${statusPresentation.label}",
        onClick = onClick,
    )
}

private const val AUTO_LOAD_REMAINING_ITEMS = 2

private fun sessionListTitle(session: SessionSnapshot): String {
    return session.displayTitle.ifBlank {
        if (session.isStructured) "聊天会话" else "终端会话"
    }
}

/** 轻分隔线把会话组织成一个连续列表，而不是并列的一叠卡片。 */
@Composable
private fun SessionListDivider(compact: Boolean) {
    HorizontalDivider(
        modifier = Modifier.padding(start = if (compact) 48.dp else 54.dp, end = 4.dp),
        thickness = 0.5.dp,
        color = WandColors.border,
    )
}

/**
 * 会话列表的基础媒体行。
 *
 * 视觉优先级固定为标题、状态与目录、时间。普通行保持透明并通过分隔线分组，
 * 当前会话只用左侧定位线，运行态只保留状态色提示，绝不把状态扩张成整行色块。
 */
@Composable
private fun SessionListRow(
    title: String,
    provider: String?,
    timeLabel: String,
    status: String?,
    contextIcon: ImageVector?,
    contextLabel: String,
    path: String,
    selecting: Boolean,
    selected: Boolean,
    compact: Boolean,
    enabled: Boolean,
    trailingLoading: Boolean,
    stateDescription: String,
    onClick: () -> Unit,
) {
    val statusPresentation = status?.let(::wandStatusPresentation)
    val prominentStatus = statusPresentation?.breathing == true
    val statusTint = statusPresentation?.let { sessionStatusTint(it.tone) } ?: WandColors.textMuted
    val brand = WandColors.brand
    val shape = RoundedCornerShape(if (compact) 12.dp else 14.dp)
    val metadataColor = lerp(WandColors.textSecondary, WandColors.textMuted, 0.34f)
    val pathColor = lerp(WandColors.textSecondary, WandColors.textMuted, 0.66f)
    val labelColor = if (prominentStatus) statusTint else metadataColor

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (enabled || trailingLoading) 1f else 0.48f }
            .semantics {
                this.stateDescription = if (selecting) {
                    "${if (selected) "已选中" else "未选中"}，$stateDescription"
                } else if (selected) {
                    "当前会话，$stateDescription"
                } else {
                    stateDescription
                }
            }
            .heightIn(min = if (compact) 62.dp else 70.dp)
            .clip(shape)
            .drawBehind {
                if (selected) {
                    val railWidth = if (compact) 2.dp.toPx() else 3.dp.toPx()
                    val railInset = if (compact) 12.dp.toPx() else 14.dp.toPx()
                    drawRoundRect(
                        color = brand,
                        topLeft = Offset.Zero.copy(y = railInset),
                        size = Size(railWidth, size.height - railInset * 2),
                        cornerRadius = CornerRadius(railWidth, railWidth),
                    )
                }
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(
                start = if (compact) 8.dp else 10.dp,
                end = if (compact) 8.dp else 10.dp,
                top = if (compact) 8.dp else 9.dp,
                bottom = if (compact) 8.dp else 9.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
    ) {
        if (selecting) {
            SelectionMark(selected = selected, compact = compact)
        } else {
            ProviderMark(provider = provider, compact = compact)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp),
        ) {
            Text(
                title,
                fontSize = if (compact) 14.sp else 15.5.sp,
                lineHeight = if (compact) 19.sp else 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = WandColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                when {
                    status != null -> {
                        StatusDot(status, modifier = Modifier.size(if (compact) 5.dp else 6.dp))
                        Text(
                            text = statusPresentation?.label.orEmpty(),
                            fontSize = if (compact) 10.sp else 11.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = labelColor,
                            maxLines = 1,
                        )
                    }
                    contextIcon != null -> {
                        Icon(
                            imageVector = contextIcon,
                            contentDescription = null,
                            tint = metadataColor,
                            modifier = Modifier.size(if (compact) 11.dp else 12.dp),
                        )
                        Text(
                            text = contextLabel,
                            fontSize = if (compact) 10.sp else 11.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = metadataColor,
                            maxLines = 1,
                        )
                    }
                    contextLabel.isNotBlank() -> {
                        Text(
                            text = contextLabel,
                            fontSize = if (compact) 10.sp else 11.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = metadataColor,
                            maxLines = 1,
                        )
                    }
                }
                if (path.isNotBlank()) {
                    Text(
                        text = normalizedWorkingPath(path),
                        modifier = Modifier.weight(1f),
                        fontSize = if (compact) 9.5.sp else 10.5.sp,
                        lineHeight = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        color = pathColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Column(
            modifier = Modifier.width(if (compact) 42.dp else 48.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Top,
        ) {
            if (trailingLoading) {
                CircularProgressIndicator(
                    color = WandColors.brand,
                    strokeWidth = 1.5.dp,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(if (compact) 10.dp else 11.dp),
                )
            }
            Text(
                text = timeLabel,
                modifier = Modifier.padding(top = 1.dp),
                fontSize = if (compact) 9.5.sp else 10.5.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (prominentStatus) statusTint.copy(alpha = 0.90f) else WandColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/** 左侧标记是低对比度的身份锚点，不再像嵌套的应用图标。 */
@Composable
private fun ProviderMark(
    provider: String?,
    compact: Boolean = false,
) {
    val isCodex = provider == "codex"
    val tint = if (isCodex) WandColors.info else WandColors.brand
    val shape = RoundedCornerShape(if (compact) 8.dp else 10.dp)
    Box(
        modifier = Modifier
            .size(if (compact) 30.dp else 34.dp)
            .clip(shape)
            .background(tint.copy(alpha = if (isWandDarkTheme()) 0.12f else 0.07f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = BrandLogos.painterForProvider(provider),
            contentDescription = null,
            tint = BrandLogos.tintForProvider(provider, tint.copy(alpha = 0.96f)),
            modifier = Modifier.size(if (compact) 18.dp else 20.dp),
        )
    }
}

/** 多选控件保留原有的占位宽度，但用圆形勾选反馈取代厚重方形图标盒。 */
@Composable
private fun SelectionMark(selected: Boolean, compact: Boolean) {
    val slotSize = if (compact) 32.dp else 36.dp
    val controlSize = if (compact) 20.dp else 22.dp
    Box(
        modifier = Modifier.size(slotSize),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(controlSize)
                .clip(CircleShape)
                .background(if (selected) WandColors.brand else Color.Transparent)
                .border(
                    if (selected) 0.dp else 1.4.dp,
                    if (selected) Color.Transparent else WandColors.borderStrong,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = WandIcons.check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(if (compact) 14.dp else 16.dp),
                )
            }
        }
    }
}

/** 保留完整工作目录，仅统一分隔符并移除末尾斜杠。 */
private fun normalizedWorkingPath(path: String): String =
    path.trim().replace('\\', '/').trimEnd('/')

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
 * 待授权 > 思考中 > 原始状态，喂给公共 StatusDot。
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
