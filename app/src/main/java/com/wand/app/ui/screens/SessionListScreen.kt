package com.wand.app.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.data.HistorySession
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.WandApi
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.components.EmptyState
import com.wand.app.ui.components.ErrorState
import com.wand.app.ui.components.LoadingState
import com.wand.app.ui.components.StatusBadge
import com.wand.app.ui.components.StatusDot
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.AmbientBackground
import com.wand.app.ui.theme.GlassBackdrop
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandGlass
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.glassBackdropSource
import com.wand.app.ui.theme.glassSurface
import com.wand.app.ui.theme.rememberGlassBackdrop
import kotlin.math.abs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * 会话列表状态。提升到导航栈外层持有（remember 于 WandApp），
 * 进聊天后返回不重新加载。对称 iOS SessionListView 的 @State。
 */
class SessionListState(val api: WandApi) {
    var sessions by mutableStateOf<List<SessionSnapshot>>(emptyList())
    var historySessions by mutableStateOf<List<HistorySession>>(emptyList())
    var loading by mutableStateOf(true)
    var loadError by mutableStateOf<String?>(null)

    val visibleSessions: List<SessionSnapshot>
        get() = sessions.filter { (it.archived ?: false) == false }

    /** 历史会话：过滤空会话 / 已被 wand 纳管的，按修改时间倒序（对齐 iOS visibleHistorySessions）。 */
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

    fun removeHistoryLocally(historyId: String) {
        historySessions = historySessions.filter { it.id != historyId }
    }
}

