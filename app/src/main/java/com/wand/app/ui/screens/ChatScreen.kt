package com.wand.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wand.app.SessionWatcher
import com.wand.app.data.ContentBlock
import com.wand.app.data.matchesModelSearch
import com.wand.app.data.ConversationTurn
import com.wand.app.data.EscalationRequest
import com.wand.app.data.PermissionRequestInfo
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.UploadedFile
import com.wand.app.data.WandApi
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.SESSION_MODE_OPTIONS
import com.wand.app.data.sessionModeLabel
import com.wand.app.data.supportedSessionModeIds
import com.wand.app.speech.SherpaSpeechEngine
import com.wand.app.speech.SttModelManager
import com.wand.app.speech.VoiceInputController
import com.wand.app.ui.ChatStore
import com.wand.app.ui.LocalServerBaseUrl
import com.wand.app.ui.QuickCommitStore
import com.wand.app.ui.SessionDraftStore
import com.wand.app.ui.SessionTitleStore
import com.wand.app.ui.latestUserInputText
import com.wand.app.ui.sessionChromeTitle
import com.wand.app.ui.sessionTopicBlocklist
import com.wand.app.ui.ThinkingEffortOption
import com.wand.app.ui.parseUserAttachmentText
import com.wand.app.ui.thinkingEffortOptions
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.components.LoadingState
import com.wand.app.ui.components.ErrorState
import com.wand.app.ui.components.NoOverscroll
import com.wand.app.ui.components.TailMarqueePathText
import com.wand.app.ui.components.WandDetailBackButton
import com.wand.app.ui.components.WandDetailTopBar
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandSnackbarHost
import com.wand.app.ui.components.showWandNotice
import com.wand.app.ui.components.WandDialog
import com.wand.app.ui.components.WandBottomSheet
import com.wand.app.ui.components.WandTextField
import com.wand.app.ui.components.WandDialogAction
import com.wand.app.ui.components.WandProviderMark
import com.wand.app.ui.components.clickableWithoutRipple
import com.wand.app.ui.theme.AmbientBackground
import com.wand.app.ui.theme.GlassBackdrop
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandGlass
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.reduceMotionEnabled
import com.wand.app.ui.theme.glassBackdropSource
import com.wand.app.ui.components.wandCardSurface
import com.wand.app.ui.theme.glassSurface
import com.wand.app.ui.theme.rememberGlassBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private enum class ChatScrollMode {
    StickToBottom,
    Manual,
}

/** LazyColumn 中正向手指位移表示内容被拉向更早的消息。 */
internal fun shouldPauseBottomFollow(userScrollDeltaY: Float): Boolean = userScrollDeltaY > 0f

internal fun shouldRefreshQuickCommitStatus(isLoading: Boolean, isResponding: Boolean): Boolean {
    return !isLoading && !isResponding
}

/** 首屏消息不要跑 item 入场动画，避免和打开会话抢同一帧。 */
internal fun shouldAnimateChatListItems(listSettled: Boolean): Boolean = listSettled

/** 首屏只补一次贴底，后续流式/视口变化再走完整重试链。 */
internal fun chatStickToBottomRetryDelaysMs(listSettled: Boolean): List<Long> =
    if (listSettled) listOf(50L, 150L, 350L, 700L) else listOf(80L)

