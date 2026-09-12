package com.wand.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wand.app.data.WandApi
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.WandAuth
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.WorkspaceTaskSummary
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.components.WandBrandMark
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.ambientBackground
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.reduceMotionEnabled
import com.wand.app.ui.screens.ChatScreen
import com.wand.app.ui.screens.HomeListMode
import com.wand.app.ui.screens.MissionsScreen
import com.wand.app.ui.screens.TaskBoardScreen
import com.wand.app.ui.screens.PtyTerminalScreen
import com.wand.app.ui.screens.SettingsScreen
import com.wand.app.ui.screens.SharedTaskListExpansionStore
import com.wand.app.ui.screens.TaskListScreen
import com.wand.app.ui.screens.TaskListState
import com.wand.app.ui.screens.collapsedRailTasks
import com.wand.app.ui.screens.TaskSessionRoute
import com.wand.app.ui.screens.siblingSessionsFor
import com.wand.app.ui.screens.taskSessionTransitionDirection
import com.wand.app.ui.screens.WorkspaceTaskScreen
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
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
        var attempt = 0
        while (attempt < MAX_AUTH_RETRY_ATTEMPTS) {
            phase = AuthPhase.Authenticating
            phase = try {
                if (actions.connection.hasToken && api.token != null) {
                    WandAuth.loginWithToken(api.baseUrl, api.token)
                } else {
                    // 裸地址连接（无 token）：直接试列表，401 时引导重新连接。
                    api.listSessions()
                }
                onAuthenticated()
                AuthPhase.Ready
            } catch (e: Exception) {
                val msg = e.message ?: "未知错误"
                AuthPhase.Failed(
                    message = if (actions.connection.hasToken) {
                        msg
                    } else {
                        "无法访问服务器：$msg\n如果服务器设有密码，请用「连接码」重新连接。"
                    },
                    retryAttempt = attempt + 1,
                )
            }

            if (phase is AuthPhase.Ready) return@LaunchedEffect

            val delayMs = reconnectDelayMs(attempt)
            attempt++
            delay(delayMs)
        }

        // 达到重试上限后，返回服务器选择页让用户手动重连。
        actions.navigation.switchServer()
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
                retryAttempt = p.retryAttempt,
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
    data class Failed(val message: String, val retryAttempt: Int) : AuthPhase()
}

internal fun reconnectDelayMs(attempt: Int): Long {
    val exponent = attempt.coerceIn(0, 6)
    return (1_000L shl exponent).coerceAtMost(60_000L)
}

