package com.wand.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.data.HistorySession
import com.wand.app.data.SessionListEntry
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
import kotlin.math.roundToInt
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
            if (actions.connection.hasToken && api.token != null) {
                WandAuth.loginWithToken(api.baseUrl, api.token)
            } else {
                // 裸地址连接（无 token）：直接试列表，401 时引导重新连接。
                api.fetchSessionList(offset = 0, limit = 1, revision = null)
            }
            onAuthenticated()
            AuthPhase.Ready
        } catch (e: Exception) {
            val msg = e.message ?: "未知错误"
            if (actions.connection.hasToken) {
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
                onSwitchServer = actions.navigation.switchServer,
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

private val WideLayoutMinWidth = 640.dp
private val WideLayoutMinHeight = 480.dp
private val MediumSidebarMinWidth = 232.dp
private val MediumSidebarMaxWidth = 280.dp
private val ExpandedSidebarMinWidth = 280.dp
private val ExpandedSidebarMaxWidth = 360.dp
private val ExpandedDetailMinWidth = 560.dp

/**
 * 展开折叠屏与平板会在运行时反复跨越这个边界；只依据当前窗口尺寸，
 * 不依赖设备类型或物理方向，才能同时覆盖分屏和自由窗口。
 */
internal fun usesWideListDetail(width: Dp, height: Dp): Boolean =
    width >= WideLayoutMinWidth && height >= WideLayoutMinHeight

/**
 * 中等宽度优先给详情区留下可读空间；840dp 起再逐步放宽列表栏，
 * 避免旧的固定 304/336/368dp 阶梯在断点处突然挤压主内容。
 */
internal fun wideListPaneWidth(windowWidth: Dp): Dp =
    if (windowWidth < 840.dp) {
        (windowWidth.value * 0.36f).roundToInt().dp
            .coerceIn(MediumSidebarMinWidth, MediumSidebarMaxWidth)
    } else {
        (windowWidth - ExpandedDetailMinWidth)
            .coerceIn(ExpandedSidebarMinWidth, ExpandedSidebarMaxWidth)
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
    val listState = remember(api) { SessionListState(api) }
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var sidebarCollapsed by rememberSaveable { mutableStateOf(false) }
    val settingsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // 会话同步跟随 ReadyContent 生命周期，而不是跟随“完整列表是否正在组合”。
    // 宽屏折叠 rail、聊天详情和列表页因此始终共享同一份加载/轮询状态。
    DisposableEffect(listState) {
        listState.startSync()
        onDispose { listState.shutdown() }
    }
    LaunchedEffect(listState.sessions) {
        com.wand.app.WandShortcuts.update(context, listState.sessions)
    }

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
                is QuickAction.OpenWeb -> actions.navigation.openWeb()
                is QuickAction.OpenSession -> nav.push(Screen.Chat(action.sessionId))
                null -> {}
            }
        }
    }

    BackHandler(enabled = showSettings) { dismissSettings() }
    BackHandler(enabled = nav.stack.size > 1 && !showSettings) { nav.pop() }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wideLayout = usesWideListDetail(maxWidth, maxHeight)
        val listPaneWidth = wideListPaneWidth(maxWidth)
        val openDetail: (Screen) -> Unit = { screen ->
            if (wideLayout) nav.setDetail(screen) else nav.push(screen)
        }
        val openSession: (SessionSnapshot) -> Unit = { session ->
            openDetail(session.detailScreen())
        }
        val openHistory: (HistorySession) -> Unit = { history ->
            if (!listState.isRestoringHistory) {
                scope.launch {
                    listState.restore(history)?.let { resumed ->
                        openDetail(resumed.detailScreen())
                    }
                }
            }
        }
        val openNewSession = { openDetail(Screen.NewSession) }
        val onCreated: (SessionSnapshot) -> Unit = { snapshot ->
            listState.addCreated(snapshot)
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
                        connection = actions.connection,
                        navigation = actions.navigation,
                        settings = actions.settings,
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
            onOpenWeb = actions.navigation.openWeb,
            onSwitchServer = actions.navigation.switchServer,
        )
        is Screen.Chat -> ChatScreen(
            api = api,
            sessionId = screen.sessionId,
            isHapticEnabled = actions.settings.isHapticEnabled,
            onBack = { nav.pop() },
        )
        is Screen.PtyTerminal -> PtyTerminalScreen(
            api = api,
            sessionId = screen.sessionId,
            isHapticEnabled = actions.settings.isHapticEnabled,
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
    val sidebarContentWidth = if (sidebarCollapsed) 56.dp else listPaneWidth
    val sidebarWidth by animateDpAsState(
        targetValue = sidebarContentWidth,
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
                .fillMaxHeight(),
        ) {
            WideSidebarPanel(modifier = Modifier.fillMaxSize()) {
                if (sidebarCollapsed) {
                    CollapsedSessionRail(
                        listState = listState,
                        selectedSessionId = selectedSessionId,
                        onOpenSession = onOpenSession,
                        onOpenHistory = onOpenHistory,
                        onNewSession = onNewSession,
                        onExpandSidebar = onToggleSidebarCollapsed,
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
                        onOpenWeb = actions.navigation.openWeb,
                        onSwitchServer = actions.navigation.switchServer,
                        onCollapseSidebar = onToggleSidebarCollapsed,
                    )
                }
            }
        }
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
                    isHapticEnabled = actions.settings.isHapticEnabled,
                    onBack = { nav.pop() },
                )
                is Screen.PtyTerminal -> PtyTerminalScreen(
                    api = api,
                    sessionId = screen.sessionId,
                    isHapticEnabled = actions.settings.isHapticEnabled,
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
    val shape = RoundedCornerShape(0.dp)
    val rimColor = WandColors.borderStrong.copy(alpha = 0.32f)
    Box(
        modifier = modifier
            .clip(shape)
            .background(WandColors.bgElevated.copy(alpha = 0.86f))
            .drawBehind {
                val x = size.width - 0.6.dp.toPx()
                drawLine(
                    color = rimColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 0.8.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            },
    ) {
        content()
    }
}

@Composable
private fun CollapsedSessionRail(
    listState: SessionListState,
    selectedSessionId: String?,
    onOpenSession: (SessionSnapshot) -> Unit,
    onOpenHistory: (HistorySession) -> Unit,
    onNewSession: () -> Unit,
    onExpandSidebar: () -> Unit,
) {
    val entries = listState.entries
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WandColors.bgPrimary)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CollapsedRailTile(
            icon = rememberVectorPainter(WandIcons.chevronRight),
            iconTint = WandColors.textSecondary,
            selected = false,
            badge = null,
            contentDescription = "展开会话栏",
            outlined = true,
            onClick = onExpandSidebar,
        )
        Spacer(modifier = Modifier.height(10.dp))
        if (listState.loading && entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = WandColors.brand,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                itemsIndexed(
                    items = entries,
                    key = { _, entry -> entry.key },
                    contentType = { _, entry ->
                        when (entry) {
                            is SessionListEntry.Managed -> "managed"
                            is SessionListEntry.Recoverable -> "recoverable"
                        }
                    },
                ) { index, entry ->
                    when (entry) {
                        is SessionListEntry.Managed -> CollapsedSessionTile(
                            session = entry.session,
                            index = index + 1,
                            selected = entry.session.id == selectedSessionId,
                            onClick = { onOpenSession(entry.session) },
                        )
                        is SessionListEntry.Recoverable -> CollapsedRecoverableSessionTile(
                            history = entry.history,
                            index = index + 1,
                            loading = listState.isRestoring(entry.history),
                            onClick = { onOpenHistory(entry.history) },
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        CollapsedNewSessionTile(onClick = onNewSession)
    }
}

@Composable
private fun CollapsedSessionTile(
    session: SessionSnapshot,
    index: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val isCodex = session.provider == "codex"
    val accentTint = if (isCodex) WandColors.info else WandColors.brand
    val iconTint = BrandLogos.tintForProvider(
        session.provider,
        accentTint,
    )
    val icon = BrandLogos.painterForProvider(session.provider)
    CollapsedRailTile(
        icon = icon,
        iconTint = iconTint,
        accentTint = accentTint,
        selected = selected,
        selectionStateEnabled = true,
        badge = index.toString(),
        contentDescription = "$index. ${session.providerLabel} ${session.displayTitle}",
        onClick = onClick,
    )
}

@Composable
private fun CollapsedRecoverableSessionTile(
    history: HistorySession,
    index: Int,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val isCodex = history.provider == "codex"
    val tint = if (isCodex) WandColors.info else WandColors.brand
    val icon = rememberVectorPainter(if (isCodex) BrandLogos.codex else BrandLogos.claude)
    CollapsedRailTile(
        icon = icon,
        iconTint = tint,
        selected = false,
        badge = index.toString(),
        contentDescription = buildString {
            append(index)
            append(". 可恢复的 ")
            append(if (isCodex) "Codex" else "Claude")
            append(' ')
            append(history.firstUserMessage.ifEmpty { "会话" })
        },
        loading = loading,
        onClick = onClick,
    )
}

@Composable
private fun CollapsedNewSessionTile(onClick: () -> Unit) {
    CollapsedRailTile(
        icon = rememberVectorPainter(WandIcons.add),
        iconTint = WandColors.brand,
        selected = false,
        badge = null,
        contentDescription = "新建会话",
        outlined = true,
        onClick = onClick,
    )
}

@Composable
private fun CollapsedRailTile(
    icon: Painter,
    iconTint: Color,
    selected: Boolean,
    badge: String?,
    contentDescription: String,
    accentTint: Color = iconTint,
    selectionStateEnabled: Boolean = false,
    outlined: Boolean = false,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val badgeShape = RoundedCornerShape(8.dp)
    val showContainer = outlined || selected
    val background = when {
        outlined -> WandColors.brand.copy(alpha = 0.06f)
        selected -> accentTint.copy(alpha = 0.12f)
        else -> Color.Transparent
    }
    val borderColor = when {
        outlined -> WandColors.brand.copy(alpha = 0.50f)
        selected -> accentTint.copy(alpha = 0.52f)
        else -> Color.Transparent
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(
                enabled = !loading,
                onClickLabel = contentDescription,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                this.contentDescription = contentDescription
                if (selectionStateEnabled) this.selected = selected
                if (loading) stateDescription = "正在恢复会话"
            },
        contentAlignment = Alignment.Center,
    ) {
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
                    }
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
                    // Color.Unspecified must survive so multicolor assets do not receive a black filter.
                    tint = BrandLogos.tintWithAlpha(
                        iconTint,
                        alpha = if (outlined) 0.86f else 0.94f,
                    ),
                    modifier = Modifier.size(if (outlined) 20.dp else 25.dp),
                )
            }
        }
        if (badge != null) {
            val badgeWidth = when {
                badge.length <= 1 -> 17.dp
                badge.length == 2 -> 21.dp
                else -> 25.dp
            }
            val badgeBackground = WandColors.surfaceSoft.copy(alpha = if (selected) 0.98f else 0.92f)
            val badgeBorder = accentTint.copy(alpha = if (selected) 0.78f else 0.46f)
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
                    color = accentTint.copy(alpha = if (selected) 1f else 0.92f),
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
