package com.wand.app.ui.screens

import android.text.format.DateUtils
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.AmbientBackground
import com.wand.app.ui.theme.GlassBackdrop
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandGlass
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.bevelRimBrush
import com.wand.app.ui.theme.cardShadowColors
import com.wand.app.ui.theme.glassBackdropSource
import com.wand.app.ui.theme.glassSurface
import com.wand.app.ui.theme.layeredShadow
import com.wand.app.ui.theme.rememberGlassBackdrop
import com.wand.app.ui.theme.surfaceSheenBrush
import kotlin.math.roundToInt
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
    // 切换范围时退出多选并静默刷新（对齐 iOS onChange(of: scope)）。
    LaunchedEffect(listScope) {
        endSelection()
        state.load(silent = true)
    }

    // 液态玻璃：列表是 backdrop 捕获源，顶栏/多选栏悬浮其上采样模糊。
    val glassBackdrop = rememberGlassBackdrop()
    // 顶栏玻璃去掉厚重投影：全幅栏的大软影只在底缘可见，糊成一道脏脏的「接缝」。
    // 改用一道发丝分隔线收边，顶栏读作干净的玻璃表面而非浮在内容上的色块。
    val barGlass = WandGlass.regular.copy(refractionHeight = 0.dp, shadowElevation = 0.dp)
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            // 紧凑自定义顶栏（替代 64dp 固定高的 Material3 TopAppBar）：玻璃底铺到状态栏下，
            // 内容区压到 46dp，仅留下导航 / 范围切换 / 操作三栏，底缘以发丝线收口。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(glassBackdrop, RoundedCornerShape(0.dp), barGlass, edgeToEdge = true),
            ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(46.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (isSelecting) {
                        Text(
                            "已选择 ${selectedIds.size} 项",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WandColors.textPrimary,
                        )
                    } else {
                        ScopeToggle(
                            selected = listScope,
                            onSelect = { listScope = it },
                        )
                    }
                }
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
            }
            HorizontalDivider(thickness = 0.5.dp, color = WandColors.border)
            }
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
                                top = 12.dp + padding.calculateTopPadding(),
                                bottom = 20.dp + padding.calculateBottomPadding(),
                            ),
                            verticalArrangement = Arrangement.spacedBy(11.dp),
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
                                            selected = false,
                                            onClick = {
                                                if (revealed) closeReveal() else onOpenSession(session)
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

/**
 * 范围切换胶囊：玻璃顶栏内的双段开关，选中段品牌渐变实心 + 白字 + 轻投影（读作浮起的滑块），
 * 未选中透明 + 次级色；切换时颜色动画过渡。比 Material3 SegmentedButton 更贴合品牌、玻璃底上更干净。
 */
@Composable
private fun ScopeToggle(
    selected: String,
    onSelect: (String) -> Unit,
) {
    val options = listOf("active" to "进行中", "history" to "历史会话")
    val brand = WandColors.brand
    Row(
        modifier = Modifier
            .clip(WandShapes.full)
            .background(WandColors.textPrimary.copy(alpha = 0.05f))
            .border(1.dp, WandColors.border, WandShapes.full)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            val bg by animateColorAsState(
                if (active) brand else Color.Transparent,
                label = "scopeToggleBg",
            )
            val fg by animateColorAsState(
                if (active) Color.White else WandColors.textSecondary,
                label = "scopeToggleFg",
            )
            val knobShadow = if (active) brand.copy(alpha = 0.38f) else Color.Transparent
            Box(
                modifier = Modifier
                    .layeredShadow(WandShapes.full, if (active) 3.dp else 0.dp, knobShadow, knobShadow)
                    .clip(WandShapes.full)
                    .background(bg)
                    // 选中段叠一层顶亮渐变，从平涂实色变成有受光的滑块。
                    .then(
                        if (active) {
                            Modifier.background(
                                Brush.verticalGradient(
                                    listOf(lerp(brand, Color.White, 0.16f), brand),
                                ),
                                WandShapes.full,
                            )
                        } else Modifier
                    )
                    .clickable { onSelect(value) }
                    .padding(horizontal = 15.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 12.5.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = fg,
                    maxLines = 1,
                )
            }
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
                top = 12.dp + padding.calculateTopPadding(),
                bottom = 20.dp + padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            items(visible, key = { it.id }) { history ->
                SwipeRevealRow(
                    modifier = Modifier.animateItem(),
                    onDelete = { onDelete(history) },
                ) { revealed, closeReveal ->
                    HistorySessionCard(
                        history = history,
                        enabled = !actionInProgress,
                        onClick = { if (revealed) closeReveal() else onResume(history) },
                    )
                }
            }
        }
    }
}

/**
 * 历史会话卡：浮起的渐变头像（时钟图标）+ 首条用户消息 + 元信息行（provider 胶囊 + 相对时间）
 * + 工作目录路径独占一行（对齐 iOS HistorySessionRow）。头像深度与 [ProviderMark] 一致。
 */
