package com.wand.app.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.SessionWatcher
import com.wand.app.data.EscalationRequest
import com.wand.app.data.PermissionRequestInfo
import com.wand.app.data.WandApi
import com.wand.app.speech.SherpaSpeechEngine
import com.wand.app.speech.SttModelManager
import com.wand.app.speech.VoiceInputController
import com.wand.app.ui.ChatStore
import com.wand.app.ui.QuickCommitStore
import com.wand.app.ui.components.LoadingState
import com.wand.app.ui.components.ErrorState
import com.wand.app.ui.components.StatusBadge
import com.wand.app.ui.components.StatusDot
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    var followsLatest by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val isListDragged by listState.interactionSource.collectIsDraggedAsState()
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

    // 用户开始拖动后立即暂停跟随，避免流式更新把历史阅读位置抢回底部。
    LaunchedEffect(isListDragged) {
        if (isListDragged) followsLatest = false
    }

    // 监听完整消息列表而不是 size：流式回复会原地替换最后一条消息，数量不变。
    // 列表末尾有独立锚点，确保长消息增长时滚到真正底部而非最后一项顶部。
    val bottomIndex = store.messages.size + if (store.isResponding) 1 else 0
    LaunchedEffect(store.messages, store.isResponding, store.loading) {
        if (!store.loading && followsLatest) {
            listState.scrollToItem(bottomIndex)
        }
    }

    // Toast 自动消失。
    LaunchedEffect(store.toast) {
        if (store.toast != null) {
            delay(2_600)
            store.toast = null
        }
    }

    Scaffold(
        containerColor = WandColors.bgPrimary,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            store.snapshot?.displayTitle ?: "会话",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WandColors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val cwd = store.snapshot?.cwd
                        if (!cwd.isNullOrBlank()) {
                            Text(
                                cwdTail(cwd),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = WandColors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = WandColors.textSecondary,
                        )
                    }
                },
                actions = {
                    GitTopBarBadge(quickCommit) { quickCommit.openPanel() }
                    StatusBadge(
                        chatStatus(store),
                        modifier = Modifier.padding(end = 14.dp),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = WandColors.bgPrimary,
                ),
            )
        },
        bottomBar = { BottomBar(store, draft, onDraftChange = { draft = it }, voice = voice, onMicDown = onMicDown) {
            // 发送回调（带触感反馈）
            if (isHapticEnabled()) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            val text = draft
            draft = ""
            store.send(text)
        } },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when {
                store.loading -> LoadingState("正在加载会话…")
                store.loadError != null -> ErrorState(store.loadError ?: "加载失败")
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 14.dp, end = 14.dp, top = 8.dp, bottom = 6.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // key = index：流式原地替换最后一条时 key 不变不触发动画，
                        // 只有真正新增的消息才走 animateItem 淡入。
                        itemsIndexed(store.messages, key = { index, _ -> index }) { index, turn ->
                            Box(modifier = Modifier.animateItem()) {
                                TurnView(
                                    turn,
                                    isLastTurn = index == store.messages.lastIndex,
                                    isResponding = store.isResponding,
                                    askSelections = store.askUserSelections,
                                    onAskToggle = { toolUseId, qIdx, optIdx, multi ->
                                        store.toggleAskOption(toolUseId, qIdx, optIdx, multi)
                                    },
                                    onAskSubmit = { toolUseId, answerText ->
                                        store.submitAskUser(toolUseId, answerText)
                                    },
                                )
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
                    }
                }
            }

            // 回到底部按钮：淡入 + 缩放，替换硬切显隐。
            AnimatedVisibility(
                visible = !store.loading && store.loadError == null && !followsLatest,
                enter = fadeIn(WandMotion.tweenFast()) +
                    scaleIn(initialScale = 0.8f, animationSpec = WandMotion.tweenFast()),
                exit = fadeOut(WandMotion.tweenFast()) +
                    scaleOut(targetScale = 0.8f, animationSpec = WandMotion.tweenFast()),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 12.dp),
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        followsLatest = true
                        scrollScope.launch { listState.animateScrollToItem(bottomIndex) }
                    },
                    containerColor = WandColors.brand,
                    contentColor = Color.White,
                    shape = CircleShape,
                ) {
                    Icon(WandIcons.expand, contentDescription = "回到底部")
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
                Text(
                    store.toast ?: lastToast,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.78f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/**
 * 顶栏状态折算：派生态（重连/授权/思考）优先于服务端 status。
 * 结束态用 store.status（实时，WS ended 推送会更新）而不是 snapshot.status
 * （只在 REST 加载/恢复时刷新，可能还停在 running）。
 */
private fun chatStatus(store: ChatStore): String = when {
    !store.connected -> "reconnecting"
    store.permissionBlocked -> "permission"
    store.isResponding -> "thinking"
    store.sessionEnded -> store.status
    else -> "idle"
}

/** 取工作目录最后两段做顶栏副标题。 */
private fun cwdTail(cwd: String): String {
    val segments = cwd.trimEnd('/').split('/').filter { it.isNotEmpty() }
    return if (segments.size <= 2) {
        cwd
    } else {
        "…/" + segments.takeLast(2).joinToString("/")
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

// MARK: - 底部栏（权限卡 + 队列 + 输入框）

@Composable
private fun BottomBar(
    store: ChatStore,
    draft: String,
    onDraftChange: (String) -> Unit,
    voice: VoiceInputController,
    onMicDown: () -> Unit,
    onSend: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(WandColors.bgPrimary.copy(alpha = 0.97f))
            // 先垫系统导航栏，再垫输入法：键盘弹起时输入栏精确贴在键盘上方 ——
            // 这就是 WebView 时代键盘重叠问题的原生解法。
            .navigationBarsPadding()
            .imePadding()
            .padding(bottom = 4.dp),
    ) {
        // 待办进度条：当前 turn 有未完成 todos 时悬浮在输入栏上方（对齐 Web todo-progress）。
        val todos = remember(store.messages) { currentTodos(store.messages) }
        if (todos.isNotEmpty()) {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                TodoProgressBar(todos)
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
                )
            }
        }
        if (store.queuedMessages.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            ) {
                Icon(
                    WandIcons.history,
                    contentDescription = null,
                    tint = WandColors.textMuted,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    "已排队 ${store.queuedMessages.size} 条消息",
                    fontSize = 12.sp,
                    color = WandColors.textMuted,
                )
            }
        }
        if (store.sessionEnded) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
            ) {
                Text(
                    "会话已结束",
                    fontSize = 12.sp,
                    color = WandColors.textMuted,
                )
                Spacer(modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { store.resume() }) {
                    Text("恢复会话", fontSize = 13.sp, color = WandColors.brand)
                }
            }
        }
        // 按住说话实时转写气泡（按住期间悬浮在输入栏上方）。
        if (voice.pressed) {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                VoiceTranscriptBubble(voice)
            }
        }
        InputBar(store, draft, onDraftChange, voice, onMicDown, onSend)
    }
}

