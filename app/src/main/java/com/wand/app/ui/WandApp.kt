package com.wand.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wand.app.data.WandApi
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.WandAuth
import com.wand.app.data.WandApiException
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.ui.components.WandBrandMark
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.ambientBackground
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.isWandDarkTheme
import com.wand.app.ui.theme.reduceMotionEnabled
import com.wand.app.ui.screens.ChatScreen
import com.wand.app.ui.screens.AiTeamChatScreen
import com.wand.app.ui.screens.AiTeamDetailScreen
import com.wand.app.ui.screens.AiTeamEditorScreen
import com.wand.app.ui.screens.ContactsScreen
import com.wand.app.ui.screens.SiliconEmployeeEditorScreen
import com.wand.app.ui.screens.HomeListMode
import com.wand.app.ui.screens.MissionsScreen
import com.wand.app.ui.screens.TaskBoardScreen
import com.wand.app.ui.screens.TaskBoardTaskScreen
import com.wand.app.ui.screens.PtyTerminalScreen
import com.wand.app.ui.screens.SettingsScreen
import com.wand.app.ui.screens.SharedTaskListExpansionStore
import com.wand.app.ui.screens.TaskListScreen
import com.wand.app.ui.screens.TaskListState
import com.wand.app.ui.screens.PadLandingScreen
import com.wand.app.ui.screens.CollapsedDirectoryRail
import com.wand.app.ui.screens.DirectoryPeekOverlay
import com.wand.app.ui.screens.TaskSessionRoute
import com.wand.app.ui.screens.collapsedRailDirectories
import com.wand.app.ui.screens.foldModeEmptyNote
import com.wand.app.ui.screens.foldModeGroup
import com.wand.app.ui.screens.taskSessionRoute
import kotlin.math.abs
import com.wand.app.ui.screens.siblingSessionsFor
import com.wand.app.ui.screens.taskSessionTransitionDirection
import com.wand.app.ui.screens.WorkspaceTaskScreen
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
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
        while (true) {
            var shouldRetry = true
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
                if (e is CancellationException) throw e
                shouldRetry = when (e) {
                    is WandAuth.AuthException -> e.retryable
                    is WandApiException -> e.status != 401
                    else -> true
                }
                val msg = e.message ?: "未知错误"
                AuthPhase.Failed(
                    message = if (actions.connection.hasToken) {
                        msg
                    } else {
                        "无法访问服务器：$msg\n如果服务器设有密码，请用「连接码」重新连接。"
                    },
                    retrying = shouldRetry,
                )
            }

            if (phase is AuthPhase.Ready) return@LaunchedEffect
            if (!shouldRetry) return@LaunchedEffect

            val delayMs = reconnectDelayMs(attempt)
            attempt = (attempt + 1).coerceAtMost(6)
            delay(delayMs)
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
                retrying = p.retrying,
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
    data class Failed(
        val message: String,
        val retrying: Boolean,
    ) : AuthPhase()
}

internal fun reconnectDelayMs(attempt: Int): Long {
    val exponent = attempt.coerceIn(0, 6)
    return (1_000L shl exponent).coerceAtMost(60_000L)
}

private val WideLayoutMinWidth = 640.dp
private val WideLayoutMinHeight = 480.dp
private val MediumSidebarMinWidth = 232.dp
private val MediumSidebarMaxWidth = 280.dp
private val ExpandedSidebarMinWidth = 280.dp
private val ExpandedSidebarMaxWidth = 400.dp
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

/** 同一任务内切换会话时的横向滑动；不是同任务内切换时返回 null 由调用方挑其它转场。 */
private fun taskSessionTransition(
    initial: Screen,
    target: Screen,
    siblings: List<WorkspaceSessionSummary>,
): ContentTransform? {
    val direction = taskSessionTransitionDirection(initial, target, siblings) ?: return null
    val sign = if (direction > 0) 1 else -1
    return (slideInHorizontally(WandMotion.tweenNormal()) { sign * it / 3 } +
        fadeIn(WandMotion.tweenNormal())) togetherWith
        (slideOutHorizontally(WandMotion.tweenNormal()) { -sign * it / 3 } +
            fadeOut(WandMotion.tweenFast()))
}