@Composable
private fun HistorySessionCard(
    history: HistorySession,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val isCodex = history.provider == "codex"
    val tint = if (isCodex) WandColors.info else WandColors.brand
    val shape = RoundedCornerShape(14.dp)
    val (keyShadow, ambientShadow) = cardShadowColors()
    WandCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = if (enabled) onClick else null,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .layeredShadow(shape, 3.dp, keyShadow, ambientShadow)
                    .clip(shape)
                    .background(
                        Brush.linearGradient(
                            listOf(tint.copy(alpha = 0.26f), tint.copy(alpha = 0.10f)),
                        ),
                    )
                    // 半透明彩色图标片：满白受光高光会在彩底上糊出白印，几乎抹掉只留彩色本身。
                    .background(surfaceSheenBrush(highlightScale = 0.1f), shape)
                    .border(1.dp, bevelRimBrush(tint.copy(alpha = 0.35f), tint.copy(alpha = 0.12f)), shape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    WandIcons.history,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    history.firstUserMessage.ifEmpty { "空会话" },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WandColors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MetaChip(
                        text = if (isCodex) "Codex" else "Claude",
                        icon = WandIcons.history,
                        tint = tint,
                    )
                    val relative = relativeTimeLabel(history.timestamp)
                    if (relative.isNotEmpty()) {
                        MetaChip(text = relative, icon = WandIcons.clock)
                    }
                }
                val cwd = history.cwd
                if (cwd.isNotEmpty()) {
                    Text(
                        middleTruncatePath(cwd),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = WandColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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
        targetValue = if (pressed) 0.9f else 1f,
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
 * 单条会话卡片：左侧身份列（provider logo + 会话类型），右侧内容列（标题 + 路径/运行时长）。
 * 实时状态只由 logo 右下角圆点表达，避免状态徽章与状态点重复抢占视觉空间。
 * 多选模式时行首加 ○/✓ 指示。
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
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (selecting) {
                Icon(
                    if (selected) WandIcons.statusDone else WandIcons.statusPending,
                    contentDescription = if (selected) "已选中" else "未选中",
                    tint = if (selected) WandColors.brand else WandColors.textSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ProviderMark(session = session, status = status)
                MetaChip(
                    text = if (session.isStructured) "聊天" else "终端",
                    icon = if (session.isStructured) WandIcons.chat else WandIcons.terminal,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    session.displayTitle,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WandColors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val cwd = session.cwd.orEmpty()
                    Text(
                        if (cwd.isEmpty()) "未设置工作目录" else middleTruncatePath(cwd, 44),
                        modifier = Modifier.weight(1f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = WandColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val duration = sessionDurationLabel(session)
                    if (duration.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                WandIcons.clock,
                                contentDescription = null,
                                tint = WandColors.textMuted,
                                modifier = Modifier.size(11.dp),
                            )
                            Text(
                                duration,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = WandColors.textMuted,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 左侧助手标识：品牌渐变圆角方块 + brand logo，右下角叠加实时状态点（对齐 iOS providerMark）。
 * 44dp 头像放在不裁切的外层容器中，状态点轻贴右下角；柔和半透明外环避免切出醒目的白色缺口。
 */
@Composable
private fun ProviderMark(session: SessionSnapshot, status: String) {
    val isCodex = session.provider == "codex"
    val tint = if (isCodex) WandColors.info else WandColors.brand
    val icon = if (isCodex) BrandLogos.codex else BrandLogos.claude
    val label = if (isCodex) "Codex" else "Claude"
    val shape = RoundedCornerShape(14.dp)
    val (keyShadow, ambientShadow) = cardShadowColors()

    Box(modifier = Modifier.size(48.dp)) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(44.dp)
                .layeredShadow(shape, 3.dp, keyShadow, ambientShadow)
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        listOf(tint.copy(alpha = 0.18f), tint.copy(alpha = 0.07f)),
                    ),
                )
                // 半透明彩色图标片：底色比上者更淡，白受光高光全部抹掉，避免白印浮在彩底上。
                .background(surfaceSheenBrush(highlightScale = 0f), shape)
                .border(1.dp, bevelRimBrush(tint.copy(alpha = 0.24f), tint.copy(alpha = 0.08f)), shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = "$label，${session.displayTitle}",
                tint = tint.copy(alpha = 0.88f),
                modifier = Modifier.size(20.dp),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(12.dp)
                .clip(CircleShape)
                .background(WandColors.surface.copy(alpha = 0.82f))
                .padding(2.dp),
            contentAlignment = Alignment.Center,
        ) {
            StatusDot(status, modifier = Modifier.fillMaxSize())
        }
    }
}

/** 弱底胶囊徽章：图标 + 文字，统一次级色淡底（对齐 iOS metadataChip）。 */
@Composable
private fun MetaChip(
    text: String,
    icon: ImageVector,
    tint: Color = WandColors.textSecondary,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(WandShapes.full)
            .background(tint.copy(alpha = 0.10f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
        Text(
            text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = tint,
            maxLines = 1,
        )
    }
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
    val seconds = ((ended ?: System.currentTimeMillis()) - started).coerceAtLeast(0L) / 1000L
    return DateUtils.formatElapsedTime(seconds)
}

/**
 * 中段省略长路径：保留头部与尾部、中间塞 "…"，比纯尾部省略更能同时看出「在哪个根目录、哪个项目」。
 * 短路径原样返回；超长才折叠（阈值约一行等宽字符宽度）。
 */
private fun middleTruncatePath(path: String, max: Int = 38): String {
    if (path.length <= max) return path
    val keep = max - 1
    val head = (keep + 1) / 2
    val tail = keep - head
    return path.take(head) + "…" + path.takeLast(tail)
}