/**
 * 原生聊天视图 —— 对称 iOS ChatView.swift：
 * 结构化消息渲染 + 原生输入栏 + 权限审批卡片。
 * 输入栏跟随 imePadding()，键盘升降由系统接管 ——
 * 这正是 WebView 方案里键盘重叠/状态栏错位问题的根治点。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    api: WandApi,
    sessionId: String,
    serverDisplayName: String,
    workspaceName: String? = null,
    taskName: String? = null,
    taskId: String? = null,
    siblingSessions: List<WorkspaceSessionSummary> = emptyList(),
    onSwitchSession: ((WorkspaceSessionSummary) -> Unit)? = null,
    onCreateTaskSession: ((SessionSnapshot) -> Unit)? = null,
    onDeleteTaskSession: ((WorkspaceSessionSummary) -> Unit)? = null,
    isHapticEnabled: () -> Boolean,
    drafts: SessionDraftStore,
    showBack: Boolean = true,
    onBack: () -> Unit,
) {
    val store = remember(sessionId) { ChatStore(sessionId, api) }
    val quickCommit = remember(sessionId) {
        QuickCommitStore(sessionId, api) { msg -> store.toast = msg }
    }
    DisposableEffect(store) {
        store.start()
        onDispose { store.shutdown() }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(store, lifecycleOwner) {
        var paused = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> paused = true
                Lifecycle.Event.ON_RESUME -> {
                    if (paused) {
                        paused = false
                        store.handleEnterForeground()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(quickCommit) {
        onDispose { quickCommit.shutdown() }
    }
    // 注册「正在看」的会话：通知中枢据此抑制当前会话的打扰通知（对齐网页
    // skipWhenSelectedSessionId —— 正盯着的会话不需要系统通知再吵一遍）。
    DisposableEffect(sessionId) {
        SessionWatcher.activeChatSessionId = sessionId
        onDispose {
            if (SessionWatcher.activeChatSessionId == sessionId) {
                SessionWatcher.activeChatSessionId = null
            }
        }
    }
    QuickCommitStatusRefreshEffect(
        quickCommit = quickCommit,
        sessionId = sessionId,
        enabled = shouldRefreshQuickCommitStatus(store.loading, store.isResponding),
    )

    // 草稿读取下沉到 BottomBar 内部：在这里读会把整个 ChatScreen（含消息列表）
    // 订阅到输入框的每个按键，打字时全部可见消息卡跟着重组。
    // 发送后跟随列表底部；用户手动浏览时暂停跟随。
    var scrollMode by rememberSaveable(sessionId) { mutableStateOf(ChatScrollMode.StickToBottom) }
    val listState = key(sessionId) { rememberLazyListState() }
    var chromeSettled by remember(sessionId) { mutableStateOf(false) }
    var listSettled by remember(sessionId) { mutableStateOf(false) }
    LaunchedEffect(sessionId) {
        chromeSettled = false
        delay(WandMotion.normal.toLong())
        chromeSettled = true
    }
    LaunchedEffect(sessionId, store.loading) {
        listSettled = false
        if (store.loading) return@LaunchedEffect
        repeat(2) { withFrameNanos {} }
        listSettled = true
    }
    var listViewportHeightPx by remember(sessionId) { mutableIntStateOf(0) }
    val scrollScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val context = LocalContext.current
    val voiceInput = rememberVoiceInputHandle(
        isHapticEnabled = isHapticEnabled,
        onToast = { store.toast = it },
        onCommit = { text -> drafts[sessionId] = appendVoiceText(drafts[sessionId], text) },
    )
    val voice = voiceInput.voice
    val onMicDown = voiceInput.onMicDown

    val focusManager = LocalFocusManager.current

    // 探索类工具跨消息合并成「探索上下文」紧凑卡（对齐 iOS groupExplorationTurns）。
    // 所有用户输入始终完整显示；历史回复默认展开，和当前轮一起展示。
    val displayItems = remember(store.messages) { groupExplorationTurns(store.messages) }
    val scrubberTargets = remember(displayItems) { conversationScrubberTargets(displayItems) }
    val lastUserTurnIndex = remember(store.messages) {
        store.messages.indexOfLast { it.role == "user" }
    }
    val subagentActivities = remember(store.messages, store.isResponding) {
        collectSubagentActivities(store.messages, store.isResponding)
    }
    val showActivityDock = store.isStructured && (store.isResponding || subagentActivities.isNotEmpty())
    // 顶栏用量：避免 ChatScreen 每次重组都对全部消息线性扫描。
    val lastAssistantUsage = remember(store.messages) {
        store.messages.lastOrNull { it.role == "assistant" }?.usage
    }
    var activityDockExpanded by rememberSaveable(sessionId) { mutableStateOf(false) }
    LaunchedEffect(showActivityDock) {
        if (!showActivityDock) activityDockExpanded = false
    }
    val activityDockListPadding = when {
        !showActivityDock -> 4.dp
        subagentActivities.isEmpty() -> 28.dp
        else -> 62.dp
    }
    val activityDockFabPadding = when {
        !showActivityDock -> 12.dp
        subagentActivities.isEmpty() -> 38.dp
        else -> 70.dp
    }

    // 附件上传：savedPath 回填输入框（多选 ≤5 个 / 单个 ≤10MB）。
    // 对齐 iOS：相册图片 / 任意文件两条入口共用同一段上传逻辑。
    var uploadingAttachments by remember { mutableStateOf(false) }
    var pendingAttachments by remember(sessionId) { mutableStateOf<List<UploadedFile>>(emptyList()) }
    val attachmentPickers = rememberAttachmentPickerActions { uris ->
        scrollScope.launchAttachmentUpload(
            context = context,
            api = api,
            sessionId = sessionId,
            uris = uris,
            onUploadingChange = { uploadingAttachments = it },
            onUploaded = { uploaded -> pendingAttachments = (pendingAttachments + uploaded).takeLast(5) },
            onToast = { store.toast = it },
        )
    }

    // 历史不再整段隐藏，分页入口始终可达。
    val showLoadEarlierSentinel = store.canLoadEarlier
    val headerOffset = if (showLoadEarlierSentinel) 1 else 0
    // bottomIndex 是最后的 chat-bottom 哨兵下标（即它之前的项数）。
    val bottomIndex = headerOffset + displayItems.size

    // 用户一开始向上浏览旧内容就立即暂停贴底跟随。流式消息刷新很频繁，若等拖动
    // 累计超过某个阈值才暂停，阈值内的新 token 会先把列表重新拽回底部。
    // Manual 模式不会因用户自己滚回底部而退出；只有“回到底部”按钮或主动发送才恢复。
    val followPauseConnection = remember(focusManager) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    if (available.y != 0f) focusManager.clearFocus()
                    if (shouldPauseBottomFollow(available.y)) {
                        scrollMode = ChatScrollMode.Manual
                    }
                }
                return Offset.Zero
            }
        }
    }
    // 输入栏聚焦后会从单行胶囊变成双行卡片，IME 弹出/收起也会改变列表视口。
    // 贴底模式必须把这些尺寸变化视作一次新的定位请求，否则最后一行会落到
    // 变高的输入栏之后；手动浏览模式则保持用户当前阅读位置，不主动跳转。
    //
    // 拆成两个 effect：流式期间 messages 每个事件都换新引用，若把「等布局稳定的
    // 重试链」也挂在它上面，链会在每个 chunk 到来时被取消重启、永远走不完，
    // 还会每个 chunk 触发多次 scrollToItem。内容变化只做一次即时贴底；
    // 重试链只挂在模式/视口/回合切换这类低频 key 上。
    LaunchedEffect(store.messages, store.loading) {
        if (!store.loading && scrollMode != ChatScrollMode.Manual) {
            listState.scrollToItem(
                maxOf(bottomIndex, listState.layoutInfo.totalItemsCount - 1, 0)
            )
        }
    }
    LaunchedEffect(
        store.isResponding,
        bottomIndex,
        scrollMode,
        listViewportHeightPx,
        listSettled,
    ) {
        if (!store.loading && scrollMode != ChatScrollMode.Manual) {
            fun targetIndex(): Int = when (scrollMode) {
                ChatScrollMode.StickToBottom ->
                    maxOf(bottomIndex, listState.layoutInfo.totalItemsCount - 1, 0)
                ChatScrollMode.Manual -> bottomIndex
            }
            listState.scrollToItem(targetIndex())
            for (waitMs in chatStickToBottomRetryDelaysMs(listSettled)) {
                delay(waitMs)
                if (scrollMode == ChatScrollMode.Manual) break
                listState.scrollToItem(targetIndex())
            }
        }
    }
    // 展开某条历史回复时，把它的标题行滚到顶部区域来读；
    // 同时暂停贴底跟随，免得流式刷新把视图拽回底部。
    val scrollReplyToTop: (Int) -> Unit = { absoluteTurnIndex ->
        scrollMode = ChatScrollMode.Manual
        scrollScope.launch {
            val position = displayItems.indexOfFirst {
                store.loadedOffset + messageItemTurnIndex(it) == absoluteTurnIndex
            }
            val target = if (position >= 0) headerOffset + position else -1
            if (target >= 0) listState.animateScrollToItem(target)
        }
    }
    val expandCurrentReplyToBottom: () -> Unit = {
        scrollMode = ChatScrollMode.StickToBottom
        scrollScope.launch {
            for (waitMs in listOf(50L, 150L, 350L, 700L)) {
                delay(waitMs)
                if (scrollMode == ChatScrollMode.Manual) break
                val target = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                if (waitMs == 50L) {
                    listState.animateScrollToItem(target)
                } else {
                    listState.scrollToItem(target)
                }
            }
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(store.toast) {
        val message = store.toast ?: return@LaunchedEffect
        snackbarHostState.showWandNotice(message)
        if (store.toast == message) store.toast = null
    }

    // 液态玻璃：内容区是 backdrop 捕获源，顶栏/输入栏/FAB 悬浮其上采样模糊+折射。
    val glassBackdrop = rememberGlassBackdrop()
    val activeBackdrop = if (chromeSettled) glassBackdrop else null
    var composerExpanded by remember { mutableStateOf(false) }
    CompositionLocalProvider(
        LocalServerBaseUrl provides api.baseUrl,
        LocalChatApi provides api,
        LocalChatSessionId provides sessionId,
        LocalCardExpandDefaults provides store.cardDefaults,
    ) {
    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { WandSnackbarHost(snackbarHostState) },
        topBar = {
            // 稳定标题 + 简明会话上下文；避免流式任务名和长路径持续跳动、抢占操作区。
            // 顶栏使用稳定页底，避免滚动文字透进状态栏形成残影。
            Column {
                WandDetailTopBar(
                    title = "对话详情",
                    backdrop = activeBackdrop,
                contentHeight = 56.dp,
                leading = if (showBack) {
                    {
                        WandDetailBackButton(
                            onClick = onBack,
                            icon = WandIcons.back,
                        )
                    }
                } else {
                    null
                },
                titleContent = {
                    WandProviderMark(store.snapshot?.provider)
                    Column(
                        horizontalAlignment = Alignment.Start,
                        modifier = Modifier.weight(1f),
                    ) {
                        val workspaceTitle = workspaceName?.trim().takeUnless { it.isNullOrEmpty() }
                        val blockedTitles = sessionTopicBlocklist(
                            taskName = taskName,
                            workspaceName = workspaceName,
                            cwd = store.snapshot?.cwd,
                        )
                        ChatTopicTitle(
                            text = sessionChromeTitle(
                                title = store.snapshot?.title,
                                latestUserInput = latestUserInputText(store.messages),
                                liveTitle = SessionTitleStore.titleOf(sessionId),
                                blockedTitles = blockedTitles,
                                fallback = "对话详情",
                            ),
                            generating = store.snapshot?.titleGenerating == true ||
                                SessionTitleStore.isGenerating(sessionId),
                        )
                        val workingPath = chatWorkingPath(store.snapshot?.cwd)
                        TailMarqueePathText(
                            path = if (workspaceTitle != null) {
                                "$serverDisplayName · $workspaceTitle"
                            } else if (workingPath == null) {
                                serverDisplayName
                            } else {
                                "$serverDisplayName · $workingPath"
                            },
                            modifier = Modifier.fillMaxWidth(),
                            fontSize = 11.sp,
                            color = WandColors.textMuted,
                            fallback = serverDisplayName,
                            initialDelayMillis = 1_800L,
                            velocity = 28.dp,
                            revealOnce = true,
                        )
                    }
                },
                actions = {
                    GitChangesButton(quickCommit, compact = true) { quickCommit.openPanel() }
                },
                )
                // 顶部「其他会话」快捷条：任务内展示同任务工作窗口；未分组会话展示同
                // 目录的兄弟终端。都没有第二个会话时整条隐藏，不占垂直空间。
                if (taskId != null && onSwitchSession != null && onCreateTaskSession != null) {
                    TaskSessionTabStrip(
                        api = api,
                        taskId = taskId,
                        currentSessionId = sessionId,
                        onSelect = onSwitchSession,
                        onCreated = onCreateTaskSession,
                        onDeleted = onDeleteTaskSession,
                    )
                } else if (taskId == null && onSwitchSession != null && siblingSessions.size >= 2) {
                    StandaloneSessionTabStrip(
                        sessions = siblingSessions,
                        currentSessionId = sessionId,
                        parentNames = listOfNotNull(workspaceName?.trim()?.takeIf { it.isNotEmpty() }),
                        onSelect = onSwitchSession,
                    )
                }
            }
        },
        bottomBar = { BottomBar(
            backdrop = activeBackdrop,
            store = store,
            drafts = drafts,
            sessionId = sessionId,
            voice = voice,
            onMicDown = onMicDown,
            uploading = uploadingAttachments,
            pendingAttachments = pendingAttachments,
            baseUrl = api.baseUrl,
            onRemoveAttachment = { file ->
                pendingAttachments = pendingAttachments.filterNot { it.savedPath == file.savedPath }
            },
            onPickPhoto = attachmentPickers.pickPhoto,
            onPickFile = attachmentPickers.pickFile,
            onExpandedChange = { composerExpanded = it },
        ) {
            // 发送回调（带触感反馈）；新输入出现后，上一条回复会自动转为历史折叠态。
            if (isHapticEnabled()) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            val text = buildAttachmentPrompt(pendingAttachments, drafts[sessionId])
            drafts[sessionId] = ""
            pendingAttachments = emptyList()
            scrollMode = ChatScrollMode.StickToBottom
            store.send(text)
        } },
    ) { padding ->
        // 捕获层：环境渐变背景全幅铺开，消息流只在顶栏与输入栏之间滚动，
        // 避免正文被玻璃栏遮挡或透进状态栏。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(focusManager) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        focusManager.clearFocus()
                    }
                }
                .then(
                    if (onSwitchSession != null) {
                        Modifier.taskSessionSwipe(
                            sessions = siblingSessions,
                            currentSessionId = sessionId,
                            onSelect = onSwitchSession,
                        )
                    } else {
                        Modifier
                    },
                )
                .then(if (chromeSettled) Modifier.glassBackdropSource(glassBackdrop) else Modifier),
        ) {
            AmbientBackground(Modifier.fillMaxSize())
            when {
                store.loading -> LoadingState(
                    modifier = Modifier.padding(padding),
                    text = "正在加载会话…",
                )
                store.loadError != null ->
                    ErrorState(store.loadError ?: "加载失败", modifier = Modifier.padding(padding))
                store.isStructured && store.messages.isEmpty() && !store.isResponding ->
                    Box(Modifier.padding(padding)) {
                        SessionLaunchPanel(store, showSettings = !composerExpanded)
                    }
                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                    LazyColumn(
                        state = listState,
                        // 超宽详情区限宽居中。不要把 wrapContentWidth 直接套在 LazyColumn 上：
                        // 平板分栏里它会按内容测宽，列表视口塌掉，看起来像「能输入但没有输出」。
                        modifier = Modifier
                            .widthIn(max = ChatReadableMaxWidth)
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .onSizeChanged { listViewportHeightPx = it.height }
                            .nestedScroll(followPauseConnection),
                        contentPadding = PaddingValues(
                            start = 14.dp,
                            end = 14.dp,
                            top = 12.dp,
                            bottom = activityDockListPadding,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // 显式分页控件常驻在已加载内容顶部，保证更早消息始终可达。
                        if (showLoadEarlierSentinel) {
                            item(key = "chat-load-earlier") {
                                TextButton(
                                    onClick = store::loadEarlier,
                                    enabled = !store.loadingEarlier,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp),
                                ) {
                                    if (store.loadingEarlier) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = WandColors.brand,
                                        )
                                        Spacer(Modifier.size(8.dp))
                                    } else {
                                        Icon(
                                            WandIcons.history,
                                            contentDescription = null,
                                            tint = WandColors.brand,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.size(8.dp))
                                    }
                                    Text(
                                        if (store.loadingEarlier) "正在加载更早消息…" else "加载更早消息",
                                        fontSize = 12.sp,
                                        color = WandColors.textSecondary,
                                    )
                                }
                            }
                        }
                        // key 基于绝对 turn 位置/稳定工具 id：prepend 分页不会重建所有卡片。
                        itemsIndexed(
                            displayItems,
                            key = { _, item ->
                                messageItemKey(
                                    item = item,
                                    loadedOffset = store.loadedOffset,
                                    anchorExplorationAtEnd = lastUserTurnIndex >= 0 &&
                                        messageItemTurnIndex(item) < lastUserTurnIndex,
                                )
                            },
                            // 列表混排 user 气泡 / assistant turn / 探索聚合卡三种形态，
                            // 提供 contentType 提高槽位复用命中率。
                            contentType = { _, item -> item::class },
                        ) { _, item ->
                            Box(
                                modifier = if (shouldAnimateChatListItems(listSettled)) {
                                    Modifier.animateItem()
                                } else {
                                    Modifier
                                },
                            ) {
                                when (item) {
                                    is MessageDisplayItem.Turn -> {
                                        val absoluteTurnIndex = store.loadedOffset + item.index
                                        val collapseReply = item.turn.role != "user" &&
                                            shouldCollapseReply(item.index, lastUserTurnIndex)
                                        val isCurrentReply = item.turn.role != "user" && !collapseReply
                                        TurnView(
                                            item.turn,
                                            isLastTurn = item.index == store.messages.lastIndex,
                                            isResponding = store.isResponding,
                                            compactUser = false,
                                            initiallyCollapsed = collapseReply,
                                            showHeader = true,
                                            onUserExpand = { scrollReplyToTop(absoluteTurnIndex) },
                                            onCurrentReplyExpandToBottom = {
                                                if (isCurrentReply) expandCurrentReplyToBottom()
                                            },
                                            askSelections = store.askUserSelections,
                                            onAskToggle = { toolUseId, qIdx, optIdx, multi ->
                                                store.toggleAskOption(toolUseId, qIdx, optIdx, multi)
                                            },
                                            onAskSubmit = { toolUseId, answerText ->
                                                scrollMode = ChatScrollMode.StickToBottom
                                                store.submitAskUser(toolUseId, answerText)
                                            },
                                        )
                                    }
                                    is MessageDisplayItem.Exploration -> ExplorationGroupCard(
                                        tools = item.tools,
                                        running = store.isResponding &&
                                            item.lastTurnIndex == store.messages.lastIndex &&
                                            item.tools.any { it.result == null },
                                        expandAll = item.lastTurnIndex == store.messages.lastIndex,
                                    )
                                }
                            }
                        }
                        item(key = "chat-bottom") {
                            Spacer(modifier = Modifier.size(1.dp))
                        }
                    }
                    val currentScrubberItem = conversationScrubberIndexForDisplayItem(
                        scrubberTargets,
                        listState.layoutInfo.visibleItemsInfo
                            .filter { it.index >= headerOffset && it.index < headerOffset + displayItems.size }
                            .minByOrNull { item ->
                                kotlin.math.abs(
                                    item.offset + item.size / 2 -
                                        (listState.layoutInfo.viewportStartOffset +
                                            listState.layoutInfo.viewportEndOffset) / 2,
                                )
                            }
                            ?.index
                            ?.minus(headerOffset)
                            ?.coerceIn(0, (displayItems.size - 1).coerceAtLeast(0))
                            ?: 0,
                    )
                    ConversationTurnScrubber(
                        itemCount = scrubberTargets.size,
                        currentItem = currentScrubberItem,
                        currentPreview = scrubberUserPreview(
                            displayItems,
                            scrubberTargets.getOrNull(currentScrubberItem) ?: 0,
                        ),
                        onSelect = { itemIndex, animate ->
                            val displayIndex = scrubberTargets.getOrNull(itemIndex)
                            if (displayIndex != null) {
                                scrollMode = ChatScrollMode.Manual
                                scrollScope.launch {
                                    if (animate) listState.animateScrollToItem(headerOffset + displayIndex)
                                    else listState.scrollToItem(headerOffset + displayIndex)
                                }
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 3.dp, top = 12.dp, bottom = 12.dp),
                    )
                    }
                    }
                    }
                }
            }
        // 浮层（FAB / 对话框）：吃 innerPadding、不进捕获层 ——
        // 玻璃元素不能采样到自己。应用内通知走 Scaffold SnackbarHost。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 断线提示条：贴顶，安全区内显示。恢复连接后滑出消失。
            ConnectionBanner(
                visible = !store.connected,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            AnimatedVisibility(
                visible = showActivityDock,
                enter = fadeIn(WandMotion.tweenFast()) +
                    slideInVertically(WandMotion.settleSpringSpec()) { height -> height / 3 },
                exit = fadeOut(WandMotion.tweenFast()) +
                    slideOutVertically(WandMotion.settleSpringSpec()) { height -> height / 3 },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                key(sessionId) {
                    SubagentActivityDock(
                        backdrop = activeBackdrop,
                        activities = subagentActivities,
                        usage = lastAssistantUsage,
                        taskTitle = store.currentTaskTitle,
                        sessionRunning = store.isResponding,
                        onExpandedChange = { activityDockExpanded = it },
                    )
                }
            }
            // 回到底部按钮：品牌色玻璃圆钮，淡入 + 缩放。用户上滚后点它，回到真正的列表底部。
            AnimatedVisibility(
                visible = !store.loading &&
                    store.loadError == null &&
                    !activityDockExpanded &&
                    scrollMode == ChatScrollMode.Manual &&
                    listState.canScrollForward,
                enter = fadeIn(WandMotion.tweenFast()) +
                    scaleIn(initialScale = 0.8f, animationSpec = WandMotion.tweenFast()),
                exit = fadeOut(WandMotion.tweenFast()) +
                    scaleOut(targetScale = 0.8f, animationSpec = WandMotion.tweenFast()),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = activityDockFabPadding),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .glassSurface(activeBackdrop, CircleShape, WandGlass.accent)
                        .clickable {
                            scrollMode = ChatScrollMode.StickToBottom
                            scrollScope.launch {
                                delay(50)
                                val target = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                                listState.animateScrollToItem(target)
                            }
                        },
                ) {
                    Icon(
                        WandIcons.expand,
                        contentDescription = "回到底部",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // 端侧语音模型下载对话框（无可用识别引擎时由麦克风按钮触发）。
            if (voice.showModelDialog) {
                SttModelDownloadDialog(onDismiss = { voice.showModelDialog = false })
            }

            // Git 快捷提交弹层（磁吸气泡 dock，对齐网页版交互）。
            if (quickCommit.panelOpen) {
                QuickCommitSheet(
                    qc = quickCommit,
                    isHapticEnabled = isHapticEnabled,
                    onDismiss = { quickCommit.closePanel() },
                )
            }
        }
    }
    }
}

@Composable
internal fun ChatTopicTitle(text: String, generating: Boolean) {
    if (!generating || reduceMotionEnabled()) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = WandColors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    val transition = rememberInfiniteTransition(label = "topicTitleRhythm")
    val rhythm by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = WandMotion.breath(),
        label = "topicTitlePhase",
    )
    val liftPx = with(LocalDensity.current) { 1.dp.toPx() }
    Text(
        text,
        modifier = Modifier.graphicsLayer {
            alpha = 0.64f + rhythm * 0.36f
            translationY = -liftPx * rhythm
        },
        style = MaterialTheme.typography.titleMedium,
        color = WandColors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 顶栏副标题只展示完整工作目录；空间不足时由路径文本负责延迟滚动。 */