private const val MAX_AUTH_RETRY_ATTEMPTS = 10

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
private fun AuthFailed(
    message: String,
    retryAttempt: Int,
    onRetry: () -> Unit,
    onSwitchServer: () -> Unit,
) {
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
                Text(
                    "连接失败，正在自动重试（${retryAttempt}/${MAX_AUTH_RETRY_ATTEMPTS}）…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                WandButton(label = "立即重试", onClick = onRetry, modifier = Modifier.fillMaxWidth())
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
    val sessionDrafts = rememberSaveable(api.baseUrl, saver = SessionDraftStore.Saver) {
        SessionDraftStore()
    }
    var initialQuickActionConsumed by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    // 任务聚合是根导航的数据真源。通知走 SessionWatcher，快捷方式用当前任务树。
    val taskState = remember(api, context) {
        TaskListState(api, SharedTaskListExpansionStore(context, api.baseUrl))
    }
    var sidebarCollapsed by rememberSaveable { mutableStateOf(false) }
    var homeListMode by remember {
        mutableStateOf(HomeListMode.fromStorage(actions.settings.getHomeListMode()))
    }
    val changeHomeListMode: (HomeListMode) -> Unit = { mode ->
        homeListMode = mode
        actions.settings.setHomeListMode(mode.storageValue)
    }

    DisposableEffect(taskState) {
        taskState.startSync()
        onDispose {
            taskState.shutdown()
        }
    }
    LaunchedEffect(taskState.groups, actions.connection.serverId) {
        com.wand.app.WandShortcuts.update(
            context,
            actions.connection.serverId,
            taskState.groups,
        )
    }
    // 长按图标的旧 ACTION_NEW_SESSION 保持二进制兼容，但语义迁移为「新任务」。
    LaunchedEffect(Unit) {
        if (!initialQuickActionConsumed) {
            initialQuickActionConsumed = true
            when (val action = initialQuickAction) {
                is QuickAction.NewSession -> {
                    nav.popToRoot()
                    taskState.requestNewTask()
                }
                is QuickAction.OpenSession -> {
                    val snapshot = runCatching { api.getSession(action.sessionId) }.getOrNull()
                    nav.push(
                        snapshot?.detailScreen() ?: if (action.isStructured == false) {
                            Screen.PtyTerminal(action.sessionId)
                        } else {
                            Screen.Chat(action.sessionId)
                        },
                    )
                }
                null -> {}
            }
        }
    }

    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wideLayout = usesWideListDetail(maxWidth, maxHeight)
        val listPaneWidth = wideListPaneWidth(maxWidth)
        val showDetailBack = !wideLayout || nav.stack.size > 2
        val openDetail: (Screen) -> Unit = { screen ->
            if (wideLayout) nav.setDetail(screen) else nav.push(screen)
        }
        val openSnapshot: (SessionSnapshot) -> Unit = { session ->
            openDetail(session.detailScreen())
        }
        val openTaskSession: (TaskSessionRoute) -> Unit = { route ->
            openDetail(
                if (route.structured) {
                    Screen.Chat(
                        route.sessionId,
                        route.workspaceName,
                        route.taskName,
                        route.workspaceId,
                        route.taskId,
                    )
                } else {
                    Screen.PtyTerminal(
                        route.sessionId,
                        route.workspaceName,
                        route.taskName,
                        route.workspaceId,
                        route.taskId,
                    )
                },
            )
        }
        val openSettings: () -> Unit = {
            if (nav.current !is Screen.Settings) {
                nav.push(Screen.Settings)
            }
        }
        val openWorkspaceTask: (String, String, String, String) -> Unit =
            { workspaceId, taskId, workspaceName, taskName ->
                openDetail(Screen.WorkspaceTask(workspaceId, taskId, workspaceName, taskName))
            }
        val openBoardSession: (String, Boolean) -> Unit = { sessionId, structured ->
            openDetail(if (structured) Screen.Chat(sessionId) else Screen.PtyTerminal(sessionId))
        }

        if (wideLayout) {
            WideReadyContent(
                nav = nav,
                api = api,
                actions = actions,
                sessionDrafts = sessionDrafts,
                taskState = taskState,
                listPaneWidth = listPaneWidth,
                windowWidth = maxWidth,
                sidebarCollapsed = sidebarCollapsed,
                homeListMode = homeListMode,
                onHomeListModeChange = changeHomeListMode,
                selectedSessionId = nav.current.sessionIdOrNull(),
                onOpenSession = openTaskSession,
                onOpenBoardSession = openBoardSession,
                onOpenRestoredSession = openSnapshot,
                onOpenSettings = openSettings,
                onToggleSidebarCollapsed = { sidebarCollapsed = !sidebarCollapsed },
                onOpenWorkspaceTask = openWorkspaceTask,
                showDetailBack = showDetailBack,
            )
        } else {
            SinglePaneContent(
                nav = nav,
                api = api,
                actions = actions,
                sessionDrafts = sessionDrafts,
                taskState = taskState,
                homeListMode = homeListMode,
                onHomeListModeChange = changeHomeListMode,
                onOpenSession = openTaskSession,
                onOpenBoardSession = openBoardSession,
                onOpenRestoredSession = openSnapshot,
                onOpenSettings = openSettings,
                onOpenWorkspaceTask = openWorkspaceTask,
            )
        }
    }
}

