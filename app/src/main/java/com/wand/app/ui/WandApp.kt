package com.wand.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wand.app.SessionCreationCoordinator
import com.wand.app.data.HistorySession
import com.wand.app.data.SessionListEntry
import com.wand.app.data.WandApi
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.WandAuth
import com.wand.app.data.providerDisplayName
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.components.NoOverscroll
import com.wand.app.ui.components.WandBrandMark
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandBottomSheet
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.ambientBackground
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.screens.ChatScreen
import com.wand.app.ui.screens.NewSessionScreen
import com.wand.app.ui.screens.MissionsScreen
import com.wand.app.ui.screens.PtyTerminalScreen
import com.wand.app.ui.screens.SessionListScreen
import com.wand.app.ui.screens.SessionListState
import com.wand.app.ui.screens.SessionListViewMode
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
            .ambientBackground()
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
private const val SessionListViewPreferences = "wand-session-list-view"
private const val SessionListViewPreferenceKey = "mode"

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
        WandBrandMark(size = 52)
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp,
            modifier = Modifier
                .padding(top = 20.dp)
                .size(20.dp),
        )
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        WandCard(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    WandIcons.error,
                    contentDescription = null,
                    tint = WandColors.danger,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                WandButton(label = "重试", onClick = onRetry, modifier = Modifier.fillMaxWidth())
                WandButton(
                    label = "重新连接",
                    onClick = onSwitchServer,
                    modifier = Modifier.fillMaxWidth(),
                    variant = WandButtonVariant.Secondary,
                )
            }
        }
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
    val creationState by SessionCreationCoordinator.state.collectAsStateWithLifecycle(
        minActiveState = Lifecycle.State.RESUMED,
    )
    val sessionCreationInFlight = creationState !is SessionCreationCoordinator.State.Idle
    var sidebarCollapsed by rememberSaveable { mutableStateOf(false) }
    val viewPreferences = remember(context) {
        context.getSharedPreferences(SessionListViewPreferences, android.content.Context.MODE_PRIVATE)
    }
    var sessionListViewMode by rememberSaveable {
        mutableStateOf(
            if (viewPreferences.getString(SessionListViewPreferenceKey, null) == "directories") {
                SessionListViewMode.Directories
            } else {
                SessionListViewMode.Sessions
            },
        )
    }
    val settingsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // 会话同步跟随 ReadyContent 生命周期，而不是跟随“完整列表是否正在组合”。
    // 宽屏折叠 rail、聊天详情和列表页因此始终共享同一份加载/轮询状态。
    DisposableEffect(listState) {
        listState.startSync()
        onDispose { listState.shutdown() }
    }
    LaunchedEffect(listState.sessions, actions.connection.serverId) {
        com.wand.app.WandShortcuts.update(
            context,
            actions.connection.serverId,
            listState.sessions,
        )
    }
    // 目录数据跟随提升后的视图偏好，而不是依赖展开侧栏是否正在组合。
    // 这样从已保存的「目录 + 收起」状态恢复时也能正常加载，切换动画也不会取消请求。
    LaunchedEffect(sessionListViewMode, listState.entries) {
        if (sessionListViewMode == SessionListViewMode.Directories) {
            listState.loadDirectories(silent = listState.directoryTree != null)
        }
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
                is QuickAction.NewSession -> nav.push(Screen.NewSession())
                is QuickAction.OpenWeb -> actions.navigation.openWeb()
                is QuickAction.OpenSession -> nav.push(
                    if (action.isStructured == false) {
                        Screen.PtyTerminal(action.sessionId)
                    } else {
                        Screen.Chat(action.sessionId)
                    },
                )
                null -> {}
            }
        }
    }

    BackHandler(enabled = showSettings) { dismissSettings() }
    BackHandler(
        enabled = nav.stack.size > 1 && !showSettings && !sessionCreationInFlight,
    ) { nav.pop() }
    BackHandler(
        enabled = sessionCreationInFlight && nav.current !is Screen.NewSession,
    ) {}

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wideLayout = usesWideListDetail(maxWidth, maxHeight)
        val listPaneWidth = wideListPaneWidth(maxWidth)
        val openDetail: (Screen) -> Unit = { screen ->
            if (!sessionCreationInFlight) {
                if (wideLayout) nav.setDetail(screen) else nav.push(screen)
            }
        }
        val openSession: (SessionSnapshot) -> Unit = { session ->
            openDetail(session.detailScreen())
        }
        val openHistory: (HistorySession) -> Unit = { history ->
            if (!sessionCreationInFlight && !listState.isRestoringHistory) {
                scope.launch {
                    listState.restore(history)?.let { resumed ->
                        openDetail(resumed.detailScreen())
                    }
                }
            }
        }
        val openNewSession: (String?) -> Unit = { cwd -> openDetail(Screen.NewSession(cwd)) }
        val changeSessionListView: (SessionListViewMode) -> Unit = { mode ->
            sessionListViewMode = mode
            viewPreferences.edit()
                .putString(SessionListViewPreferenceKey, if (mode == SessionListViewMode.Directories) "directories" else "sessions")
                .apply()
        }
        val openMissions = { openDetail(Screen.Missions) }

        if (wideLayout) {
            WideReadyContent(
                nav = nav,
                api = api,
                actions = actions,
                listState = listState,
                listPaneWidth = listPaneWidth,
                sidebarCollapsed = sidebarCollapsed,
                selectedSessionId = nav.current.sessionIdOrNull(),
                viewMode = sessionListViewMode,
                onOpenSession = openSession,
                onOpenHistory = openHistory,
                onNewSession = openNewSession,
                onOpenMissions = openMissions,
                onOpenSettings = { if (!sessionCreationInFlight) showSettings = true },
                onToggleSidebarCollapsed = { sidebarCollapsed = !sidebarCollapsed },
                onViewModeChange = changeSessionListView,
                sessionCreationInFlight = sessionCreationInFlight,
            )
        } else {
            SinglePaneContent(
                nav = nav,
                api = api,
                actions = actions,
                listState = listState,
                viewMode = sessionListViewMode,
                onOpenSession = openSession,
                onNewSession = openNewSession,
                onOpenMissions = openMissions,
                onOpenSettings = { if (!sessionCreationInFlight) showSettings = true },
                onViewModeChange = changeSessionListView,
                sessionCreationInFlight = sessionCreationInFlight,
            )
        }
        if (sessionCreationInFlight && nav.current !is Screen.NewSession) {
            SessionCreationRecoveryOverlay()
        }
    }

    if (showSettings) {
        WandBottomSheet(
            onDismissRequest = { showSettings = false },
            sheetState = settingsSheetState,
            // 设置页本身是长列表。关闭 sheet 拖拽后，纵向手势只交给内部 verticalScroll，
            // 避免列表到达边界时与 ModalBottomSheet 的嵌套滚动反复争抢位移而抖动。
            gesturesEnabled = false,
            transparent = true,
            showDragHandle = false,
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
    viewMode: SessionListViewMode,
    onOpenSession: (SessionSnapshot) -> Unit,
    onNewSession: (String?) -> Unit,
    onOpenMissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onViewModeChange: (SessionListViewMode) -> Unit,
    sessionCreationInFlight: Boolean,
) {
    when (val screen = nav.current) {
        is Screen.SessionList -> SessionListScreen(
            state = listState,
            serverDisplayName = actions.connection.serverDisplayName,
            interactionEnabled = !sessionCreationInFlight,
            viewMode = viewMode,
            onOpenSession = onOpenSession,
            onNewSession = onNewSession,
            onViewModeChange = onViewModeChange,
            onOpenMissions = onOpenMissions,
            onOpenSettings = onOpenSettings,
            onOpenWeb = {
                if (!sessionCreationInFlight) actions.navigation.openWeb()
            },
            onSwitchServer = {
                if (!sessionCreationInFlight) actions.navigation.switchServer()
            },
        )
        is Screen.Chat -> ChatScreen(
            api = api,
            sessionId = screen.sessionId,
            serverDisplayName = actions.connection.serverDisplayName,
            isHapticEnabled = actions.settings.isHapticEnabled,
            onBack = { nav.pop() },
        )
        is Screen.PtyTerminal -> PtyTerminalScreen(
            api = api,
            sessionId = screen.sessionId,
            serverDisplayName = actions.connection.serverDisplayName,
            isHapticEnabled = actions.settings.isHapticEnabled,
            onBack = { nav.pop() },
        )
        is Screen.NewSession -> NewSessionScreen(
            api = api,
            servers = actions.servers,
            activeServerId = actions.connection.serverId,
            initialCwd = screen.initialCwd,
            creating = sessionCreationInFlight,
            onReconnectServer = actions.navigation.reconnectServer,
            onBack = { nav.pop() },
        )
        is Screen.Missions -> MissionsScreen(
            api = api,
            onBack = { nav.pop() },
            onOpenSession = { sessionId -> nav.push(Screen.Chat(sessionId)) },
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
    viewMode: SessionListViewMode,
    onOpenSession: (SessionSnapshot) -> Unit,
    onOpenHistory: (HistorySession) -> Unit,
    onNewSession: (String?) -> Unit,
    onOpenMissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleSidebarCollapsed: () -> Unit,
    onViewModeChange: (SessionListViewMode) -> Unit,
    sessionCreationInFlight: Boolean,
) {
    val lockedSidebarInteraction = remember { MutableInteractionSource() }
    val sidebarContentWidth = if (sidebarCollapsed) 56.dp else listPaneWidth
    val sidebarWidth by animateDpAsState(
        targetValue = sidebarContentWidth,
        animationSpec = WandMotion.settleSpringSpec(),
        label = "wideSidebarWidth",
    )
    Row(
        modifier = Modifier
            .fillMaxSize()
            .ambientBackground(),
    ) {
        Box(
            modifier = Modifier
                .width(sidebarWidth)
                .fillMaxHeight(),
        ) {
            WideSidebarPanel(modifier = Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = sidebarCollapsed,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        val enterDelay = if (targetState) 55 else 75
                        fadeIn(
                            tween(
                                durationMillis = 105,
                                delayMillis = enterDelay,
                                easing = WandMotion.easing,
                            ),
                        ) togetherWith fadeOut(
                            tween(durationMillis = 70, easing = WandMotion.easing),
                        )
                    },
                    label = "wideSidebarContent",
                ) { collapsed ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        if (collapsed) {
                            CollapsedSessionRail(
                                listState = listState,
                                selectedSessionId = selectedSessionId,
                                viewMode = viewMode,
                                onOpenSession = onOpenSession,
                                onOpenHistory = onOpenHistory,
                                onNewSession = onNewSession,
                                onOpenMissions = onOpenMissions,
                                onExpandSidebar = onToggleSidebarCollapsed,
                            )
                        } else {
                            SessionListScreen(
                                state = listState,
                                serverDisplayName = actions.connection.serverDisplayName,
                                modifier = Modifier.fillMaxSize(),
                                selectedSessionId = selectedSessionId,
                                topBarContentHeight = 64.dp,
                                compactLayout = true,
                                interactionEnabled = !sessionCreationInFlight,
                                viewMode = viewMode,
                                onOpenSession = onOpenSession,
                                onNewSession = onNewSession,
                                onViewModeChange = onViewModeChange,
                                onOpenMissions = onOpenMissions,
                                onOpenSettings = onOpenSettings,
                                onOpenWeb = {
                                    if (!sessionCreationInFlight) actions.navigation.openWeb()
                                },
                                onSwitchServer = {
                                    if (!sessionCreationInFlight) actions.navigation.switchServer()
                                },
                                onCollapseSidebar = onToggleSidebarCollapsed,
                            )
                        }
                    }
                }
            }
            if (sessionCreationInFlight) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(WandColors.bgPrimary.copy(alpha = 0.08f))
                        .clickable(
                            interactionSource = lockedSidebarInteraction,
                            indication = null,
                            onClick = {},
                        ),
                )
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            when (val screen = nav.current) {
                is Screen.SessionList -> DetailPlaceholder { onNewSession(null) }
                is Screen.Chat -> ChatScreen(
                    api = api,
                    sessionId = screen.sessionId,
                    serverDisplayName = actions.connection.serverDisplayName,
                    isHapticEnabled = actions.settings.isHapticEnabled,
                    onBack = { nav.pop() },
                )
                is Screen.PtyTerminal -> PtyTerminalScreen(
                    api = api,
                    sessionId = screen.sessionId,
                    serverDisplayName = actions.connection.serverDisplayName,
                    isHapticEnabled = actions.settings.isHapticEnabled,
                    onBack = { nav.pop() },
                )
                is Screen.NewSession -> NewSessionScreen(
                    api = api,
                    servers = actions.servers,
                    activeServerId = actions.connection.serverId,
                    initialCwd = screen.initialCwd,
                    creating = sessionCreationInFlight,
                    onReconnectServer = actions.navigation.reconnectServer,
                    onBack = { nav.pop() },
                )
                is Screen.Missions -> MissionsScreen(
                    api = api,
                    onBack = { nav.pop() },
                    onOpenSession = { sessionId -> nav.setDetail(Screen.Chat(sessionId)) },
                )
            }
        }
    }
}