private fun chatWorkingPath(path: String?): String? =
    path
        ?.trim()
        ?.replace('\\', '/')
        ?.trimEnd('/')
        ?.takeIf { it.isNotEmpty() }

internal fun conversationTurnPreview(turn: ConversationTurn): String {
    val rawText = turn.content
        .filterIsInstance<ContentBlock.Text>()
        .joinToString(" ") { it.text }
    if (rawText.isNotBlank()) {
        val parsed = if (turn.role == "user") parseUserAttachmentText(rawText) else null
        val body = compactPreviewText(parsed?.body ?: rawText)
        if (body.isNotBlank()) return body
        parsed?.paths?.takeIf { it.isNotEmpty() }?.let { paths ->
            return "${paths.size} 个附件"
        }
    }
    val toolCount = turn.content.count { it is ContentBlock.ToolUse }
    return if (toolCount > 0) "$toolCount 个工具调用" else ""
}

internal fun compactPreviewText(text: String): String {
    val compacted = text
        .lineSequence()
        .map { line ->
            line.trim().replaceFirst(Regex("^(#{1,6}|[-+*>])\\s+"), "")
        }
        .joinToString(" ")
        .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
        // 下划线常属于文件名/标识符；不要像 Markdown 装饰符一样全局删除。
        .replace(Regex("[`*~]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
    val preview = compacted.take(240)
    return if (preview.lastOrNull()?.isHighSurrogate() == true) preview.dropLast(1) else preview
}

internal fun messageItemKey(
    item: MessageDisplayItem,
    loadedOffset: Int,
    anchorExplorationAtEnd: Boolean = false,
): String = when (item) {
    is MessageDisplayItem.Turn -> "turn-${loadedOffset + item.index}"
    is MessageDisplayItem.Exploration -> {
        // 当前流式分组从右侧增长，用首个 tool id；历史分页从左侧 prepend，
        // 用绝对右边界。两类列表分别锚住不变的一端，避免局部展开状态抖动。
        val stableIdentity = if (anchorExplorationAtEnd) {
            (loadedOffset + item.lastTurnIndex).toString()
        } else {
            item.tools.firstOrNull()?.use?.id?.takeIf { it.isNotBlank() }
                ?: (loadedOffset + item.lastTurnIndex).toString()
        }
        "explore-$stableIdentity"
    }
}

/** 历史回复不再默认收起，和当前轮一起展示。 */
internal fun shouldCollapseReply(turnIndex: Int, lastUserTurnIndex: Int): Boolean =
    false

/** 只有一条用户消息时整屏都能看到，侧边缩略条没有定位价值。 */
internal fun shouldShowConversationTurnScrubber(itemCount: Int): Boolean = itemCount > 1

/** 刻度只对应已加载页里的用户消息，助手回复不单独占一条。 */
internal fun conversationScrubberTargets(items: List<MessageDisplayItem>): List<Int> =
    items.mapIndexedNotNull { index, item ->
        index.takeIf { item is MessageDisplayItem.Turn && item.turn.role == "user" }
    }

/** 当前可见条目落到某轮问答时，高亮该轮的用户消息刻度。 */
internal fun conversationScrubberIndexForDisplayItem(
    targets: List<Int>,
    displayIndex: Int,
): Int {
    if (targets.isEmpty()) return 0
    val exact = targets.binarySearch(displayIndex)
    if (exact >= 0) return exact
    return ( -exact - 2).coerceAtLeast(0)
}

/**
 * 会话缩略滚动条：每一条代表一条已加载的用户消息。
 * 点击或拖动都可快速定位；它不改变分页顺序，只负责在当前已加载页内跳转。
 */
private fun scrubberUserPreview(items: List<MessageDisplayItem>, index: Int): String {
    for (position in index downTo 0) {
        val item = items.getOrNull(position) as? MessageDisplayItem.Turn ?: continue
        if (item.turn.role == "user") {
            return conversationTurnPreview(item.turn).ifBlank { "用户消息" }
        }
    }
    return ""
}

@Composable
private fun ConversationTurnScrubber(
    itemCount: Int,
    currentItem: Int,
    currentPreview: String,
    onSelect: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!shouldShowConversationTurnScrubber(itemCount)) return
    val latestOnSelect = rememberUpdatedState(onSelect)
    // 书签轨只有 40.dp 宽；气泡必须 unbounded 测量，否则会被父级压成一条细条。
    // 不要用英文字符数估宽：中文实际占宽约 1em，length*7.dp 会明显偏窄。
    val maxBubbleWidth = (LocalConfiguration.current.screenWidthDp * 0.82f).dp
    var dragging by remember { mutableStateOf(false) }
    val railScale by animateFloatAsState(
        targetValue = if (dragging) 1.18f else 1f,
        animationSpec = WandMotion.tweenFast(),
        label = "scrubber-scale",
    )
    val scrubberHeight = (itemCount * 5).coerceIn(48, 240).dp
    Box(
        modifier = modifier
            .width(40.dp)
            .height(scrubberHeight)
            .pointerInput(itemCount) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        val index = ((offset.y / size.height) * itemCount)
                            .toInt().coerceIn(0, itemCount - 1)
                        latestOnSelect.value(index, false)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val index = ((change.position.y / size.height) * itemCount)
                            .toInt().coerceIn(0, itemCount - 1)
                        latestOnSelect.value(index, false)
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                )
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (dragging && currentPreview.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(
                        x = (-44).dp,
                        y = scrubberHeight * ((currentItem + 0.5f) / itemCount.toFloat()) - 22.dp,
                    )
                    .wrapContentWidth(align = Alignment.End, unbounded = true)
                    .widthIn(min = 120.dp, max = maxBubbleWidth)
                    .clip(RoundedCornerShape(14.dp))
                    .background(WandColors.bgElevated.copy(alpha = 0.96f))
                    .border(0.55.dp, WandColors.border.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = currentPreview,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = WandColors.textPrimary,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = railScale; scaleY = railScale },
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.End,
        ) {
            (0 until itemCount).forEach { index ->
                val distance = kotlin.math.abs(index - currentItem)
                val targetWidth = when {
                    distance == 0 -> 30.dp
                    distance == 1 -> 16.dp
                    distance == 2 -> 11.dp
                    else -> 7.dp
                }
                val width by animateDpAsState(
                    targetValue = targetWidth,
                    animationSpec = WandMotion.tweenFast(),
                    label = "scrubber-width",
                )
                val selected = distance == 0
                Box(
                    modifier = Modifier
                        .width(width)
                        .height(if (selected) 3.dp else 2.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (selected) WandColors.brand.copy(alpha = 0.88f)
                            else WandColors.textMuted.copy(alpha = 0.25f),
                        )
                        .clickableWithoutRipple { onSelect(index, true) },
                )
            }
        }
    }
}


