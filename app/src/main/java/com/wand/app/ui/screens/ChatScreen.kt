package com.wand.app.ui.screens

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.SessionWatcher
import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import com.wand.app.data.EscalationRequest
import com.wand.app.data.PermissionRequestInfo
import com.wand.app.data.UploadedFile
import com.wand.app.data.WandApi
import com.wand.app.data.WandApiException
import com.wand.app.speech.SherpaSpeechEngine
import com.wand.app.speech.SttModelManager
import com.wand.app.speech.VoiceInputController
import com.wand.app.ui.ChatStore
import com.wand.app.ui.LocalServerBaseUrl
import com.wand.app.ui.QuickCommitStore
import com.wand.app.ui.WandAsyncImage
import com.wand.app.ui.WandFileChip
import com.wand.app.ui.WandImage
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.components.LoadingState
import com.wand.app.ui.components.ErrorState
import com.wand.app.ui.components.StatusDot
import com.wand.app.ui.components.WandBrandMark
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.clickableWithoutRipple
import com.wand.app.ui.theme.AmbientBackground
import com.wand.app.ui.theme.GlassBackdrop
import com.wand.app.ui.theme.GlassStyle
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandGlass
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.glassBackdropSource
import com.wand.app.ui.theme.glassCard
import com.wand.app.ui.theme.glassSurface
import com.wand.app.ui.theme.rememberGlassBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private enum class ChatScrollMode {
    PinLatestTurn,
    StickToBottom,
    Manual,
}

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
    isHapticEnabled: () -> Boolean,
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
    // 进屏加载一次 git 状态（决定顶栏徽标显隐）；每回合结束后刷新（agent 可能改了文件）。
    LaunchedEffect(store.isResponding) {
        if (!store.isResponding) quickCommit.loadStatus(force = true)
    }

    var draft by remember { mutableStateOf("") }
    // 把「最新轮次置顶」「真正到底部」「用户手动浏览」拆成三种状态，避免到底部按钮
    // 复用置顶 spacer 后滚进空白区域。
    var scrollMode by remember { mutableStateOf(ChatScrollMode.PinLatestTurn) }
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    // 按住说话：端侧语音识别控制器（sherpa 本地模型优先，系统识别器兜底）。
    val context = LocalContext.current
    val voice = remember { VoiceInputController(context) }
    DisposableEffect(voice) {
        voice.onToast = { store.toast = it }
        onDispose { voice.destroy() }
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        store.toast = if (granted) "已获得麦克风权限，按住麦克风说话" else "需要麦克风权限才能语音输入"
    }
    val onMicDown: () -> Unit = {
        if (voice.hasMicPermission()) {
            if (isHapticEnabled()) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            voice.beginPress { text -> draft = appendVoiceText(draft, text) }
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // 仅用户明确向上滚动（拉出更早内容）时暂停贴底跟随（阈值 18dp）。轻微触摸 / 收键盘
    // 不误关，新回复仍自动贴在输入框上方。程序化滚动 source != UserInput，不会误触发。
    val density = LocalDensity.current
    val followPauseConnection = remember(density) {
        val thresholdPx = with(density) { 18.dp.toPx() }
        object : NestedScrollConnection {
            private var pulledDown = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    if (available.y > 0f) {
                        pulledDown += available.y
                        if (pulledDown > thresholdPx) scrollMode = ChatScrollMode.Manual
                    } else if (available.y < 0f) {
                        pulledDown = 0f
                    }
                }
                return Offset.Zero
            }
        }
    }

    // 探索类工具跨消息合并成「探索上下文」紧凑卡（对齐 iOS groupExplorationTurns）。
    // 历史不再折成单张「展开历史对话」摘要卡，改为每条助手回复各自带折叠头
    // （头像 + 名字 + 折叠开关，见 TurnView / AssistantReplyHeader）：旧回复默认折起、当前展开，
    // 像抽纸一样一截一截往上收。
    val displayItems = remember(store.messages) { groupExplorationTurns(store.messages) }
    val lastUserTurnIndex = remember(store.messages) {
        store.messages.indexOfLast { it.role == "user" }
    }
    val absoluteLastUserTurnIndex = remember(store.loadedOffset, lastUserTurnIndex) {
        if (lastUserTurnIndex >= 0) store.loadedOffset + lastUserTurnIndex else -1
    }
    var historyExpanded by rememberSaveable(sessionId) { mutableStateOf(false) }
    var expandedCurrentReplyIndex by rememberSaveable(sessionId) { mutableStateOf(-1) }
    val historyItems = remember(displayItems, lastUserTurnIndex) {
        if (lastUserTurnIndex > 0) {
            displayItems.filter { messageItemTurnIndex(it) < lastUserTurnIndex }
        } else {
            emptyList()
        }
    }
    val currentItems = remember(displayItems, lastUserTurnIndex) {
        if (lastUserTurnIndex >= 0) {
            displayItems.filter { messageItemTurnIndex(it) >= lastUserTurnIndex }
        } else {
            displayItems
        }
    }
    LaunchedEffect(absoluteLastUserTurnIndex) {
        historyExpanded = false
        expandedCurrentReplyIndex = -1
    }
    val unloadedHistoryCount = remember(store.loadedOffset, absoluteLastUserTurnIndex) {
        if (absoluteLastUserTurnIndex > 0) minOf(store.loadedOffset, absoluteLastUserTurnIndex) else 0
    }
    val hasCollapsedHistory = historyItems.isNotEmpty() || unloadedHistoryCount > 0
    val collapsedHistoryCount = historyItems.size + unloadedHistoryCount

    // 附件上传：savedPath 回填输入框（多选 ≤5 个 / 单个 ≤10MB）。
    // 对齐 iOS：相册图片 / 任意文件两条入口共用同一段上传逻辑。
    var uploadingAttachments by remember { mutableStateOf(false) }
    var pendingAttachments by remember(sessionId) { mutableStateOf<List<UploadedFile>>(emptyList()) }
    val uploadUris: (List<Uri>) -> Unit = { uris ->
        if (uris.isNotEmpty()) {
            uploadingAttachments = true
            scrollScope.launch {
                try {
                    val files = withContext(Dispatchers.IO) {
                        uris.take(5).map { uri -> readAttachment(context, uri) }
                    }
                    val uploaded = api.uploadAttachments(sessionId, files)
                    pendingAttachments = (pendingAttachments + uploaded).takeLast(5)
                    store.toast = "已上传 ${uploaded.size} 个附件"
                } catch (e: Exception) {
                    store.toast = e.message ?: "附件上传失败"
                } finally {
                    uploadingAttachments = false
                }
            }
        }
    }
    // 「从文件选择」：系统文档选择器，任意类型。
    val attachmentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> uploadUris(uris.orEmpty()) }
    // 「从相册选择」：系统相册选择器（Photo Picker），只回传用户勾选的图片、无需整库权限。
    // 对齐 iOS PHPicker。
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(5),
    ) { uris -> uploadUris(uris) }

    // 监听完整消息列表而不是 size：流式回复会原地替换最后一条消息，数量不变。
    // 顶部「加载更早」哨兵与历史折叠条都占项；跟随最新时锚到最后一条用户消息。
    // 当前助手回复内部会把旧正文折进头部摘要，所以锚点不需要跟到整条消息尾部。
    val headerOffset = if (store.canLoadEarlier) 1 else 0
    val visibleHistoryCount = when {
        !hasCollapsedHistory -> 0
        historyExpanded -> historyItems.size + 1
        else -> 0
    }
    val latestAnchorIndex = headerOffset + visibleHistoryCount
    val bottomIndex = headerOffset + visibleHistoryCount + currentItems.size + if (store.isResponding) 1 else 0
    val collapsedBottomIndex = headerOffset + currentItems.size + if (store.isResponding) 1 else 0
    LaunchedEffect(store.messages, store.isResponding, store.loading, latestAnchorIndex, bottomIndex, scrollMode) {
        if (!store.loading && scrollMode != ChatScrollMode.Manual) {
            val target = when (scrollMode) {
                ChatScrollMode.PinLatestTurn ->
                    if (lastUserTurnIndex >= 0) latestAnchorIndex else bottomIndex
                ChatScrollMode.StickToBottom -> bottomIndex
                ChatScrollMode.Manual -> bottomIndex
            }
            listState.scrollToItem(target)
            for (waitMs in listOf(50L, 150L, 350L, 700L)) {
                delay(waitMs)
                if (scrollMode == ChatScrollMode.Manual) break
                listState.scrollToItem(target)
            }
        }
    }
    // 展开某条折叠回复时，把它的「头（第一行）」滚到顶部区域来读，且不被顶出屏幕上沿；
    // 同时暂停贴底跟随，免得流式刷新又把视图拽回底部。
    val scrollReplyToTop: (Int) -> Unit = { turnIdx ->
        scrollMode = ChatScrollMode.Manual
        expandedCurrentReplyIndex = -1
        scrollScope.launch {
            val visibleItems = if (historyExpanded) historyItems + currentItems else currentItems
            val baseOffset = headerOffset + when {
                historyItems.isEmpty() -> 0
                historyExpanded -> 0
                else -> 0
            }
            val p = visibleItems.indexOfFirst { messageItemTurnIndex(it) == turnIdx }
            if (p >= 0) listState.animateScrollToItem(baseOffset + p)
        }
    }
    val toggleHistory: () -> Unit = {
        val next = !historyExpanded
        scrollMode = if (next) ChatScrollMode.Manual else ChatScrollMode.PinLatestTurn
        if (next) expandedCurrentReplyIndex = -1
        historyExpanded = next
        if (next) store.loadEarlier()
        scrollScope.launch {
            delay(50)
            listState.animateScrollToItem(headerOffset)
        }
    }
    val expandCurrentReplyToBottom: (Int) -> Unit = { turnIdx ->
        expandedCurrentReplyIndex = turnIdx
        historyExpanded = false
        scrollMode = ChatScrollMode.StickToBottom
        scrollScope.launch {
            for (waitMs in listOf(50L, 150L, 350L, 700L)) {
                delay(waitMs)
                if (expandedCurrentReplyIndex != turnIdx) break
                listState.scrollToItem(collapsedBottomIndex)
            }
        }
    }
    val collapseExpandedCurrentReply: () -> Unit = {
        expandedCurrentReplyIndex = -1
        historyExpanded = false
        scrollMode = ChatScrollMode.PinLatestTurn
        scrollScope.launch {
            listState.animateScrollToItem(latestAnchorIndex)
        }
    }
    val latestUserTurn = store.messages.getOrNull(lastUserTurnIndex)
    val expandedReplyTurn = store.messages.getOrNull(expandedCurrentReplyIndex)
    val showPinnedLatestContext = expandedCurrentReplyIndex >= 0 &&
        latestUserTurn != null &&
        expandedReplyTurn != null &&
        expandedReplyTurn.role != "user"

    // Toast 自动消失。
    LaunchedEffect(store.toast) {
        if (store.toast != null) {
            delay(2_600)
            store.toast = null
        }
    }

    // 液态玻璃：内容区是 backdrop 捕获源，顶栏/输入栏/FAB 悬浮其上采样模糊+折射。
    val glassBackdrop = rememberGlassBackdrop()
    var composerExpanded by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalServerBaseUrl provides api.baseUrl) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            // 顶栏对齐 iOS navigationStatus：居中显示最新一条用户消息 + 完整工作目录，
            // 右侧是 Git 变更统计 + 会话设置菜单（仅结构化会话）。
            // 顶栏使用稳定页底，避免滚动文字透进状态栏形成残影。
            CenterAlignedTopAppBar(
                modifier = Modifier.glassSurface(
                    glassBackdrop,
                    RoundedCornerShape(0.dp),
                    WandGlass.regular.copy(refractionHeight = 0.dp, shadowElevation = 0.dp),
                    edgeToEdge = true,
                ),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        ChatProviderBadge(store.snapshot?.provider)
                        Column(
                            horizontalAlignment = Alignment.Start,
                            modifier = Modifier.widthIn(max = 220.dp),
                        ) {
                            Text(
                                latestUserMessage(store.messages)
                                    ?: store.snapshot?.displayTitle ?: "对话详情",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = WandColors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                middleTruncate(store.snapshot?.cwd ?: "未设置工作目录", 42),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = WandColors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    QuietTopIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        onClick = onBack,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                },
                actions = {
                    // 模型 / 思考深度 / 模式开关已下沉到输入栏展开态的控制行（对齐 Codex App），
                    // 顶栏右侧只留 Git 变更入口。
                    GitChangesButton(quickCommit) { quickCommit.openPanel() }
                    Spacer(modifier = Modifier.size(6.dp))
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
            )
        },
        bottomBar = { BottomBar(
            backdrop = glassBackdrop,
            store = store,
            draft = draft,
            onDraftChange = { draft = it },
            voice = voice,
            onMicDown = onMicDown,
            uploading = uploadingAttachments,
            pendingAttachments = pendingAttachments,
            baseUrl = api.baseUrl,
            onRemoveAttachment = { file ->
                pendingAttachments = pendingAttachments.filterNot { it.savedPath == file.savedPath }
            },
            onPickPhoto = {
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onPickFile = { attachmentPicker.launch(arrayOf("*/*")) },
            onExpandedChange = { composerExpanded = it },
        ) {
            // 发送回调（带触感反馈）；发送后恢复当前轮次钉顶，让旧对话折起。
            if (isHapticEnabled()) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            val text = buildAttachmentPrompt(pendingAttachments, draft)
            draft = ""
            pendingAttachments = emptyList()
            scrollMode = ChatScrollMode.PinLatestTurn
            historyExpanded = false
            expandedCurrentReplyIndex = -1
            store.send(text)
        } },
    ) { padding ->
        // 捕获层：环境渐变背景全幅铺开，消息流只在顶栏与输入栏之间滚动，
        // 避免正文被玻璃栏遮挡或透进状态栏。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .glassBackdropSource(glassBackdrop),
        ) {
            AmbientBackground(Modifier.fillMaxSize())
            when {
                store.loading -> LoadingState("正在加载会话…", Modifier.padding(padding))
                store.loadError != null ->
                    ErrorState(store.loadError ?: "加载失败", modifier = Modifier.padding(padding))
                store.isStructured && store.messages.isEmpty() && !store.isResponding ->
                    Box(Modifier.padding(padding)) {
                        SessionLaunchPanel(store, showSettings = !composerExpanded)
                    }
                else -> {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    ) {
                    val pinnedContextTopPadding = if (showPinnedLatestContext) 126.dp else 0.dp
                    val pinSpacerHeight = if (
                        scrollMode == ChatScrollMode.PinLatestTurn &&
                        lastUserTurnIndex >= 0 &&
                        !historyExpanded
                    ) {
                        maxHeight
                    } else {
                        0.dp
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = if (scrollMode == ChatScrollMode.Manual) 50.dp else 0.dp)
                            .nestedScroll(followPauseConnection),
                        contentPadding = PaddingValues(
                            start = 14.dp,
                            end = 14.dp,
                            top = 8.dp + pinnedContextTopPadding,
                            bottom = 18.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // 窗口化：顶部还有更早消息时放一个哨兵项。滚到顶进入视口即自动拉
                        // 下一页（prepend）。初始固定在底部，哨兵不在视口，不会误触发。
                        if (store.canLoadEarlier) {
                            item(key = "chat-load-earlier") {
                                LaunchedEffect(Unit) { store.loadEarlier() }
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = WandColors.brand,
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text(
                                        "加载更早的消息…",
                                        fontSize = 12.sp,
                                        color = WandColors.textSecondary,
                                    )
                                }
                            }
                        }
                        // key = index：流式原地替换最后一条时 key 不变不触发动画，
                        // 只有真正新增的消息才走 animateItem 淡入。
                        if (hasCollapsedHistory) {
                            if (historyExpanded && historyItems.isNotEmpty()) {
                                itemsIndexed(
                                    historyItems,
                                    key = { _, item -> "history-${messageItemKey(item)}" },
                                ) { _, item ->
                                    Box(modifier = Modifier.animateItem()) {
                                        when (item) {
                                            is MessageDisplayItem.Turn -> TurnView(
                                                item.turn,
                                                isLastTurn = false,
                                                isResponding = false,
                                                turnIndex = item.index,
                                                historyBoundary = lastUserTurnIndex,
                                                onUserExpand = { scrollReplyToTop(item.index) },
                                                askSelections = store.askUserSelections,
                                                onAskToggle = { toolUseId, qIdx, optIdx, multi ->
                                                    store.toggleAskOption(toolUseId, qIdx, optIdx, multi)
                                                },
                                                onAskSubmit = { toolUseId, answerText ->
                                                    scrollMode = ChatScrollMode.PinLatestTurn
                                                    store.submitAskUser(toolUseId, answerText)
                                                },
                                            )
                                            is MessageDisplayItem.Exploration -> ExplorationGroupCard(
                                                tools = item.tools,
                                                running = false,
                                            )
                                        }
                                    }
                                }
                            }
                            if (historyExpanded) {
                                item(key = "history-summary") {
                                    HistorySummaryStrip(
                                        count = collapsedHistoryCount,
                                        preview = historyPreview(store.messages, lastUserTurnIndex),
                                        expanded = true,
                                        onToggle = toggleHistory,
                                    )
                                }
                            }
                            if (historyExpanded && store.loadingEarlier && historyItems.isEmpty()) {
                                item(key = "history-loading-earlier") {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = WandColors.brand,
                                        )
                                        Spacer(Modifier.size(8.dp))
                                        Text(
                                            "正在展开历史消息…",
                                            fontSize = 12.sp,
                                            color = WandColors.textSecondary,
                                        )
                                    }
                                }
                            }
                        }
                        itemsIndexed(currentItems, key = { _, item -> "current-${messageItemKey(item)}" }) { _, item ->
                            Box(modifier = Modifier.animateItem()) {
                                when (item) {
                                    is MessageDisplayItem.Turn -> {
                                        val controlsCurrentReplyExpansion = item.index == store.messages.lastIndex &&
                                            item.turn.role != "user"
                                        val hidePinnedUserInList = showPinnedLatestContext &&
                                            item.index == lastUserTurnIndex &&
                                            item.turn.role == "user"
                                        val hidePinnedReplyHeader = showPinnedLatestContext &&
                                            controlsCurrentReplyExpansion &&
                                            expandedCurrentReplyIndex == item.index
                                        val showInlineHistory = hasCollapsedHistory &&
                                            !showPinnedLatestContext &&
                                            !historyExpanded &&
                                            item.index == lastUserTurnIndex &&
                                            item.turn.role == "user"
                                        val renderTurn: @Composable () -> Unit = {
                                            TurnView(
                                                item.turn,
                                                isLastTurn = item.index == store.messages.lastIndex,
                                                isResponding = store.isResponding,
                                                hideAssistantHeader = hidePinnedReplyHeader,
                                                compactUser = scrollMode == ChatScrollMode.PinLatestTurn &&
                                                    !historyExpanded &&
                                                    item.index == lastUserTurnIndex &&
                                                    (store.isResponding || item.index < store.messages.lastIndex),
                                                currentReplyExpandedOverride =
                                                    if (controlsCurrentReplyExpansion) {
                                                        expandedCurrentReplyIndex == item.index
                                                    } else {
                                                        null
                                                    },
                                                turnIndex = item.index,
                                                historyBoundary = lastUserTurnIndex,
                                                onUserExpand = { scrollReplyToTop(item.index) },
                                                onCurrentReplyExpandedChange = { expanded ->
                                                    if (controlsCurrentReplyExpansion) {
                                                        expandedCurrentReplyIndex = if (expanded) item.index else -1
                                                    }
                                                },
                                                onCurrentReplyExpandToBottom = {
                                                    if (controlsCurrentReplyExpansion) {
                                                        expandCurrentReplyToBottom(item.index)
                                                    }
                                                },
                                                askSelections = store.askUserSelections,
                                                onAskToggle = { toolUseId, qIdx, optIdx, multi ->
                                                    store.toggleAskOption(toolUseId, qIdx, optIdx, multi)
                                                },
                                                onAskSubmit = { toolUseId, answerText ->
                                                    scrollMode = ChatScrollMode.PinLatestTurn
                                                    store.submitAskUser(toolUseId, answerText)
                                                },
                                            )
                                        }
                                        if (hidePinnedUserInList) {
                                            Spacer(modifier = Modifier.height(0.dp))
                                        } else if (showInlineHistory) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.Top,
                                            ) {
                                                InlineHistoryChip(
                                                    count = collapsedHistoryCount,
                                                    onToggle = toggleHistory,
                                                    modifier = Modifier.padding(top = 3.dp),
                                                )
                                                Box(modifier = Modifier.weight(1f)) {
                                                    renderTurn()
                                                }
                                            }
                                        } else {
                                            renderTurn()
                                        }
                                    }
                                    is MessageDisplayItem.Exploration -> ExplorationGroupCard(
                                        tools = item.tools,
                                        running = store.isResponding &&
                                            item.lastTurnIndex == store.messages.lastIndex &&
                                            item.tools.any { it.result == null },
                                    )
                                }
                            }
                        }
                        if (store.isResponding) {
                            item(key = "responding") {
                                Box(modifier = Modifier.animateItem()) {
                                    RespondingIndicator(store.currentTaskTitle)
                                }
                            }
                        }
                        item(key = "chat-bottom") {
                            Spacer(modifier = Modifier.size(1.dp))
                        }
                        if (pinSpacerHeight > 0.dp) {
                            item(key = "chat-pin-spacer") {
                                Spacer(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(pinSpacerHeight),
                                )
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = showPinnedLatestContext,
                        enter = fadeIn(WandMotion.tweenFast()),
                        exit = fadeOut(WandMotion.tweenFast()),
                        modifier = Modifier.align(Alignment.TopCenter),
                    ) {
                        PinnedLatestContextBar(
                            historyCount = collapsedHistoryCount,
                            showHistoryChip = hasCollapsedHistory,
                            userText = turnPlainText(latestUserTurn),
                            replyPreview = turnPlainText(expandedReplyTurn),
                            onHistoryToggle = toggleHistory,
                            onReplyToggle = collapseExpandedCurrentReply,
                            modifier = Modifier.padding(horizontal = 14.dp),
                        )
                    }
                    }
                }
            }
        }

        // 浮层（FAB / 对话框 / toast）：吃 innerPadding、不进捕获层 ——
        // 玻璃元素不能采样到自己。
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
                visible = historyExpanded && hasCollapsedHistory,
                enter = fadeIn(WandMotion.tweenFast()) +
                    scaleIn(initialScale = 0.88f, animationSpec = WandMotion.tweenFast()),
                exit = fadeOut(WandMotion.tweenFast()) +
                    scaleOut(targetScale = 0.88f, animationSpec = WandMotion.tweenFast()),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 12.dp),
            ) {
                InlineHistoryChip(
                    count = collapsedHistoryCount,
                    expanded = true,
                    onToggle = toggleHistory,
                )
            }
            // 回到底部按钮：品牌色玻璃圆钮，淡入 + 缩放。用户上滚后点它，回到真正的列表底部。
            AnimatedVisibility(
                visible = !store.loading && store.loadError == null && scrollMode == ChatScrollMode.Manual,
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
                        .size(42.dp)
                        .glassSurface(glassBackdrop, CircleShape, WandGlass.accent)
                        .clickable {
                            scrollMode = ChatScrollMode.StickToBottom
                            historyExpanded = false
                            scrollScope.launch {
                                delay(50)
                                listState.animateScrollToItem(collapsedBottomIndex)
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

            // Toast：顶部居中胶囊（淡入淡出 + 缓存文案避免退场闪空）。
            var lastToast by remember { mutableStateOf("") }
            LaunchedEffect(store.toast) {
                if (store.toast != null) lastToast = store.toast ?: lastToast
            }
            AnimatedVisibility(
                visible = store.toast != null,
                enter = fadeIn(WandMotion.tweenFast()) +
                    slideInVertically(WandMotion.tweenNormal()) { -it / 2 },
                exit = fadeOut(WandMotion.tweenNormal()),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                // 深色玻璃胶囊：采样背后内容微透，比纯黑底更通透。
                val toastGlass = WandGlass.regular.copy(
                    tint = Color.Black,
                    tintAlpha = 0.62f,
                    fallbackAlpha = 0.78f,
                    rimLight = Color.White.copy(alpha = 0.28f),
                    rimShade = Color.White.copy(alpha = 0.06f),
                )
                Text(
                    store.toast ?: lastToast,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .glassSurface(glassBackdrop, CircleShape, toastGlass)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
    }
}

@Composable
private fun QuietTopIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp),
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = WandColors.textSecondary,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** 顶栏左侧 provider 小徽标：品牌色弱底圆角方块 + 品牌 logo，标明当前 Claude / Codex。 */
@Composable
private fun ChatProviderBadge(provider: String?) {
    val isCodex = provider == "codex"
    val tint = if (isCodex) WandColors.info else WandColors.brand
    val background = if (isCodex) WandColors.infoSoft else WandColors.brandSoft
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(1.dp, tint.copy(alpha = 0.24f), RoundedCornerShape(8.dp)),
    ) {
        Icon(
            if (isCodex) BrandLogos.codex else BrandLogos.claude,
            contentDescription = if (isCodex) "Codex" else "Claude",
            tint = tint,
            modifier = Modifier.size(15.dp),
        )
    }
}

/** 最新一条非空用户消息（顶栏标题，对齐 iOS latestUserMessage）。 */
private fun latestUserMessage(messages: List<ConversationTurn>): String? {
    for (turn in messages.asReversed()) {
        if (turn.role != "user") continue
        val text = turn.content
            .filterIsInstance<ContentBlock.Text>()
            .joinToString(" ") { it.text }
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (text.isNotEmpty()) return text
    }
    return null
}

/** 中间截断（对齐 iOS .truncationMode(.middle)，Compose 没有内置实现）。 */
private fun middleTruncate(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val head = (maxChars - 1) / 2
    val tail = maxChars - 1 - head
    return text.take(head) + "…" + text.takeLast(tail)
}

@Composable
private fun HistorySummaryStrip(
    count: Int,
    preview: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(WandColors.surfaceSoft)
            .border(1.dp, WandColors.border, CircleShape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(
            WandIcons.expand,
            contentDescription = null,
            tint = WandColors.textMuted,
            modifier = Modifier
                .size(15.dp)
                .graphicsLayer { rotationZ = if (expanded) 90f else -90f },
        )
        Text(
            "已收起 $count 段上文",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = WandColors.textSecondary,
            maxLines = 1,
        )
        if (preview.isNotBlank()) {
            Text(
                preview,
                fontSize = 12.sp,
                color = WandColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
        Text(
            if (expanded) "收起" else "展开",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = WandColors.brand,
        )
    }
}

@Composable
private fun InlineHistoryChip(
    count: Int,
    expanded: Boolean = false,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = modifier
            .widthIn(max = 132.dp)
            .clip(CircleShape)
            .background(WandColors.surfaceSoft)
            .border(1.dp, WandColors.border, CircleShape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        Icon(
            WandIcons.expand,
            contentDescription = null,
            tint = WandColors.textMuted,
            modifier = Modifier
                .size(13.dp)
                .graphicsLayer { rotationZ = if (expanded) 90f else -90f },
        )
        Text(
            if (expanded) "收起上文" else "已收起 $count 段",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = WandColors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PinnedLatestContextBar(
    historyCount: Int,
    showHistoryChip: Boolean,
    userText: String,
    replyPreview: String,
    onHistoryToggle: () -> Unit,
    onReplyToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(7.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(WandColors.bgElevated)
            .border(1.dp, WandColors.border.copy(alpha = 0.72f), RoundedCornerShape(18.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (showHistoryChip) {
                InlineHistoryChip(
                    count = historyCount,
                    onToggle = onHistoryToggle,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            PinnedUserBubble(
                text = userText,
                modifier = Modifier.weight(1f),
            )
        }
        PinnedReplyHeader(
            preview = replyPreview,
            onToggle = onReplyToggle,
        )
    }
}

@Composable
private fun PinnedUserBubble(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(
            text.ifBlank { "用户消息" },
            fontSize = 14.sp,
            lineHeight = 19.sp,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = WandShapes.radiusLg,
                        topEnd = WandShapes.radiusLg,
                        bottomStart = WandShapes.radiusLg,
                        bottomEnd = 6.dp,
                    ),
                )
                .background(WandColors.brand)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun PinnedReplyHeader(
    preview: String,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(WandShapes.full)
            .clickableWithoutRipple { onToggle() }
            .padding(vertical = 3.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(WandColors.brand.copy(alpha = 0.14f)),
        ) {
            Icon(
                WandIcons.sparkle,
                contentDescription = null,
                tint = WandColors.brand,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            "Wand",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = WandColors.textPrimary,
        )
        Text(
            preview.ifBlank { "已展开全文，点此收起" },
            fontSize = 12.sp,
            color = WandColors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            WandIcons.expand,
            contentDescription = "收起回复",
            tint = WandColors.textSecondary,
            modifier = Modifier
                .size(15.dp)
                .graphicsLayer { rotationZ = 180f },
        )
    }
}

private fun turnPlainText(turn: ConversationTurn?): String {
    return turn?.content
        ?.filterIsInstance<ContentBlock.Text>()
        ?.joinToString(" ") { it.text }
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        .orEmpty()
}

private fun historyPreview(messages: List<ConversationTurn>, beforeTurnIndex: Int): String {
    if (beforeTurnIndex <= 0) return ""
    for (turn in messages.take(beforeTurnIndex).asReversed()) {
        val text = turn.content
            .filterIsInstance<ContentBlock.Text>()
            .joinToString(" ") { it.text }
            .trim()
            .replace(Regex("\\s+"), " ")
        if (text.isNotEmpty()) return text
    }
    return ""
}

private fun messageItemKey(item: MessageDisplayItem): String = when (item) {
    is MessageDisplayItem.Turn -> "turn-${item.index}"
    is MessageDisplayItem.Exploration -> "explore-${item.lastTurnIndex}"
}

/** 空结构化会话的居中启动卡：首条消息前显示模型/思考深度，发送后自然消失。 */
@Composable
private fun SessionLaunchPanel(store: ChatStore, showSettings: Boolean) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 360.dp)
                .glassCard(RoundedCornerShape(24.dp))
                .padding(horizontal = 20.dp, vertical = 24.dp),
        ) {
            // 标记 + 标题 + 副标题收成一个更紧凑的头部单元（彼此间距小），
            // 与下方设置组之间留出更大的呼吸位，强化「品牌头 → 操作区」的层次。
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                WandBrandMark(size = 56)
                Text(
                    store.snapshot?.providerLabel ?: "结构化会话",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = WandColors.textPrimary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "输入消息，让它帮你完成任务",
                    fontSize = 12.5.sp,
                    lineHeight = 19.sp,
                    color = WandColors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            if (showSettings) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(WandColors.surfaceSoft.copy(alpha = 0.72f))
                        .border(1.dp, WandColors.border, RoundedCornerShape(16.dp)),
                ) {
                    LaunchSettingPicker(
                        icon = WandIcons.tune,
                        label = "模型",
                        value = modelDisplayLabel(store, store.selectedModel),
                        options = buildList {
                            add(null to "默认 · ${modelDisplayLabel(store, null)}")
                            store.availableModels
                                .filter { it.id != "default" }
                                .forEach { add(it.id to it.label) }
                        },
                        selected = store.selectedModel?.takeUnless { it == "default" },
                        onSelect = store::setModel,
                    )
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = WandColors.border,
                        modifier = Modifier.padding(start = 60.dp),
                    )
                    LaunchSettingPicker(
                        icon = WandIcons.thinking,
                        label = "思考深度",
                        value = thinkingLabel(store.thinkingEffort),
                        options = THINKING_LEVELS.map { it.id to it.menuLabel },
                        selected = store.thinkingEffort,
                        onSelect = { it?.let(store::chooseThinkingEffort) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LaunchSettingPicker(
    icon: ImageVector,
    label: String,
    value: String,
    options: List<Pair<String?, String>>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 13.dp),
        ) {
            // 左侧品牌色图标片，让两行各有清晰的身份（模型 / 思考深度）。
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(WandColors.brandSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = WandColors.brand,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WandColors.textMuted,
                )
                Text(
                    value,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = WandColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                WandIcons.chevronRight,
                contentDescription = "选择$label",
                tint = WandColors.textMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            ModalBottomSheet(
                onDismissRequest = { expanded = false },
                containerColor = WandColors.bgElevated,
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
                ) {
                    Text(
                        "选择$label",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = WandColors.textPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                    options.forEach { (id, optionLabel) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (selected == id) WandColors.brandSoft else Color.Transparent,
                                )
                                .clickable {
                                    onSelect(id)
                                    expanded = false
                                }
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                        ) {
                            Text(
                                optionLabel,
                                fontSize = 14.sp,
                                fontWeight = if (selected == id) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected == id) WandColors.brand else WandColors.textPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected == id) {
                                Icon(
                                    WandIcons.check,
                                    contentDescription = "当前选中",
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

private data class ThinkingLevel(
    val id: String,
    val label: String,
    val shortLabel: String,
    val menuLabel: String,
)

/** 思考深度档位（对齐 iOS thinkingLevels / 服务端 thinking-effort 端点）。 */
private val THINKING_LEVELS = listOf(
    ThinkingLevel("off", "关闭", "关", "关闭"),
    ThinkingLevel("standard", "低", "低", "低（think）"),
    ThinkingLevel("deep", "中", "中", "中（think hard）"),
    ThinkingLevel("max", "高", "高", "高（ultrathink）"),
)

private fun thinkingLabel(id: String): String =
    THINKING_LEVELS.firstOrNull { it.id == id }?.label ?: "关闭"

private fun thinkingShortLabel(id: String): String =
    THINKING_LEVELS.firstOrNull { it.id == id }?.shortLabel ?: "关"

private fun modelDisplayLabel(store: ChatStore, id: String?): String {
    val effectiveId = id?.takeIf { it != "default" } ?: store.defaultModel
    if (effectiveId.isNullOrBlank()) {
        return store.availableModels.firstOrNull { it.id == "default" }?.label ?: "跟随服务端默认"
    }
    return store.availableModels.firstOrNull { it.id == effectiveId }?.label ?: effectiveId
}

/** 执行模式档位（对齐 iOS sessionModes / NewSessionView）。codex 锁 full-access。 */
private val SESSION_MODES = listOf(
    "managed" to "托管",
    "full-access" to "全权限",
    "auto-edit" to "自动编辑",
    "default" to "标准",
    "native" to "原生",
)

private fun modeLabel(id: String): String =
    SESSION_MODES.firstOrNull { it.first == id }?.second ?: "标准"

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
    return "$model · ${thinkingShortLabel(store.thinkingEffort)}"
}

@Composable
private fun SettingsMenuOption(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, fontSize = 13.sp, color = WandColors.textPrimary) },
        leadingIcon = {
            if (selected) {
                Icon(
                    WandIcons.check,
                    contentDescription = "当前选中",
                    tint = WandColors.brand,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Spacer(modifier = Modifier.size(16.dp))
            }
        },
        onClick = onClick,
    )
}

/** 从 content Uri 读出 (文件名, 字节)，供 multipart 上传。 */
internal fun readAttachment(context: Context, uri: Uri): Pair<String, ByteArray> {
    var name = "attachment"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) {
            cursor.getString(index)?.takeIf { it.isNotEmpty() }?.let { name = it }
        }
    }
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw WandApiException(null, "无法读取 $name")
    return name to bytes
}

internal fun buildAttachmentPrompt(attachments: List<UploadedFile>, body: String): String {
    if (attachments.isEmpty()) return body
    val paths = attachments.joinToString("\n") { it.savedPath }
    return "[附件已上传，请查看以下文件:\n$paths\n]\n\n$body"
}

@Composable
internal fun PendingAttachmentsPreview(
    attachments: List<UploadedFile>,
    baseUrl: String,
    onRemove: (UploadedFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        attachments.forEach { file ->
            Box {
                if (baseUrl.isNotBlank() && WandImage.isImagePath(file.savedPath)) {
                    WandAsyncImage(
                        path = file.savedPath,
                        baseUrl = baseUrl,
                        modifier = Modifier.size(width = 96.dp, height = 72.dp),
                        maxWidth = 96,
                        maxHeight = 72,
                    )
                } else {
                    WandFileChip(
                        path = file.savedPath,
                        modifier = Modifier.widthIn(max = 190.dp),
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(WandColors.surface.copy(alpha = 0.92f))
                        .border(1.dp, WandColors.border, CircleShape)
                        .clickable { onRemove(file) },
                ) {
                    Icon(
                        WandIcons.close,
                        contentDescription = "移除附件",
                        tint = WandColors.textSecondary,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RespondingIndicator(taskTitle: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        StatusDot("running")
        Text(
            taskTitle ?: "正在思考…",
            fontSize = 13.sp,
            color = WandColors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 排队消息条（对位 Web 端 queue-bar）：折叠态显示「已排队 N 条」+ 操作按钮；
 * 展开后逐条列出，每条带「立即发送 ⚡」「删除 ×」，外加底部「全部清空」。
 * promote 的中断/preserveQueue 语义在 ChatStore.promoteQueued 里按 inFlight 自动决定。
 */
@Composable
private fun QueueBar(store: ChatStore, backdrop: GlassBackdrop) {
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
            .padding(horizontal = 10.dp, vertical = 6.dp),
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
                .padding(horizontal = 4.dp, vertical = 2.dp),
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
    backdrop: GlassBackdrop,
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 玻璃化：容器本身透明（消息从输入药丸的缝隙间滚过），
            // 各子元素自带玻璃表面。
            // 先垫系统导航栏，再垫输入法：键盘弹起时输入栏精确贴在键盘上方 ——
            // 这就是 WebView 时代键盘重叠问题的原生解法。padding 链一字不动。
            .navigationBarsPadding()
            .imePadding()
            .padding(bottom = 4.dp),
    ) {
        // 待办进度条：当前 turn 有未完成 todos 时悬浮在输入栏上方（对齐 Web todo-progress）。
        val todos = remember(store.messages) { currentTodos(store.messages) }
        if (todos.isNotEmpty()) {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                TodoProgressBar(todos, backdrop)
            }
        }
        // 权限审批卡：底部滑入 + 淡入；退场期间用缓存内容避免闪空。
        val hasPermission = store.pendingEscalation != null || store.legacyPermissionPrompt != null
        var cachedEscalation by remember { mutableStateOf<EscalationRequest?>(null) }
        var cachedLegacy by remember { mutableStateOf<PermissionRequestInfo?>(null) }
        LaunchedEffect(store.pendingEscalation, store.legacyPermissionPrompt) {
            if (store.pendingEscalation != null) cachedEscalation = store.pendingEscalation
            if (store.legacyPermissionPrompt != null) cachedLegacy = store.legacyPermissionPrompt
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
        // 语音输入模式：轻点话筒进入，整个输入框变成「按住说话」面板（转屏后保留）。
        var voiceMode by rememberSaveable { mutableStateOf(false) }
        InputBar(
            backdrop = backdrop,
            store = store,
            draft = draft,
            onDraftChange = onDraftChange,
            voice = voice,
            voiceMode = voiceMode,
            onVoiceModeChange = { voiceMode = it },
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

@Composable
private fun InputBar(
    backdrop: GlassBackdrop,
    store: ChatStore,
    draft: String,
    onDraftChange: (String) -> Unit,
    voice: VoiceInputController,
    voiceMode: Boolean,
    onVoiceModeChange: (Boolean) -> Unit,
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
    // 从语音模式轻点切回键盘时自动聚焦文本框，键盘直接弹起。
    val focusRequester = remember { FocusRequester() }
    var focusAfterExit by remember { mutableStateOf(false) }
    // 发送后保持输入框焦点：避免权限卡/todo bar 插入时 @FocusState 丢焦点、键盘收起，
    // 用户连续对话时不需要再点一次输入框（对位 iOS ChatView.sendDraft 末尾的 inputFocused = true）。
    var refocusAfterSend by remember { mutableStateOf(false) }
    // 停止任务二次确认弹窗开关：点停止按钮先弹确认，避免误触中断正在跑的任务。
    var showStopConfirm by remember { mutableStateOf(false) }
    // 文本框是否聚焦：驱动「胶囊 ↔ 卡片」两态切换（对齐 Codex App）。
    var isFocused by remember { mutableStateOf(false) }
    LaunchedEffect(voiceMode, focusAfterExit) {
        if (!voiceMode && focusAfterExit) {
            focusAfterExit = false
            runCatching { focusRequester.requestFocus() }
        }
    }
    LaunchedEffect(refocusAfterSend, voiceMode, store.sessionEnded) {
        // 语音模式 / 会话已结束时不抢焦点；这两种情况本来就用不到键盘。
        if (refocusAfterSend && !voiceMode && !store.sessionEnded) {
            refocusAfterSend = false
            runCatching { focusRequester.requestFocus() }
        }
    }
    // 展开态（聚焦 / 语音模式 / 有草稿）：长成卡片，底部多一条控制行；否则收成单行胶囊。
    val expanded = isFocused || voiceMode || draft.isNotBlank() || pendingAttachments.isNotEmpty()
    LaunchedEffect(expanded) {
        onExpandedChange(expanded)
    }
    // 顶部内容：键盘模式是自增高文本框，语音模式是「按住说话」面板。背景/描边交给外层卡片。
    val inputContent: @Composable RowScope.() -> Unit = {
        Column(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 34.dp)
                .then(
                    if (!expanded) {
                        Modifier.clickableWithoutRipple { runCatching { focusRequester.requestFocus() } }
                    } else {
                        Modifier
                    },
                ),
        ) {
            if (pendingAttachments.isNotEmpty() && !voiceMode) {
                PendingAttachmentsPreview(
                    attachments = pendingAttachments,
                    baseUrl = baseUrl,
                    onRemove = onRemoveAttachment,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
                )
            }
            if (voiceMode) {
                VoiceHoldField(
                    draft = draft,
                    voice = voice,
                    onMicDown = onMicDown,
                    onExitVoiceMode = {
                        focusAfterExit = true
                        onVoiceModeChange(false)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
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
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
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
                                        if (store.messages.isEmpty()) "发消息…" else "跟进",
                                        fontSize = 16.sp,
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
                            .heightIn(min = 34.dp, max = 132.dp)
                            .focusRequester(focusRequester)
                            .onFocusChanged { isFocused = it.isFocused },
                    )
                }
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
    val mic: @Composable () -> Unit = {
        VoiceMicButton(
            voice = voice,
            voiceMode = voiceMode,
            onToggleMode = { onVoiceModeChange(!voiceMode) },
            onMicDown = onMicDown,
        )
    }
    val trailing: @Composable () -> Unit = {
        TrailingSendStop(
            backdrop = backdrop,
            store = store,
            canSend = canSend,
            onStop = { showStopConfirm = true },
            onSend = {
                onSend()
                refocusAfterSend = true
            },
        )
    }

    NativeComposerSurface(
        backdrop = backdrop,
        expanded = expanded,
        onFocusInput = {
            if (!voiceMode) runCatching { focusRequester.requestFocus() }
        },
        collapsedLeading = { plusMenu() },
        inputContent = { inputContent() },
        collapsedTrailing = {
            mic()
            trailing()
        },
        expandedControls = { controlsCompact ->
            // 控制行：+ / 模式徽标 / 模型·思考徽标 / 话筒 / 发送·停止。
            // 窄屏时退成图标芯片，右侧按钮始终保留固定空间。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
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
            mic()
            trailing()
        },
    )
    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            containerColor = WandColors.surface,
            title = {
                Text("停止任务", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            },
            text = {
                Text("确定要停止当前正在运行的任务吗？", fontSize = 13.sp)
            },
            confirmButton = {
                TextButton(onClick = {
                    showStopConfirm = false
                    store.stopResponding()
                }) {
                    Text("停止", color = WandColors.danger, fontSize = 13.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirm = false }) {
                    Text("取消", color = WandColors.textMuted, fontSize = 13.sp)
                }
            },
        )
    }
}

/**
 * 发送 / 停止按钮组（对齐 iOS trailingButtons）：
 * - 运行中且无草稿 → 唯一按钮是黑底停止（对齐 Codex collapsed composer）；
 * - 有草稿 → 发送按钮（运行中时左侧追加一个红色停止，可一边排队一边停）。
 */
@Composable
private fun TrailingSendStop(
    backdrop: GlassBackdrop,
    store: ChatStore,
    canSend: Boolean,
    onStop: () -> Unit,
    onSend: () -> Unit,
) {
    if (store.isResponding && !canSend) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(WandColors.textPrimary)
                .border(0.5.dp, WandColors.border.copy(alpha = 0.25f), CircleShape)
                .clickable(onClick = onStop),
        ) {
            Icon(
                WandIcons.stop,
                contentDescription = "停止任务",
                tint = WandColors.surface,
                modifier = Modifier.size(15.dp),
            )
        }
        return
    }
    if (store.isResponding) {
        ComposerIconButton(
            backdrop = backdrop,
            style = WandGlass.accent.copy(tint = WandColors.danger),
            enabled = true,
            onClick = onStop,
        ) {
            Icon(
                WandIcons.stop,
                contentDescription = "停止任务",
                tint = Color.White,
                modifier = Modifier.size(15.dp),
            )
        }
    }
    ComposerIconButton(
        backdrop = backdrop,
        style = if (canSend) {
            WandGlass.accent.copy(
                tint = WandColors.textPrimary,
                tintAlpha = 0.92f,
                fallbackAlpha = 1f,
                shadowElevation = 0.dp,
            )
        } else {
            WandGlass.clear.copy(
                tint = WandColors.textMuted,
                tintAlpha = 0.10f,
                fallbackAlpha = 0.14f,
                shadowElevation = 0.dp,
            )
        },
        enabled = canSend,
        onClick = onSend,
    ) {
        Icon(
            WandIcons.arrowUp,
            contentDescription = "发送",
            tint = if (canSend) WandColors.surface else WandColors.textMuted.copy(alpha = 0.55f),
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 控制行通用胶囊徽标：图标 + 文字 + 弱色底 + 下拉箭头。 */
@Composable
private fun ControlChip(
    icon: ImageVector,
    text: String,
    tint: Color,
    enabled: Boolean = true,
    showText: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.22f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (showText) 9.dp else 8.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
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
            Icon(WandIcons.expand, contentDescription = null, tint = tint.copy(alpha = 0.7f), modifier = Modifier.size(12.dp))
        }
    }
}

/** 控制行下拉菜单内的分组标题（模型 / 思考深度）。 */
@Composable
private fun MenuSectionHeader(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = WandColors.textMuted,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 4.dp),
    )
}

/** 执行模式徽标 + 下拉菜单（中途切换 managed/full-access/...）。codex 锁 full-access。 */
@Composable
private fun ModeChip(store: ChatStore, compact: Boolean = false) {
    val isCodex = store.snapshot?.provider == "codex"
    var open by remember { mutableStateOf(false) }
    // 高权限模式（托管 / 全权限）用橙色提示，其余用次要色。
    val tint = if (store.mode == "full-access" || store.mode == "managed")
        WandColors.warning else WandColors.textSecondary
    Box {
        ControlChip(
            icon = WandIcons.permission,
            text = modeLabel(store.mode),
            tint = tint,
            enabled = !isCodex,
            showText = !compact,
        ) { open = true }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = WandColors.surface,
        ) {
            SESSION_MODES.forEach { (id, label) ->
                SettingsMenuOption(label, selected = store.mode == id) {
                    store.chooseMode(id)
                    open = false
                }
            }
        }
    }
}

/** 模型 · 思考深度合并徽标 + 下拉菜单（对齐 iOS modelThinkingChip）。 */
@Composable
private fun ModelThinkingChip(
    store: ChatStore,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val tint = when (store.thinkingEffort) {
        "standard" -> Color(0xFF5D8A66)
        "deep" -> WandColors.warning
        "max" -> WandColors.danger
        else -> WandColors.brand
    }
    Box(modifier = modifier) {
        ControlChip(
            icon = WandIcons.tune,
            text = modelThinkingText(store),
            tint = tint,
            showText = !compact,
            modifier = if (compact) Modifier.widthIn(max = 112.dp) else Modifier,
        ) { open = true }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = WandColors.surface,
        ) {
            MenuSectionHeader("模型")
            SettingsMenuOption(
                "默认 · ${modelDisplayLabel(store, null)}",
                selected = store.selectedModel == null || store.selectedModel == "default",
            ) {
                store.setModel(null)
                open = false
            }
            store.availableModels.filter { it.id != "default" }.forEach { model ->
                SettingsMenuOption(model.label, selected = store.selectedModel == model.id) {
                    store.setModel(model.id)
                    open = false
                }
            }
            HorizontalDivider(color = WandColors.border)
            MenuSectionHeader("思考深度")
            THINKING_LEVELS.forEach { level ->
                SettingsMenuOption(level.menuLabel, selected = store.thinkingEffort == level.id) {
                    store.chooseThinkingEffort(level.id)
                    open = false
                }
            }
        }
    }
}

/**
 * 输入栏左侧「更多操作」按钮（对齐 iOS composerActionsMenu）：
 * 圆形 + 号，点开菜单「从相册选择 / 从文件选择」两项；上传中显示转圈。
 */
@Composable
internal fun ComposerActionsMenu(
    backdrop: GlassBackdrop,
    uploading: Boolean,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .clickable(enabled = !uploading) { open = true },
        ) {
            if (uploading) {
                CircularProgressIndicator(
                    color = WandColors.textSecondary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                // 卡片内的「+」走极简：无玻璃圆底，仅图标（对齐 Codex / iOS）。
                Icon(
                    WandIcons.add,
                    contentDescription = "更多操作",
                    tint = WandColors.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = WandColors.surface,
        ) {
            DropdownMenuItem(
                text = { Text("从相册选择", fontSize = 13.sp, color = WandColors.textPrimary) },
                leadingIcon = {
                    Icon(
                        WandIcons.attach,
                        contentDescription = null,
                        tint = WandColors.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                },
                onClick = {
                    open = false
                    onPickPhoto()
                },
            )
            DropdownMenuItem(
                text = { Text("从文件选择", fontSize = 13.sp, color = WandColors.textPrimary) },
                leadingIcon = {
                    Icon(
                        WandIcons.attach,
                        contentDescription = null,
                        tint = WandColors.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                },
                onClick = {
                    open = false
                    onPickFile()
                },
            )
        }
    }
}

// MARK: - 按住说话（端侧语音识别）

/** 识别文本追加进草稿（不覆盖已有内容，对齐 Web commitVoiceTranscript / iOS appendTranscriptToDraft）。 */
internal fun appendVoiceText(existing: String, text: String): String {
    val clean = text.trim()
    if (clean.isEmpty()) return existing
    val base = existing.trimEnd()
    return if (base.isEmpty()) clean else "$base $clean"
}

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

/**
 * 麦克风按钮：
 * - 轻点 → 切换语音输入模式（整个输入框变成「按住说话」面板，图标变键盘）；
 * - 长按 → 立即按住说话（原交互）：按住录音、上滑取消、松手把识别文本追加进输入框。
 */
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
    // 对齐 iOS micButton：32dp 圆形嵌输入框右下角，平时品牌弱底，按住实底（取消态红）。
    val background = when {
        voice.pressed && voice.canceling -> WandColors.danger
        voice.pressed -> WandColors.brand
        else -> WandColors.brand.copy(alpha = 0.12f)
    }
    val scale by animateFloatAsState(
        if (voice.pressed) 1.1f else 1f,
        WandMotion.tweenFast(),
        label = "micScale",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(32.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(background)
            .pointerInput(voice) {
                voiceTapOrHoldGesture(
                    voice = voice,
                    onTap = { currentOnToggle() },
                    onHoldStart = { currentOnMicDown() },
                )
            },
    ) {
        Icon(
            if (voiceMode && !voice.pressed) WandIcons.keyboard else WandIcons.mic,
            contentDescription = if (voiceMode) "切回键盘输入" else "轻点切语音模式，长按说话",
            tint = if (voice.pressed) Color.White else WandColors.brand,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 语音模式下替换文本框的「按住说话」面板：
 * 按住录音（同话筒长按），轻点切回键盘输入；非录音时显示当前草稿，所见即所得。
 */
@Composable
internal fun VoiceHoldField(
    draft: String,
    voice: VoiceInputController,
    onMicDown: () -> Unit,
    onExitVoiceMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnExit by rememberUpdatedState(onExitVoiceMode)
    val currentOnMicDown by rememberUpdatedState(onMicDown)
    // 背景/描边交给外层输入卡片；这里只在按住时叠一层淡淡的状态色罩。
    val stateTint by androidx.compose.animation.animateColorAsState(
        when {
            voice.pressed && voice.canceling -> WandColors.danger.copy(alpha = 0.18f)
            voice.pressed -> WandColors.brand.copy(alpha = 0.14f)
            else -> Color.Transparent
        },
        WandMotion.tweenFast(),
        label = "voiceFieldBg",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(stateTint)
            .pointerInput(voice) {
                voiceTapOrHoldGesture(
                    voice = voice,
                    onTap = { currentOnExit() },
                    onHoldStart = { currentOnMicDown() },
                )
            }
            .padding(start = 8.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
    ) {
        when {
            voice.pressed && voice.canceling -> Text(
                "松开手指，取消输入",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = WandColors.danger,
            )
            voice.pressed -> Text(
                "松开结束 · 上滑取消",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = WandColors.brand,
            )
            draft.isBlank() -> Text(
                "按住说话",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = WandColors.textSecondary,
            )
            else -> Text(
                draft,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                color = WandColors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 按住期间的实时转写气泡：覆盖式文本 + 引擎标签 + 上滑取消提示。 */
@Composable
internal fun VoiceTranscriptBubble(backdrop: GlassBackdrop, voice: VoiceInputController) {
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
                        .padding(horizontal = 6.dp, vertical = 1.dp),
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
    AlertDialog(
        onDismissRequest = { if (state !is SttModelManager.State.Downloading) onDismiss() },
        containerColor = WandColors.surface,
        title = {
            Text(
                "下载本地语音模型",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = WandColors.textPrimary,
            )
        },
        text = {
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
        },
        confirmButton = {
            when (state) {
                is SttModelManager.State.Downloading -> TextButton(onClick = { SttModelManager.cancelDownload() }) {
                    Text("取消下载", color = WandColors.danger, fontSize = 13.sp)
                }
                is SttModelManager.State.Ready -> TextButton(onClick = onDismiss) {
                    Text("知道了", color = WandColors.brand, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                else -> TextButton(onClick = { SttModelManager.startDownload(context) }) {
                    Text(
                        if (state is SttModelManager.State.Failed) "重试" else "下载",
                        color = WandColors.brand,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
        dismissButton = {
            if (state is SttModelManager.State.Idle || state is SttModelManager.State.Failed) {
                TextButton(onClick = onDismiss) {
                    Text("暂不", color = WandColors.textMuted, fontSize = 13.sp)
                }
            }
        },
    )
}

private fun formatMb(bytes: Long): String = "%.1f MB".format(bytes / 1024.0 / 1024.0)

/** 输入栏操作按钮：38dp 玻璃圆钮（对齐 iOS 停止/发送圆钮），保留按压缩放反馈。 */
@Composable
private fun ComposerIconButton(
    backdrop: GlassBackdrop,
    style: GlassStyle,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.92f else 1f,
        WandMotion.tweenFast(),
        label = "composerBtnScale",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(38.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .glassSurface(backdrop, CircleShape, style)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
    ) {
        content()
    }
}
