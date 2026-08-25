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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wand.app.SessionCreationCoordinator
import com.wand.app.data.WandApi
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.WandAuth
import com.wand.app.data.WorkspaceSessionSummary
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
import com.wand.app.ui.screens.NewSessionScreen
import com.wand.app.ui.screens.MissionsScreen
import com.wand.app.ui.screens.PtyTerminalScreen
import com.wand.app.ui.screens.SessionListState
import com.wand.app.ui.screens.SettingsScreen
import com.wand.app.ui.screens.TaskListScreen
import com.wand.app.ui.screens.TaskListState
import com.wand.app.ui.screens.TaskSessionRoute
import com.wand.app.ui.screens.WorkspaceTaskScreen
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
    val sessionDrafts = rememberSaveable(api.baseUrl, saver = SessionDraftStore.Saver) {
        SessionDraftStore()
    }
    var initialQuickActionConsumed by rememberSaveable { mutableStateOf(false) }
    // 任务聚合是根导航的数据真源；会话列表仅保留历史恢复、通知和 launcher 快捷方式。
    val taskState = remember(api) { TaskListState(api) }
    val listState = remember(api) { SessionListState(api) }
    val context = LocalContext.current
    val creationState by SessionCreationCoordinator.state.collectAsStateWithLifecycle(
        minActiveState = Lifecycle.State.RESUMED,
    )
    val sessionCreationInFlight = creationState !is SessionCreationCoordinator.State.Idle
    var sidebarCollapsed by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(taskState, listState) {
        taskState.startSync()
        listState.startSync()
        onDispose {
            taskState.shutdown()
            listState.shutdown()
        }
    }
    LaunchedEffect(Unit) {
        // 旧版「会话/项目」偏好不再参与导航，升级后固定进入任务首页。
        context.getSharedPreferences(SessionListViewPreferences, android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
    LaunchedEffect(listState.sessions, actions.connection.serverId) {
        com.wand.app.WandShortcuts.update(
            context,
            actions.connection.serverId,
            listState.sessions,
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

    BackHandler(
        enabled = nav.stack.size > 1 && !sessionCreationInFlight,
    ) { nav.pop() }
    BackHandler(
        enabled = sessionCreationInFlight && nav.current !is Screen.NewSession,
    ) {}

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wideLayout = usesWideListDetail(maxWidth, maxHeight)
        val listPaneWidth = wideListPaneWidth(maxWidth)
        val showDetailBack = !wideLayout || nav.stack.size > 2
        val openDetail: (Screen) -> Unit = { screen ->
            if (!sessionCreationInFlight) {
                if (wideLayout) nav.setDetail(screen) else nav.push(screen)
            }
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
            if (!sessionCreationInFlight && nav.current !is Screen.Settings) {
                nav.push(Screen.Settings)
            }
        }
        // 旧新建会话页面只用于恢复已有导航状态；主入口已经迁移为新任务。
        val openMissionsFromNewSession = { nav.setDetail(Screen.Missions()) }
        val openWorkspaceTask: (String, String, String, String) -> Unit =
            { workspaceId, taskId, workspaceName, taskName ->
                openDetail(Screen.WorkspaceTask(workspaceId, taskId, workspaceName, taskName))
            }

        if (wideLayout) {
            WideReadyContent(
                nav = nav,
                api = api,
                actions = actions,
                sessionDrafts = sessionDrafts,
                taskState = taskState,
                listState = listState,
                listPaneWidth = listPaneWidth,
                sidebarCollapsed = sidebarCollapsed,
                selectedSessionId = nav.current.sessionIdOrNull(),
                onOpenSession = openTaskSession,
                onOpenRestoredSession = openSnapshot,
                onOpenMissions = openMissionsFromNewSession,
                onOpenSettings = openSettings,
                onToggleSidebarCollapsed = { sidebarCollapsed = !sidebarCollapsed },
                onOpenWorkspaceTask = openWorkspaceTask,
                sessionCreationInFlight = sessionCreationInFlight,
                showDetailBack = showDetailBack,
            )
        } else {
            SinglePaneContent(
                nav = nav,
                api = api,
                actions = actions,
                sessionDrafts = sessionDrafts,
                taskState = taskState,
                listState = listState,
                onOpenSession = openTaskSession,
                onOpenRestoredSession = openSnapshot,
                onOpenMissions = openMissionsFromNewSession,
                onOpenSettings = openSettings,
                onOpenWorkspaceTask = openWorkspaceTask,
                sessionCreationInFlight = sessionCreationInFlight,
            )
        }
        if (sessionCreationInFlight && nav.current !is Screen.NewSession) {
            SessionCreationRecoveryOverlay()
        }
    }
}

@Composable
private fun SinglePaneContent(
    nav: NavState,
    api: WandApi,
    actions: HomeActions,
    sessionDrafts: SessionDraftStore,
    taskState: TaskListState,
    listState: SessionListState,
    onOpenSession: (TaskSessionRoute) -> Unit,
    onOpenRestoredSession: (SessionSnapshot) -> Unit,
    onOpenMissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWorkspaceTask: (String, String, String, String) -> Unit,
    sessionCreationInFlight: Boolean,
) {
    val scope = rememberCoroutineScope()
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
    when (val screen = currentFrame.screen) {
        is Screen.SessionList -> TaskListScreen(
            state = taskState,
            historyState = listState,
            api = api,
            serverDisplayName = actions.connection.serverDisplayName,
            interactionEnabled = !sessionCreationInFlight,
            onOpenTask = onOpenWorkspaceTask,
            onOpenSession = onOpenSession,
            onOpenRestoredSession = onOpenRestoredSession,
            onTaskRenamed = nav::renameWorkspaceTask,
            onTaskClosed = nav::closeWorkspaceTask,
            onSessionClosed = nav::closeSession,
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
            workspaceName = screen.workspaceName,
            taskName = screen.taskName,
            taskId = screen.taskId,
            onSwitchTaskSession = { session -> switchTaskSession(nav, session, screen) },
            onCreateTaskSession = { session ->
                scope.launch { taskState.refreshAfterMutation() }
                switchTaskSession(nav, session, screen)
            },
            isHapticEnabled = actions.settings.isHapticEnabled,
            drafts = sessionDrafts,
            onBack = { nav.pop() },
        )
        is Screen.PtyTerminal -> PtyTerminalScreen(
            api = api,
            sessionId = screen.sessionId,
            serverDisplayName = actions.connection.serverDisplayName,
            workspaceName = screen.workspaceName,
            taskName = screen.taskName,
            taskId = screen.taskId,
            onSwitchTaskSession = { session -> switchTaskSession(nav, session, screen) },
            onCreateTaskSession = { session ->
                scope.launch { taskState.refreshAfterMutation() }
                switchTaskSession(nav, session, screen)
            },
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
            onOpenMissions = onOpenMissions,
            onBack = { nav.pop() },
        )
        is Screen.Missions -> MissionsScreen(
            api = api,
            onBack = { nav.pop() },
            onOpenSession = { sessionId ->
                nav.push(
                    Screen.Chat(
                        sessionId,
                        taskName = screen.taskName,
                        taskId = screen.taskId,
                    ),
                )
            },
            linkedTaskId = screen.taskId,
            linkedTaskName = screen.taskName,
            linkedCwd = screen.cwd,
        )
        is Screen.Settings -> SettingsScreen(
            api = api,
            connection = actions.connection,
            navigation = actions.navigation,
            settings = actions.settings,
            onBack = { nav.pop() },
        )
        is Screen.WorkspaceTask -> WorkspaceTaskScreen(
            api = api,
            workspaceId = screen.workspaceId,
            taskId = screen.taskId,
            workspaceName = screen.workspaceName,
            taskName = screen.taskName,
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
            onTaskChanged = { scope.launch { taskState.refreshAfterMutation() } },
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
    listState: SessionListState,
    listPaneWidth: Dp,
    sidebarCollapsed: Boolean,
    selectedSessionId: String?,
    onOpenSession: (TaskSessionRoute) -> Unit,
    onOpenRestoredSession: (SessionSnapshot) -> Unit,
    onOpenMissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleSidebarCollapsed: () -> Unit,
    onOpenWorkspaceTask: (String, String, String, String) -> Unit,
    sessionCreationInFlight: Boolean,
    showDetailBack: Boolean,
) {
    val scope = rememberCoroutineScope()
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
                                onNewTask = {
                                    taskState.requestNewTask()
                                    onToggleSidebarCollapsed()
                                },
                                onExpandSidebar = onToggleSidebarCollapsed,
                            )
                        } else {
                            TaskListScreen(
                                state = taskState,
                                historyState = listState,
                                api = api,
                                serverDisplayName = actions.connection.serverDisplayName,
                                modifier = Modifier.fillMaxSize(),
                                selectedSessionId = selectedSessionId,
                                selectedTaskId = nav.current.taskIdOrNull(),
                                interactionEnabled = !sessionCreationInFlight,
                                onOpenTask = onOpenWorkspaceTask,
                                onOpenSession = onOpenSession,
                                onOpenRestoredSession = onOpenRestoredSession,
                                onTaskRenamed = nav::renameWorkspaceTask,
                                onTaskClosed = nav::closeWorkspaceTask,
                                onSessionClosed = nav::closeSession,
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
            val reduceMotion = reduceMotionEnabled()
            AnimatedContent(
                targetState = nav.current,
                modifier = Modifier.fillMaxSize(),
                contentKey = { it.transitionKey() },
                transitionSpec = {
                    val spec = if (reduceMotion) {
                        fadeIn(snap()) togetherWith fadeOut(snap())
                    } else {
                        fadeIn(WandMotion.tweenEnter()) togetherWith fadeOut(WandMotion.tweenExit())
                    }
                    spec.using(SizeTransform(clip = false) { _, _ -> snap() })
                },
                label = "wideDetailNav",
            ) { screen ->
            when (screen) {
                is Screen.SessionList -> DetailPlaceholder(
                    onNewTask = {
                        taskState.requestNewTask()
                        if (sidebarCollapsed) onToggleSidebarCollapsed()
                    },
                )
                is Screen.Chat -> ChatScreen(
                    api = api,
                    sessionId = screen.sessionId,
                    serverDisplayName = actions.connection.serverDisplayName,
                    workspaceName = screen.workspaceName,
                    taskName = screen.taskName,
                    taskId = screen.taskId,
                    onSwitchTaskSession = { session -> switchTaskSession(nav, session, screen) },
                    onCreateTaskSession = { session ->
                        scope.launch { taskState.refreshAfterMutation() }
                        switchTaskSession(nav, session, screen)
                    },
                    isHapticEnabled = actions.settings.isHapticEnabled,
                    drafts = sessionDrafts,
                    showBack = showDetailBack,
                    onBack = { nav.pop() },
                )
                is Screen.PtyTerminal -> PtyTerminalScreen(
                    api = api,
                    sessionId = screen.sessionId,
                    serverDisplayName = actions.connection.serverDisplayName,
                    workspaceName = screen.workspaceName,
                    taskName = screen.taskName,
                    taskId = screen.taskId,
                    onSwitchTaskSession = { session -> switchTaskSession(nav, session, screen) },
                    onCreateTaskSession = { session ->
                        scope.launch { taskState.refreshAfterMutation() }
                        switchTaskSession(nav, session, screen)
                    },
                    isHapticEnabled = actions.settings.isHapticEnabled,
                    showBack = showDetailBack,
                    onBack = { nav.pop() },
                )
                is Screen.NewSession -> NewSessionScreen(
                    api = api,
                    servers = actions.servers,
                    activeServerId = actions.connection.serverId,
                    initialCwd = screen.initialCwd,
                    creating = sessionCreationInFlight,
                    onReconnectServer = actions.navigation.reconnectServer,
                    onOpenMissions = onOpenMissions,
                    onBack = { nav.pop() },
                    embedded = true,
                )
                is Screen.Missions -> MissionsScreen(
                    api = api,
                    onBack = { nav.pop() },
                    onOpenSession = { sessionId ->
                        nav.setDetail(
                            Screen.Chat(
                                sessionId,
                                taskName = screen.taskName,
                                taskId = screen.taskId,
                            ),
                        )
                    },
                    embedded = true,
                    linkedTaskId = screen.taskId,
                    linkedTaskName = screen.taskName,
                    linkedCwd = screen.cwd,
                )
                is Screen.Settings -> SettingsScreen(
                    api = api,
                    connection = actions.connection,
                    navigation = actions.navigation,
                    settings = actions.settings,
                    onBack = { nav.pop() },
                    embedded = true,
                )
                is Screen.WorkspaceTask -> WorkspaceTaskScreen(
                    api = api,
                    workspaceId = screen.workspaceId,
                    taskId = screen.taskId,
                    workspaceName = screen.workspaceName,
                    taskName = screen.taskName,
                    showBack = showDetailBack,
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
                    onTaskChanged = { scope.launch { taskState.refreshAfterMutation() } },
                )
            }
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
    onNewTask: () -> Unit,
    onExpandSidebar: () -> Unit,
) {
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
        Spacer(modifier = Modifier.weight(1f))
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
    is Screen.NewSession -> "new-session"
    is Screen.Missions -> "missions:${taskId.orEmpty()}"
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
 * 任务内「其他终端」快捷 Tab 的切换（对齐 iOS sessionStrip）：按 sessionKind 路由到
 * Chat / PTY 页，并用 replaceTop 替换栈顶 —— 返回键仍回到任务详情，不会堆一层会话页。
 */
private fun switchTaskSession(nav: NavState, session: WorkspaceSessionSummary, from: Screen) {
    switchTaskSession(nav, session.id, session.sessionKind == "structured", from)
}

private fun switchTaskSession(nav: NavState, session: SessionSnapshot, from: Screen) {
    switchTaskSession(nav, session.id, session.isStructured, from)
}

private fun switchTaskSession(
    nav: NavState,
    sessionId: String,
    isStructured: Boolean,
    from: Screen,
) {
    val taskId = from.taskIdOrNull() ?: return
    val (workspaceName, taskName, workspaceId) = when (from) {
        is Screen.Chat -> Triple(from.workspaceName, from.taskName, from.workspaceId)
        is Screen.PtyTerminal -> Triple(from.workspaceName, from.taskName, from.workspaceId)
        else -> return
    }
    nav.replaceTop(
        if (isStructured) {
            Screen.Chat(sessionId, workspaceName, taskName, workspaceId, taskId)
        } else {
            Screen.PtyTerminal(sessionId, workspaceName, taskName, workspaceId, taskId)
        },
    )
}

private fun Screen.taskIdOrNull(): String? = when (this) {
    is Screen.Chat -> taskId
    is Screen.PtyTerminal -> taskId
    is Screen.WorkspaceTask -> taskId
    Screen.SessionList,
    is Screen.Missions,
    Screen.Settings,
    is Screen.NewSession -> null
}

private fun Screen.sessionIdOrNull(): String? = when (this) {
    is Screen.Chat -> sessionId
    is Screen.PtyTerminal -> sessionId
    Screen.SessionList,
    is Screen.Missions,
    Screen.Settings,
    is Screen.NewSession -> null
    is Screen.WorkspaceTask -> null
}