@Composable
private fun SessionLaunchPanel(store: ChatStore, showSettings: Boolean) {
    // apple-design §7/§16 Familiarity & Spatial consistency:
    // 头部品牌标随 provider 切换（Claude/Codex/Grok/OpenCode/Qoder 各自的原生 logo），
    // 让用户从会话列表进入聊天页时视觉连续——之前的 WandBrandMark 对所有 provider 都显示同一星芒标，
    // 与标题文字（如 "Codex"）对不上，是 logo 对应关系的根因。
    val provider = store.snapshot?.provider
    val accent = if (provider == "codex") WandColors.info else WandColors.brand
    val accentSoft = if (provider == "codex") WandColors.infoSoft else WandColors.brandSoft
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            // 光学中心略高于几何中心；避免空页在大屏上读起来“下坠”。
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 360.dp)
                .offset(y = (-28).dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                ProviderBrandMark(
                    provider = provider,
                    size = 52,
                )
                // apple-design §15 Typography：大字号配负 tracking、SemiBold 而非 Bold，
                // 让 provider 名既有存在感又不喧宾夺主。
                Text(
                    store.snapshot?.providerLabel ?: "结构化会话",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.35).sp,
                    color = WandColors.textPrimary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "输入消息，让它帮你完成任务",
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                    color = WandColors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            if (showSettings) {
                // apple-design §12 Materials：欢迎区直接落在背景上，只让可操作的设置组成为唯一浮层。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wandCardSurface(WandShapes.lg)
                        .clip(RoundedCornerShape(20.dp)),
                ) {
                    LaunchSettingPicker(
                        icon = WandIcons.tune,
                        label = "模型",
                        value = launchModelDisplayLabel(store, store.selectedModel),
                        accent = accent,
                        accentSoft = accentSoft,
                        options = buildList {
                            add(null to "默认 · ${modelDisplayLabel(store, null)}")
                            store.availableModels
                                .filter { it.id != "default" }
                                .forEach { add(it.id to it.label) }
                        },
                        selected = store.selectedModel?.takeUnless { it == "default" },
                        onSelect = store::setModel,
                        searchable = true,
                    )
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = WandColors.border.copy(alpha = 0.62f),
                        modifier = Modifier.padding(start = 68.dp),
                    )
                    LaunchSettingPicker(
                        icon = WandIcons.thinking,
                        label = "思考深度",
                        value = thinkingLabel(store, store.thinkingEffort),
                        accent = accent,
                        accentSoft = accentSoft,
                        options = thinkingLevels(store).map { it.id to it.menuLabel },
                        selected = store.thinkingEffort,
                        onSelect = { it?.let(store::chooseThinkingEffort) },
                    )
                }
            }
        }
    }
}

