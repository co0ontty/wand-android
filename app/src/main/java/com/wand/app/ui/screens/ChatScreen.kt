package com.wand.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.wand.app.ui.components.SessionCompletionViewEffect
import kotlinx.coroutines.flow.collect
import com.wand.app.SessionWatcher
import com.wand.app.data.ContentBlock
import com.wand.app.data.matchesModelSearch
import com.wand.app.data.ConversationTurn
import com.wand.app.data.EscalationRequest
import com.wand.app.data.PermissionRequestInfo
import com.wand.app.data.SiliconEmployee
import com.wand.app.data.TurnUsage
import com.wand.app.data.UploadedFile
import com.wand.app.data.WandApi
import com.wand.app.data.SESSION_MODE_OPTIONS
import com.wand.app.data.sessionModeLabel
import com.wand.app.data.supportedSessionModeIds
import com.wand.app.speech.SherpaSpeechEngine
import com.wand.app.speech.SpeechNativeLibrary
import com.wand.app.speech.SttModelManager
import com.wand.app.speech.VoiceInputController
import com.wand.app.ui.ChatStore
import com.wand.app.ui.ChatComposer
import com.wand.app.ui.SendPhase
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.wand.app.ui.components.WandMorphingIcon

import androidx.activity.compose.BackHandler
import com.wand.app.ui.SendActionVisual
import com.wand.app.ui.sendActionVisual
import com.wand.app.ui.components.WandInPlaceSwap
import com.wand.app.ui.components.WandInlinePanelAction
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandListItem
import com.wand.app.ui.components.WandListItemIconSlot
import com.wand.app.ui.components.WandSnackbarHost
import com.wand.app.ui.components.showWandNotice
import com.wand.app.ui.components.WandDialog
import com.wand.app.ui.components.WandBottomSheet
import com.wand.app.ui.components.WandTextField
import com.wand.app.ui.components.WandDialogAction
import com.wand.app.ui.components.WandProviderMark
import com.wand.app.ui.components.WandStatusIconSlot
import com.wand.app.data.AGENT_TOOL_OPTIONS
import com.wand.app.data.agentToolOption
import com.wand.app.ui.components.EmployeeAvatar
import com.wand.app.ui.components.clickableWithoutRipple
import com.wand.app.ui.theme.AmbientBackground
import com.wand.app.ui.theme.GlassBackdrop
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandGlass
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.reduceMotionEnabled
import com.wand.app.ui.theme.isWandDarkTheme
import com.wand.app.ui.theme.glassBackdropSource
import com.wand.app.ui.components.wandCardSurface
import com.wand.app.ui.theme.glassSurface
import com.wand.app.ui.theme.rememberGlassBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import com.wand.app.data.WandApiException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private enum class ChatScrollMode {
    StickToBottom,
    Manual,
}

/** LazyColumn 中正向手指位移表示内容被拉向更早的消息。 */
internal fun shouldPauseBottomFollow(userScrollDeltaY: Float): Boolean = userScrollDeltaY > 0f

/** 打开会话时先铺满两屏；之后再靠用户上拉继续往前翻。 */
internal const val EARLIER_TARGET_SCREENS = 2f

/** 一轮「需要上一页」里最多补几页：估算偏差不至于把整段历史一次性翻完。 */
private const val MAX_EARLIER_PAGES_PER_ROUND = 8

/** 网络失败后的静默重试间隔；期间只留加载圈，不弹提示。 */
private const val EARLIER_LOAD_RETRY_MS = 1500L

/** 上拉到离列表绝对顶部不超过这么多像素时就补上一页，避免撞到硬边才开始等。 */
private const val EARLIER_TOP_SLOP_PX = 48

/**
 * 是否需要静默翻上一页（没有按钮、没有文案、没有点击）：
 * - 用户已经上拉到列表绝对顶部 —— 继续往前翻一屏；
 * - 已加载内容按已测量行高估算不到目标屏数 —— 打开会话时补满两屏，无需用户操作。
 * 正在翻一条很长的回复中间（第 0 项仍可见、但滚动偏移很大）不算到顶，不会误触发。
 */
internal fun shouldLoadEarlierPage(
    canLoadEarlier: Boolean,
    userPulledToTop: Boolean,
    loadedRowCount: Int,
    measuredRowHeightsPx: List<Int>,
    viewportHeightPx: Int,
    targetScreens: Float = EARLIER_TARGET_SCREENS,
): Boolean {
    if (!canLoadEarlier || viewportHeightPx <= 0 || loadedRowCount <= 0) return false
    if (userPulledToTop) return true
    // 首帧还没量到任何行时先不动，等布局稳定再决定补不补页。
    if (measuredRowHeightsPx.isEmpty()) return false
    val estimatedContentHeightPx = measuredRowHeightsPx.average() * loadedRowCount
    return estimatedContentHeightPx < viewportHeightPx * targetScreens
}

/** 顶部静默翻页的观察值：需要上一页、上一页已经结束（成败都算）、当前是否正在加载。 */
private data class EarlierLoadProbe(
    val needed: Boolean,
    val attempts: Int,
    val loading: Boolean,
)

internal data class EarlierLoadAnchor(
    val itemKey: Any?,
    val scrollOffset: Int,
    val turnOffset: Int,
    val blockOffset: Int,
    /** prepend 前这一项的高度；同一项被从头部撑高时用来补回阅读位置。 */
    val itemSize: Int = 0,
)

internal fun earlierLoadAdvanced(anchor: EarlierLoadAnchor, turnOffset: Int, blockOffset: Int): Boolean =
    turnOffset < anchor.turnOffset || (turnOffset == anchor.turnOffset && blockOffset < anchor.blockOffset)

/** 同一列表项从头部被 prepend 撑高时，滚动偏移要加上增高量，阅读位置才不会往下跳。 */
internal fun earlierAnchorScrollOffset(anchor: EarlierLoadAnchor, laidOutSize: Int?): Int {
    val grown = laidOutSize?.let { (it - anchor.itemSize).coerceAtLeast(0) } ?: 0
    return anchor.scrollOffset + grown
}

/** 状态坞只承接流式状态 / 子 Agent；完成时间和用量留在各轮消息里，避免右下角再显一遍。 */
internal fun shouldShowStructuredActivityDock(
    isStructured: Boolean,
    isResponding: Boolean,
    hasSubagentActivities: Boolean,
): Boolean = isStructured && (isResponding || hasSubagentActivities)

internal fun shouldRefreshQuickCommitStatus(isLoading: Boolean, isResponding: Boolean): Boolean {
    return !isLoading && !isResponding
}

/** 视口/键盘逐帧变化时，贴底先等这一段再落位，不每次都立刻拽一次列表。 */
private const val STICK_TO_BOTTOM_SETTLE_MS = 80L

/**
 * 落位之后的补落位节奏：变高卡片（markdown、工具卡）逐帧测得真实高度时把底边重新贴住。
 * 首屏只补一次，避免和打开会话的入场布局抢帧。
 */
internal fun chatStickToBottomRetryDelaysMs(listSettled: Boolean): List<Long> =
    if (listSettled) listOf(180L, 420L) else emptyList()

/**
 * 程序化落位只在用户没有自己滚（含抬手后的惯性段）时执行：
 * 用户正在滚的时候抢来的那一下就是「不规律跳动」。
 * scrollToItem/scrollBy 不会置 isScrollInProgress，所以这个判据只反映用户手势。
 */
