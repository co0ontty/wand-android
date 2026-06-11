package com.wand.app.ui.screens

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.SessionWatcher
import com.wand.app.data.EscalationRequest
import com.wand.app.data.PermissionRequestInfo
import com.wand.app.data.WandApi
import com.wand.app.ui.ChatStore
import com.wand.app.ui.QuickCommitStore
import com.wand.app.ui.components.LoadingState
import com.wand.app.ui.components.ErrorState
import com.wand.app.ui.components.StatusBadge
import com.wand.app.ui.components.StatusDot
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
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
        bottomBar = { BottomBar(store, draft, onDraftChange = { draft = it }) {
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
                            start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        // key = index：流式原地替换最后一条时 key 不变不触发动画，
                        // 只有真正新增的消息才走 animateItem 淡入。
                        itemsIndexed(store.messages, key = { index, _ -> index }) { index, turn ->
                            Box(modifier = Modifier.animateItem()) {
                                TurnView(
                                    turn,
                                    isLastTurn = index == store.messages.lastIndex,
                                    isResponding = store.isResponding,
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
            .padding(bottom = 8.dp),
    ) {
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
        InputBar(store, draft, onDraftChange, onSend)
    }
}

@Composable
private fun InputBar(
    store: ChatStore,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val canSend = draft.isNotBlank() && !store.sessionEnded
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text("发消息…", fontSize = 16.sp, color = WandColors.textMuted) },
            minLines = 1,
            maxLines = 5,
            shape = WandShapes.lg,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = WandColors.brand,
                unfocusedBorderColor = WandColors.border,
                focusedContainerColor = WandColors.surface,
                unfocusedContainerColor = WandColors.surface,
            ),
            modifier = Modifier.weight(1f),
        )
        if (store.isResponding) {
            CircleIconButton(
                background = WandColors.danger,
                onClick = { store.stopResponding() },
            ) {
                Icon(
                    WandIcons.stop,
                    contentDescription = "停止回复",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        CircleIconButton(
            background = if (canSend) WandColors.brand else WandColors.brand.copy(alpha = 0.35f),
            onClick = { if (canSend) onSend() },
        ) {
            Icon(
                WandIcons.send,
                contentDescription = "发送",
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 圆形操作按钮：按压时 0.92 缩放反馈。 */
@Composable
private fun CircleIconButton(
    background: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.92f else 1f,
        WandMotion.tweenFast(),
        label = "circleBtnScale",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(bottom = 4.dp)
            .size(40.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
    ) {
        content()
    }
}