@Composable
private fun SessionDetailScreen(
    screen: Screen,
    nav: NavState,
    api: WandApi,
    actions: HomeActions,
    sessionDrafts: SessionDraftStore,
    taskState: TaskListState,
    showBack: Boolean,
    embedded: Boolean,
    onOpenMissionSession: (sessionId: String, screen: Screen.Missions) -> Unit,
) {
    val scope = rememberCoroutineScope()
    when (screen) {
        is Screen.SessionList -> Unit
        is Screen.Chat -> ChatScreen(
            api = api,
            sessionId = screen.sessionId,
            serverDisplayName = actions.connection.serverDisplayName,
            workspaceName = screen.workspaceName,
            taskName = screen.taskName,
            taskId = screen.taskId,
            siblingSessions = siblingSessionsFor(taskState.groups, screen.taskId, screen.sessionId),
            onSwitchSession = { session -> switchSession(nav, session, screen) },
            onCreateTaskSession = { session ->
                scope.launch { taskState.refreshAfterMutation() }
                switchSession(nav, session, screen)
            },
            isHapticEnabled = actions.settings.isHapticEnabled,
            onDeleteTaskSession = { session ->
                scope.launch { taskState.refreshAfterMutation() }
                nav.closeSession(session.id)
            },
            drafts = sessionDrafts,
            showBack = showBack,
            onBack = { nav.pop() },
        )
        is Screen.PtyTerminal -> PtyTerminalScreen(
            api = api,
            sessionId = screen.sessionId,
            serverDisplayName = actions.connection.serverDisplayName,
            workspaceName = screen.workspaceName,
            taskName = screen.taskName,
            taskId = screen.taskId,
            siblingSessions = siblingSessionsFor(taskState.groups, screen.taskId, screen.sessionId),
            onSwitchSession = { session -> switchSession(nav, session, screen) },
            onCreateTaskSession = { session ->
                scope.launch { taskState.refreshAfterMutation() }
                switchSession(nav, session, screen)
            },
            onDeleteTaskSession = { session ->
                scope.launch { taskState.refreshAfterMutation() }
                nav.closeSession(session.id)
            },
            isHapticEnabled = actions.settings.isHapticEnabled,
            showBack = showBack,
            onBack = { nav.pop() },
        )
        is Screen.Missions -> MissionsScreen(
            api = api,
            onBack = { nav.pop() },
            onOpenSession = { sessionId -> onOpenMissionSession(sessionId, screen) },
            embedded = embedded,
            linkedTaskId = screen.taskId,
            linkedTaskName = screen.taskName,
            linkedCwd = screen.cwd,
        )
        is Screen.TaskBoard -> TaskBoardScreen(
            api = api,
            onBack = { nav.pop() },
            onOpenSession = { sessionId, isStructured ->
                nav.push(
                    if (isStructured) Screen.Chat(sessionId) else Screen.PtyTerminal(sessionId),
                )
            },
            linkedWorkspaceId = screen.workspaceId,
            embedded = embedded,
        )
        is Screen.Settings -> SettingsScreen(
            api = api,
            connection = actions.connection,
            navigation = actions.navigation,
            settings = actions.settings,
            onBack = { nav.pop() },
            embedded = embedded,
        )
        is Screen.WorkspaceTask -> WorkspaceTaskScreen(
            api = api,
            workspaceId = screen.workspaceId,
            taskId = screen.taskId,
            workspaceName = screen.workspaceName,
            taskName = screen.taskName,
            showBack = showBack,
            onBack = { nav.pop() },
            onOpenSession = { sessionId ->
                nav.push(
                    Screen.Chat(
                        sessionId,
                        screen.workspaceName,
                        screen.taskName,
                        screen.workspaceId,
                        screen.taskId,
                    ),
                )
            },
            onOpenPty = { sessionId ->
                nav.push(
                    Screen.PtyTerminal(
                        sessionId,
                        screen.workspaceName,
                        screen.taskName,
                        screen.workspaceId,
                        screen.taskId,
                    ),
                )
            },
            onOpenMissions = { cwd ->
                nav.push(Screen.Missions(screen.taskId, cwd, screen.taskName))
            },
            onOpenTaskBoard = { nav.push(Screen.TaskBoard(screen.workspaceId)) },
            onTaskChanged = { scope.launch { taskState.refreshAfterMutation() } },
        )
    }
}