@Composable
private fun SessionCreationRecoveryOverlay() {
    val blockerInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WandColors.bgPrimary.copy(alpha = 0.88f))
            .clickable(
                interactionSource = blockerInteraction,
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        WandCard(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth()
                .widthIn(max = 360.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    "正在创建会话",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "请求仍在所选服务器上运行，完成后会自动打开。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
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
    val rimColor = WandColors.borderStrong.copy(alpha = 0.38f)
    val panelBrush = Brush.verticalGradient(
        colors = listOf(
            WandColors.bgElevated.copy(alpha = 0.94f),
            WandColors.bgElevated.copy(alpha = 0.84f),
            WandColors.bgPrimary.copy(alpha = 0.90f),
        ),
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(panelBrush)
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
    viewMode: SessionListViewMode,
    onOpenSession: (SessionSnapshot) -> Unit,
    onOpenHistory: (HistorySession) -> Unit,
    onNewSession: (String?) -> Unit,
    onOpenMissions: () -> Unit,
    onExpandSidebar: () -> Unit,
) {
    val entries = listState.entries
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .width(56.dp)
            .fillMaxHeight()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val showingSessions = viewMode == SessionListViewMode.Sessions
        CollapsedRailTile(
            icon = rememberVectorPainter(if (showingSessions) WandIcons.chat else WandIcons.folder),
            iconTint = WandColors.brand,
            accentTint = WandColors.brand,
            selected = true,
            selectionStateEnabled = true,
            contentDescription = if (showingSessions) "当前为会话视图" else "当前为目录视图",
            onClickLabel = if (showingSessions) "展开会话栏" else "展开目录栏",
            outlined = true,
            onClick = onExpandSidebar,
        )
        Spacer(modifier = Modifier.height(10.dp))
        CollapsedRailDivider()
        Spacer(modifier = Modifier.height(10.dp))
        val contentLoading = when (viewMode) {
            SessionListViewMode.Sessions -> listState.loading && entries.isEmpty()
            SessionListViewMode.Directories -> listState.directoryLoading && listState.directoryTree == null
        }
        if (contentLoading) {
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
        } else if (viewMode == SessionListViewMode.Sessions) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(
                    items = entries,
                    key = { entry -> entry.key },
                    contentType = { entry ->
                        when (entry) {
                            is SessionListEntry.Managed -> "managed"
                            is SessionListEntry.Recoverable -> "recoverable"
                        }
                    },
                ) { entry ->
                    when (entry) {
                        is SessionListEntry.Managed -> CollapsedSessionTile(
                            session = entry.session,
                            selected = entry.session.id == selectedSessionId,
                            onClick = { onOpenSession(entry.session) },
                        )
                        is SessionListEntry.Recoverable -> CollapsedRecoverableSessionTile(
                            history = entry.history,
                            loading = listState.isRestoring(entry.history),
                            onClick = { onOpenHistory(entry.history) },
                        )
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val directoryError = listState.directoryError
                if (directoryError != null && listState.directoryTree == null) {
                    CollapsedRailTile(
                        icon = rememberVectorPainter(WandIcons.error),
                        iconTint = WandColors.danger,
                        selected = false,
                        contentDescription = directoryError,
                        onClickLabel = "重新加载目录",
                        outlined = true,
                        onClick = { scope.launch { listState.loadDirectories() } },
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = (listState.directoryTree?.directoryCount ?: 0).toString(),
                            style = MaterialTheme.typography.titleSmall,
                            color = WandColors.textSecondary,
                        )
                        Text(
                            text = "目录",
                            fontSize = 9.sp,
                            color = WandColors.textMuted,
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        CollapsedRailDivider()
        Spacer(modifier = Modifier.height(8.dp))
        CollapsedNewSessionTile(onClick = { onNewSession(null) })
        Spacer(modifier = Modifier.height(4.dp))
        CollapsedMissionsTile(onClick = onOpenMissions)
    }
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
private fun CollapsedSessionTile(
    session: SessionSnapshot,
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
        iconScale = BrandLogos.opticalScale(session.provider),
        accentTint = accentTint,
        selected = selected,
        selectionStateEnabled = true,
        contentDescription = "${session.providerLabel} ${session.displayTitle}",
        onClickLabel = "打开会话",
        onClick = onClick,
    )
}

@Composable
private fun CollapsedRecoverableSessionTile(
    history: HistorySession,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val provider = history.apiProvider
    val tint = if (provider == "codex") WandColors.info else WandColors.brand
    val iconTint = BrandLogos.tintForProvider(provider, tint)
    val icon = BrandLogos.painterForProvider(provider)
    CollapsedRailTile(
        icon = icon,
        iconTint = iconTint,
        iconScale = BrandLogos.opticalScale(provider),
        selected = false,
        contentDescription = buildString {
            append("可恢复的 ")
            append(providerDisplayName(provider))
            append(' ')
            append(history.firstUserMessage.ifEmpty { "会话" })
        },
        onClickLabel = "恢复会话",
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
        contentDescription = "新建会话",
        onClickLabel = "新建会话",
        emphasized = true,
        onClick = onClick,
    )
}

@Composable
private fun CollapsedMissionsTile(onClick: () -> Unit) {
    CollapsedRailTile(
        icon = rememberVectorPainter(WandIcons.agent),
        iconTint = WandColors.info,
        selected = false,
        contentDescription = "Agent Inbox",
        onClickLabel = "打开 Agent Inbox",
        outlined = true,
        onClick = onClick,
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
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(durationMillis = 110, easing = WandMotion.easing),
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
                if (loading) stateDescription = "正在恢复会话"
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

@Composable
private fun DetailPlaceholder(onNewSession: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .ambientBackground()
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
        WandButton(
            label = "新建会话",
            onClick = onNewSession,
            modifier = Modifier.padding(top = 18.dp),
        )
    }
}

private fun SessionSnapshot.detailScreen(): Screen =
    if (isStructured) Screen.Chat(id) else Screen.PtyTerminal(id)

private fun Screen.sessionIdOrNull(): String? = when (this) {
    is Screen.Chat -> sessionId
    is Screen.PtyTerminal -> sessionId
    Screen.SessionList,
    Screen.Missions -> null
    is Screen.NewSession -> null
}