/**
 * 会话列表：「进行中 / 历史会话」双范围（对齐 iOS SessionListView）。
 * 进行中：下拉刷新 + 10s 轮询 + 滑动删除 + 长按多选（按住拖动连续选择）+ 批量删除；
 * 历史会话：本机 Claude/Codex 历史扫描，点击恢复为 wand 会话，可滑删 / 一键清空。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    state: SessionListState,
    onOpenSession: (SessionSnapshot) -> Unit,
    onNewSession: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWeb: () -> Unit,
    onSwitchServer: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var listScope by rememberSaveable { mutableStateOf("active") }
    var isSelecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showClearHistoryConfirmation by remember { mutableStateOf(false) }
    var historyActionInProgress by remember { mutableStateOf(false) }
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

    LaunchedEffect(Unit) {
        state.load(silent = state.sessions.isNotEmpty())
        while (true) {
            delay(10_000)
            state.load(silent = true)
        }
    }
    // 切换范围时退出多选并静默刷新（对齐 iOS onChange(of: scope)）。
    LaunchedEffect(listScope) {
        endSelection()
        state.load(silent = true)
    }

    // 液态玻璃：列表是 backdrop 捕获源，顶栏/多选栏悬浮其上采样模糊。
    val glassBackdrop = rememberGlassBackdrop()
    val barGlass = WandGlass.regular.copy(refractionHeight = 0.dp)
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                modifier = Modifier.glassSurface(glassBackdrop, RoundedCornerShape(0.dp), barGlass),
                title = {
                    if (isSelecting) {
                        Text(
                            "已选择 ${selectedIds.size} 项",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WandColors.textPrimary,
                        )
                    } else {
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.width(190.dp)) {
                            listOf("active" to "进行中", "history" to "历史会话")
                                .forEachIndexed { index, (value, label) ->
                                    SegmentedButton(
                                        selected = listScope == value,
                                        onClick = { listScope = value },
                                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                                        colors = SegmentedButtonDefaults.colors(
                                            activeContainerColor = WandColors.brandSoft,
                                            activeContentColor = WandColors.brand,
                                            // 透明非选中底：分段开关嵌在玻璃顶栏里，让玻璃自己透出来。
                                            inactiveContainerColor = Color.Transparent,
                                            inactiveContentColor = WandColors.textSecondary,
                                        ),
                                        icon = {},
                                    ) {
                                        Text(label, fontSize = 12.sp, maxLines = 1)
                                    }
                                }
                        }
                    }
                },
                navigationIcon = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "菜单",
                                tint = WandColors.textSecondary,
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("设置") },
                                leadingIcon = {
                                    Icon(
                                        WandIcons.settings,
                                        contentDescription = null,
                                        tint = WandColors.textSecondary,
                                    )
                                },
                                onClick = { menuOpen = false; onOpenSettings() },
                            )
                            DropdownMenuItem(
                                text = { Text("打开网页版") },
                                leadingIcon = {
                                    Icon(
                                        WandIcons.web,
                                        contentDescription = null,
                                        tint = WandColors.textSecondary,
                                    )
                                },
                                onClick = { menuOpen = false; onOpenWeb() },
                            )
                            DropdownMenuItem(
                                text = { Text("切换服务器") },
                                leadingIcon = {
                                    Icon(
                                        WandIcons.swapServer,
                                        contentDescription = null,
                                        tint = WandColors.textSecondary,
                                    )
                                },
                                onClick = { menuOpen = false; onSwitchServer() },
                            )
                        }
                    }
                },
                actions = {
                    // 右上角按模式切换：多选 ✕ 退出 / 历史 🗑 清空 / 进行中 + 新建（对齐 iOS）。
                    when {
                        isSelecting -> IconButton(onClick = { endSelection() }) {
                            Icon(
                                WandIcons.close,
                                contentDescription = "退出多选",
                                tint = WandColors.brand,
                            )
                        }
                        listScope == "history" -> IconButton(
                            onClick = { showClearHistoryConfirmation = true },
                            enabled = state.visibleHistorySessions.isNotEmpty(),
                        ) {
                            Icon(
                                WandIcons.delete,
                                contentDescription = "清空历史会话",
                                tint = if (state.visibleHistorySessions.isEmpty()) {
                                    WandColors.textMuted
                                } else {
                                    WandColors.danger
                                },
                            )
                        }
                        else -> IconButton(onClick = onNewSession) {
                            Icon(
                                WandIcons.add,
                                contentDescription = "新建会话",
                                tint = WandColors.brand,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
            )
        },
        bottomBar = {
            if (isSelecting) {
                SelectionBar(
                    backdrop = glassBackdrop,
                    selectedCount = selectedIds.size,
                    totalCount = state.visibleSessions.size,
                    onToggleAll = {
                        selectedIds = if (selectedIds.size == state.visibleSessions.size) {
                            emptySet()
                        } else {
                            state.visibleSessions.map { it.id }.toSet()
                        }
                    },
                    onDelete = {
                        val ids = selectedIds
                        ids.forEach { state.removeLocally(it) }
                        endSelection()
                        scope.launch {
                            ids.forEach { id ->
                                runCatching { state.api.deleteSession(id) }
                            }
                        }
                    },
                    onDone = { endSelection() },
                )
            }
        },
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
                listScope == "history" -> HistoryList(
                    state = state,
                    padding = padding,
                    refreshing = refreshing,
                    actionInProgress = historyActionInProgress,
                    onRefresh = {
                        scope.launch {
                            refreshing = true
                            state.load(silent = true)
                            refreshing = false
                        }
                    },
                    onResume = { history ->
                        if (!historyActionInProgress) {
                            historyActionInProgress = true
                            scope.launch {
                                try {
                                    val resumed = state.api.resumeHistory(history)
                                    state.removeHistoryLocally(history.id)
                                    state.prepend(resumed)
                                    state.loadError = null
                                    onOpenSession(resumed)
                                } catch (e: Exception) {
                                    state.loadError = e.message ?: "恢复失败"
                                }
                                historyActionInProgress = false
                            }
                        }
                    },
                    onDelete = { history ->
                        state.removeHistoryLocally(history.id)
                        scope.launch {
                            runCatching { state.api.deleteHistory(history) }
                        }
                    },
                )
                state.visibleSessions.isEmpty() -> {
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
                            contentPadding = PaddingValues(
                                start = 16.dp + padding.calculateStartPadding(direction),
                                end = 16.dp + padding.calculateEndPadding(direction),
                                top = 8.dp + padding.calculateTopPadding(),
                                bottom = 16.dp + padding.calculateBottomPadding(),
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.visibleSessions, key = { it.id }) { session ->
                                val rowModifier = Modifier
                                    .animateItem()
                                    .onGloballyPositioned { coords ->
                                        rowBounds[session.id] = coords.boundsInWindow()
                                    }
                                    // 长按进入多选 + 保持按住拖动做范围选择（对齐 iOS selectionGesture）。
                                    .pointerInput(session.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { offset ->
                                                if (dragAnchorId == null) {
                                                    if (!isSelecting) isSelecting = true
                                                    dragAnchorId = session.id
                                                    dragBaseIds = selectedIds
                                                    selectedIds = selectedIds + session.id
                                                }
                                                // offset 是行内坐标，仅用于触发；范围由 onDrag 驱动。
                                            },
                                            onDrag = { change, _ ->
                                                val anchor = dragAnchorId ?: return@detectDragGesturesAfterLongPress
                                                val originY = rowBounds[session.id]?.top ?: return@detectDragGesturesAfterLongPress
                                                val pointerY = originY + change.position.y
                                                val visible = state.visibleSessions
                                                val anchorIndex = visible.indexOfFirst { it.id == anchor }
                                                val targetId = nearestRowId(rowBounds, pointerY)
                                                val targetIndex = visible.indexOfFirst { it.id == targetId }
                                                if (anchorIndex >= 0 && targetIndex >= 0) {
                                                    val range = (minOf(anchorIndex, targetIndex)..maxOf(anchorIndex, targetIndex))
                                                        .map { visible[it].id }
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
                                if (isSelecting) {
                                    Box(modifier = rowModifier) {
                                        SessionCard(
                                            session = session,
                                            selecting = true,
                                            selected = session.id in selectedIds,
                                            onClick = {
                                                selectedIds = if (session.id in selectedIds) {
                                                    selectedIds - session.id
                                                } else {
                                                    selectedIds + session.id
                                                }
                                            },
                                        )
                                    }
                                } else {
                                    SwipeDeleteRow(
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
                                    ) {
                                        SessionCard(
                                            session = session,
                                            selecting = false,
                                            selected = false,
                                            onClick = { onOpenSession(session) },
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

    // 清空历史确认（对齐 iOS confirmationDialog）。
    if (showClearHistoryConfirmation) {
        val count = state.visibleHistorySessions.size
        AlertDialog(
            onDismissRequest = { showClearHistoryConfirmation = false },
            containerColor = WandColors.surface,
            title = {
                Text(
                    "确认清空全部历史会话？",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WandColors.textPrimary,
                )
            },
            text = {
                Text(
                    "这会删除本机 Claude 和 Codex 的历史会话文件，无法撤销。",
                    fontSize = 13.sp,
                    color = WandColors.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearHistoryConfirmation = false
                    val targets = state.visibleHistorySessions
                    targets.forEach { state.removeHistoryLocally(it.id) }
                    scope.launch {
                        val claudeIds = targets.filter { it.provider != "codex" }.map { it.id }
                        val codexIds = targets.filter { it.provider == "codex" }.map { it.id }
                        runCatching { state.api.deleteHistoryBatch("claude", claudeIds) }
                        runCatching { state.api.deleteHistoryBatch("codex", codexIds) }
                    }
                }) {
                    Text(
                        "清空全部 $count 条历史会话",
                        color = WandColors.danger,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryConfirmation = false }) {
                    Text("取消", color = WandColors.textMuted, fontSize = 13.sp)
                }
            },
        )
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

// MARK: - 历史会话列表

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryList(
    state: SessionListState,
    padding: PaddingValues,
    refreshing: Boolean,
    actionInProgress: Boolean,
    onRefresh: () -> Unit,
    onResume: (HistorySession) -> Unit,
    onDelete: (HistorySession) -> Unit,
) {
    val visible = state.visibleHistorySessions
    if (visible.isEmpty()) {
        EmptyState(
            icon = WandIcons.history,
            title = "没有历史会话",
            subtitle = "Claude 和 Codex 的本地历史会话会显示在这里",
            modifier = Modifier.padding(padding),
        )
        return
    }
    val pullState = rememberPullToRefreshState()
    val direction = LocalLayoutDirection.current
    PullToRefreshBox(
        isRefreshing = refreshing,
        state = pullState,
        onRefresh = onRefresh,
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
            contentPadding = PaddingValues(
                start = 16.dp + padding.calculateStartPadding(direction),
                end = 16.dp + padding.calculateEndPadding(direction),
                top = 8.dp + padding.calculateTopPadding(),
                bottom = 16.dp + padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(visible, key = { it.id }) { history ->
                SwipeDeleteRow(
                    modifier = Modifier.animateItem(),
                    onDelete = { onDelete(history) },
                ) {
                    HistorySessionCard(
                        history = history,
                        enabled = !actionInProgress,
                        onClick = { onResume(history) },
                    )
                }
            }
        }
    }
}

/** 历史会话卡：时钟图标 + 首条用户消息 + provider/相对时间 + 紧凑路径 + 恢复按钮（对齐 iOS HistorySessionRow）。 */
@Composable
private fun HistorySessionCard(
    history: HistorySession,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val isCodex = history.provider == "codex"
    val tint = if (isCodex) WandColors.info else WandColors.brand
    WandCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = if (enabled) onClick else null,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(WandShapes.md)
                    .background(tint.copy(alpha = 0.13f))
                    .border(1.dp, tint.copy(alpha = 0.24f), WandShapes.md),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    WandIcons.history,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(21.dp),
                )
            }
            Spacer(modifier = Modifier.width(13.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    history.firstUserMessage.ifEmpty { "空会话" },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WandColors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        if (isCodex) "Codex" else "Claude",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = tint,
                    )
                    val relative = relativeTimeLabel(history.timestamp)
                    if (relative.isNotEmpty()) {
                        Text(
                            relative,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = WandColors.textSecondary,
                        )
                    }
                }
                val path = compactPath(history.cwd)
                if (path.isNotEmpty()) {
                    Text(
                        path,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = WandColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                WandIcons.refresh,
                contentDescription = "恢复此会话",
                tint = tint.copy(alpha = 0.8f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** ISO8601 时间 → 相对时间（"3 分钟前"），解析失败返回空。 */
private fun relativeTimeLabel(timestamp: String?): String {
    if (timestamp.isNullOrEmpty()) return ""
    return try {
        val millis = Instant.parse(timestamp).toEpochMilli()
        DateUtils.getRelativeTimeSpanString(
            millis,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
    } catch (_: Exception) {
        ""
    }
}

/** 路径超过 3 层时只留后 3 层（对齐 iOS compactPath）。 */
private fun compactPath(cwd: String?): String {
    if (cwd.isNullOrEmpty()) return ""
    val components = cwd.split('/').filter { it.isNotEmpty() }
    if (components.size <= 3) return cwd
    return "…/" + components.takeLast(3).joinToString("/")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeDeleteRow(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            // 卡片玻璃化后是半透明的：静止时红底会透出来，
            // 只有行真正位移时才绘制删除背景。
            val offset = runCatching { dismissState.requireOffset() }.getOrDefault(0f)
            if (abs(offset) > 1f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(WandShapes.md)
                        .background(WandColors.danger),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(
                        WandIcons.delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.padding(end = 20.dp),
                    )
                }
            }
        },
    ) {
        content()
    }
}

/**
 * 单条会话卡片：助手图标 + 状态点 + 标题 + provider/runner + 紧凑路径 + 行尾 StatusBadge。
 * 多选模式时行首加 ○/✓ 指示（对齐 iOS SessionRow）。
 */
@Composable
private fun SessionCard(
    session: SessionSnapshot,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val status = derivedStatus(session)
    WandCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        selected = selecting && selected,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                Icon(
                    if (selected) WandIcons.statusDone else WandIcons.statusPending,
                    contentDescription = if (selected) "已选中" else "未选中",
                    tint = if (selected) WandColors.brand else WandColors.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            ProviderMark(session = session, status = status)
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    session.displayTitle,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = WandColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        session.providerLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (session.provider == "codex") WandColors.info else WandColors.brand,
                        maxLines = 1,
                    )
                    RunnerBadge(isStructured = session.isStructured)
                }
                val path = compactPath(session.cwd)
                if (path.isNotEmpty()) {
                    Text(
                        path,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = WandColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            StatusBadge(status)
        }
    }
}

/** 左侧助手标识：按实际 CLI 显示品牌 logo——Claude 星芒（brand 色）/ Codex 六角结（info 色），右下角叠加实时状态。 */
@Composable
private fun ProviderMark(session: SessionSnapshot, status: String) {
    val isCodex = session.provider == "codex"
    val tint = if (isCodex) WandColors.info else WandColors.brand
    val background = if (isCodex) WandColors.infoSoft else WandColors.brandSoft
    val icon = if (isCodex) BrandLogos.codex else BrandLogos.claude
    val label = if (isCodex) "Codex" else "Claude"

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(WandShapes.md)
            .background(background)
            .border(1.dp, tint.copy(alpha = 0.24f), WandShapes.md),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = "$label，${session.displayTitle}",
            tint = tint,
            modifier = Modifier.size(21.dp),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(13.dp)
                .clip(CircleShape)
                .background(WandColors.surface)
                .padding(2.dp),
            contentAlignment = Alignment.Center,
        ) {
            StatusDot(status, modifier = Modifier.size(8.dp))
        }
    }
}

/** runner 类型徽章：聊天 brandSoft/brand，终端 infoSoft/info，11sp 弱底胶囊。 */
@Composable
private fun RunnerBadge(isStructured: Boolean) {
    val bg = if (isStructured) WandColors.brandSoft else WandColors.infoSoft
    val fg = if (isStructured) WandColors.brand else WandColors.info
    Text(
        if (isStructured) "聊天" else "终端",
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = fg,
        maxLines = 1,
        modifier = Modifier
            .clip(WandShapes.full)
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
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