internal fun shouldApplyProgrammaticStick(isUserScrolling: Boolean, followPaused: Boolean): Boolean =
    !isUserScrolling && !followPaused

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
    isHapticEnabled: () -> Boolean,
    trafficTimelineMode: () -> String = { "wifi" },
    drafts: SessionDraftStore,
    showBack: Boolean = true,
    onBack: () -> Unit,
) {
    val store = remember(sessionId, api) { ChatStore(sessionId, api) }
    val composerScope = rememberCoroutineScope()
    val resources = remember(sessionId, api) { com.wand.app.ui.PiResourcesController(sessionId, api, composerScope) }
    DisposableEffect(resources) { onDispose { resources.dismiss() } }
    val composer = remember(sessionId, api, drafts, store) {
        ChatComposer(
            sessionId = sessionId,
            drafts = drafts,
            parentScope = composerScope,
            ready = { !store.loading && !store.providerSwitching && !store.directoryChanging && store.snapshot != null && resources.phase != "saving" },
            send = store::submitInput,
            notice = { store.toast = it },
        )
    }
    DisposableEffect(composer) {
        onDispose { composer.shutdown() }
    }
    val quickCommit = remember(sessionId, api) {
        QuickCommitStore(sessionId, api) { msg -> store.toast = msg }
    }
    DisposableEffect(store) {
        store.start()
        onDispose { store.shutdown() }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    SessionCompletionViewEffect(api, sessionId,
        ready = !store.loading && store.snapshot != null && store.loadError == null,
        completionRevision = store.snapshot?.completionRevision,
        viewedCompletionRevision = store.snapshot?.viewedCompletionRevision,
    )
    var liveEmployee by remember(sessionId) { mutableStateOf<SiliconEmployee?>(null) }
    val employeeId = store.snapshot?.employeeId
    LaunchedEffect(api, employeeId, lifecycleOwner) {
        if (employeeId == null) { liveEmployee = null; return@LaunchedEffect }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            suspend fun refreshEmployee() {
                try {
                    liveEmployee = api.siliconEmployee(employeeId).takeUnless { it.archived }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: WandApiException) {
                    if (e.status == 404) liveEmployee = null
                } catch (_: Exception) {
                    // Keep the current identity/avatar during transient refresh failures.
                }
            }
            refreshEmployee()
            SessionWatcher.employeeDefinitionChanges.collect { changedId ->
                if (changedId == employeeId) refreshEmployee()
            }
        }
    }
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
    // 向下展开的工具时间线长在摘要行下面：只有面板底边越过视口底部时才补最小滚动，
    // 放得下就一点不动（摘要行与上一屏内容都不位移），收起时按同一份账对称退回。
    // 同一个 item 只保留最后一次补偿：连点开合时旧循环立即让位。
    val panelRevealJobs = remember { mutableMapOf<String, Job>() }
    val panelRevealApplied = remember { mutableMapOf<String, Int>() }
    val revealActivityPanel: (String) -> Unit = remember(listState, scrollScope) {
        { key ->
            if (key.isNotBlank()) {
                panelRevealJobs.remove(key)?.cancel()
                panelRevealJobs[key] = scrollScope.launch {
                    listState.revealActivityPanelTail(
                        key,
                        panelRevealApplied,
                        // 手势/惯性还在走就不补这一段：两个写者同帧抢落点就是跳动。
                        isCancelled = { listState.isScrollInProgress },
                    )
                }
            }
        }
    }
    val haptic = LocalHapticFeedback.current

    val context = LocalContext.current
    val voiceInput = rememberVoiceInputHandle(
        isHapticEnabled = isHapticEnabled,
        onToast = { store.toast = it },
        onCommit = composer::appendVoice,
        sessionKey = composer,
        onCommitForPress = composer::voiceCommitForCurrentDraft,
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
    val toolResultsById = remember(store.messages) { conversationToolResults(store.messages) }
    val activeCommandIds = remember(store.messages, lastUserTurnIndex, toolResultsById, store.isResponding) {
        if (!store.isResponding) emptySet() else {
            latestPendingCommandToolId(store.messages, lastUserTurnIndex, toolResultsById)
                ?.let(::setOf) ?: emptySet()
        }
    }
    val subagentActivities = remember(store.messages, store.isResponding) {
        subagentDockActivities(collectSubagentActivities(store.messages, store.isResponding))
    }
    val showActivityDock = shouldShowStructuredActivityDock(
        isStructured = store.isStructured,
        isResponding = store.isResponding,
        hasSubagentActivities = subagentActivities.isNotEmpty(),
    )
    // 顶栏用量：避免 ChatScreen 每次重组都对全部消息线性扫描。
    val lastAssistantUsage = remember(store.messages) {
        store.messages.lastOrNull { it.role == "assistant" }?.usage
    }
    var activityDockExpanded by rememberSaveable(sessionId) { mutableStateOf(false) }
    LaunchedEffect(showActivityDock) {
        if (!showActivityDock) activityDockExpanded = false
    }

    // Picker/upload adapters capture this composer; a late result never targets a new session.
    val attachmentPickers = rememberAttachmentPickerActions { uris ->
        if (uris.isNotEmpty()) composer.upload { remainingSlots ->
            if (uris.size > remainingSlots) {
                store.toast = "最多添加 5 个附件，本次仅上传前 $remainingSlots 个"
            }
            uploadComposerAttachments(context, api, sessionId, uris, remainingSlots)
        }
    }

    // 更早内容不再占一个列表项：没有按钮、没有文案，加载圈浮在顶部。
    val headerOffset = 0
    // bottomIndex 是最后的 chat-bottom 哨兵下标（即它之前的项数）。
    val bottomIndex = headerOffset + displayItems.size
    var earlierLoadAnchor by remember(sessionId) { mutableStateOf<EarlierLoadAnchor?>(null) }
    val captureEarlierLoadAnchor: () -> Unit = {
        // 只在用户手动浏览时保阅读位置；贴底跟随补页时交给贴底逻辑重新贴底，不抢滚动。
        if (scrollMode == ChatScrollMode.Manual) {
            val firstContent = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                it.key != "chat-bottom"
            }
            earlierLoadAnchor = EarlierLoadAnchor(
                itemKey = firstContent?.key,
                scrollOffset = firstContent?.let {
                    (listState.layoutInfo.viewportStartOffset - it.offset).coerceAtLeast(0)
                } ?: 0,
                turnOffset = store.loadedOffset,
                blockOffset = store.leadingBlockOffset,
                itemSize = firstContent?.size ?: 0,
            )
        }
    }
    // 锚点未落地前不翻下一页。同一条 turn 从头部被撑高时，落地时按增高量补回偏移。
    val earlierPageNeeded: () -> Boolean = {
        val pulledToTop = scrollMode == ChatScrollMode.Manual &&
            listState.firstVisibleItemIndex == 0 &&
            listState.firstVisibleItemScrollOffset <= EARLIER_TOP_SLOP_PX
        earlierLoadAnchor == null && shouldLoadEarlierPage(
            canLoadEarlier = store.canLoadEarlier,
            userPulledToTop = pulledToTop,
            loadedRowCount = displayItems.size,
            measuredRowHeightsPx = listState.layoutInfo.visibleItemsInfo
                .filter { it.key != "chat-bottom" }
                .map { it.size },
            viewportHeightPx = listViewportHeightPx,
        )
    }
    val latestEarlierPageNeeded = rememberUpdatedState(earlierPageNeeded)
    // 锚点在 prepend 后落回原位：消息 key 稳定，不能让加载位把阅读位置锁在最上方。
    LaunchedEffect(store.loadedOffset, store.leadingBlockOffset, store.loadingEarlier, earlierLoadAnchor) {
        val anchor = earlierLoadAnchor ?: return@LaunchedEffect
        if (earlierLoadAdvanced(anchor, store.loadedOffset, store.leadingBlockOffset)) {
            withFrameNanos { }
            val anchoredIndex = displayItems.indexOfFirst { item ->
                messageItemKey(
                    item = item,
                    loadedOffset = store.loadedOffset,
                    anchorExplorationAtEnd = lastUserTurnIndex >= 0 &&
                        messageItemTurnIndex(item) < lastUserTurnIndex,
                ) == anchor.itemKey
            }
            val laidOutSize = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == anchor.itemKey }
                ?.size
            listState.scrollToItem(
                if (anchoredIndex >= 0) headerOffset + anchoredIndex else headerOffset,
                earlierAnchorScrollOffset(anchor, laidOutSize),
            )
            earlierLoadAnchor = null
        } else if (!store.loadingEarlier) {
            // 空页/请求失败时不要让旧锚点误用于后续 WS 推送。
            earlierLoadAnchor = null
        }
    }
    // 静默翻页：需要上一页就自动补，不提示也不等点击；
    // 没取到（网络失败 / 状态已变）不弹提示，只把 loading 留在原位并稍后重试。
    LaunchedEffect(listState, sessionId) {
        var pagesInRound = 0
        snapshotFlow {
            EarlierLoadProbe(
                needed = latestEarlierPageNeeded.value(),
                attempts = store.earlierPageAttempts,
                loading = store.loadingEarlier,
            )
        }
            .distinctUntilChanged()
            .collect { probe ->
                if (!probe.needed) {
                    pagesInRound = 0
                    return@collect
                }
                if (probe.loading || store.loadingEarlier) return@collect
                if (pagesInRound >= MAX_EARLIER_PAGES_PER_ROUND) return@collect
                if (!latestEarlierPageNeeded.value()) return@collect
                captureEarlierLoadAnchor()
                if (store.loadEarlierPage()) pagesInRound += 1 else delay(EARLIER_LOAD_RETRY_MS)
            }
    }

    // 用户一开始向上浏览旧内容就立即暂停贴底跟随。流式消息刷新很频繁，若等拖动
    // 累计超过某个阈值才暂停，阈值内的新 token 会先把列表重新拽回底部。
    // 抬手后的惯性段同样要暂停：那时已经没有 UserInput 回调，只有 Fling，
    // 不暂停的话下一条补落位会在用户滑到一半时把列表拽回底部。
    // Manual 模式不会因用户自己滚回底部而退出；只有“回到底部”按钮或主动发送才恢复。
    val followPauseConnection = remember(focusManager, listState, store) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    if (available.y != 0f) focusManager.clearFocus()
                    if (shouldPauseBottomFollow(available.y)) scrollMode = ChatScrollMode.Manual
                }
                return Offset.Zero
            }

            // 只有真实手势 fling 才进 onPreFling，程序化 scrollToItem/scrollBy 不会，
            // 所以这里不会把补落位自己判成用户接管。
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (shouldPauseBottomFollow(available.y)) scrollMode = ChatScrollMode.Manual
                return Velocity.Zero
            }
        }
    }

    // 书签轨的居中条目每帧都在变，但只有换成另一个条目才需要重组。组合期直接读
    // layoutInfo 会让整个 ChatScreen（含全部可见卡片与输入栏）跟着滚动逐帧重组，
    // 卡片逐帧重测高——掉帧和「跳动」都从这里来。派生成只按结果变化的状态。
    val currentScrubberItem by remember(listState, scrubberTargets, displayItems) {
        derivedStateOf {
            val info = listState.layoutInfo
            conversationScrubberIndexForDisplayItem(
                scrubberTargets,
                info.visibleItemsInfo
                    .filter { it.index >= headerOffset && it.index < headerOffset + displayItems.size }
                    .minByOrNull { item ->
                        kotlin.math.abs(
                            item.offset + item.size / 2 -
                                (info.viewportStartOffset + info.viewportEndOffset) / 2,
                        )
                    }
                    ?.index
                    ?.minus(headerOffset)
                    ?.coerceIn(0, (displayItems.size - 1).coerceAtLeast(0))
                    ?: 0,
            )
        }
    }
    // 「回到底部」按钮的可见性只关心能不能再往前滚，不关心每帧滚了多少：
    // 同样不能在组合期直接读 layoutInfo。
    val canScrollForwardToTail by remember(listState) {
        derivedStateOf { listState.canScrollForward }
    }
    // 贴底的唯一程序化写者：新请求取消上一条链。多个写者各拽各的落点，
    // 就是「不规律跳动」里最容易被看到的那一段。
    // firstWaitMs 用于尺寸/视口这类逐帧变化的触发源——只在变化停下来之后落一次；
    // 内容变化用 0，流式输出才跟得上。animate 只作用在首落位，补落位保持瞬时。
    val latestScrollMode = rememberUpdatedState(scrollMode)
    val latestBottomIndex = rememberUpdatedState(bottomIndex)
    var stickJob by remember(sessionId) { mutableStateOf<Job?>(null) }
    val stickToBottom: (firstWaitMs: Long, extraWaitsMs: List<Long>, animate: Boolean) -> Unit =
        { firstWaitMs, extraWaitsMs, animate ->
            stickJob?.cancel()
            stickJob = scrollScope.launch {
                var step = 0
                for (waitMs in listOf(firstWaitMs) + extraWaitsMs) {
                    if (waitMs > 0) delay(waitMs)
                    if (!shouldApplyProgrammaticStick(
                            isUserScrolling = listState.isScrollInProgress,
                            followPaused = latestScrollMode.value == ChatScrollMode.Manual,
                        )
                    ) break
                    val target = maxOf(
                        latestBottomIndex.value,
                        listState.layoutInfo.totalItemsCount - 1,
                        0,
                    )
                    if (step == 0 && animate) {
                        listState.animateScrollToItem(target)
                    } else {
                        listState.scrollToItem(target)
                    }
                    step += 1
                }
            }
        }
    // 输入栏聚焦后会从单行胶囊变成双行卡片，IME 弹出/收起也会改变列表视口。
    // 贴底模式必须把这些尺寸变化视作一次新的定位请求，否则最后一行会落到
    // 变高的输入栏之后；手动浏览模式则保持用户当前阅读位置，不主动跳转。
    //
    // 内容变化和尺寸变化分开：流式期间 messages 每个事件都换新引用，只配一次即时落位；
    // 视口在键盘动画里是逐帧改值的，挂上去会每帧重启落位链，改成延后落位之后
    // 只有停下来那次真正落一次。
    // scrollMode 与 listSettled 都不再是触发键：scrollMode 恢复贴底的三个入口
    // （发送、「回到底部」、展开当前回复）自己会请求落位；listSettled 在首屏定稿时
    // 会 false→true 翻转一次，那次翻转会重新点着整条补落位链，看起来像初始化反复弹回底部。
    LaunchedEffect(store.messages, store.loading) {
        if (!store.loading && scrollMode != ChatScrollMode.Manual) {
            stickToBottom(0L, emptyList(), false)
        }
    }
    LaunchedEffect(
        store.isResponding,
        bottomIndex,
        listViewportHeightPx,
    ) {
        if (!store.loading && scrollMode != ChatScrollMode.Manual) {
            stickToBottom(
                STICK_TO_BOTTOM_SETTLE_MS,
                chatStickToBottomRetryDelaysMs(listSettled),
                false,
            )
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
        stickToBottom(0L, chatStickToBottomRetryDelaysMs(listSettled), true)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(store, store.toast) {
        val message = store.toast ?: return@LaunchedEffect
        snackbarHostState.showWandNotice(message)
        if (store.toast == message) store.toast = null
    }

    // 液态玻璃：内容区是 backdrop 捕获源，顶栏/输入栏/FAB 悬浮其上采样模糊+折射。
    val glassBackdrop = rememberGlassBackdrop()
    val activeBackdrop = if (chromeSettled) glassBackdrop else null
    var composerExpanded by remember(sessionId) { mutableStateOf(false) }
    // ＋ 展开的动作面板：就地展开，返回键/发送/换会话时收起（规则 2）。
    var attachOpen by remember(sessionId) { mutableStateOf(false) }
    BackHandler(enabled = attachOpen) { attachOpen = false }
    LaunchedEffect(voice.pressed, store.snapshot?.provider, store.snapshot?.toolId, store.providerSwitching, store.isStructured, store.pendingEscalation) {
        if (voice.pressed || store.providerSwitching || store.snapshot?.toolId == "wand-agent" || store.snapshot?.provider != "pi" || !store.isStructured || store.pendingEscalation != null) resources.dismiss()
    }
    CompositionLocalProvider(
        LocalServerBaseUrl provides api.baseUrl,
        LocalChatApi provides api,
        LocalChatSessionId provides sessionId,
        LocalChatWorkingDirectory provides store.snapshot?.cwd,
        LocalCardExpandDefaults provides store.cardDefaults,
        LocalActivityPanelReveal provides revealActivityPanel,
        LocalActivityTrafficTimelineMode provides trafficTimelineMode(),
        LocalChatViewportHeightPx provides listViewportHeightPx,
    ) {
    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { WandSnackbarHost(snackbarHostState) },
        topBar = {
            // 稳定标题 + 简明会话上下文；避免流式任务名和长路径持续跳动、抢占操作区。
            // 顶栏使用稳定页底，避免滚动文字透进状态栏形成残影。
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
                    if (employeeId != null) {
                        EmployeeAvatar(employeeId,
                            liveEmployee?.name ?: store.snapshot?.employeeName,
                            liveEmployee?.avatar ?: store.snapshot?.employeeAvatar,
                            provider = store.snapshot?.provider)
                    } else WandProviderMark(store.snapshot?.provider)
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
                        val topicTitle = sessionChromeTitle(
                                title = store.snapshot?.title,
                                latestUserInput = latestUserInputText(store.messages),
                                liveTitle = SessionTitleStore.titleOf(sessionId),
                                blockedTitles = blockedTitles,
                                fallback = "对话详情",
                            )
                        ChatTopicTitle(
                            text = if (employeeId != null) {
                                liveEmployee?.name ?: store.snapshot?.employeeName ?: "硅基员工"
                            } else topicTitle,
                            generating = store.snapshot?.titleGenerating == true ||
                                SessionTitleStore.isGenerating(sessionId),
                        )
                        val workingPath = chatWorkingPath(store.snapshot?.cwd)
                        TailMarqueePathText(
                            path = if (employeeId != null) {
                                "$topicTitle · $serverDisplayName"
                            } else if (workspaceTitle != null) {
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
                    SessionFilesButton(api, sessionId, store.snapshot?.cwd) {
                        resources.dismiss()
                        quickCommit.closePanel()
                        attachOpen = false
                        focusManager.clearFocus()
                    }
                    GitChangesButton(quickCommit, compact = true) { resources.dismiss(); quickCommit.openPanel() }
                },
            )
        },
        bottomBar = { BottomBar(
            backdrop = activeBackdrop,
            store = store,
            resources = resources,
            composer = composer,
            voice = voice,
            onMicDown = onMicDown,
            uploading = composer.uploading,
            pendingAttachments = composer.attachments,
            baseUrl = api.baseUrl,
            onRemoveAttachment = composer::removeAttachment,
            onPickPhoto = attachmentPickers.pickPhoto,
            onPickFile = attachmentPickers.pickFile,
            onExpandedChange = { composerExpanded = it },
            attachOpen = attachOpen,
            onAttachOpenChange = { attachOpen = it; if (it) resources.dismiss() },
            activityDockVisible = showActivityDock,
            subagentActivities = subagentActivities,
            lastAssistantUsage = lastAssistantUsage,
            onActivityDockExpandedChange = { activityDockExpanded = it },
        ) {
            resources.dismiss()
            if (composer.submit()) {
                attachOpen = false
                if (isHapticEnabled()) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                scrollMode = ChatScrollMode.StickToBottom
            }
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
                        attachOpen = false
                        resources.dismiss()
                        focusManager.clearFocus()
                    }
                }
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
                            bottom = 4.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // key 基于绝对 turn 位置/稳定工具 id：prepend 分页不会重建所有卡片。
                        // 同一个 item key 同时充当卡片的 fold scope（设计规格 v2 §8.2）：
                        // 列表键与卡片折叠态用同一个结构身份，只在一处计算。
                        val itemFoldScope: (MessageDisplayItem) -> String = { item ->
                            messageItemKey(
                                item = item,
                                loadedOffset = store.loadedOffset,
                                anchorExplorationAtEnd = lastUserTurnIndex >= 0 &&
                                    messageItemTurnIndex(item) < lastUserTurnIndex,
                            )
                        }
                        itemsIndexed(
                            displayItems,
                            key = { _, item -> itemFoldScope(item) },
                            // 列表混排 user 气泡 / assistant turn / 探索聚合卡三种形态，
                            // 提供 contentType 提高槽位复用命中率。
                            contentType = { _, item -> item::class },
                        ) { _, item ->
                            val foldScope = itemFoldScope(item)
                            // 不挂 Modifier.animateItem()：聊天卡是变高内容，滚动与逐帧测准时
                            // 会不断修正条目偏移，位移动画把这些修正当成动画播出来，
                            // 就是滚动里的橡皮筋抖动。卡片入场动效在各卡内部自己做。
                            when (item) {
                                is MessageDisplayItem.Turn -> {
                                    val absoluteTurnIndex = store.loadedOffset + item.index
                                    val collapseReply = item.turn.role != "user" &&
                                        shouldCollapseReply(item.index, lastUserTurnIndex)
                                    val isCurrentReply = item.turn.role != "user" && !collapseReply
                                    TurnView(
                                        item.turn,
                                        employeeId = employeeId,
                                        employeeName = liveEmployee?.name ?: store.snapshot?.employeeName,
                                        employeeAvatar = liveEmployee?.avatar ?: store.snapshot?.employeeAvatar,
                                        employeeProvider = store.snapshot?.provider,
                                        isLastTurn = item.index == store.messages.lastIndex,
                                        isResponding = store.isResponding,
                                        activeCommandIds = activeCommandIds,
                                        toolResultsById = toolResultsById,
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
                                        foldScope = foldScope,
                                    )
                                }
                                is MessageDisplayItem.Exploration -> ExplorationGroupCard(
                                    tools = item.tools,
                                    running = store.isResponding &&
                                        item.lastTurnIndex == store.messages.lastIndex &&
                                        item.tools.any { it.result == null },
                                    foldScope = foldScope,
                                    isLatestActivity = item.lastTurnIndex == store.messages.lastIndex,
                                )
                            }
                        }
                        item(key = "chat-bottom") {
                            Spacer(modifier = Modifier.size(1.dp))
                        }
                    }
                    // 补页/重试只在原位转圈，不占列表高度，也不写文案。
                    if (store.loadingEarlier || store.earlierLoadFailed) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 10.dp)
                                .size(16.dp),
                            strokeWidth = 2.dp,
                            color = WandColors.brand,
                        )
                    }
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
            // 回到底部按钮：品牌色玻璃圆钮，淡入 + 缩放。用户上滚后点它，回到真正的列表底部。
            AnimatedVisibility(
                visible = !store.loading &&
                    store.loadError == null &&
                    !activityDockExpanded &&
                    scrollMode == ChatScrollMode.Manual &&
                    canScrollForwardToTail,
                enter = fadeIn(WandMotion.tweenFast()) +
                    scaleIn(initialScale = 0.8f, animationSpec = WandMotion.tweenFast()),
                exit = fadeOut(WandMotion.tweenFast()) +
                    scaleOut(targetScale = 0.8f, animationSpec = WandMotion.tweenFast()),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 12.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .glassSurface(activeBackdrop, CircleShape, WandGlass.accent)
                        .clickable {
                            // 也走同一个协调器：自己另起一条 animateScrollToItem 链，
                            // 会和延后落位的补落位撞在一起（动画中途被瞬移打断）。
                            scrollMode = ChatScrollMode.StickToBottom
                            stickToBottom(0L, chatStickToBottomRetryDelaysMs(listSettled), true)
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
        // 拖动气泡：淡入 + 缩放出现，松手后跟着淡出，不再是从无到有地闪一下。
        AnimatedVisibility(
            visible = dragging && currentPreview.isNotBlank(),
            enter = fadeIn(WandMotion.tweenFast()) +
                scaleIn(initialScale = 0.94f, animationSpec = WandMotion.tweenFast()),
            exit = fadeOut(WandMotion.tweenFast()) +
                scaleOut(targetScale = 0.94f, animationSpec = WandMotion.tweenFast()),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(
                    x = (-44).dp,
                    y = scrubberHeight * ((currentItem + 0.5f) / itemCount.toFloat()) - 22.dp,
                ),
        ) {
            Box(
                modifier = Modifier
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
    var directoryOpen by remember(store) { mutableStateOf(false) }
    LaunchedEffect(store.canChangeDirectory) {
        if (!store.canChangeDirectory) directoryOpen = false
    }
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
                LaunchProviderPicker(store)
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
                    "输入消息，让它帮你完成任务 · 点 Logo 更换工具",
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
                            add("default" to "默认 · ${modelDisplayLabel(store, null)}")
                            store.availableModels
                                .filter { it.id != "default" }
                                .forEach { add(it.id to it.label) }
                        },
                        selected = store.selectedModel?.takeUnless { it == "default" } ?: "default",
                        onSelect = { store.setModel(it.takeUnless { id -> id == "default" }) },
                        searchable = true,
                        enabled = !store.providerSwitching && !store.directoryChanging,
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
                        onSelect = store::chooseThinkingEffort,
                        enabled = !store.providerSwitching && !store.directoryChanging,
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = WandColors.border.copy(alpha = 0.62f),
                        modifier = Modifier.padding(start = 68.dp))
                    LaunchSettingPicker(
                        icon = WandIcons.folder, label = "运行目录",
                        value = store.snapshot?.cwd.orEmpty(), accent = accent, accentSoft = accentSoft,
                        options = emptyList(), selected = "", onSelect = {},
                        enabled = store.canChangeDirectory, onOpen = { directoryOpen = true },
                        valueOverflow = TextOverflow.MiddleEllipsis,
                    )
                    Text(
                        store.directoryChangeError ?: if (store.directoryChanging) "正在切换运行目录…"
                        else if (store.snapshot?.workspaceTaskId != null || store.snapshot?.directoryLocked == true)
                            "当前任务或会话的运行目录已固定"
                        else "首次发送前可更换运行目录",
                        color = if (store.directoryChangeError != null) WandColors.danger else WandColors.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
    if (directoryOpen) WorkspaceDirectoryPicker(
        api = store.api, currentCwd = store.snapshot?.cwd.orEmpty(),
        onSelect = { directoryOpen = false; store.chooseWorkingDirectory(it.cwd) },
        onDismiss = { directoryOpen = false },
    )
}

/** 空白对话的 Logo 原位打开工具菜单；切换保留同一会话和同一个输入 composer。 */
@Composable
private fun LaunchProviderPicker(store: ChatStore) {
    var menuOpen by remember(store) { mutableStateOf(false) }
    BackHandler(enabled = menuOpen) { menuOpen = false }
    LaunchedEffect(store.canSwitchProvider) {
        if (!store.canSwitchProvider) menuOpen = false
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Box(
                modifier = Modifier.size(52.dp).semantics {
                    contentDescription = "更换执行工具，当前 ${store.snapshot?.providerLabel.orEmpty()}"
                    role = Role.Button
                }.clickable(enabled = store.canSwitchProvider, role = Role.Button) { menuOpen = !menuOpen },
            ) {
                WandInPlaceSwap(contentKey = store.snapshot?.toolId, enterScale = 1f, exitScale = 1f) { shown ->
                    ProviderBrandMark(provider = agentToolOption(shown as? String)?.provider, size = 52)
                }
                WandStatusIconSlot(
                    running = store.providerSwitching,
                    icon = WandIcons.expand,
                    indicatorColor = WandColors.brand,
                    containerColor = WandColors.bgElevated,
                    boxSize = 20.dp,
                    iconSize = 13.dp,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = WandColors.bgElevated,
            ) {
                AGENT_TOOL_OPTIONS.forEach { tool ->
                    DropdownMenuItem(
                        text = { Text(tool.label) },
                        leadingIcon = { WandProviderMark(tool.provider) },
                        trailingIcon = if (store.snapshot?.toolId == tool.id) {
                            { Icon(WandIcons.check, contentDescription = "当前工具", tint = WandColors.brand) }
                        } else null,
                        onClick = { menuOpen = false; store.chooseProvider(tool.id) },
                    )
                }
            }
        }
        Box(
            modifier = Modifier.fillMaxWidth().height(38.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                store.providerSwitchError ?: if (store.providerSwitching) "正在切换工具…"
                    else store.providerSwitchResult.orEmpty(),
                color = if (store.providerSwitchError != null) WandColors.danger else WandColors.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

/**
 * Provider 品牌标：中性悬浮圆角方块 + 工具原生 logo。
 * 不再铺 Wand 橙色底，也不把官方配色改写成 accent。
 * 与 [com.wand.app.ui.components.WandBrandMark] 同尺寸/同圆角比例，但内容随 provider 变化。
 */
@Composable
internal fun ProviderBrandMark(
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
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    searchable: Boolean = false,
    enabled: Boolean = true,
    onOpen: (() -> Unit)? = null,
    valueOverflow: TextOverflow = TextOverflow.Ellipsis,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // 收起面板时一并清掉搜索词，下次打开不会残留上次的过滤条件。
    fun closePicker() {
        expanded = false
        query = ""
    }
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
                    enabled = enabled,
                ) { if (onOpen != null) onOpen() else expanded = true }
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
                    overflow = valueOverflow,
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
                onDismissRequest = { closePicker() },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                // 列表滚到边界时剩余位移会交给弹层拖动，滚起来整块回弹；纵向手势归内部列表。
                gesturesEnabled = false,
            ) {
                NoOverscroll {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 620.dp)
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
                        ChoiceOptionsList(
                            options = visibleOptions,
                            selected = selected,
                            accent = accent,
                            accentSoft = accentSoft,
                            emptyLabel = "没有匹配的$label",
                            onSelect = { id ->
                                onSelect(id)
                                closePicker()
                            },
                            modifier = Modifier.weight(1f, fill = false),
                        )
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

/** 「default」是「跟随服务端默认」的占位值；解析成真正生效的模型 id（可能为空）。 */
private fun effectiveModelId(store: ChatStore, id: String?): String? =
    id?.takeIf { it != "default" } ?: store.defaultModel

private fun modelDisplayLabel(store: ChatStore, id: String?): String {
    val effectiveId = effectiveModelId(store, id)
    if (effectiveId.isNullOrBlank()) {
        return store.availableModels.firstOrNull { it.id == "default" }?.label ?: "跟随服务端默认"
    }
    return store.availableModels.firstOrNull { it.id == effectiveId }?.label ?: effectiveId
}

/** 启动卡空间宝贵；当服务端 label 已是“人读名 · id”时去掉重复 id。 */
private fun launchModelDisplayLabel(store: ChatStore, id: String?): String =
    compactModelDisplayLabel(modelDisplayLabel(store, id), effectiveModelId(store, id))

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
                        onEdit = { store.editQueued(index, it) },
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
    onEdit: (String) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(text) { mutableStateOf(text) }
    if (editing) WandDialog(
        title = "编辑排队消息",
        onDismissRequest = { editing = false },
        confirm = WandDialogAction("保存", {
            if (draft.isNotBlank()) { onEdit(draft); editing = false }
        }),
        dismiss = WandDialogAction("取消", { editing = false }),
    ) {
        WandTextField(value = draft, onValueChange = { draft = it }, label = "消息")
    }
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
        IconButton(onClick = { editing = true }, modifier = Modifier.size(28.dp)) {
            Text("✎", fontSize = 18.sp, color = WandColors.brand)
        }
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
                tint = WandColors.onDanger,
                modifier = Modifier.size(14.dp),
            )
            Text(
                "连接已断开，正在重连…",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = WandColors.onDanger,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// MARK: - 底部栏（待办 + 权限卡 + 队列 + Agent 状态坞 + 输入框）

@Composable
private fun BottomBar(
    backdrop: GlassBackdrop?,
    store: ChatStore,
    composer: ChatComposer,
    resources: com.wand.app.ui.PiResourcesController,
    voice: VoiceInputController,
    onMicDown: () -> Unit,
    uploading: Boolean,
    pendingAttachments: List<UploadedFile>,
    baseUrl: String,
    onRemoveAttachment: (UploadedFile) -> Unit,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    // 就地展开的附件面板状态：放在 onSend 之前，保证末尾的尾随 lambda 仍然绑定 onSend。
    attachOpen: Boolean = false,
    onAttachOpenChange: (Boolean) -> Unit = {},
    activityDockVisible: Boolean = false,
    subagentActivities: List<SubagentActivity> = emptyList(),
    lastAssistantUsage: TurnUsage? = null,
    onActivityDockExpandedChange: (Boolean) -> Unit = {},
    onSend: () -> Unit,
) {
    // 草稿订阅收敛在这里：打字只重组底部栏，不再波及消息列表。
    val draft = composer.draft
    val onDraftChange: (String) -> Unit = composer::editDraft
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(bottom = 4.dp),
    ) {
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
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
        // Agent 状态坞排在待办/权限/队列之后，作为输入栏上方的最后一项贴着输入框。
        AnimatedVisibility(
            visible = activityDockVisible,
            enter = fadeIn(WandMotion.tweenFast()) +
                slideInVertically(WandMotion.settleSpringSpec()) { height -> height / 3 },
            exit = fadeOut(WandMotion.tweenFast()) +
                slideOutVertically(WandMotion.settleSpringSpec()) { height -> height / 3 },
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
        ) {
            key(store.sessionId) {
                SubagentActivityDock(
                    backdrop = backdrop,
                    activities = subagentActivities,
                    usage = lastAssistantUsage,
                    sessionRunning = store.isResponding,
                    onExpandedChange = onActivityDockExpandedChange,
                )
            }
        }
        InputBar(
            backdrop = backdrop,
            store = store,
            canSubmit = composer.canSubmit && resources.phase != "saving",
            resources = resources,
            sendPhase = composer.sendPhase,
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
            attachOpen = attachOpen,
            onAttachOpenChange = onAttachOpenChange,
        )
    }
    }
}

/**
 * 输入框高度上限（§2.15 R1）：折叠态不设上限，只由 `heightIn(min = 34.dp)` 兜底，
 * 系统字体放大时占位与正文按行高长开而不是被裁切；展开态保留原有内容滚动上限。
 */
internal fun composerInputMaxHeight(expanded: Boolean): Dp =
    if (expanded) ComposerExpandedInputMaxHeight else Dp.Infinity

internal val ComposerExpandedInputMaxHeight = 132.dp

@Composable
private fun InputBar(
    backdrop: GlassBackdrop?,
    store: ChatStore,
    canSubmit: Boolean,
    resources: com.wand.app.ui.PiResourcesController,
    sendPhase: SendPhase,
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
    attachOpen: Boolean,
    onAttachOpenChange: (Boolean) -> Unit,
) {
    val canSend = draft.isNotBlank() || pendingAttachments.isNotEmpty()
    var showStopConfirm by remember(store.sessionId) { mutableStateOf(false) }
    var modeOpen by remember(store.sessionId) { mutableStateOf(false) }
    val menuTriggerFocus = remember(store.sessionId) { FocusRequester() }
    val menuStopVisible = composerMenuHasStop(
        store.isResponding,
        sendActionVisual(sendPhase, store.isResponding, canSend),
    )
    SharedMessageComposer(
        backdrop = backdrop,
        sessionKey = store.sessionId,
        draft = draft,
        onDraftChange = onDraftChange,
        attachments = pendingAttachments,
        baseUrl = baseUrl,
        onRemoveAttachment = onRemoveAttachment,
        uploading = uploading,
        attachOpen = attachOpen,
        onAttachOpenChange = { open -> resources.dismiss(); onAttachOpenChange(open) },
        onPickPhoto = onPickPhoto,
        onPickFile = onPickFile,
        canSubmit = canSubmit,
        onSend = onSend,
        allowRefocus = !store.sessionEnded,
        voicePressed = voice.pressed,
        onExpandedChange = onExpandedChange,
        resourcePanel = { PiResourcesPanel(resources) { runCatching { menuTriggerFocus.requestFocus() } } },
        resourcePanelOpen = resources.open,
        menuActionModifier = Modifier.focusRequester(menuTriggerFocus),
        menuContent = {
            if (store.isStructured) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WandInlinePanelAction(
                        icon = WandIcons.permission,
                        label = "权限 · ${sessionModeLabel(store.mode)}",
                        enabled = store.snapshot?.provider != "codex",
                        modifier = Modifier.weight(1f).heightIn(min = ComposerActionTouchSize),
                        onClick = { onAttachOpenChange(false); resources.dismiss(); modeOpen = true },
                    )
                    if (store.snapshot?.provider == "pi") {
                        WandInlinePanelAction(
                            icon = WandIcons.settings,
                            label = "会话设置",
                            modifier = Modifier.weight(1f).heightIn(min = ComposerActionTouchSize),
                            onClick = { onAttachOpenChange(false); resources.toggle() },
                        )
                    }
                }
            }
            if (menuStopVisible) {
                WandInlinePanelAction(
                    icon = WandIcons.stop,
                    label = "停止当前任务",
                    modifier = Modifier.fillMaxWidth().heightIn(min = ComposerActionTouchSize),
                    onClick = { onAttachOpenChange(false); resources.dismiss(); showStopConfirm = true },
                )
            }
        },
        trailingActions = { requestFocus, sendAndRefocus ->
            TrailingSendStop(
                store = store,
                sendPhase = sendPhase,
                canSend = canSend,
                canSubmit = canSubmit,
                onStop = { onAttachOpenChange(false); resources.dismiss(); showStopConfirm = true },
                voiceAction = {
                    VoiceMicButton(
                        voice = voice,
                        voiceMode = false,
                        onToggleMode = requestFocus,
                        onMicDown = onMicDown,
                    )
                },
                onSend = sendAndRefocus,
            )
        },
        controls = {
            Row(
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (store.isStructured) {
                    ModelThinkingChip(store, beforeOpen = {
                        onAttachOpenChange(false)
                        resources.dismiss()
                    }, modifier = Modifier.weight(1f))
                }
            }
        },
    )
    if (modeOpen) {
        ComposerModeChoiceSheet(store, onDismiss = { modeOpen = false })
    }
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
 * - 有草稿 → 发送按钮（运行中排队，停止入口收进加号面板）。
 *
 * 动效（`docs/motion-design.md` 规则 3 / 4）：
 * - 提交后按钮**原地**依次显示 发送中 → 已送达 / 失败，不弹 Toast、不换位置；
 * - 箭头 ⇄ 停止方块是同构变形，不再是一帧硬切。
 */
@Composable
private fun TrailingSendStop(
    store: ChatStore,
    sendPhase: SendPhase,
    canSend: Boolean,
    canSubmit: Boolean,
    onStop: () -> Unit,
    voiceAction: @Composable () -> Unit,
    onSend: () -> Unit,
) {
    val visual = sendActionVisual(
        phase = sendPhase,
        turnRunning = store.isResponding,
        hasDraft = canSend,
    ).let { visual ->
        if (visual == SendActionVisual.Send && !canSubmit) SendActionVisual.Blocked else visual
    }
    ComposerSendStopActions(
        voiceAction = voiceAction,
        visual = visual,
        stopDescription = "停止任务",
        sendDescription = if (store.isResponding) "排队发送消息" else "发送消息",
        onSend = onSend,
        onStop = onStop,
    )
}

/** 控制行通用胶囊徽标：图标 + 文字 + 弱色底 + 下拉箭头。 */
/**
 * 让 [key] 这个 item 的底边（向下展开的工具面板底边）留在列表视口里。
 *
 * 面板长在摘要行下面，item 在动画里逐帧变高：这里每帧只补「越过视口底部」的那一段，
 * 因此有空间时补偿量为零（摘要行和上一屏内容都不动），放不下时才把列表抬起最小距离。
 * 高度回落时同一个目标值会自然把列表放回原位（负增量走同一条滚动路径）。
 * [applied] 按 item 记录已补偿量：无补偿时的尾部落点必须把它加回去，否则读数永远滞后一帧。
 */
private suspend fun LazyListState.revealActivityPanelTail(
    key: String,
    applied: MutableMap<String, Int>,
    maxFrames: Int = 48,
    isCancelled: () -> Boolean = { false },
) {
    repeat(maxFrames) {
        withFrameNanos { }
        if (isCancelled()) {
            // 补偿途中用户自己接管了列表（拖动/惯性/进入手动浏览）：以当前位置为新基准，
            // 既不再拽回去，也不留下过期账目让下一次开合反向拉一把。
            applied.remove(key)
            return
        }
        val info = layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: run {
            // 面板不可见时不做补偿，也不要留着过期账目影响下一次开合。
            applied.remove(key)
            return
        }
        val compensated = applied[key] ?: 0
        val naturalTail = info.offset + info.size + compensated
        val allowed = (layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding).coerceAtLeast(0)
        val target = (naturalTail - allowed).coerceAtLeast(0)
        val delta = target - compensated
        if (delta != 0) applied[key] = compensated + scrollBy(delta.toFloat()).toInt()
    }
    if ((applied[key] ?: 0) == 0) applied.remove(key)
}

@Composable
internal fun ControlChip(
    icon: ImageVector,
    text: String,
    tint: Color,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showText: Boolean = true,
    onClick: () -> Unit,
) {
    val dark = isWandDarkTheme()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motionEnabled = !reduceMotionEnabled()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenPress()),
        label = "controlChipScale",
    )

    // 精巧全圆角胶囊：半透明通透底色 + 极细柔和微边框
    val chipBg = WandColors.surface.copy(alpha = if (dark) 0.70f else 0.90f)
    val chipBorderColor = WandColors.border.copy(alpha = if (dark) 0.50f else 0.65f)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 34.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = ripple(bounded = true),
                onClick = onClick,
            ),
    ) {
        val rowModifier = if (showText && text.isNotBlank()) {
            Modifier
                .height(28.dp)
                .clip(CircleShape)
                .background(chipBg)
                .border(0.75.dp, chipBorderColor, CircleShape)
                .padding(horizontal = 8.dp)
        } else {
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(chipBg)
                .border(0.75.dp, chipBorderColor, CircleShape)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = rowModifier,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(13.dp),
            )
            if (showText && text.isNotBlank()) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = text,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp),
                )
            }
        }
    }
}

