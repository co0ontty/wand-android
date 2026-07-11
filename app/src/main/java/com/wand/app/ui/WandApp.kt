package com.wand.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.data.HistorySession
import com.wand.app.data.WandApi
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.WandAuth
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.components.NoOverscroll
import com.wand.app.ui.components.WandBrandMark
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.screens.ChatScreen
import com.wand.app.ui.screens.NewSessionScreen
import com.wand.app.ui.screens.PtyTerminalScreen
import com.wand.app.ui.screens.SessionListScreen
import com.wand.app.ui.screens.SessionListState
import com.wand.app.ui.screens.SettingsScreen
import java.time.Instant
import kotlinx.coroutines.launch

/**
 * 原生界面根组合：认证状态机 + 手写页面栈。
 * 对称 iOS NativeRootView：先用 appToken 登录拿 session cookie（CookieJar 在内存，
 * 冷启动后为空，所以每次启动都要重新登录），成功后进入会话列表。
 */
@Composable
fun WandApp(
    api: WandApi,
    actions: HomeActions,
    initialQuickAction: QuickAction? = null,
    onAuthenticated: () -> Unit,
) {
    var phase by remember { mutableStateOf<AuthPhase>(AuthPhase.Authenticating) }
    var retryKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(retryKey) {
        phase = AuthPhase.Authenticating
        phase = try {
            if (actions.hasToken && api.token != null) {
                WandAuth.loginWithToken(api.baseUrl, api.token)
            } else {
                // 裸地址连接（无 token）：直接试列表，401 时引导重新连接。
                api.listSessions()
            }
            onAuthenticated()
            AuthPhase.Ready
        } catch (e: Exception) {
            val msg = e.message ?: "未知错误"
            if (actions.hasToken) {
                AuthPhase.Failed(msg)
            } else {
                AuthPhase.Failed("无法访问服务器：$msg\n如果服务器设有密码，请用「连接码」重新连接。")
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (val p = phase) {
            is AuthPhase.Authenticating -> AuthProgress()
            is AuthPhase.Failed -> AuthFailed(
                message = p.message,
                onRetry = { retryKey++ },
                onSwitchServer = actions.switchServer,
            )
            is AuthPhase.Ready -> ReadyContent(api, actions, initialQuickAction)
        }
    }
}

private sealed class AuthPhase {
    data object Authenticating : AuthPhase()
    data object Ready : AuthPhase()
    data class Failed(val message: String) : AuthPhase()
}

@Composable
private fun AuthProgress() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Text(
            "正在登录…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun AuthFailed(message: String, onRetry: () -> Unit, onSwitchServer: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) { Text("重试") }
        OutlinedButton(onClick = onSwitchServer) { Text("重新连接") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadyContent(
    api: WandApi,
    actions: HomeActions,
    initialQuickAction: QuickAction? = null,
) {
    val nav = rememberSaveable(saver = NavState.Saver) { NavState() }
    var initialQuickActionConsumed by rememberSaveable { mutableStateOf(false) }
    // 列表状态提升到这里：进聊天再返回时不丢已加载的会话与滚动位置。
    val listState = remember { SessionListState(api) }
    var showSettings by remember { mutableStateOf(false) }
    var sidebarCollapsed by rememberSaveable { mutableStateOf(false) }
    val settingsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    fun dismissSettings() {
        scope.launch {
            runCatching { settingsSheetState.hide() }
            showSettings = false
        }
    }

    // 认证就绪后消费一次长按图标快捷操作（对称 iOS consume）。
    LaunchedEffect(Unit) {
        if (!initialQuickActionConsumed) {
            initialQuickActionConsumed = true
            when (val action = initialQuickAction) {
                is QuickAction.NewSession -> nav.push(Screen.NewSession)
                is QuickAction.OpenWeb -> actions.openWeb()
                is QuickAction.OpenSession -> nav.push(Screen.Chat(action.sessionId))
                null -> {}
            }
        }
    }

    BackHandler(enabled = showSettings) { dismissSettings() }
    BackHandler(enabled = nav.stack.size > 1 && !showSettings) { nav.pop() }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wideLayout = maxWidth >= 640.dp && maxHeight >= 480.dp
        val listPaneWidth = when {
            maxWidth < 760.dp -> 304.dp
            maxWidth < 900.dp -> 336.dp
            else -> 368.dp
        }
        val openDetail: (Screen) -> Unit = { screen ->
            if (wideLayout) nav.setDetail(screen) else nav.push(screen)
        }
        val openSession: (SessionSnapshot) -> Unit = { session ->
            openDetail(session.detailScreen())
        }
        val openHistory: (HistorySession) -> Unit = { history ->
            scope.launch {
                try {
                    val resumed = listState.api.resumeHistory(history)
                    listState.removeHistoryLocally(history)
                    listState.prepend(resumed)
                    openDetail(resumed.detailScreen())
                } catch (e: Exception) {
                    listState.loadError = e.message ?: "恢复失败"
                }
            }
        }
        val openNewSession = { openDetail(Screen.NewSession) }
        val onCreated: (SessionSnapshot) -> Unit = { snapshot ->
            listState.prepend(snapshot)
            if (wideLayout) {
                nav.setDetail(snapshot.detailScreen())
            } else {
                nav.pop()
                nav.push(snapshot.detailScreen())
            }
        }

        if (wideLayout) {
            WideReadyContent(
                nav = nav,
                api = api,
                actions = actions,
                listState = listState,
                listPaneWidth = listPaneWidth,
                sidebarCollapsed = sidebarCollapsed,
                selectedSessionId = nav.current.sessionIdOrNull(),
                onOpenSession = openSession,
                onOpenHistory = openHistory,
                onNewSession = openNewSession,
                onOpenSettings = { showSettings = true },
                onToggleSidebarCollapsed = { sidebarCollapsed = !sidebarCollapsed },
                onCreated = onCreated,
            )
        } else {
            SinglePaneContent(
                nav = nav,
                api = api,
                actions = actions,
                listState = listState,
                onOpenSession = openSession,
                onNewSession = openNewSession,
                onOpenSettings = { showSettings = true },
                onCreated = onCreated,
            )
        }
    }

    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            sheetState = settingsSheetState,
            // 设置页本身是长列表。关闭 sheet 拖拽后，纵向手势只交给内部 verticalScroll，
            // 避免列表到达边界时与 ModalBottomSheet 的嵌套滚动反复争抢位移而抖动。
            sheetGesturesEnabled = false,
            containerColor = WandColors.bgElevated,
            scrimColor = Color.Black.copy(alpha = 0.42f),
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            dragHandle = null,
        ) {
            NoOverscroll {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.86f),
                ) {
                    SettingsScreen(
                        api = api,
                        actions = actions,
                        onBack = { dismissSettings() },
                    )
                }
            }
        }
    }
}