/** 栈内前进 / 后退的横向滑动。 */
private fun stackNavTransition(forward: Boolean): ContentTransform {
    val sign = if (forward) 1 else -1
    return (slideInHorizontally(WandMotion.tweenEnter()) { sign * it / 5 } +
        fadeIn(WandMotion.tweenEnter())) togetherWith
        (slideOutHorizontally(WandMotion.tweenExit()) { -sign * it / 8 } +
            fadeOut(WandMotion.tweenExit()))
}

/** 无方向感的固定淡入淡出。 */
private fun fadeNavTransition(): ContentTransform =
    fadeIn(WandMotion.tweenEnter()) togetherWith fadeOut(WandMotion.tweenExit())

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
    retrying: Boolean,
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
                    if (retrying) "连接失败，正在自动重试…"
                    else "登录已失效，请用新的连接码重新连接。",
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
    // 手机离页、平板切换详情都结束本次临时展开；标题/任务归属刷新不算导航。
    LaunchedEffect(taskState, nav.current.transitionKey()) {
        taskState.clearTemporaryExpansions()
    }
    LaunchedEffect(taskState.groups, actions.connection.serverId) {
        nav.syncTaskMembership(taskState.groups)
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
            openDetail(route.toScreen())
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
        // 看板卡片在侧栏只负责选中，详情交给右侧主区（窄屏则推入新页）。
        val openBoardTaskDetail: (String) -> Unit = { taskId ->
            openDetail(Screen.TaskBoard(taskId = taskId))
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
                onOpenBoardTaskDetail = openBoardTaskDetail,
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
                onOpenBoardTaskDetail = openBoardTaskDetail,
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
            isHapticEnabled = actions.settings.isHapticEnabled,
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
        is Screen.TaskBoard -> if (screen.taskId != null) {
            // 平板 / 折叠屏：看板卡片详情单独占右侧主区，侧栏只留列表与唯一一条顶栏。
            TaskBoardTaskScreen(
                baseUrl = api.baseUrl,
                api = api,
                workspaceApi = api,
                taskId = screen.taskId,
                showBack = showBack,
                onBack = { nav.pop() },
                // 面包屑首段与 Web 同口径：永远落到「任务看板列表」。落点由 taskBoardLanding
                // 这个纯函数决定（pop / 换栈顶），两个分支都有单测，见 TaskBoardLandingTest。
                onBackToBoard = {
                    when (taskBoardLanding(nav.stack.getOrNull(nav.stack.lastIndex - 1))) {
                        TaskBoardLanding.Pop -> nav.pop()
                        TaskBoardLanding.ReplaceTop -> nav.replaceTop(Screen.TaskBoard())
                    }
                },
                onTaskGone = { nav.pop() },
                onOpenSession = { route -> nav.push(route.toScreen()) },
                onOpenTeamChat = { runId, identifier ->
                    nav.push(Screen.AiTeamChat(runId, identifier.takeIf { it.isNotBlank() }))
                },
            )
        } else {
            TaskBoardScreen(
                api = api,
                onOpenBoundSession = { route -> nav.push(route.toScreen()) },
                onBack = { nav.pop() },
                onOpenSession = { sessionId, isStructured ->
                    nav.push(
                        if (isStructured) Screen.Chat(sessionId) else Screen.PtyTerminal(sessionId),
                    )
                },
                // 全屏工作台也走同一套详情页，列表与详情各占一屏，不再叠两条顶栏。
                onOpenTaskDetail = { taskId -> nav.push(Screen.TaskBoard(taskId = taskId)) },
                linkedWorkspaceId = screen.workspaceId,
                // 这里不是侧栏内嵌那种收窄列表，顶栏要留（标题 + 返回）；
                // 侧栏实例由 TaskListScreen 自己传 embedded = true。
                embedded = false,
            )
        }
        is Screen.Settings -> SettingsScreen(
            api = api,
            connection = actions.connection,
            navigation = actions.navigation,
            settings = actions.settings,
            onBack = { nav.pop() },
            embedded = embedded,
        )
        is Screen.Contacts -> ContactsScreen(
            api = api,
            // 通讯录这一支刻意不走 openDetail：宽屏左栏是会话/任务列表，没有通讯录列表，
            // setDetail 会把栈里的通讯录覆盖掉，用户就回不去了。push 保留「可回上一层」，
            // 于是员工资料/新对话/群聊的首段面包屑恒有真实落点。
            onBack = { nav.pop() },
            onOpenEmployee = { nav.push(Screen.SiliconEmployeeEditor(it)) },
            onOpenTeam = { nav.push(Screen.AiTeamDetail(it)) },
            onCreateEmployee = { nav.push(Screen.SiliconEmployeeEditor()) },
            onOpenSession = { route -> nav.push(route.toScreen()) },
            onOpenGroupChat = { runId -> nav.push(Screen.AiTeamChat(runId)) },
            onCreateTeam = { templateId -> nav.push(Screen.AiTeamEditor(templateId = templateId)) },
        )
        is Screen.SiliconEmployeeEditor -> SiliconEmployeeEditorScreen(
            api = api,
            employeeId = screen.employeeId,
            onBack = { nav.pop() },
        )
        is Screen.AiTeamDetail -> AiTeamDetailScreen(
            api = api,
            workspaceApi = api,
            teamId = screen.teamId,
            onBack = { nav.pop() },
            onOpenTask = { taskId -> nav.push(Screen.TaskBoard(taskId = taskId)) },
            onOpenGroupChat = { runId -> nav.push(Screen.AiTeamChat(runId)) },
            onOpenMemberSession = { sessionId -> nav.push(Screen.Chat(sessionId)) },
            onEditTeam = { nav.push(Screen.AiTeamEditor(teamId = screen.teamId)) },
        )
        is Screen.AiTeamEditor -> AiTeamEditorScreen(
            api = api,
            teamId = screen.teamId,
            templateId = screen.templateId,
            onBack = { nav.pop() },
            // 删掉的团队不能留着它的详情页：编辑器一律由列表或详情 push 上来，
            // 出栈后如果下一层还是团队详情，再出栈一次。
            onDeleted = {
                nav.pop()
                if (nav.current is Screen.AiTeamDetail) nav.pop()
            },
        )
        is Screen.AiTeamChat -> AiTeamChatScreen(
            api = api,
            runId = screen.runId,
            sessionDrafts = sessionDrafts,
            isHapticEnabled = actions.settings.isHapticEnabled,
            taskIdentifier = screen.taskIdentifier,
            showBack = showBack,
            onBack = { nav.pop() },
            onOpenMemberSession = { sessionId -> nav.push(Screen.Chat(sessionId)) },
            onOpenFullSession = { sessionId -> nav.push(Screen.Chat(sessionId)) },
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
    onOpenBoardTaskDetail: (String) -> Unit,
) {
    val reduceMotion = reduceMotionEnabled()
    val frame = SinglePaneFrame(nav.current, nav.stack.size)
    AnimatedContent(
        targetState = frame,
        modifier = Modifier.fillMaxSize(),
        contentKey = { it.screen.transitionKey() },
        transitionSpec = {
            val spec = if (reduceMotion) {
                fadeIn(snap()) togetherWith fadeOut(snap())
            } else {
                taskSessionTransition(
                    initial = initialState.screen,
                    target = targetState.screen,
                    siblings = siblingSessionsFor(
                        taskState.groups,
                        initialState.screen.taskIdOrNull(),
                        initialState.screen.sessionIdOrNull(),
                    ),
                ) ?: when {
                    usesHeavyDetailTransition(initialState.screen) ||
                        usesHeavyDetailTransition(targetState.screen) -> fadeNavTransition()
                    else -> stackNavTransition(targetState.depth >= initialState.depth)
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
                onOpenBoardTaskDetail = onOpenBoardTaskDetail,
                onOpenRestoredSession = onOpenRestoredSession,
                onTaskRenamed = nav::renameWorkspaceTask,
                onTaskClosed = nav::closeWorkspaceTask,
                onSessionClosed = nav::closeSession,
                onOpenSettings = onOpenSettings,
                onOpenContacts = { nav.push(Screen.Contacts()) },
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
    onOpenBoardTaskDetail: (String) -> Unit,
    showDetailBack: Boolean,
) {
    val density = LocalDensity.current.density
    // 中缝上下收进系统栏：侧栏和画布本来就各自处理系统栏内边距，中缝只画在真实内容区里。
    val seamTopPx = with(LocalDensity.current) { WindowInsets.systemBars.getTop(this) }.toFloat()
    val seamBottomPx = with(LocalDensity.current) { WindowInsets.systemBars.getBottom(this) }.toFloat()
    // 亮色下中缝是画布投给侧栏的一道暖影；暗色下投影看不见，改用一档更亮的描边色表达相邻表面。
    val seamColor = if (isWandDarkTheme()) {
        WandColors.border.copy(alpha = 0.52f)
    } else {
        WandColors.borderStrong.copy(alpha = 0.18f)
    }
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
    var peekDirectoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var peekAnchorTop by remember { mutableStateOf(0.dp) }
    val rootWindowTop = remember { floatArrayOf(0f) }
    val selectedTaskId = nav.current.taskIdOrNull()
    val peeked = collapsedRailDirectories(taskState.groups)
        .firstOrNull { it.group.workspaceId == peekDirectoryId }
    LaunchedEffect(sidebarCollapsed, peekDirectoryId, taskState.groups) {
        val missing = peekDirectoryId != null && peeked == null
        if (!sidebarCollapsed || missing) peekDirectoryId = null
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootWindowTop[0] = it.positionInWindow().y },
    ) {
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
                        CollapsedDirectoryRail(
                            groups = taskState.groups,
                            selectedTaskId = selectedTaskId,
                            selectedSessionId = selectedSessionId,
                            peekDirectoryId = peekDirectoryId,
                            rootWindowTop = { rootWindowTop[0] },
                            onToggleDirectory = { group, top ->
                                if (peekDirectoryId == group.workspaceId) {
                                    peekDirectoryId = null
                                } else {
                                    peekAnchorTop = top
                                    peekDirectoryId = group.workspaceId
                                }
                            },
                            onDirectoryTop = { top ->
                                if (abs(peekAnchorTop.value - top.value) > 0.5f) peekAnchorTop = top
                            },
                            onNewTask = {
                                peekDirectoryId = null
                                taskState.requestNewTask()
                                onToggleSidebarCollapsed()
                            },
                            onExpandSidebar = {
                                peekDirectoryId = null
                                onToggleSidebarCollapsed()
                            },
                        )
                    } else {
                        // 宽屏时这里是侧栏：内容留出与中缝等宽的呼吸位，
                        // 卡片不再贴着中缝被切，两侧栏目有同一种页边距节奏。
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp),
                        ) {
                            TaskListScreen(
                                state = taskState,
                                api = api,
                                boardApi = api,
                                serverDisplayName = actions.connection.serverDisplayName,
                                modifier = Modifier.fillMaxSize(),
                                homeListMode = homeListMode,
                                onHomeListModeChange = onHomeListModeChange,
                                selectedSessionId = selectedSessionId,
                                selectedTaskId = selectedTaskId,
                                onOpenTask = onOpenWorkspaceTask,
                                onOpenSession = onOpenSession,
                                onOpenBoardSession = onOpenBoardSession,
                                onOpenBoardTaskDetail = onOpenBoardTaskDetail,
                                onOpenRestoredSession = onOpenRestoredSession,
                                onTaskRenamed = nav::renameWorkspaceTask,
                                onTaskClosed = nav::closeWorkspaceTask,
                                onSessionClosed = nav::closeSession,
                                onOpenSettings = onOpenSettings,
                                onOpenContacts = {
                                    if (nav.current !is Screen.Contacts) nav.push(Screen.Contacts())
                                },
                                onSwitchServer = actions.navigation.switchServer,
                                onCollapseSidebar = onToggleSidebarCollapsed,
                                // 与手机首页共用底部悬浮菜单；视图切换只影响左栏，详情仍留在右侧。
                            )
                        }
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .paneSeam(seamColor = seamColor, seamTopPx = seamTopPx, seamBottomPx = seamBottomPx),
        ) {
            val reduceMotion = reduceMotionEnabled()
            AnimatedContent(
                targetState = nav.current,
                modifier = Modifier.fillMaxSize(),
                contentKey = { it.transitionKey() },
                transitionSpec = {
                    val spec = if (reduceMotion) {
                        fadeIn(snap()) togetherWith fadeOut(snap())
                    } else {
                        taskSessionTransition(
                            initial = initialState,
                            target = targetState,
                            siblings = siblingSessionsFor(
                                taskState.groups,
                                initialState.taskIdOrNull(),
                                initialState.sessionIdOrNull(),
                            ),
                        ) ?: fadeNavTransition()
                    }
                    spec.using(SizeTransform(clip = false) { _, _ -> snap() })
                },
                label = "wideDetailNav",
            ) { screen ->
                if (screen is Screen.SessionList) {
                    PadLandingScreen(
                        groups = taskState.groups,
                        onOpenSession = onOpenSession,
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
            if (sidebarCollapsed && peeked != null) {
                val directory = peeked.group
                // 目录预览跟随首页三段式档位：在跑档下列在跑、失败和待处理的会话，
                // 整条被筛空时保留目录身份、由 peek 自己的空态说明原因。
                val peekGroup = foldModeGroup(taskState.workspaceFold, directory)
                DirectoryPeekOverlay(
                    anchorTop = peekAnchorTop,
                    group = peekGroup,
                    emptyNote = foldModeEmptyNote(taskState.workspaceFold),
                    selectedTaskId = selectedTaskId,
                    selectedSessionId = selectedSessionId,
                    onOpenTask = { task ->
                        peekDirectoryId = null
                        onOpenWorkspaceTask(
                            task.task.workspaceId,
                            task.id,
                            directory.workspaceName,
                            task.name,
                        )
                    },
                    onOpenSession = { session, task ->
                        peekDirectoryId = null
                        onOpenSession(taskSessionRoute(session, directory, task))
                    },
                    onExpand = {
                        peekDirectoryId = null
                        onToggleSidebarCollapsed()
                    },
                    onDismiss = { peekDirectoryId = null },
                )
            }
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

/** 双栏中缝的柔光宽度：够宽才看得出是渐隐，而不是一条竖线。 */
private val PaneSeamWidth = 14.dp

/**
 * 宽屏双栏的中缝。
 *
 * 侧栏原先在右缘画一条 1dp 实线，从状态栏一路切到手势条，加上侧栏自带的浅色底块，
 * 左右两栏看起来像两个页面拼在一起。这里改成画在画布左缘的一道柔光：
 * 横向渐隐、上下收进系统栏范围，中缝既不压到系统栏，也不再是一条硬线。
 */
private fun Modifier.paneSeam(
    seamColor: Color,
    seamTopPx: Float,
    seamBottomPx: Float,
): Modifier = drawBehind {
    val seamWidth = PaneSeamWidth.toPx()
    val seamHeight = size.height - seamTopPx - seamBottomPx
    if (seamHeight <= 0f) return@drawBehind
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, seamColor),
            startX = -seamWidth,
            endX = 0f,
        ),
        topLeft = Offset(-seamWidth, seamTopPx),
        size = Size(seamWidth, seamHeight),
    )
}


private data class SinglePaneFrame(val screen: Screen, val depth: Int)

private fun Screen.transitionKey(): String = when (this) {
    Screen.SessionList -> "session-list"
    is Screen.Chat -> "chat:$sessionId"
    is Screen.PtyTerminal -> "pty:$sessionId"
    is Screen.Missions -> "missions:${taskId.orEmpty()}"
    is Screen.TaskBoard -> "task-board:${workspaceId.orEmpty()}:${taskId.orEmpty()}"
    Screen.Settings -> "settings"
    is Screen.Contacts -> "contacts"
    is Screen.SiliconEmployeeEditor -> "silicon-employee-editor:${employeeId.orEmpty()}"
    is Screen.AiTeamDetail -> "ai-team-detail:$teamId"
    is Screen.AiTeamEditor -> "ai-team-editor:${teamId.orEmpty()}:${templateId.orEmpty()}"
    is Screen.AiTeamChat -> "ai-team-chat:$runId"
    is Screen.WorkspaceTask -> "workspace-task:$taskId"
}

/** Chat / PTY 路由：同一份任务上下文下按会话形态选页面。 */
private fun sessionScreen(
    sessionId: String,
    structured: Boolean,
    workspaceName: String? = null,
    taskName: String? = null,
    workspaceId: String? = null,
    taskId: String? = null,
): Screen = if (structured) {
    Screen.Chat(sessionId, workspaceName, taskName, workspaceId, taskId)
} else {
    Screen.PtyTerminal(sessionId, workspaceName, taskName, workspaceId, taskId)
}

private fun TaskSessionRoute.toScreen(): Screen {
    // 群聊会话行优先：同一 sessionId 走普通聊天页会丢掉公告卡 / 派工 / 状态芯片那套层次。
    teamChatRunId?.takeIf { it.isNotBlank() }?.let { return Screen.AiTeamChat(it) }
    return sessionScreen(sessionId, structured, workspaceName, taskName, workspaceId, taskId)
}

private fun SessionSnapshot.detailScreen(): Screen =
    sessionScreen(
        sessionId = id,
        structured = isStructured,
        workspaceId = workspaceId,
        taskId = workspaceTaskId,
    )

private fun Screen.taskIdOrNull(): String? = when (this) {
    is Screen.Chat -> taskId
    is Screen.PtyTerminal -> taskId
    is Screen.WorkspaceTask -> taskId
    Screen.SessionList,
    is Screen.Missions,
    is Screen.TaskBoard,
    Screen.Settings,
    is Screen.Contacts,
    is Screen.SiliconEmployeeEditor,
    is Screen.AiTeamDetail,
    // 群聊页的运行 id 不是任务/会话 id：会话 id 由服务端 run 详情给出，不在导航里。
    is Screen.AiTeamChat,
    // 团队编辑器只认团队 / 模板参数，不携带任务与会话。
    is Screen.AiTeamEditor -> null
}

private fun Screen.sessionIdOrNull(): String? = when (this) {
    is Screen.Chat -> sessionId
    is Screen.PtyTerminal -> sessionId
    Screen.SessionList,
    is Screen.Missions,
    is Screen.TaskBoard,
    Screen.Settings,
    is Screen.Contacts,
    is Screen.SiliconEmployeeEditor,
    is Screen.AiTeamDetail,
    is Screen.AiTeamChat,
    is Screen.AiTeamEditor,
    is Screen.WorkspaceTask -> null
}

/** 任务详情面包屑首段「回看板列表」的两种落点，见 [taskBoardLanding]。 */
internal enum class TaskBoardLanding { Pop, ReplaceTop }

/**
 * 纯决策，不碰导航栈也不碰渲染 —— 两个分支由单测钉死，不靠模拟器走到哪一层。
 *
 * 下一层已经是看板列表（`TaskBoard` 且 `taskId == null`）就 pop 回去；否则把当前层换成
 * 看板列表：从首页会话列表「打开完整任务页」进来时栈里根本没有看板层，pop 会落到首页。
 */
internal fun taskBoardLanding(belowDetail: Screen?): TaskBoardLanding =
    if (belowDetail is Screen.TaskBoard && belowDetail.taskId == null) {
        TaskBoardLanding.Pop
    } else {
        TaskBoardLanding.ReplaceTop
    }