@Composable
private fun ChoiceOptionsList(
    options: List<Pair<String, String>>,
    selected: String?,
    accent: Color,
    accentSoft: Color,
    emptyLabel: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 只布局可见选项；模型目录可能很长，不能让整个弹层在拖动时重排所有行。
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (options.isEmpty()) {
            item {
                Text(
                    emptyLabel,
                    fontSize = 14.sp,
                    color = WandColors.textMuted,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                )
            }
        }
        items(options, key = { it.first }) { (id, optionLabel) ->
            val isSelected = selected == id
            WandListItem(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) accentSoft else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(id) },
                headlineColor = if (isSelected) accent else WandColors.textPrimary,
                headlineContent = { Text(optionLabel) },
                trailingContent = { WandListItemIconSlot(if (isSelected) WandIcons.check else null, tint = accent) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerChoiceSheet(
    title: String,
    options: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    searchable: Boolean = false,
    searchPlaceholder: String = "搜索模型",
) {
    var query by remember { mutableStateOf("") }
    val visibleOptions = remember(options, selected, query) {
        val filtered = if (searchable && query.isNotBlank()) {
            options.filter { matchesModelSearch(query, it.first, it.second) }
        } else {
            options
        }
        prioritizeSelectedItem(filtered, selected) { it.first }
    }
    WandBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // 列表滚到边界时剩余位移会交给弹层拖动，滚起来整块回弹；纵向手势交给内部列表。
        gesturesEnabled = false,
    ) {
        NoOverscroll {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
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
                if (searchable) {
                    WandTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = searchPlaceholder,
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
                ChoiceOptionsList(
                    options = visibleOptions,
                    selected = selected,
                    accent = WandColors.brand,
                    accentSoft = WandColors.brandSoft,
                    emptyLabel = "没有匹配的$title",
                    onSelect = onSelect,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

/** 执行模式从加号面板进入；弹层状态由输入栏持有，关闭面板不会销毁选择器。 */
@Composable
private fun ComposerModeChoiceSheet(store: ChatStore, onDismiss: () -> Unit) {
    val supportedModeIds = supportedSessionModeIds(store.snapshot?.provider)
    ComposerChoiceSheet(
        title = "执行模式",
        options = SESSION_MODE_OPTIONS
            .filter { it.id in supportedModeIds }
            .map { it.id to "${it.label} · ${it.description}" },
        selected = store.mode,
        onSelect = { id ->
            store.chooseMode(id)
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

/** 模型与思考深度合体弹层：上方思考深度横向快捷切换，下方模型搜索与列表。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelThinkingChoiceSheet(
    store: ChatStore,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val thinkingOptions = remember(store.snapshot?.provider, store.selectedModel, store.thinkingEffort) {
        thinkingLevels(store)
    }
    val selectedModelId = store.selectedModel?.takeUnless { it == "default" } ?: "default"
    val modelOptions = remember(store.availableModels, store.selectedModel, selectedModelId) {
        val base = buildList {
            add("default" to "默认 · ${modelDisplayLabel(store, null)}")
            store.availableModels.filter { it.id != "default" }.forEach { model ->
                add(model.id to model.label)
            }
        }
        prioritizeSelectedItem(base, selectedModelId) { it.first }
    }
    val visibleModels = remember(modelOptions, query, selectedModelId) {
        if (query.isNotBlank()) {
            val matches = modelOptions.filter { matchesModelSearch(query, it.first, it.second) }
            prioritizeSelectedItem(matches, selectedModelId) { it.first }
        } else {
            modelOptions
        }
    }

    WandBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // 列表滚到边界时剩余位移会交给弹层拖动，滚起来整块回弹；纵向手势交给内部列表。
        gesturesEnabled = false,
    ) {
        NoOverscroll {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .imePadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
            ) {
                // 顶部：思考深度分段快捷切换
                Text(
                    "思考深度",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WandColors.textSecondary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    thinkingOptions.forEach { option ->
                        val isSelected = option.id == store.thinkingEffort
                        val chipBg = if (isSelected) WandColors.brandSoft else WandColors.surface
                        val chipBorder = if (isSelected) WandColors.brand else WandColors.border.copy(alpha = 0.6f)
                        val chipTint = if (isSelected) WandColors.brand else WandColors.textSecondary
                        Box(
                            modifier = Modifier
                                .clip(WandShapes.full)
                                .background(chipBg)
                                .border(0.8.dp, chipBorder, WandShapes.full)
                                .clickable {
                                    store.chooseThinkingEffort(option.id)
                                }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                option.menuLabel,
                                fontSize = 12.5.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = chipTint,
                            )
                        }
                    }
                }

                HorizontalDivider(
                    color = WandColors.border.copy(alpha = 0.45f),
                    thickness = 0.6.dp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                )

                // 中间：模型搜索
                Text(
                    "模型",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WandColors.textSecondary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
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

                // 下部：模型列表
                ChoiceOptionsList(
                    options = visibleModels,
                    selected = selectedModelId,
                    accent = WandColors.brand,
                    accentSoft = WandColors.brandSoft,
                    emptyLabel = "没有匹配的模型",
                    onSelect = { id ->
                        store.setModel(id.takeUnless { it == "default" })
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

/** 模型与思考深度合体徽标：Logo +「模型名 · 思考程度」。 */
@Composable
private fun ModelThinkingChip(store: ChatStore, beforeOpen: () -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val thinkingTint = when (store.thinkingEffort) {
        "standard" -> WandColors.success
        "deep" -> WandColors.warning
        "max" -> WandColors.danger
        else -> WandColors.brand
    }
    val labelText = "${shortModelLabel(store)} · ${thinkingLabel(store, store.thinkingEffort)}"
    Box(modifier = modifier) {
        ControlChip(
            icon = WandIcons.sparkle,
            text = labelText,
            modifier = Modifier.heightIn(min = ComposerActionTouchSize),
            tint = thinkingTint,
            contentDescription = "模型与思考深度：模型 ${modelDisplayLabel(store, store.selectedModel)}，思考深度 ${thinkingLabel(store, store.thinkingEffort)}",
            showText = true,
        ) { beforeOpen(); open = true }
        if (open) {
            ModelThinkingChoiceSheet(
                store = store,
                onDismiss = { open = false },
            )
        }
    }
}

/**
 * 输入栏左侧「更多操作」按钮（对齐 iOS composerActionsMenu）：
 * 圆形 + 号，展开附件、会话设置与当前任务操作；上传中显示转圈。
 */
@Composable
internal fun ComposerActionsMenu(
    backdrop: GlassBackdrop?,
    uploading: Boolean,
    attachOpen: Boolean,
    onAttachOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    allowDuringUpload: Boolean = false,
) {
    val attachProgress by animateFloatAsState(
        targetValue = if (attachOpen) 1f else 0f,
        animationSpec = WandMotion.respectMotion(!reduceMotionEnabled(), WandMotion.morph()),
        label = "composerAttachMorph",
    )
    FilledComposerAction(
        enabled = !uploading || allowDuringUpload,
        fillColor = if (attachOpen) WandColors.brandSoft else WandColors.surface,
        contentDescription = when {
            uploading && allowDuringUpload -> "正在上传附件，更多操作"
            uploading -> "正在上传附件"
            attachOpen -> "收起更多操作"
            else -> "更多操作：附件、设置与任务"
        },
        modifier = modifier,
        onClick = { onAttachOpenChange(!attachOpen) },
    ) {
        WandInPlaceSwap(contentKey = uploading, modifier = Modifier.size(ComposerActionIconSize)) { busy ->
            if (busy as Boolean) {
                CircularProgressIndicator(
                    color = WandColors.brand,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(ComposerActionIconSize),
                )
            } else {
                WandMorphingIcon(
                    progress = attachProgress,
                    from = WandIcons.add,
                    to = WandIcons.close,
                    tint = if (attachOpen) WandColors.brand else WandColors.textPrimary,
                    modifier = Modifier.size(ComposerActionIconSize),
                )
            }
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
    val micProgress by animateFloatAsState(
        targetValue = if (voiceMode && !voice.pressed) 1f else 0f,
        animationSpec = WandMotion.respectMotion(!reduceMotionEnabled(), WandMotion.morph()),
        label = "composerMicMorph",
    )
    val iconTint = when {
        voice.pressed && voice.canceling -> WandColors.danger
        voice.pressed -> WandColors.brand
        else -> WandColors.textSecondary
    }
    val scale by animateFloatAsState(
        if (voice.pressed) 1.1f else 1f,
        WandMotion.respectMotion(!reduceMotionEnabled(), WandMotion.tweenPress()),
        label = "micScale",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(ComposerActionTouchSize)
            .clip(CircleShape)
            .semantics {
                role = Role.Button
                contentDescription = if (voiceMode) "切回键盘输入" else "长按说话，松开完成，上滑取消"
                stateDescription = if (voice.pressed) "正在录音" else "长按录音"
                onClick(label = "聚焦文字输入") {
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
                .clip(CircleShape)
                .background(if (voice.pressed) WandColors.brandSoft else WandColors.surface)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            WandMorphingIcon(
                progress = micProgress,
                from = WandIcons.mic,
                to = WandIcons.keyboard,
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
internal fun SttModelDownloadDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state = SttModelManager.state
    val model = remember { SttModelManager.selectedModel(context) }
    val needsLibrary = !SpeechNativeLibrary.isInstalled(context)
    val needsModel = !SttModelManager.isModelDownloaded(context, model)
    // 下载完成立刻预热模型，让「下载完→按住即用」无加载等待。
    LaunchedEffect(state) {
        if (state is SttModelManager.State.Ready) SherpaSpeechEngine.warmUp(context)
    }
    WandDialog(
        title = "启用本地语音识别",
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
                            "正在下载语音识别组件…",
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
                        "${state.message}\n请检查网络后重试；模型下载会尝试备用镜像。",
                        fontSize = 13.sp,
                        color = WandColors.danger,
                    )
                    else -> Text(
                        "此设备没有可用的系统语音识别服务。" +
                            (if (needsLibrary) "将从官方 GitHub 下载语音引擎（约 38 MB）；" else "") +
                            (if (needsModel) "另下载${model.label}（${model.sizeLabel}）。" else "模型已在本机。") +
                            "仅在确认后下载；启用后识别完全在本机离线运行。",
                        fontSize = 13.sp,
                        color = WandColors.textSecondary,
                    )
                }
            }
    }
}

private fun formatMb(bytes: Long): String =
    String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