@Composable
private fun SinglePaneContent(
    nav: NavState,
    api: WandApi,
    actions: HomeActions,
    listState: SessionListState,
    onOpenSession: (SessionSnapshot) -> Unit,
    onNewSession: () -> Unit,
    onOpenSettings: () -> Unit,
    onCreated: (SessionSnapshot) -> Unit,
) {
    when (val screen = nav.current) {
        is Screen.SessionList -> SessionListScreen(
            state = listState,
            onOpenSession = onOpenSession,
            onNewSession = onNewSession,
            onOpenSettings = onOpenSettings,
            onOpenWeb = actions.openWeb,
            onSwitchServer = actions.switchServer,
        )
        is Screen.Chat -> ChatScreen(
            api = api,
            sessionId = screen.sessionId,
            isHapticEnabled = actions.isHapticEnabled,
            onBack = { nav.pop() },
        )
        is Screen.PtyTerminal -> PtyTerminalScreen(
            api = api,
            sessionId = screen.sessionId,
            isHapticEnabled = actions.isHapticEnabled,
            onBack = { nav.pop() },
        )
        is Screen.NewSession -> NewSessionScreen(
            api = api,
            onBack = { nav.pop() },
            onCreated = onCreated,
        )
    }
}

@Composable
private fun WideReadyContent(
    nav: NavState,
    api: WandApi,
    actions: HomeActions,
    listState: SessionListState,
    listPaneWidth: Dp,
    sidebarCollapsed: Boolean,
    selectedSessionId: String?,
    onOpenSession: (SessionSnapshot) -> Unit,
    onOpenHistory: (HistorySession) -> Unit,
    onNewSession: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleSidebarCollapsed: () -> Unit,
    onCreated: (SessionSnapshot) -> Unit,
) {
    val sidebarEndGutter = 8.dp
    val sidebarVerticalInset = 10.dp
    val sidebarContentWidth = if (sidebarCollapsed) 56.dp else listPaneWidth
    val sidebarWidth by animateDpAsState(
        targetValue = sidebarContentWidth + sidebarEndGutter,
        animationSpec = tween(durationMillis = 180),
        label = "wideSidebarWidth",
    )
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(WandColors.bgPrimary),
    ) {
        Box(
            modifier = Modifier
                .width(sidebarWidth)
                .fillMaxHeight()
                .padding(
                    top = sidebarVerticalInset,
                    end = sidebarEndGutter,
                    bottom = sidebarVerticalInset,
                ),
        ) {
            WideSidebarPanel(modifier = Modifier.fillMaxSize()) {
                if (sidebarCollapsed) {
                    CollapsedSessionRail(
                        listState = listState,
                        selectedSessionId = selectedSessionId,
                        onOpenSession = onOpenSession,
                        onOpenHistory = onOpenHistory,
                        onNewSession = onNewSession,
                    )
                } else {
                    SessionListScreen(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        selectedSessionId = selectedSessionId,
                        topBarContentHeight = 64.dp,
                        compactLayout = true,
                        onOpenSession = onOpenSession,
                        onNewSession = onNewSession,
                        onOpenSettings = onOpenSettings,
                        onOpenWeb = actions.openWeb,
                        onSwitchServer = actions.switchServer,
                    )
                }
            }
        }
        SplitPaneCollapseHandle(
            collapsed = sidebarCollapsed,
            onClick = onToggleSidebarCollapsed,
            modifier = Modifier.padding(vertical = sidebarVerticalInset),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            when (val screen = nav.current) {
                is Screen.SessionList -> DetailPlaceholder(onNewSession)
                is Screen.Chat -> ChatScreen(
                    api = api,
                    sessionId = screen.sessionId,
                    isHapticEnabled = actions.isHapticEnabled,
                    onBack = { nav.pop() },
                )
                is Screen.PtyTerminal -> PtyTerminalScreen(
                    api = api,
                    sessionId = screen.sessionId,
                    isHapticEnabled = actions.isHapticEnabled,
                    onBack = { nav.pop() },
                )
                is Screen.NewSession -> NewSessionScreen(
                    api = api,
                    onBack = { nav.pop() },
                    onCreated = onCreated,
                )
            }
        }
    }
}

