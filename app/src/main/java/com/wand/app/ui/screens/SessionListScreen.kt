package com.wand.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 会话列表状态。提升到导航栈外层持有（remember 于 WandApp），
 * 进聊天后返回不重新加载。对称 iOS SessionListView 的 @State。
 */
class SessionListState(val api: WandApi) {
    var sessions by mutableStateOf<List<SessionSnapshot>>(emptyList())
    var loading by mutableStateOf(true)
    var loadError by mutableStateOf<String?>(null)

    val visibleSessions: List<SessionSnapshot>
        get() = sessions.filter { (it.archived ?: false) == false }

    suspend fun load(silent: Boolean = false) {
        if (!silent) loading = true
        try {
            sessions = api.listSessions()
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
}

/** 会话列表：原生渲染 /api/sessions，下拉刷新 + 10s 轮询，滑动删除，新建入口。 */
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

    LaunchedEffect(Unit) {
        state.load(silent = state.sessions.isNotEmpty())
        while (true) {
            delay(10_000)
            state.load(silent = true)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Wand",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = WandColors.textPrimary,
                    )
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
                    IconButton(onClick = onNewSession) {
                        Icon(
                            WandIcons.add,
                            contentDescription = "新建会话",
                            tint = WandColors.brand,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading && state.sessions.isEmpty() -> {
                    LoadingState("正在加载会话…")
                }
                state.loadError != null && state.sessions.isEmpty() -> {
                    ErrorState(
                        message = state.loadError ?: "加载失败",
                        onRetry = { scope.launch { state.load() } },
                    )
                }
                state.visibleSessions.isEmpty() -> {
                    EmptyState(
                        icon = WandIcons.sparkle,
                        title = "还没有会话",
                        subtitle = "新建一个会话，开始与 AI 协作",
                        actionText = "创建第一个会话",
                        onAction = onNewSession,
                    )
                }
                else -> {
                    PullToRefreshBox(
                        isRefreshing = refreshing,
                        onRefresh = {
                            scope.launch {
                                refreshing = true
                                state.load(silent = true)
                                refreshing = false
                            }
                        },
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 8.dp,
                                bottom = 16.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.visibleSessions, key = { it.id }) { session ->
                                SwipeDeleteRow(
                                    modifier = Modifier.animateItem(),
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
        },
    ) {
        content()
    }
}

/**
 * 单条会话卡片：助手图标 + 状态点 + 标题 + provider/runner + 路径尾段 + 行尾 StatusBadge。
 * 内容高 ≥40dp + 上下 12dp 内边距 → 整行 ≥64dp。
 */
@Composable
private fun SessionCard(session: SessionSnapshot, onClick: () -> Unit) {
    val status = derivedStatus(session)
    WandCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                        if (session.provider == "codex") "Codex" else "Claude",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (session.provider == "codex") WandColors.info else WandColors.brand,
                        maxLines = 1,
                    )
                    RunnerBadge(isStructured = session.isStructured)
                    val cwdTail = session.cwd
                        ?.trimEnd('/')
                        ?.substringAfterLast('/')
                        .orEmpty()
                    if (cwdTail.isNotEmpty()) {
                        Text(
                            cwdTail,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = WandColors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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
