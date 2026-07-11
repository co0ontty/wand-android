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
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.data.HistorySession
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.WandApi
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.components.EmptyState
import com.wand.app.ui.components.ErrorState
import com.wand.app.ui.components.LoadingState
import com.wand.app.ui.components.StatusDot
import com.wand.app.ui.components.ToolbarIconButton
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.AmbientBackground
import com.wand.app.ui.theme.GlassBackdrop
import com.wand.app.ui.theme.GlassStyle
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandGlass
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.glassBackdropSource
import com.wand.app.ui.theme.glassSurface
import com.wand.app.ui.theme.rememberGlassBackdrop
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

sealed interface SessionListEntry {
    val key: String
    val sortTimestamp: Long

    data class Managed(val session: SessionSnapshot) : SessionListEntry {
        override val key: String = "session-${session.id}"
        override val sortTimestamp: Long = parseIsoMillis(session.startedAt)
    }

    data class Recoverable(val session: HistorySession) : SessionListEntry {
        // provider 必须进入 key：Claude / Codex 的历史 ID 分属不同接口，不能在多选时混淆。
        override val key: String = "recoverable-${session.apiProvider}-${session.id}"
        override val sortTimestamp: Long = session.mtimeMs?.toLong() ?: parseIsoMillis(session.timestamp)
    }
}

private fun parseIsoMillis(value: String?): Long =
    value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrDefault(0L) } ?: 0L

/**
 * 会话列表状态。提升到导航栈外层持有（remember 于 WandApp），
 * 进聊天后返回不重新加载。对称 iOS SessionListView 的 @State。
 */
class SessionListState(val api: WandApi) {
    var sessions by mutableStateOf<List<SessionSnapshot>>(emptyList())
    var historySessions by mutableStateOf<List<HistorySession>>(emptyList())
    var loading by mutableStateOf(true)
    var loadError by mutableStateOf<String?>(null)
    /** 状态与列表同生命周期，进入详情再返回时保留阅读位置。 */
    val scrollState = LazyListState()

    val visibleSessions: List<SessionSnapshot>
        get() = sessions

    /** 本机可恢复会话：过滤空记录 / 已被 wand 纳管的记录。 */
    val visibleHistorySessions: List<HistorySession>
        get() {
            val managedIds = sessions.mapNotNull { it.claudeSessionId }.toSet()
            return historySessions
                .filter {
                    (it.hasConversation ?: true) &&
                        !(it.managedByWand ?: false) &&
                        it.claudeSessionId !in managedIds
                }
                .sortedByDescending { it.mtimeMs ?: 0.0 }
        }

    val visibleEntries: List<SessionListEntry>
        get() = buildList {
            visibleSessions.forEach { add(SessionListEntry.Managed(it)) }
            visibleHistorySessions.forEach { add(SessionListEntry.Recoverable(it)) }
        }.sortedByDescending { it.sortTimestamp }

    suspend fun load(silent: Boolean = false) {
        if (!silent) loading = true
        try {
            coroutineScope {
                val active = async { api.listSessions() }
                // 历史扫描端点单独容错：失败不拖垮会话列表本身。
                val claude = async { runCatching { api.listClaudeHistory() }.getOrDefault(emptyList()) }
                val codex = async { runCatching { api.listCodexHistory() }.getOrDefault(emptyList()) }
                sessions = active.await()
                historySessions = claude.await() + codex.await()
            }
            loadError = null
        } catch (e: Exception) {
            if (!silent || sessions.isEmpty()) {
                loadError = e.message ?: "加载失败"
            }
        }
        loading = false
    }

    fun prepend(snapshot: SessionSnapshot) {
        sessions = listOf(snapshot) + sessions.filter { it.id != snapshot.id }
    }

    fun removeLocally(sessionId: String) {
        sessions = sessions.filter { it.id != sessionId }
    }

    fun removeHistoryLocally(history: HistorySession) {
        historySessions = historySessions.filter {
            it.id != history.id || it.apiProvider != history.apiProvider
        }
    }