@Composable
private fun WideSidebarPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp)
    val rimColor = WandColors.borderStrong.copy(alpha = 0.32f)
    Box(
        modifier = modifier
            .clip(shape)
            .background(WandColors.bgElevated.copy(alpha = 0.86f))
            .drawBehind {
                val x = size.width - 0.6.dp.toPx()
                val yInset = 22.dp.toPx()
                drawLine(
                    color = rimColor,
                    start = Offset(x, yInset),
                    end = Offset(x, size.height - yInset),
                    strokeWidth = 0.8.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            },
    ) {
        content()
    }
}

@Composable
private fun SplitPaneCollapseHandle(
    collapsed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackShape = RoundedCornerShape(999.dp)
    val knobShape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(116.dp)
                .clip(trackShape)
                .background(WandColors.borderStrong.copy(alpha = 0.24f)),
        )
        Box(
            modifier = Modifier
                .width(20.dp)
                .height(48.dp)
                .clip(knobShape)
                .background(WandColors.bgElevated.copy(alpha = 0.88f))
                .border(0.6.dp, WandColors.borderStrong.copy(alpha = 0.34f), knobShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                WandIcons.chevronRight,
                contentDescription = if (collapsed) "展开会话栏" else "收起会话栏",
                tint = WandColors.textSecondary,
                modifier = Modifier
                    .size(15.dp)
                    .graphicsLayer { scaleX = if (collapsed) 1f else -1f },
            )
        }
    }
}