@Composable
private fun InputBar(
    store: ChatStore,
    draft: String,
    onDraftChange: (String) -> Unit,
    voice: VoiceInputController,
    onMicDown: () -> Unit,
    onSend: () -> Unit,
) {
    val canSend = draft.isNotBlank() && !store.sessionEnded
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor by androidx.compose.animation.animateColorAsState(
        if (focused) WandColors.brand.copy(alpha = 0.72f) else WandColors.border,
        WandMotion.tweenFast(),
        label = "composerBorder",
    )
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(WandColors.surface)
            .border(1.dp, borderColor, RoundedCornerShape(18.dp))
            .padding(start = 6.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
    ) {
        VoiceMicButton(voice, onMicDown)
        Spacer(modifier = Modifier.size(7.dp))
        BasicTextField(
            value = draft,
            onValueChange = onDraftChange,
            interactionSource = interaction,
            textStyle = TextStyle(
                fontSize = 15.sp,
                lineHeight = 20.sp,
                color = WandColors.textPrimary,
            ),
            cursorBrush = SolidColor(WandColors.brand),
            minLines = 1,
            maxLines = 5,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
            ),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (draft.isEmpty()) {
                        Text("输入消息…", fontSize = 15.sp, color = WandColors.textMuted)
                    }
                    innerTextField()
                }
            },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 34.dp, max = 108.dp)
                .padding(vertical = 6.dp),
        )
        if (store.isResponding) {
            ComposerIconButton(
                background = WandColors.dangerSoft,
                enabled = true,
                onClick = { store.stopResponding() },
            ) {
                Icon(
                    WandIcons.stop,
                    contentDescription = "停止回复",
                    tint = WandColors.danger,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(modifier = Modifier.size(5.dp))
        }
        ComposerIconButton(
            background = if (canSend) WandColors.brand else WandColors.surfaceSoft,
            enabled = canSend,
            onClick = onSend,
        ) {
            Icon(
                WandIcons.send,
                contentDescription = "发送",
                tint = if (canSend) Color.White else WandColors.textMuted,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

// MARK: - 按住说话（端侧语音识别）

/** 识别文本追加进草稿（不覆盖已有内容，对齐 Web commitVoiceTranscript / iOS appendTranscriptToDraft）。 */
private fun appendVoiceText(existing: String, text: String): String {
    val clean = text.trim()
    if (clean.isEmpty()) return existing
    val base = existing.trimEnd()
    return if (base.isEmpty()) clean else "$base $clean"
}

/** 麦克风按钮：按住录音、上滑取消、松手把识别文本追加进输入框。 */
@Composable
private fun VoiceMicButton(voice: VoiceInputController, onMicDown: () -> Unit) {
    val cancelThresholdPx = with(LocalDensity.current) { 60.dp.toPx() }
    val background = when {
        voice.pressed && voice.canceling -> WandColors.danger
        voice.pressed -> WandColors.brand
        else -> WandColors.surfaceSoft
    }
    val scale by animateFloatAsState(
        if (voice.pressed) 1.08f else 1f,
        WandMotion.tweenFast(),
        label = "micScale",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(34.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(11.dp))
            .background(background)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    onMicDown()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        voice.updateCancel(down.position.y - change.position.y > cancelThresholdPx)
                    }
                    voice.endPress()
                }
            },
    ) {
        Icon(
            WandIcons.mic,
            contentDescription = "按住说话",
            tint = if (voice.pressed) Color.White else WandColors.textSecondary,
            modifier = Modifier.size(17.dp),
        )
    }
}

/** 按住期间的实时转写气泡：覆盖式文本 + 引擎标签 + 上滑取消提示。 */
@Composable
private fun VoiceTranscriptBubble(voice: VoiceInputController) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(WandColors.surface)
            .border(
                1.dp,
                if (voice.canceling) WandColors.danger.copy(alpha = 0.55f) else WandColors.border,
                RoundedCornerShape(12.dp),
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
                            "（中文，${SttModelManager.DOWNLOAD_SIZE_LABEL}）后，" +
                            "语音识别完全在本机离线运行：不耗流量、语音内容不出设备。",
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

/** 输入器内操作按钮：紧凑圆角方块，保留按压缩放反馈。 */
@Composable
private fun ComposerIconButton(
    background: Color,
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
            .size(34.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(11.dp))
            .background(background)
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