@Composable
private fun SinglePaneContent(
    nav: NavState,
    api: WandApi,
    actions: HomeActions,
    sessionDrafts: SessionDraftStore,
    taskState: TaskListState,
    homeListMode: HomeListMode,
    onHomeListModeChange: (HomeListMode) -> Unit,
    onOpenSession: (TaskSessionRoute) -> Unit,
    onOpenBoardSession: (String, Boolean) -> Unit,
    onOpenRestoredSession: (SessionSnapshot) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWorkspaceTask: (String, String, String, String) -> Unit,
) {
    val reduceMotion = reduceMotionEnabled()
    val frame = SinglePaneFrame(nav.current, nav.stack.size)
    AnimatedContent(
        targetState = frame,
        modifier = Modifier.fillMaxSize(),
        contentKey = { it.screen.transitionKey() },
        transitionSpec = {
            val taskSessionDirection = taskSessionTransitionDirection(
                initial = initialState.screen,
                target = targetState.screen,
                sessions = siblingSessionsFor(
                    taskState.groups,
                    initialState.screen.taskIdOrNull(),
                    initialState.screen.sessionIdOrNull(),
                ),
            )
            val spec = if (reduceMotion) {
                fadeIn(snap()) togetherWith fadeOut(snap())
            } else if (taskSessionDirection != null) {
                val forward = taskSessionDirection > 0
                if (forward) {
                    (slideInHorizontally(WandMotion.tweenNormal()) { it / 3 } +
                        fadeIn(WandMotion.tweenNormal())) togetherWith
                        (slideOutHorizontally(WandMotion.tweenNormal()) { -it / 3 } +
                            fadeOut(WandMotion.tweenFast()))
                } else {
                    (slideInHorizontally(WandMotion.tweenNormal()) { -it / 3 } +
                        fadeIn(WandMotion.tweenNormal())) togetherWith
                        (slideOutHorizontally(WandMotion.tweenNormal()) { it / 3 } +
                            fadeOut(WandMotion.tweenFast()))
                }
            } else if (
                usesHeavyDetailTransition(initialState.screen) ||
                usesHeavyDetailTransition(targetState.screen)
            ) {
                fadeIn(WandMotion.tweenEnter()) togetherWith fadeOut(WandMotion.tweenExit())
            } else {
                val forward = targetState.depth >= initialState.depth
                if (forward) {
                    (slideInHorizontally(WandMotion.tweenEnter()) { it / 5 } +
                        fadeIn(WandMotion.tweenEnter())) togetherWith
                        (slideOutHorizontally(WandMotion.tweenExit()) { -it / 8 } +
                            fadeOut(WandMotion.tweenExit()))
                } else {
                    (slideInHorizontally(WandMotion.tweenEnter()) { -it / 5 } +
                        fadeIn(WandMotion.tweenEnter())) togetherWith
                        (slideOutHorizontally(WandMotion.tweenExit()) { it / 8 } +
                            fadeOut(WandMotion.tweenExit()))
                }
            }
            spec.using(SizeTransform(clip = false) { _, _ -> snap() })
        },
        label = "singlePaneNav",
    ) { currentFrame ->
        val screen = currentFrame.screen
        if (screen is Screen.SessionList) {
            TaskListScreen(
                state = taskState,
                api = api,
                boardApi = api,
                serverDisplayName = actions.connection.serverDisplayName,
                homeListMode = homeListMode,
                onHomeListModeChange = onHomeListModeChange,
                onOpenTask = onOpenWorkspaceTask,
                onOpenSession = onOpenSession,
                onOpenBoardSession = onOpenBoardSession,
                onOpenRestoredSession = onOpenRestoredSession,
                onTaskRenamed = nav::renameWorkspaceTask,
                onTaskClosed = nav::closeWorkspaceTask,
                onSessionClosed = nav::closeSession,
                onOpenSettings = onOpenSettings,
                onOpenWeb = actions.navigation.openWeb,
                onSwitchServer = actions.navigation.switchServer,
            )
        } else {
            SessionDetailScreen(
                screen = screen,
                nav = nav,
                api = api,
                actions = actions,
                sessionDrafts = sessionDrafts,
                taskState = taskState,
                showBack = true,
                embedded = false,
                onOpenMissionSession = { sessionId, missions ->
                    nav.push(
                        Screen.Chat(
                            sessionId,
                            taskName = missions.taskName,
                            taskId = missions.taskId,
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun WideReadyContent(
    nav: NavState,
    api: WandApi,
    actions: HomeActions,
    sessionDrafts: SessionDraftStore,
    taskState: TaskListState,
    listPaneWidth: Dp,
    windowWidth: Dp,
    sidebarCollapsed: Boolean,
    homeListMode: HomeListMode,
    onHomeListModeChange: (HomeListMode) -> Unit,
    selectedSessionId: String?,
    onOpenSession: (TaskSessionRoute) -> Unit,
    onOpenBoardSession: (String, Boolean) -> Unit,
    onOpenRestoredSession: (SessionSnapshot) -> Unit,
    onOpenSettings: () -> Unit,
    onToggleSidebarCollapsed: () -> Unit,
    onOpenWorkspaceTask: (String, String, String, String) -> Unit,
    showDetailBack: Boolean,
) {
    val density = LocalDensity.current.density
    var sidebarDragDeltaDp by rememberSaveable { mutableStateOf(0f) }
    val minSidebarWidth = 220.dp
    val maxSidebarWidth = (windowWidth - 360.dp)
        .coerceAtLeast(minSidebarWidth)
        .coerceAtMost(420.dp)
    val sidebarContentWidth = if (sidebarCollapsed) {
        56.dp
    } else {
        (listPaneWidth + sidebarDragDeltaDp.dp).coerceIn(minSidebarWidth, maxSidebarWidth)
    }
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
                val reduceMotion = reduceMotionEnabled()
                AnimatedContent(
                    targetState = sidebarCollapsed,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        if (reduceMotion) {
                            fadeIn(snap()) togetherWith fadeOut(snap())
                        } else {
                            fadeIn(WandMotion.tweenEnter()) togetherWith fadeOut(WandMotion.tweenExit())
                        }
                    },
                    label = "wideSidebarContent",
                ) { collapsed ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        if (collapsed) {
                            CollapsedTaskRail(
                                groups = taskState.groups,
                                selectedTaskId = nav.current.taskIdOrNull(),
                                onNewTask = {
                                    taskState.requestNewTask()
                                    onToggleSidebarCollapsed()
                                },
                                onOpenTask = { group, task ->
                                    onOpenWorkspaceTask(
                                        task.task.workspaceId,
                                        task.id,
                                        group.workspaceName,
                                        task.name,
                                    )
                                },
                                onExpandSidebar = onToggleSidebarCollapsed,
                            )
                        } else {
                            TaskListScreen(
                                state = taskState,
                                api = api,
                                boardApi = api,
                                serverDisplayName = actions.connection.serverDisplayName,
                                modifier = Modifier.fillMaxSize(),
                                homeListMode = homeListMode,
                                onHomeListModeChange = onHomeListModeChange,
                                selectedSessionId = selectedSessionId,
                                selectedTaskId = nav.current.taskIdOrNull(),
                                                onOpenTask = onOpenWorkspaceTask,
                                onOpenSession = onOpenSession,
                                onOpenBoardSession = onOpenBoardSession,
                                onOpenRestoredSession = onOpenRestoredSession,
                                onTaskRenamed = nav::renameWorkspaceTask,
                                onTaskClosed = nav::closeWorkspaceTask,
                                onSessionClosed = nav::closeSession,
                                onOpenSettings = onOpenSettings,
                                onOpenWeb = actions.navigation.openWeb,
                                onSwitchServer = actions.navigation.switchServer,
                                onCollapseSidebar = onToggleSidebarCollapsed,
                            )
                        }
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            val reduceMotion = reduceMotionEnabled()
            AnimatedContent(
                targetState = nav.current,
                modifier = Modifier.fillMaxSize(),
                contentKey = { it.transitionKey() },
                transitionSpec = {
                    val taskSessionDirection = taskSessionTransitionDirection(
                        initial = initialState,
                        target = targetState,
                        sessions = siblingSessionsFor(
                            taskState.groups,
                            initialState.taskIdOrNull(),
                            initialState.sessionIdOrNull(),
                        ),
                    )
                    val spec = if (reduceMotion) {
                        fadeIn(snap()) togetherWith fadeOut(snap())
                    } else if (taskSessionDirection != null) {
                        val forward = taskSessionDirection > 0
                        if (forward) {
                            (slideInHorizontally(WandMotion.tweenNormal()) { it / 3 } +
                                fadeIn(WandMotion.tweenNormal())) togetherWith
                                (slideOutHorizontally(WandMotion.tweenNormal()) { -it / 3 } +
                                    fadeOut(WandMotion.tweenFast()))
                        } else {
                            (slideInHorizontally(WandMotion.tweenNormal()) { -it / 3 } +
                                fadeIn(WandMotion.tweenNormal())) togetherWith
                                (slideOutHorizontally(WandMotion.tweenNormal()) { it / 3 } +
                                    fadeOut(WandMotion.tweenFast()))
                        }
                    } else {
                        fadeIn(WandMotion.tweenEnter()) togetherWith fadeOut(WandMotion.tweenExit())
                    }
                    spec.using(SizeTransform(clip = false) { _, _ -> snap() })
                },
                label = "wideDetailNav",
            ) { screen ->
                if (screen is Screen.SessionList) {
                    DetailPlaceholder(
                        onNewTask = {
                            taskState.requestNewTask()
                            if (sidebarCollapsed) onToggleSidebarCollapsed()
                        },
                    )
                } else {
                    SessionDetailScreen(
                        screen = screen,
                        nav = nav,
                        api = api,
                        actions = actions,
                        sessionDrafts = sessionDrafts,
                        taskState = taskState,
                        showBack = showDetailBack,
                        embedded = true,
                        onOpenMissionSession = { sessionId, missions ->
                            nav.setDetail(
                                Screen.Chat(
                                    sessionId,
                                    taskName = missions.taskName,
                                    taskId = missions.taskId,
                                ),
                            )
                        },
                    )
                }
            }
            if (!sidebarCollapsed) {
                SidebarResizeHandle(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = (-14).dp),
                    enabled = true,
                    onDrag = { deltaPx ->
                        sidebarDragDeltaDp += deltaPx / density
                    },
                )
            }
        }
    }
}

@Composable
private fun SidebarResizeHandle(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onDrag: (Float) -> Unit,
) {
    Box(
        modifier = modifier
            .width(28.dp)
            .fillMaxHeight()
            .semantics {
                contentDescription = "调整侧边栏宽度"
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(5.dp)
                .height(52.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(WandColors.textMuted.copy(alpha = 0.62f)),
        )
    }
}

@Composable
private fun WideSidebarPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val divider = WandColors.border
    Box(
        modifier = modifier
            .background(WandColors.bgElevated)
            .drawBehind {
                val stroke = 1.dp.toPx()
                val x = size.width - stroke / 2f
                drawLine(
                    color = divider,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = stroke,
                    cap = StrokeCap.Butt,
                )
            },
    ) {
        content()
    }
}

@Composable
private fun CollapsedTaskRail(
    groups: List<TaskDirectoryGroup>,
    selectedTaskId: String?,
    onNewTask: () -> Unit,
    onOpenTask: (TaskDirectoryGroup, WorkspaceTaskSummary) -> Unit,
    onExpandSidebar: () -> Unit,
) {
    val rail = collapsedRailTasks(groups, selectedTaskId)
    Column(
        modifier = Modifier
            .width(56.dp)
            .fillMaxHeight()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CollapsedRailTile(
            icon = rememberVectorPainter(WandIcons.panelExpand),
            iconTint = WandColors.textSecondary,
            accentTint = WandColors.brand,
            selected = false,
            selectionStateEnabled = false,
            contentDescription = "展开任务侧边栏",
            onClickLabel = "展开任务侧边栏",
            outlined = true,
            onClick = onExpandSidebar,
        )
        Spacer(modifier = Modifier.height(10.dp))
        CollapsedRailDivider()
        Spacer(modifier = Modifier.height(10.dp))
        CollapsedRailTile(
            icon = rememberVectorPainter(WandIcons.add),
            iconTint = WandColors.brand,
            accentTint = WandColors.brand,
            selected = false,
            selectionStateEnabled = false,
            contentDescription = "新建任务",
            onClickLabel = "新建任务",
            emphasized = true,
            onClick = onNewTask,
        )
        if (rail.items.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            CollapsedRailDivider()
            Spacer(modifier = Modifier.height(8.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            rail.items.forEach { item ->
                Box {
                    CollapsedRailTile(
                        icon = rememberVectorPainter(
                            if (item.task.isIsolated) WandIcons.commit else WandIcons.todo,
                        ),
                        iconTint = if (item.task.id == selectedTaskId) WandColors.brand else WandColors.textSecondary,
                        accentTint = WandColors.brand,
                        selected = item.task.id == selectedTaskId,
                        selectionStateEnabled = true,
                        contentDescription = "打开任务 ${item.task.name}",
                        onClickLabel = "打开任务",
                        onClick = { onOpenTask(item.group, item.task) },
                    )
                    if (item.activity != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .size(7.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    if (item.activity == "attention") WandColors.warning else WandColors.success,
                                ),
                        )
                    }
                }
            }
            if (rail.overflow > 0) {
                CollapsedRailTile(
                    icon = rememberVectorPainter(WandIcons.more),
                    iconTint = WandColors.textMuted,
                    accentTint = WandColors.brand,
                    selected = false,
                    selectionStateEnabled = false,
                    contentDescription = "展开侧栏，还有 ${rail.overflow} 个任务",
                    onClickLabel = "展开侧栏",
                    onClick = onExpandSidebar,
                )
            }
        }
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
        animationSpec = WandMotion.tweenPress(),
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
private fun DetailPlaceholder(
    onNewTask: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .ambientBackground(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            WandBrandMark(size = 44)
            Text(
                "选择一个任务",
                style = MaterialTheme.typography.titleLarge,
                color = WandColors.textPrimary,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                "从左侧打开任务，右侧会显示该任务的工作窗口。",
                style = MaterialTheme.typography.bodyMedium,
                color = WandColors.textSecondary,
                textAlign = TextAlign.Center,
            )
            WandButton(
                label = "新建任务",
                onClick = onNewTask,
                compact = true,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

private data class SinglePaneFrame(val screen: Screen, val depth: Int)

private fun Screen.transitionKey(): String = when (this) {
    Screen.SessionList -> "session-list"
    is Screen.Chat -> "chat:$sessionId"
    is Screen.PtyTerminal -> "pty:$sessionId"
    is Screen.Missions -> "missions:${taskId.orEmpty()}"
    is Screen.TaskBoard -> "task-board:${workspaceId.orEmpty()}"
    Screen.Settings -> "settings"
    is Screen.WorkspaceTask -> "workspace-task:$taskId"
}

private fun SessionSnapshot.detailScreen(): Screen =
    if (isStructured) {
        Screen.Chat(id, workspaceId = workspaceId, taskId = workspaceTaskId)
    } else {
        Screen.PtyTerminal(id, workspaceId = workspaceId, taskId = workspaceTaskId)
    }

/**
 * 「其他终端」快捷切换（对齐 iOS sessionStrip）：按 sessionKind 路由到 Chat / PTY 页，
 * 并用 replaceTop 替换栈顶 —— 返回键仍回到任务详情/会话列表，不会堆一层会话页。
 *
 * 任务内会话带上 taskId 上下文；未分组会话保持 taskId 为空，仅替换为同目录的兄弟会话。
 */
private fun switchSession(nav: NavState, session: WorkspaceSessionSummary, from: Screen) {
    switchSession(nav, session.id, session.isStructured, from)
}

private fun switchSession(nav: NavState, session: SessionSnapshot, from: Screen) {
    switchSession(nav, session.id, session.isStructured, from)
}

private fun switchSession(
    nav: NavState,
    sessionId: String,
    isStructured: Boolean,
    from: Screen,
) {
    val context = when (from) {
        is Screen.Chat -> SessionScreenContext(
            from.workspaceName,
            from.taskName,
            from.workspaceId,
            from.taskId,
        )
        is Screen.PtyTerminal -> SessionScreenContext(
            from.workspaceName,
            from.taskName,
            from.workspaceId,
            from.taskId,
        )
        else -> return
    }
    nav.replaceTop(
        if (isStructured) {
            Screen.Chat(
                sessionId,
                context.workspaceName,
                context.taskName,
                context.workspaceId,
                context.taskId,
            )
        } else {
            Screen.PtyTerminal(
                sessionId,
                context.workspaceName,
                context.taskName,
                context.workspaceId,
                context.taskId,
            )
        },
    )
}

private data class SessionScreenContext(
    val workspaceName: String?,
    val taskName: String?,
    val workspaceId: String?,
    val taskId: String?,
)

private fun Screen.taskIdOrNull(): String? = when (this) {
    is Screen.Chat -> taskId
    is Screen.PtyTerminal -> taskId
    is Screen.WorkspaceTask -> taskId
    Screen.SessionList,
    is Screen.Missions,
    is Screen.TaskBoard,
    Screen.Settings -> null
}

private fun Screen.sessionIdOrNull(): String? = when (this) {
    is Screen.Chat -> sessionId
    is Screen.PtyTerminal -> sessionId
    Screen.SessionList,
    is Screen.Missions,
    is Screen.TaskBoard,
    Screen.Settings,
    is Screen.WorkspaceTask -> null
}