@Composable
private fun CollapsedSessionRail(
    listState: SessionListState,
    selectedSessionId: String?,
    onOpenSession: (SessionSnapshot) -> Unit,
    onOpenHistory: (HistorySession) -> Unit,
    onNewSession: () -> Unit,
) {
    val entries = remember(listState.sessions, listState.historySessions) {
        collapsedRailEntries(listState)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WandColors.bgPrimary)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            entries.forEachIndexed { index, entry ->
                when (entry) {
                    is CollapsedRailEntry.Managed -> CollapsedSessionTile(
                        session = entry.session,
                        index = index + 1,
                        selected = entry.session.id == selectedSessionId,
                        onClick = { onOpenSession(entry.session) },
                    )
                    is CollapsedRailEntry.Recoverable -> CollapsedRecoverableSessionTile(
                        history = entry.history,
                        index = index + 1,
                        onClick = { onOpenHistory(entry.history) },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        CollapsedNewSessionTile(onClick = onNewSession)
    }
}

private sealed class CollapsedRailEntry {
    abstract val sortKey: Long

    data class Managed(
        val session: SessionSnapshot,
        override val sortKey: Long,
    ) : CollapsedRailEntry()

    data class Recoverable(
        val history: HistorySession,
        override val sortKey: Long,
    ) : CollapsedRailEntry()
}

private fun collapsedRailEntries(listState: SessionListState): List<CollapsedRailEntry> {
    return buildList {
        listState.visibleSessions.forEach { session ->
            add(CollapsedRailEntry.Managed(session, parseIsoMillis(session.startedAt)))
        }
        listState.visibleHistorySessions.forEach { history ->
            val sortKey = history.mtimeMs?.toLong() ?: parseIsoMillis(history.timestamp)
            add(CollapsedRailEntry.Recoverable(history, sortKey))
        }
    }.sortedByDescending { it.sortKey }
}

private fun parseIsoMillis(value: String?): Long =
    value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrDefault(0L) } ?: 0L

@Composable
private fun CollapsedSessionTile(
    session: SessionSnapshot,
    index: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val isCodex = session.provider == "codex"
    val tint = if (isCodex) WandColors.info else WandColors.brand
    val icon = if (isCodex) BrandLogos.codex else BrandLogos.claude
    CollapsedRailTile(
        icon = icon,
        tint = tint,
        selected = selected,
        badge = index.toString(),
        contentDescription = "$index. ${session.providerLabel} ${session.displayTitle}",
        onClick = onClick,
    )
}

@Composable
private fun CollapsedRecoverableSessionTile(
    history: HistorySession,
    index: Int,
    onClick: () -> Unit,
) {
    val isCodex = history.provider == "codex"
    val tint = if (isCodex) WandColors.info else WandColors.brand
    val icon = if (isCodex) BrandLogos.codex else BrandLogos.claude
    CollapsedRailTile(
        icon = icon,
        tint = tint,
        selected = false,
        badge = index.toString(),
        contentDescription = "$index. ${if (isCodex) "Codex" else "Claude"} ${history.firstUserMessage.ifEmpty { "会话" }}",
        onClick = onClick,
    )
}

@Composable
private fun CollapsedNewSessionTile(onClick: () -> Unit) {
    CollapsedRailTile(
        icon = WandIcons.add,
        tint = WandColors.brand,
        selected = false,
        badge = null,
        contentDescription = "新建会话",
        outlined = true,
        onClick = onClick,
    )
}

@Composable
private fun CollapsedRailTile(
    icon: ImageVector,
    tint: Color,
    selected: Boolean,
    badge: String?,
    contentDescription: String,
    outlined: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val badgeShape = RoundedCornerShape(
        topStart = 7.dp,
        topEnd = 5.dp,
        bottomEnd = 8.dp,
        bottomStart = 5.dp,
    )
    val background = when {
        outlined -> WandColors.brand.copy(alpha = 0.06f)
        selected -> tint.copy(alpha = 0.22f)
        else -> tint.copy(alpha = 0.10f)
    }
    val borderColor = when {
        outlined -> WandColors.brand.copy(alpha = 0.50f)
        selected -> tint.copy(alpha = 0.72f)
        else -> tint.copy(alpha = 0.20f)
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(shape)
                .background(background)
                .border(if (selected || outlined) 1.dp else 0.55.dp, borderColor, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = tint.copy(alpha = if (outlined) 0.86f else 0.90f),
                modifier = Modifier.size(if (outlined) 20.dp else 19.dp),
            )
        }
        if (badge != null) {
            val badgeWidth = when {
                badge.length <= 1 -> 17.dp
                badge.length == 2 -> 21.dp
                else -> 25.dp
            }
            val badgeBackground = WandColors.surfaceSoft.copy(alpha = if (selected) 0.98f else 0.92f)
            val badgeBorder = tint.copy(alpha = if (selected) 0.78f else 0.46f)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = (-5).dp, y = (-5).dp)
                    .width(badgeWidth)
                    .height(16.dp)
                    .clip(badgeShape)
                    .background(badgeBackground)
                    .border(0.7.dp, badgeBorder, badgeShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    badge,
                    color = tint.copy(alpha = if (selected) 1f else 0.92f),
                    fontSize = 9.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun DetailPlaceholder(onNewSession: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WandColors.bgPrimary)
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WandBrandMark(size = 58)
        Text(
            "选择会话",
            style = MaterialTheme.typography.titleMedium,
            color = WandColors.textPrimary,
            modifier = Modifier.padding(top = 18.dp),
        )
        Text(
            "继续现有对话，或开始新的工作。",
            style = MaterialTheme.typography.bodyMedium,
            color = WandColors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(
            onClick = onNewSession,
            colors = ButtonDefaults.buttonColors(containerColor = WandColors.brand),
            modifier = Modifier.padding(top = 18.dp),
        ) {
            Text("新建会话")
        }
    }
}

private fun SessionSnapshot.detailScreen(): Screen =
    if (isStructured) Screen.Chat(id) else Screen.PtyTerminal(id)

private fun Screen.sessionIdOrNull(): String? = when (this) {
    is Screen.Chat -> sessionId
    is Screen.PtyTerminal -> sessionId
    Screen.SessionList,
    Screen.NewSession -> null
}