    fun removeHistoryLocally(targets: Collection<HistorySession>) {
        val keys = targets.mapTo(mutableSetOf()) { it.apiProvider to it.id }
        historySessions = historySessions.filter { (it.apiProvider to it.id) !in keys }
    }
}

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
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var isSelecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var restoringHistoryKey by remember { mutableStateOf<String?>(null) }
    // 多选拖拽：行 id → 窗口坐标 bounds；拖动经过的行连续加入选择（对齐 iOS 范围选择）。
    val rowBounds = remember { mutableMapOf<String, Rect>() }
    var dragAnchorId by remember { mutableStateOf<String?>(null) }
    var dragBaseIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun endSelection() {
        isSelecting = false
        selectedIds = emptySet()
        dragAnchorId = null
        dragBaseIds = emptySet()
    }

    val visibleEntries = state.visibleEntries
    val selectableKeys = visibleEntries.map { it.key }.toSet()
    LaunchedEffect(selectableKeys, isSelecting) {
        if (isSelecting) selectedIds = selectedIds.intersect(selectableKeys)
    }
    LaunchedEffect(state.loadError, visibleEntries.isNotEmpty()) {
        val message = state.loadError ?: return@LaunchedEffect
        if (visibleEntries.isNotEmpty()) {
            snackbarHostState.showSnackbar(message)
            if (state.loadError == message) state.loadError = null
        }
    }

    val context = LocalContext.current
    LaunchedEffect(Unit) {
        state.load(silent = state.sessions.isNotEmpty())
        while (true) {
            delay(10_000)
            state.load(silent = true)
        }
    }
    // 会话列表变化时同步长按图标快捷项（对称 iOS updateRecentSessionShortcuts）。
    LaunchedEffect(state.sessions) {
        com.wand.app.WandShortcuts.update(context, state.sessions)
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
                        val managed = targets.filterIsInstance<SessionListEntry.Managed>().map { it.session }
                        val history = targets.filterIsInstance<SessionListEntry.Recoverable>().map { it.session }
                        managed.forEach { state.removeLocally(it.id) }
                        state.removeHistoryLocally(history)
                        endSelection()
                        scope.launch {
                            var failed = false
                            managed.forEach { session ->
                                if (runCatching { state.api.deleteSession(session.id) }.isFailure) failed = true
                            }
                            history.groupBy { it.apiProvider }.forEach { (provider, sessions) ->
                                if (runCatching {
                                        state.api.deleteHistoryBatch(provider, sessions.map { it.claudeSessionId })
                                    }.isFailure
                                ) {
                                    failed = true
                                }
                            }
                            // 乐观删除失败时从服务端重拉，让未实际删除的项目恢复显示。
                            if (failed) state.load(silent = true)
                        }
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
                state.loading && state.sessions.isEmpty() && state.historySessions.isEmpty() -> {
                    LoadingState("正在加载会话…", Modifier.padding(padding))
                }
                state.loadError != null && state.sessions.isEmpty() &&
                    state.historySessions.isEmpty() -> {
                    ErrorState(
                        message = state.loadError ?: "加载失败",
                        onRetry = { scope.launch { state.load() } },
                        modifier = Modifier.padding(padding),
                    )
                }
                state.visibleEntries.isEmpty() -> {
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
                            items(visibleEntries, key = { it.key }) { entry ->
                                DisposableEffect(entry.key) {
                                    onDispose { rowBounds.remove(entry.key) }
                                }
                                val rowModifier = Modifier
                                    .animateItem()
                                    .onGloballyPositioned { coords ->
                                        rowBounds[entry.key] = coords.boundsInWindow()
                                    }
                                    // 所有可见条目（Wand 会话、Claude/Codex 历史）都支持长按进入多选，
                                    // 并可在混排列表中连续拖选。
                                    .pointerInput(entry.key) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                if (dragAnchorId == null) {
                                                    if (!isSelecting) isSelecting = true
                                                    dragAnchorId = entry.key
                                                    dragBaseIds = selectedIds
                                                    selectedIds = selectedIds + entry.key
                                                }
                                            },
                                            onDrag = { change, _ ->
                                                val anchor = dragAnchorId ?: return@detectDragGesturesAfterLongPress
                                                val originY = rowBounds[entry.key]?.top
                                                    ?: return@detectDragGesturesAfterLongPress
                                                val pointerY = originY + change.position.y
                                                val anchorIndex = visibleEntries.indexOfFirst { it.key == anchor }
                                                val targetKey = nearestRowId(rowBounds, pointerY)
                                                val targetIndex = visibleEntries.indexOfFirst { it.key == targetKey }
                                                if (anchorIndex >= 0 && targetIndex >= 0) {
                                                    val range = (minOf(anchorIndex, targetIndex)..maxOf(anchorIndex, targetIndex))
                                                        .map { visibleEntries[it].key }
                                                    selectedIds = dragBaseIds + range
                                                }
                                            },
                                            onDragEnd = {
                                                dragAnchorId = null
                                                dragBaseIds = emptySet()
                                            },
                                            onDragCancel = {
                                                dragAnchorId = null
                                                dragBaseIds = emptySet()
                                            },
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
                                                onDelete = {
                                                    state.removeLocally(session.id)
                                                    scope.launch {
                                                        try {
                                                            state.api.deleteSession(session.id)
                                                        } catch (_: Exception) {
                                                            state.load(silent = true)
                                                        }
                                                    }
                                                },
                                            ) { revealed, closeReveal ->
                                                SessionCard(
                                                    session = session,
                                                    selecting = false,
                                                    selected = session.id == selectedSessionId,
                                                    compact = compactLayout,
                                                    onClick = {
                                                        if (revealed) closeReveal() else onOpenSession(session)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                    is SessionListEntry.Recoverable -> {
                                        val session = entry.session
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
                                                onDelete = {
                                                    state.removeHistoryLocally(session)
                                                    scope.launch { runCatching { state.api.deleteHistory(session) } }
                                                },
                                            ) { revealed, closeReveal ->
                                                HistorySessionCard(
                                                    history = session,
                                                    enabled = restoringHistoryKey == null,
                                                    selecting = false,
                                                    selected = false,
                                                    compact = compactLayout,
                                                    restoring = restoringHistoryKey == entry.key,
                                                    onClick = {
                                                        if (revealed) {
                                                            closeReveal()
                                                        } else if (restoringHistoryKey == null) {
                                                            restoringHistoryKey = entry.key
                                                            scope.launch {
                                                                try {
                                                                    val resumed = state.api.resumeHistory(session)
                                                                    state.removeHistoryLocally(session)
                                                                    state.prepend(resumed)
                                                                    state.loadError = null
                                                                    onOpenSession(resumed)
                                                                } catch (e: Exception) {
                                                                    state.loadError = e.message ?: "恢复失败"
                                                                }
                                                                restoringHistoryKey = null
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
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold,
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
                        fontSize = if (compact) 20.sp else 22.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = WandColors.textPrimary,
                    )
                    Text(
                        "$entryCount 个会话",
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = WandColors.textMuted,
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
        HorizontalDivider(thickness = 0.5.dp, color = WandColors.border)
    }
}

/** 顶栏主动作：48dp 触控区内放 36dp 品牌弱底，突出新增又不抢标题层级。 */
@Composable
private fun TopBarPrimaryAction(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(WandColors.brandSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                WandIcons.add,
                contentDescription = "新建会话",
                tint = WandColors.brand,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 拖拽范围选择：取指针垂直方向最近的行（落在卡片间隙也不漏选，对齐 iOS sessionId(nearestTo:)）。 */
private fun nearestRowId(rowBounds: Map<String, Rect>, pointerY: Float): String? {
    rowBounds.entries.firstOrNull { pointerY in it.value.top..it.value.bottom }?.let { return it.key }
    return rowBounds.minByOrNull { kotlin.math.abs((it.value.top + it.value.bottom) / 2 - pointerY) }?.key
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
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
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
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selectedCount > 0) WandColors.danger else WandColors.textMuted,
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onDone) {
                Text(
                    "完成",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WandColors.brand,
                )
            }
        }
    }
}

/**
 * 可恢复会话卡：会话图标 + 首条用户消息 + 元信息行（provider 胶囊 + 相对时间）
 * + 工作目录路径独占一行（对齐 iOS HistorySessionRow）。
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
    val providerLabel = if (isCodex) "Codex" else "Claude"
    val shape = RoundedCornerShape(if (compact) 14.dp else 18.dp)
    WandCard(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (enabled || restoring) 1f else 0.48f }
            .semantics { stateDescription = if (restoring) "正在恢复" else "可恢复" },
        onClick = if (enabled) onClick else null,
        selected = selected,
        shape = shape,
        containerColor = if (selected) {
            lerp(WandColors.surface, WandColors.brand, 0.10f)
        } else {
            WandColors.surface.copy(alpha = 0.78f)
        },
        contentPadding = PaddingValues(
            horizontal = if (compact) 10.dp else 12.dp,
            vertical = if (compact) 9.dp else 11.dp,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 9.dp else 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                Icon(
                    if (selected) WandIcons.statusDone else WandIcons.statusPending,
                    contentDescription = if (selected) "已选中" else "未选中",
                    tint = if (selected) WandColors.brand else WandColors.textSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }
            ProviderMark(
                provider = history.provider,
                contentDescription = "$providerLabel，可恢复会话",
                badgeIcon = WandIcons.history,
                compact = compact,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 7.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
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
                    val relative = relativeTimeLabel(history.timestamp)
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
                    label = "$providerLabel · 可恢复",
                    path = history.cwd,
                    compact = compact,
                    tint = tint,
                )
            }
        }
    }
}

/** ISO8601 时间 → 相对时间（单单位：刚刚 / N分钟 / N小时 / N天），解析失败返回空。 */
private fun relativeTimeLabel(timestamp: String?): String {
    if (timestamp.isNullOrEmpty()) return ""
    val millis = try {
        Instant.parse(timestamp).toEpochMilli()
    } catch (_: Exception) {
        return ""
    }
    return singleUnitDurationLabel(System.currentTimeMillis() - millis)
}

/**
 * 单位时长文案（AGENTS.md 约定：duration / relative chip 只显示一个单位）。
 * - 刚刚 / N分钟 / N小时 / N天
 * 秒数由调用方提供：相对时间传 (now - 当时)，会话时长传 (end - start)。
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
    content: @Composable (revealed: Boolean, closeReveal: () -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // 滑动行程：留出按钮宽度 + 两侧呼吸间距，让删除键像「浮起」的独立按钮而非贴边红块。
    val buttonWidth = 64.dp
    val gap = 10.dp
    val revealWidth = buttonWidth + gap * 2
    val revealPx = with(density) { revealWidth.toPx() }
    val offsetX = remember { Animatable(0f) }
    val revealed = offsetX.value <= -revealPx + 1f
    val snapSpec = WandMotion.springSpec<Float>()
    val closeReveal: () -> Unit = { scope.launch { offsetX.animateTo(0f, snapSpec) } }

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = WandMotion.springSpec(),
        label = "deletePress",
    )

    Box(modifier = modifier) {
        // 揭示出的删除按钮：右侧浮起的圆角红键，只在行有位移时绘制
        // （玻璃卡片半透明，静止时红底会透出来）。随滑动进度淡入 + 轻微放大，避免硬切。
        if (offsetX.value < -1f) {
            Box(
                modifier = Modifier.matchParentSize(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = gap, vertical = 4.dp)
                        .fillMaxHeight()
                        .width(buttonWidth)
                        .graphicsLayer {
                            val progress = (-offsetX.value / revealPx).coerceIn(0f, 1f)
                            val enter = 0.82f + 0.18f * progress
                            val s = enter * pressScale
                            scaleX = s
                            scaleY = s
                            alpha = progress
                        }
                        .clip(WandShapes.md)
                        .background(WandColors.danger)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = LocalIndication.current,
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
                            contentDescription = "删除",
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
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val next = (offsetX.value + dragAmount).coerceIn(-revealPx, 0f)
                            scope.launch { offsetX.snapTo(next) }
                        },
                        onDragEnd = {
                            val target = if (offsetX.value < -revealPx / 2) -revealPx else 0f
                            scope.launch { offsetX.animateTo(target, snapSpec) }
                        },
                        onDragCancel = {
                            val target = if (offsetX.value < -revealPx / 2) -revealPx else 0f
                            scope.launch { offsetX.animateTo(target, snapSpec) }
                        },
                    )
                },
        ) {
            content(revealed, closeReveal)
        }
    }
}

/**
 * 单条会话卡片：provider 标识、标题、无底色时间与一行上下文。
 * 状态同时以圆点和文字表达，避免只靠颜色传递信息。
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
    val shape = RoundedCornerShape(if (compact) 14.dp else 18.dp)
    WandCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { stateDescription = sessionStatusLabel(status) },
        onClick = onClick,
        selected = selected,
        shape = shape,
        containerColor = if (selected) {
            lerp(WandColors.surface, WandColors.brand, 0.10f)
        } else {
            WandColors.surface.copy(alpha = 0.78f)
        },
        contentPadding = PaddingValues(
            horizontal = if (compact) 10.dp else 12.dp,
            vertical = if (compact) 9.dp else 11.dp,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 9.dp else 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                Icon(
                    if (selected) WandIcons.statusDone else WandIcons.statusPending,
                    contentDescription = if (selected) "已选中" else "未选中",
                    tint = if (selected) WandColors.brand else WandColors.textSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }
            ProviderMark(
                provider = session.provider,
                contentDescription = "${session.providerLabel}，${sessionListTitle(session)}",
                status = status,
                compact = compact,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 7.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
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
                    val duration = sessionDurationLabel(session)
                    if (duration.isNotEmpty()) {
                        ConversationTimeLabel(text = duration, compact = compact)
                    }
                }
                ConversationContextLine(
                    icon = if (session.isStructured) WandIcons.chat else WandIcons.terminal,
                    label = buildString {
                        append(session.providerLabel)
                        append(" · ")
                        append(if (session.isStructured) "聊天" else "终端")
                        append(" · ")
                        append(sessionStatusLabel(status))
                    },
                    path = session.cwd.orEmpty(),
                    compact = compact,
                )
            }
        }
    }
}

private fun sessionListTitle(session: SessionSnapshot): String {
    return session.displayTitle.ifBlank {
        if (session.isStructured) "聊天会话" else "终端会话"
    }
}

/**
 * 左侧助手标识：普通会话叠加状态点；可恢复历史叠加 history 小标。
 */
@Composable
private fun ProviderMark(
    provider: String?,
    contentDescription: String,
    status: String? = null,
    badgeIcon: ImageVector? = null,
    compact: Boolean = false,
) {
    val isCodex = provider == "codex"
    val tint = if (isCodex) WandColors.info else WandColors.brand
    val icon = if (isCodex) BrandLogos.codex else BrandLogos.claude
    val shape = RoundedCornerShape(if (compact) 10.dp else 12.dp)
    val outerSize = if (compact) 40.dp else 44.dp
    val markSize = if (compact) 36.dp else 40.dp
    val logoSize = if (compact) 17.dp else 18.dp
    val dotSize = if (compact) 9.dp else 10.dp

    Box(modifier = Modifier.size(outerSize)) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(markSize)
                .clip(shape)
                .background(tint.copy(alpha = 0.095f))
                .border(0.55.dp, tint.copy(alpha = 0.14f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = tint.copy(alpha = 0.82f),
                modifier = Modifier.size(logoSize),
            )
        }
        if (status != null || badgeIcon != null) {
            val badgeSize = if (badgeIcon != null) {
                if (compact) 13.dp else 15.dp
            } else {
                dotSize
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(badgeSize)
                    .clip(CircleShape)
                    .background(WandColors.surface)
                    .border(0.7.dp, WandColors.borderStrong.copy(alpha = 0.34f), CircleShape)
                    .padding(if (badgeIcon != null) 2.5.dp else 1.7.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (badgeIcon != null) {
                    Icon(
                        badgeIcon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (status != null) {
                    StatusDot(status, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** 无底色的 trailing 时间，避免与标题、状态争抢层级；恢复时原位切成进度反馈。 */
@Composable
private fun ConversationTimeLabel(
    text: String,
    compact: Boolean,
    loading: Boolean = false,
    tint: Color = WandColors.textMuted,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 1.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = tint,
                strokeWidth = 1.5.dp,
                modifier = Modifier.size(if (compact) 11.dp else 12.dp),
            )
        } else {
            Icon(
                WandIcons.clock,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(if (compact) 11.dp else 12.dp),
            )
        }
        Text(
            text,
            fontSize = if (compact) 10.5.sp else 11.5.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            color = tint,
            maxLines = 1,
        )
    }
}

/**
 * 统一第二层信息：类型/状态保持可读文字，路径占用剩余空间并优先露出末端。
 * 只有一条视觉基线，不再为每个元数据套胶囊。
 */
@Composable
private fun ConversationContextLine(
    icon: ImageVector,
    label: String,
    path: String,
    compact: Boolean,
    tint: Color = WandColors.textSecondary,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint.copy(alpha = 0.88f),
            modifier = Modifier.size(if (compact) 12.dp else 13.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            label,
            fontSize = if (compact) 10.5.sp else 11.5.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Normal,
            color = WandColors.textSecondary.copy(alpha = 0.90f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (path.isNotBlank()) {
            Spacer(modifier = Modifier.width(7.dp))
            Box(
                modifier = Modifier
                    .size(3.dp)
                    .clip(CircleShape)
                    .background(WandColors.textMuted.copy(alpha = 0.48f)),
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = compactWorkingPath(path),
                modifier = Modifier.weight(1f),
                fontSize = if (compact) 9.8.sp else 10.8.sp,
                lineHeight = 16.sp,
                fontFamily = FontFamily.Monospace,
                color = WandColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 列表只保留目录末两级，完整路径留给详情页，减少每行重复噪声。 */
private fun compactWorkingPath(path: String): String {
    val normalized = path.trim().trimEnd('/', '\\').replace('\\', '/')
    if (normalized.isEmpty()) return ""
    val segments = normalized.split('/').filter { it.isNotEmpty() }
    if (segments.size <= 2) return normalized
    return "…/${segments.takeLast(2).joinToString("/")}"
}

/** 派生状态的可见文字，同时供 TalkBack stateDescription 使用。 */
private fun sessionStatusLabel(status: String): String = when (status.trim().lowercase()) {
    "running" -> "运行中"
    "thinking" -> "思考中"
    "waiting-input", "waiting_input" -> "等待输入"
    "permission" -> "等待授权"
    "reconnecting" -> "重连中"
    "idle" -> "空闲"
    "stopped" -> "已停止"
    "failed" -> "已失败"
    "exited" -> "已退出"
    "archived" -> "已归档"
    else -> status.ifBlank { "未知状态" }
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
    val started = try {
        session.startedAt?.let(Instant::parse)?.toEpochMilli()
    } catch (_: Exception) {
        null
    } ?: return ""
    val ended = try {
        session.endedAt?.let(Instant::parse)?.toEpochMilli()
    } catch (_: Exception) {
        null
    }
    return singleUnitDurationLabel((ended ?: System.currentTimeMillis()) - started)
}