/**
 * Provider 品牌标：中性悬浮圆角方块 + 工具原生 logo。
 * 不再铺 Wand 橙色底，也不把官方配色改写成 accent。
 * 与 [com.wand.app.ui.components.WandBrandMark] 同尺寸/同圆角比例，但内容随 provider 变化。
 */
@Composable
private fun ProviderBrandMark(
    provider: String?,
    size: Int = 52,
) {
    val corner = RoundedCornerShape((size * 0.26f).dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size.dp)
            .shadow(elevation = (size * 0.06f).dp, shape = corner)
            .clip(corner)
            .background(WandColors.surface)
            .border(0.5.dp, WandColors.border.copy(alpha = 0.55f), corner),
    ) {
        Icon(
            painter = BrandLogos.painterForProvider(provider),
            contentDescription = null,
            tint = BrandLogos.tintForProvider(provider, WandColors.textPrimary),
            modifier = Modifier.size(
                (size * 0.56f * BrandLogos.opticalScale(provider)).dp,
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LaunchSettingPicker(
    icon: ImageVector,
    label: String,
    value: String,
    accent: Color,
    accentSoft: Color,
    options: List<Pair<String?, String>>,
    selected: String?,
    onSelect: (String?) -> Unit,
    searchable: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val visibleOptions = if (searchable) {
        options.filter { matchesModelSearch(query, it.first.orEmpty(), it.second) }
    } else {
        options
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = WandMotion.settleSpringSpec(),
        label = "launch-setting-press",
    )
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 66.dp)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .background(if (pressed) accentSoft else Color.Transparent)
                .semantics(mergeDescendants = true) {
                    stateDescription = "当前为$value"
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    role = Role.DropdownList,
                ) { expanded = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            // 左侧品牌色图标片，让两行各有清晰的身份（模型 / 思考深度）。
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WandColors.textMuted,
                )
                Text(
                    value,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = WandColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                WandIcons.chevronRight,
                contentDescription = null,
                tint = WandColors.textMuted.copy(alpha = 0.8f),
                modifier = Modifier.size(16.dp),
            )
        }
        if (expanded) {
            WandBottomSheet(
                onDismissRequest = {
                    expanded = false
                    query = ""
                },
            ) {
                NoOverscroll {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .imePadding()
                            .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
                    ) {
                        Text(
                            "选择$label",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WandColors.textPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                        if (searchable) {
                            WandTextField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = "搜索$label",
                                singleLine = true,
                                leadingIcon = {
                                    Icon(
                                        WandIcons.search,
                                        contentDescription = null,
                                        tint = WandColors.textMuted,
                                    )
                                },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                            )
                        }
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                        ) {
                        if (visibleOptions.isEmpty()) {
                            Text(
                                "没有匹配的$label",
                                fontSize = 14.sp,
                                color = WandColors.textMuted,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                            )
                        }
                        visibleOptions.forEach { (id, optionLabel) ->
                            val isSelected = selected == id
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isSelected) accentSoft else Color.Transparent,
                                    )
                                    .selectable(
                                        selected = isSelected,
                                        role = Role.RadioButton,
                                    ) {
                                        onSelect(id)
                                        expanded = false
                                        query = ""
                                    }
                                    .padding(horizontal = 14.dp, vertical = 13.dp),
                            ) {
                                Text(
                                    optionLabel,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) accent else WandColors.textPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                                if (isSelected) {
                                    Icon(
                                        WandIcons.check,
                                        contentDescription = null,
                                        tint = accent,
                                        modifier = Modifier.size(18.dp),
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

private fun thinkingLevels(store: ChatStore): List<ThinkingEffortOption> =
    thinkingEffortOptions(
        provider = store.snapshot?.provider ?: "claude",
        selectedModel = store.selectedModel,
        defaultModel = store.defaultModel,
        models = store.availableModels,
    )

private fun thinkingLabel(store: ChatStore, id: String): String {
    val levels = thinkingLevels(store)
    return levels.firstOrNull { it.id == id }?.label
        ?: levels.firstOrNull()?.label
        ?: "自动"
}

private fun thinkingShortLabel(store: ChatStore, id: String): String =
    thinkingLevels(store).firstOrNull { it.id == id }?.shortLabel ?: "自"

private fun modelDisplayLabel(store: ChatStore, id: String?): String {
    val effectiveId = id?.takeIf { it != "default" } ?: store.defaultModel
    if (effectiveId.isNullOrBlank()) {
        return store.availableModels.firstOrNull { it.id == "default" }?.label ?: "跟随服务端默认"
    }
    return store.availableModels.firstOrNull { it.id == effectiveId }?.label ?: effectiveId
}

/** 启动卡空间宝贵；当服务端 label 已是“人读名 · id”时去掉重复 id。 */
private fun launchModelDisplayLabel(store: ChatStore, id: String?): String {
    val effectiveId = id?.takeIf { it != "default" } ?: store.defaultModel
    return compactModelDisplayLabel(modelDisplayLabel(store, id), effectiveId)
}

internal fun compactModelDisplayLabel(label: String, modelId: String?): String {
    val id = modelId?.trim()?.takeIf { it.isNotEmpty() } ?: return label
    val separator = label.lastIndexOf(" · ")
    if (separator <= 0) return label
    val suffix = label.substring(separator + 3).trim()
    return if (suffix.equals(id, ignoreCase = true)) label.substring(0, separator).trimEnd() else label
}

/**
 * 平板横屏等超宽详情区：消息流与输入栏限宽居中，保证可读行长。
 * 手机宽度不受影响；终端页（PtyTerminalScreen）刻意保持全宽，不套用此值。
 */
private val ChatReadableMaxWidth = 760.dp

/** 控制行徽标用的精简模型名：去掉「opus（最新 Opus）」括号补充（全角/半角都吃），只留主名。 */
private fun shortModelLabel(store: ChatStore): String {
    val full = modelDisplayLabel(store, store.selectedModel)
    if (full == "跟随服务端默认" || full == "默认") return "默认"
    val idx = full.indexOfFirst { it == '（' || it == '(' }
    val clean = if (idx > 0) full.substring(0, idx).trimEnd() else full
    val leaf = clean.substringAfterLast('/').trim()
    val lower = leaf.lowercase()
    return when {
        "opus" in lower -> "Opus"
        "sonnet" in lower -> "Sonnet"
        "haiku" in lower -> "Haiku"
        "gpt-5.5" in lower -> "GPT-5.5"
        "gpt-5" in lower -> "GPT-5"
        "gpt-4" in lower -> "GPT-4"
        leaf.length > 12 -> leaf.take(10) + "…"
        else -> leaf
    }
}

private fun modelThinkingText(store: ChatStore): String {
    val model = shortModelLabel(store)
    return "$model · ${thinkingShortLabel(store, store.thinkingEffort)}"
}


/**
 * 排队消息条（对位 Web 端 queue-bar）：折叠态显示「已排队 N 条」+ 操作按钮；
 * 展开后逐条列出，每条带「立即发送 ⚡」「删除 ×」，外加底部「全部清空」。
 * promote 的中断/preserveQueue 语义在 ChatStore.promoteQueued 里按 inFlight 自动决定。
 */
@Composable
private fun QueueBar(store: ChatStore, backdrop: GlassBackdrop?) {
    var expanded by rememberSaveable(store.sessionId) { mutableStateOf(false) }
    val queue = store.queuedMessages
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = WandMotion.tweenFast(),
        label = "queue-expand-rotate",
    )
    Column(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .glassSurface(backdrop, RoundedCornerShape(14.dp), WandGlass.clear)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 标题行：图标 + 计数 + 展开/收起；整行可点。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickableWithoutRipple { expanded = !expanded }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Icon(
                WandIcons.history,
                contentDescription = null,
                tint = WandColors.textMuted,
                modifier = Modifier.size(14.dp),
            )
            Text(
                "已排队 ${queue.size} 条消息",
                fontSize = 12.sp,
                color = WandColors.textMuted,
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                WandIcons.expand,
                contentDescription = if (expanded) "收起" else "展开",
                tint = WandColors.textMuted,
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = rotation },
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(WandMotion.tweenFast()),
            exit = fadeOut(WandMotion.tweenFast()),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                queue.forEachIndexed { index, text ->
                    QueueItemRow(
                        index = index,
                        text = text,
                        onPromote = { store.promoteQueued(index) },
                        onDelete = { store.deleteQueued(index) },
                    )
                }
                // 全部清空：右对齐，提示性按钮。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { store.clearQueued() }) {
                        Icon(
                            WandIcons.delete,
                            contentDescription = null,
                            tint = WandColors.danger,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.size(4.dp))
                        Text(
                            "全部清空",
                            fontSize = 12.sp,
                            color = WandColors.danger,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueItemRow(
    index: Int,
    text: String,
    onPromote: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(WandColors.surfaceSoft)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            "${index + 1}.",
            fontSize = 12.sp,
            color = WandColors.textMuted,
        )
        Text(
            text,
            fontSize = 13.sp,
            color = WandColors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // 立即发送
        IconButton(
            onClick = onPromote,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                WandIcons.send,
                contentDescription = "立即发送",
                tint = WandColors.brand,
                modifier = Modifier.size(16.dp),
            )
        }
        // 删除
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                WandIcons.close,
                contentDescription = "删除",
                tint = WandColors.textMuted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * WebSocket 断线提示条（对位 iOS ChatView.connectionBanner）。
 * 浮在聊天页顶部，连接恢复后自动消失。
 */
@Composable
fun ConnectionBanner(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            animationSpec = WandMotion.tweenFast(),
            initialOffsetY = { -it },
        ) + fadeIn(animationSpec = WandMotion.tweenFast()),
        exit = slideOutVertically(
            animationSpec = WandMotion.tweenFast(),
            targetOffsetY = { -it },
        ) + fadeOut(animationSpec = WandMotion.tweenFast()),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    stateDescription = "连接已断开，正在重连"
                }
                .background(WandColors.danger)
                .padding(vertical = 6.dp, horizontal = 12.dp),
        ) {
            Icon(
                WandIcons.wifiOff,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
            Text(
                "连接已断开，正在重连…",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// MARK: - 底部栏（权限卡 + 队列 + 输入框）

@Composable
private fun BottomBar(
    backdrop: GlassBackdrop?,
    store: ChatStore,
    drafts: SessionDraftStore,
    sessionId: String,
    voice: VoiceInputController,
    onMicDown: () -> Unit,
    uploading: Boolean,
    pendingAttachments: List<UploadedFile>,
    baseUrl: String,
    onRemoveAttachment: (UploadedFile) -> Unit,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    onSend: () -> Unit,
) {
    // 草稿订阅收敛在这里：打字只重组底部栏，不再波及消息列表。
    val draft = drafts[sessionId]
    val onDraftChange: (String) -> Unit = { drafts[sessionId] = it }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(bottom = 4.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
    Column(
        modifier = Modifier
            .widthIn(max = ChatReadableMaxWidth)
            .fillMaxWidth(),
    ) {
        // 待办进度条：当前 turn 有未完成 todos 时悬浮在输入栏上方（对齐 Web todo-progress）。
        // 会话不再 running（turn 已结束、idle/exited/archived）时直接收起：模型经常
        // 漏发最后一条全 completed 的 TodoWrite，否则进度条会卡在最后一项 in_progress
        // 直到下一次发消息才被刷新，看着像「永远执行中」（对齐 Web updateTodoProgress
        // 用 session.status 而不是 inFlight 判定，避免流式间隙闪烁）。
        val todos = remember(store.messages) { currentTodos(store.messages) }
        val todoSessionActive = store.status == "running"
        if (todos.isNotEmpty() && todoSessionActive) {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                TodoProgressBar(todos, backdrop)
            }
        }
        // 权限审批卡：底部滑入 + 淡入；退场期间用缓存内容避免闪空。
        val hasPermission = store.pendingEscalation != null || store.legacyPermissionPrompt != null
        var cachedEscalation by remember { mutableStateOf<EscalationRequest?>(null) }
        var cachedLegacy by remember { mutableStateOf<PermissionRequestInfo?>(null) }
        val focusManager = LocalFocusManager.current
        LaunchedEffect(store.pendingEscalation, store.legacyPermissionPrompt) {
            if (store.pendingEscalation != null) cachedEscalation = store.pendingEscalation
            if (store.legacyPermissionPrompt != null) cachedLegacy = store.legacyPermissionPrompt
        }
        LaunchedEffect(hasPermission) {
            if (hasPermission) focusManager.clearFocus()
        }
        AnimatedVisibility(
            visible = hasPermission,
            enter = fadeIn(WandMotion.tweenNormal()) +
                slideInVertically(WandMotion.tweenNormal()) { it / 2 },
            exit = fadeOut(WandMotion.tweenFast()) +
                slideOutVertically(WandMotion.tweenFast()) { it / 2 },
        ) {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                PermissionCard(
                    escalation = store.pendingEscalation ?: cachedEscalation,
                    legacy = store.legacyPermissionPrompt ?: cachedLegacy,
                    onResolve = { store.resolvePermission(it) },
                    backdrop = backdrop,
                )
            }
        }
        if (store.queuedMessages.isNotEmpty()) {
            QueueBar(store = store, backdrop = backdrop)
        }
        // 结构化会话没有「会话已结束 / 恢复会话」的概念：一个回合结束后只是回到 idle，
        // 直接继续输入即可（服务端 sendMessage 自动 --resume 续接）。不再渲染结束态横幅。
        // 按住说话实时转写气泡（按住期间悬浮在输入栏上方）。
        if (voice.pressed) {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                VoiceTranscriptBubble(backdrop, voice)
            }
        }
        InputBar(
            backdrop = backdrop,
            store = store,
            draft = draft,
            onDraftChange = onDraftChange,
            voice = voice,
            onMicDown = onMicDown,
            uploading = uploading,
            pendingAttachments = pendingAttachments,
            baseUrl = baseUrl,
            onRemoveAttachment = onRemoveAttachment,
            onPickPhoto = onPickPhoto,
            onPickFile = onPickFile,
            onExpandedChange = onExpandedChange,
            onSend = onSend,
        )
    }
    }
}

@Composable
private fun InputBar(
    backdrop: GlassBackdrop?,
    store: ChatStore,
    draft: String,
    onDraftChange: (String) -> Unit,
    voice: VoiceInputController,
    onMicDown: () -> Unit,
    uploading: Boolean,
    pendingAttachments: List<UploadedFile>,
    baseUrl: String,
    onRemoveAttachment: (UploadedFile) -> Unit,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    onSend: () -> Unit,
) {
    // 结构化会话不存在「已结束」终止态（停止只回到 idle，真失败也能再发消息触发
    // 服务端 --resume 续接），所以发送按钮只看草稿是否非空，不再被 sessionEnded 卡死。
    val canSend = draft.isNotBlank() || pendingAttachments.isNotEmpty()
    val focusRequester = remember { FocusRequester() }
    // 发送后保持输入框焦点：避免权限卡/todo bar 插入时 @FocusState 丢焦点、键盘收起，
    // 用户连续对话时不需要再点一次输入框（对位 iOS ChatView.sendDraft 末尾的 inputFocused = true）。
    var refocusAfterSend by remember { mutableStateOf(false) }
    // 停止任务二次确认弹窗开关：点停止按钮先弹确认，避免误触中断正在跑的任务。
    var showStopConfirm by remember { mutableStateOf(false) }
    // 文本框是否聚焦：驱动「胶囊 ↔ 卡片」两态切换（对齐 Codex App）。
    var isFocused by remember { mutableStateOf(false) }
    // 折叠为单行后若正文发生换行或溢出，立即保持展开，避免失焦后遮住草稿。
    var draftNeedsExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(refocusAfterSend, store.sessionEnded) {
        if (refocusAfterSend && !store.sessionEnded) {
            refocusAfterSend = false
            runCatching { focusRequester.requestFocus() }
        }
    }
    // 文本聚焦、按住语音或草稿无法在最小态完整显示时展开。
    val expanded = isFocused || voice.pressed || draftNeedsExpanded || pendingAttachments.isNotEmpty()
    LaunchedEffect(expanded) {
        onExpandedChange(expanded)
    }
    // 文本框只负责文本编辑；语音手势由输入框外侧的独立按钮承载。
    val inputContent: @Composable RowScope.() -> Unit = {
        Column(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 34.dp),
        ) {
            if (expanded && pendingAttachments.isNotEmpty()) {
                PendingAttachmentsPreview(
                    attachments = pendingAttachments,
                    baseUrl = baseUrl,
                    onRemove = onRemoveAttachment,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
                )
            }
            Box(contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        textStyle = TextStyle(
                            fontSize = 16.sp,
                            lineHeight = 21.sp,
                            color = WandColors.textPrimary,
                        ),
                        cursorBrush = SolidColor(WandColors.brand),
                        minLines = 1,
                        maxLines = if (expanded) 6 else 1,
                        onTextLayout = { layout ->
                            draftNeedsExpanded = draft.isNotEmpty() &&
                                (layout.lineCount > 1 || layout.hasVisualOverflow)
                        },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Send,
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (canSend) {
                                    onSend()
                                    refocusAfterSend = true
                                }
                            },
                        ),
                        decorationBox = { innerTextField ->
                            Box(
                                contentAlignment = Alignment.CenterStart,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 8.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                            ) {
                                if (draft.isEmpty()) {
                                    Text(
                                        "输入消息",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Normal,
                                        color = WandColors.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                innerTextField()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 34.dp, max = if (expanded) 132.dp else 34.dp)
                            .focusRequester(focusRequester)
                            .onFocusChanged { isFocused = it.isFocused },
                )
            }
        }
    }

    val plusMenu: @Composable () -> Unit = {
        ComposerActionsMenu(
            backdrop = backdrop,
            uploading = uploading,
            onPickPhoto = onPickPhoto,
            onPickFile = onPickFile,
        )
    }
    val trailing: @Composable () -> Unit = {
        TrailingSendStop(
            store = store,
            canSend = canSend,
            onStop = { showStopConfirm = true },
            voiceAction = {
                VoiceMicButton(
                    voice = voice,
                    voiceMode = false,
                    onToggleMode = { runCatching { focusRequester.requestFocus() } },
                    onMicDown = onMicDown,
                )
            },
            onSend = {
                onSend()
                refocusAfterSend = true
            },
        )
    }

    NativeComposerSurface(
        backdrop = backdrop,
        expanded = expanded,
        collapsedLeading = { plusMenu() },
        inputContent = { inputContent() },
        collapsedTrailing = { trailing() },
        expandedControls = { controlsCompact ->
            // 控制行：+ / 模式徽标 / 模型·思考徽标 / 停止·语音·发送。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ComposerActionSpacing),
                modifier = Modifier.weight(1f),
            ) {
                plusMenu()
                if (store.isStructured) {
                    ModeChip(store, compact = controlsCompact)
                    ModelThinkingChip(
                        store,
                        compact = controlsCompact,
                        modifier = if (controlsCompact) Modifier else Modifier.weight(1f),
                    )
                }
            }
            trailing()
        },
    )
    if (showStopConfirm) {
        WandDialog(
            title = "停止任务",
            onDismissRequest = { showStopConfirm = false },
            icon = WandIcons.stop,
            confirm = WandDialogAction(
                label = "停止",
                destructive = true,
                onClick = {
                    showStopConfirm = false
                    store.stopResponding()
                },
            ),
            dismiss = WandDialogAction("取消", { showStopConfirm = false }),
        ) {
            Text(
                "确定要停止当前正在运行的任务吗？",
                style = MaterialTheme.typography.bodyMedium,
                color = WandColors.textSecondary,
            )
        }
    }
}

/**
 * 发送 / 停止按钮组（对齐 iOS trailingButtons）：
 * - 运行中且无草稿 → 唯一按钮是黑底停止（对齐 Codex collapsed composer）；
 * - 有草稿 → 发送按钮（运行中时左侧追加一个红色停止，可一边排队一边停）。
 */
@Composable
private fun TrailingSendStop(
    store: ChatStore,
    canSend: Boolean,
    onStop: () -> Unit,
    voiceAction: @Composable () -> Unit,
    onSend: () -> Unit,
) {
    if (store.isResponding && !canSend) {
        voiceAction()
        FilledComposerAction(
            enabled = true,
            fillColor = WandColors.textPrimary,
            contentDescription = "停止任务",
            onClick = onStop,
        ) {
            Icon(
                WandIcons.stop,
                contentDescription = null,
                tint = WandColors.surface,
                modifier = Modifier.size(ComposerActionIconSize),
            )
        }
        return
    }
    if (store.isResponding) {
        FilledComposerAction(
            enabled = true,
            fillColor = WandColors.dangerSoft,
            contentDescription = "停止任务",
            onClick = onStop,
        ) {
            Icon(
                WandIcons.stop,
                contentDescription = null,
                tint = WandColors.danger,
                modifier = Modifier.size(ComposerActionIconSize),
            )
        }
    }
    voiceAction()
    FilledComposerAction(
        enabled = canSend,
        fillColor = if (canSend) WandColors.brand else WandColors.textSecondary.copy(alpha = 0.16f),
        contentDescription = if (canSend) "发送消息" else "当前没有可发送内容",
        onClick = onSend,
    ) {
        Icon(
            WandIcons.arrowUp,
            contentDescription = null,
            tint = if (canSend) Color.White else WandColors.textMuted.copy(alpha = 0.45f),
            modifier = Modifier.size(ComposerActionIconSize),
        )
    }
}

/** 控制行通用胶囊徽标：图标 + 文字 + 弱色底 + 下拉箭头。 */
@Composable
private fun ControlChip(
    icon: ImageVector,
    text: String,
    tint: Color,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showText: Boolean = true,
    onClick: () -> Unit,
) {
    val visualModifier = if (showText) {
        Modifier
            .height(ComposerActionVisualSize)
            .padding(horizontal = 10.dp)
    } else {
        Modifier.size(ComposerActionVisualSize)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (showText) Arrangement.spacedBy(4.dp) else Arrangement.Center,
            modifier = visualModifier,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(ComposerActionIconSize),
            )
            if (showText) {
                Text(
                    text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 取可用宽度但允许收缩：空间紧张时省略号缩列，而非撑出固定 130dp 把按钮挤掉。
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    WandIcons.expand,
                    contentDescription = null,
                    tint = tint.copy(alpha = 0.7f),
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

private data class ComposerChoiceSection(
    val title: String? = null,
    val options: List<Pair<String, String>>,
    val selected: String? = null,
    val searchable: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposerChoiceSheet(
    title: String,
    options: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ComposerChoiceSheet(
        title = title,
        sections = listOf(ComposerChoiceSection(options = options, selected = selected)),
        onSelect = { _, id -> onSelect(id) },
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposerChoiceSheet(
    title: String,
    sections: List<ComposerChoiceSection>,
    onSelect: (sectionIndex: Int, id: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val showSearch = sections.any { it.searchable }
    WandBottomSheet(onDismissRequest = onDismiss) {
        NoOverscroll {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
            ) {
                Text(
                    title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WandColors.textPrimary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
                if (showSearch) {
                    WandTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "搜索模型",
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                WandIcons.search,
                                contentDescription = null,
                                tint = WandColors.textMuted,
                            )
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    )
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                sections.forEachIndexed { sectionIndex, section ->
                    val searching = query.isNotBlank()
                    if (searching && !section.searchable) return@forEachIndexed
                    val visibleOptions = if (section.searchable) {
                        section.options.filter { matchesModelSearch(query, it.first, it.second) }
                    } else {
                        section.options
                    }
                    if (section.title != null && (visibleOptions.isNotEmpty() || section.searchable)) {
                        Text(
                            section.title,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WandColors.textMuted,
                            modifier = Modifier.padding(
                                start = 8.dp,
                                end = 8.dp,
                                top = if (sectionIndex == 0) 0.dp else 10.dp,
                                bottom = 4.dp,
                            ),
                        )
                    }
                    if (visibleOptions.isEmpty() && section.searchable) {
                        Text(
                            "没有匹配的模型",
                            fontSize = 14.sp,
                            color = WandColors.textMuted,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                        )
                    }
                    visibleOptions.forEach { (id, optionLabel) ->
                        val isSelected = section.selected == id
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isSelected) WandColors.brandSoft else Color.Transparent,
                                )
                                .selectable(
                                    selected = isSelected,
                                    role = Role.RadioButton,
                                ) { onSelect(sectionIndex, id) }
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                        ) {
                            Text(
                                optionLabel,
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) WandColors.brand else WandColors.textPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            if (isSelected) {
                                Icon(
                                    WandIcons.check,
                                    contentDescription = null,
                                    tint = WandColors.brand,
                                    modifier = Modifier.size(18.dp),
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

/** 执行模式徽标 + 下拉菜单（中途切换 managed/full-access/...）。codex 锁 full-access。 */
@Composable
private fun ModeChip(store: ChatStore, compact: Boolean = false) {
    val provider = store.snapshot?.provider
    val isCodex = provider == "codex"
    val supportedModeIds = supportedSessionModeIds(provider)
    var open by remember { mutableStateOf(false) }
    // 高权限模式（托管 / 全权限）用橙色提示，其余用次要色。
    val tint = if (store.mode == "full-access" || store.mode == "managed")
        WandColors.warning else WandColors.textSecondary
    Box {
        ControlChip(
            icon = WandIcons.permission,
            text = sessionModeLabel(store.mode),
            tint = tint,
            contentDescription = buildString {
                append("执行模式：${sessionModeLabel(store.mode)}")
                if (isCodex) append("，Codex 会话固定")
            },
            enabled = !isCodex,
            showText = !compact,
        ) { open = true }
        if (open) {
            ComposerChoiceSheet(
                title = "执行模式",
                options = SESSION_MODE_OPTIONS
                    .filter { it.id in supportedModeIds }
                    .map { it.id to it.label },
                selected = store.mode,
                onSelect = { id ->
                    store.chooseMode(id)
                    open = false
                },
                onDismiss = { open = false },
            )
        }
    }
}

/** 模型 · 思考深度合并徽标 + 下拉菜单（对齐 iOS modelThinkingChip）。 */
@Composable
private fun ModelThinkingChip(
    store: ChatStore,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    val tint = when (store.thinkingEffort) {
        "standard" -> WandColors.success
        "deep" -> WandColors.warning
        "max" -> WandColors.danger
        else -> WandColors.brand
    }
    Box(modifier = modifier) {
        ControlChip(
            icon = WandIcons.tune,
            text = modelThinkingText(store),
            tint = tint,
            contentDescription = "模型与思考：${modelThinkingText(store)}",
            showText = !compact,
            modifier = if (compact) Modifier.widthIn(max = 112.dp) else Modifier,
        ) { open = true }
        if (open) {
            ComposerChoiceSheet(
                title = "模型与思考",
                sections = listOf(
                    ComposerChoiceSection(
                        title = "模型",
                        options = buildList {
                            add("default" to "默认 · ${modelDisplayLabel(store, null)}")
                            store.availableModels.filter { it.id != "default" }.forEach { model ->
                                add(model.id to model.label)
                            }
                        },
                        selected = store.selectedModel?.takeUnless { it == "default" } ?: "default",
                        searchable = true,
                    ),
                    ComposerChoiceSection(
                        title = "思考深度",
                        options = thinkingLevels(store).map { it.id to it.menuLabel },
                        selected = store.thinkingEffort,
                    ),
                ),
                onSelect = { sectionIndex, id ->
                    if (sectionIndex == 0) {
                        store.setModel(id.takeUnless { it == "default" })
                    } else {
                        store.chooseThinkingEffort(id)
                    }
                    open = false
                },
                onDismiss = { open = false },
            )
        }
    }
}

/**
 * 输入栏左侧「更多操作」按钮（对齐 iOS composerActionsMenu）：
 * 圆形 + 号，点开菜单「从相册选择 / 从文件选择」两项；上传中显示转圈。
 */
@Composable
internal fun ComposerActionsMenu(
    backdrop: GlassBackdrop?,
    uploading: Boolean,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(ComposerActionTouchSize)
                .clip(CircleShape)
                .semantics {
                    contentDescription = if (uploading) "正在上传附件" else "添加附件"
                    role = Role.Button
                }
                .clickable(enabled = !uploading, role = Role.Button) { open = true },
        ) {
            if (uploading) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(ComposerActionVisualSize),
                ) {
                    CircularProgressIndicator(
                        color = WandColors.textSecondary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(ComposerActionIconSize),
                    )
                }
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(ComposerActionVisualSize),
                ) {
                    Icon(
                        WandIcons.add,
                        contentDescription = null,
                        tint = WandColors.textSecondary,
                        modifier = Modifier.size(ComposerActionIconSize),
                    )
                }
            }
        }
        if (open) {
            ComposerChoiceSheet(
                title = "添加附件",
                options = listOf(
                    "photo" to "从相册选择",
                    "file" to "从文件选择",
                ),
                selected = null,
                onSelect = { id ->
                    open = false
                    if (id == "photo") onPickPhoto() else onPickFile()
                },
                onDismiss = { open = false },
            )
        }
    }
}

// MARK: - 按住说话（端侧语音识别）

/**
 * 轻点 vs 按住的分界：按住超过该时长进入录音，否则按轻点处理。
 * 0.18s 仍足以区分轻点/长按，但比 0.3s 让识别框出现快 ~40%，减少「按下去没反应」的感知延迟
 * （对位 iOS ChatView.voiceHoldThreshold = 0.18）。
 */
private const val VOICE_HOLD_THRESHOLD_MS = 180L

/**
 * 轻点 / 按住二分手势：
 * - 按住超过 [VOICE_HOLD_THRESHOLD_MS] → onHoldStart()（开始录音），
 *   之后移动驱动「上滑取消」，松手 endPress() 提交；
 * - 阈值内松手 → onTap()。
 * 录音的触感反馈在 onHoldStart（即 onMicDown）里触发，正好对应「真正开始聆听」。
 */
private suspend fun PointerInputScope.voiceTapOrHoldGesture(
    voice: VoiceInputController,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
) {
    val cancelThresholdPx = 60.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        var recording = false
        var elapsed = 0L
        while (true) {
            val event = if (recording) {
                awaitPointerEvent()
            } else {
                withTimeoutOrNull(VOICE_HOLD_THRESHOLD_MS - elapsed) { awaitPointerEvent() }
            }
            if (event == null) {
                // 按满阈值仍未松手 → 进入按住录音（原有交互）。
                recording = true
                onHoldStart()
                continue
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            elapsed = change.uptimeMillis - down.uptimeMillis
            if (!change.pressed) {
                change.consume()
                if (!recording) onTap()
                break
            }
            change.consume()
            if (recording) {
                voice.updateCancel(down.position.y - change.position.y > cancelThresholdPx)
            }
        }
        if (recording) voice.endPress()
    }
}

/** 输入框外侧的独立语音按钮：轻点聚焦输入，长按录音。 */
@Composable
internal fun VoiceMicButton(
    voice: VoiceInputController,
    voiceMode: Boolean,
    onToggleMode: () -> Unit,
    onMicDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnToggle by rememberUpdatedState(onToggleMode)
    val currentOnMicDown by rememberUpdatedState(onMicDown)
    val iconTint = when {
        voice.pressed && voice.canceling -> WandColors.danger
        voice.pressed -> WandColors.brand
        else -> WandColors.textSecondary
    }
    val scale by animateFloatAsState(
        if (voice.pressed) 1.1f else 1f,
        WandMotion.tweenFast(),
        label = "micScale",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(ComposerActionTouchSize)
            .clip(CircleShape)
            .semantics {
                role = Role.Button
                contentDescription = if (voiceMode) "切回键盘输入" else "语音输入"
                stateDescription = if (voice.pressed) "正在录音" else if (voiceMode) "语音模式" else "键盘模式"
                onClick(label = if (voiceMode) "切回键盘" else "切换到语音模式") {
                    currentOnToggle()
                    true
                }
            }
            .pointerInput(voice) {
                voiceTapOrHoldGesture(
                    voice = voice,
                    onTap = { currentOnToggle() },
                    onHoldStart = { currentOnMicDown() },
                )
            },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(ComposerActionVisualSize)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            Icon(
                if (voiceMode && !voice.pressed) WandIcons.keyboard else WandIcons.mic,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(ComposerActionIconSize),
            )
        }
    }
}

/** 按住期间的实时转写气泡：覆盖式文本 + 引擎标签 + 上滑取消提示。 */
@Composable
internal fun VoiceTranscriptBubble(backdrop: GlassBackdrop?, voice: VoiceInputController) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(backdrop, RoundedCornerShape(12.dp), WandGlass.regular)
            .then(
                if (voice.canceling) {
                    Modifier.border(
                        1.dp,
                        WandColors.danger.copy(alpha = 0.55f),
                        RoundedCornerShape(12.dp),
                    )
                } else Modifier
            )
            .padding(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                if (voice.canceling) WandIcons.close else WandIcons.mic,
                contentDescription = null,
                tint = if (voice.canceling) WandColors.danger else WandColors.brand,
                modifier = Modifier.size(16.dp),
            )
            Text(
                when {
                    voice.canceling -> "松开手指，取消输入"
                    voice.transcript.isEmpty() -> "正在聆听…"
                    else -> voice.transcript
                },
                fontSize = 14.sp,
                color = when {
                    voice.canceling -> WandColors.danger
                    voice.transcript.isEmpty() -> WandColors.textMuted
                    else -> WandColors.textPrimary
                },
            )
        }
        if (!voice.canceling) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    voice.engineLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = WandColors.brand,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(WandColors.brand.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
                Text(
                    "松开填入输入框 · 上滑取消",
                    fontSize = 11.sp,
                    color = WandColors.textMuted,
                )
            }
        }
    }
}

/** 端侧语音模型下载对话框：说明 → 下载进度 → 就绪/失败重试。 */
@Composable
private fun SttModelDownloadDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state = SttModelManager.state
    val model = remember { SttModelManager.selectedModel(context) }
    // 下载完成立刻预热模型，让「下载完→按住即用」无加载等待。
    LaunchedEffect(state) {
        if (state is SttModelManager.State.Ready) SherpaSpeechEngine.warmUp(context)
    }
    WandDialog(
        title = "下载本地语音模型",
        onDismissRequest = { if (state !is SttModelManager.State.Downloading) onDismiss() },
        icon = WandIcons.update,
        confirm = when (state) {
            is SttModelManager.State.Downloading -> WandDialogAction(
                label = "取消下载",
                destructive = true,
                onClick = { SttModelManager.cancelDownload() },
            )
            is SttModelManager.State.Ready -> WandDialogAction("知道了", onDismiss)
            else -> WandDialogAction(
                if (state is SttModelManager.State.Failed) "重试" else "下载",
                { SttModelManager.startDownload(context) },
            )
        },
        dismiss = if (state is SttModelManager.State.Idle || state is SttModelManager.State.Failed) {
            WandDialogAction("暂不", onDismiss)
        } else null,
    ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    is SttModelManager.State.Downloading -> {
                        Text(
                            "正在下载语音识别模型…",
                            fontSize = 13.sp,
                            color = WandColors.textSecondary,
                        )
                        LinearProgressIndicator(
                            progress = { state.percent / 100f },
                            color = WandColors.brand,
                            trackColor = WandColors.surfaceSoft,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${state.percent}%（${formatMb(state.downloadedBytes)} / ${formatMb(state.totalBytes)}）",
                            fontSize = 12.sp,
                            color = WandColors.textMuted,
                        )
                    }
                    is SttModelManager.State.Ready -> Text(
                        "模型已就绪，按住麦克风即可语音输入，识别完全在本机离线运行。",
                        fontSize = 13.sp,
                        color = WandColors.textSecondary,
                    )
                    is SttModelManager.State.Failed -> Text(
                        "${state.message}\n可重试，会自动切换镜像源。",
                        fontSize = 13.sp,
                        color = WandColors.danger,
                    )
                    else -> Text(
                        "此设备没有可用的系统语音识别服务。下载开源端侧模型" +
                            "（${model.label}，${model.sizeLabel}）后，" +
                            "语音识别完全在本机离线运行：不耗流量、语音内容不出设备。" +
                            "可在设置页切换识别模型。",
                        fontSize = 13.sp,
                        color = WandColors.textSecondary,
                    )
                }
            }
    }
}

private fun formatMb(bytes: Long): String =
    String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
