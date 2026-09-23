package com.wand.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.UploadedFile
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.WandApi
import com.wand.app.ui.QuickCommitStore
import com.wand.app.ui.SessionTitleStore
import com.wand.app.ui.applyProvisionalSessionTopic
import com.wand.app.ui.ptyComposerSubmitChunks
import com.wand.app.ui.sessionChromeTitle
import com.wand.app.ui.sessionTopicBlocklist
import com.wand.app.ui.components.TailMarqueePathText
import com.wand.app.ui.components.WandDetailBackButton
import com.wand.app.ui.components.WandDetailTopBar
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandSnackbarHost
import com.wand.app.ui.components.showWandNotice
import com.wand.app.ui.components.WandProviderMark
import com.wand.app.ui.components.WandProviderMarkVariant
import com.wand.app.speech.VoiceInputController
import com.wand.app.ui.theme.GlassBackdrop
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandGlass
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.WandTerminal
import com.wand.app.ui.theme.glassSurface
import com.wand.app.ui.theme.reduceMotionEnabled
import com.wand.app.ui.terminal.DefaultTerminalShortcuts
import com.wand.app.ui.terminal.TerminalModifier
import com.wand.app.ui.terminal.TerminalSpecialKeys
import com.wand.app.ui.terminal.TerminalShortcut
import com.wand.app.ui.terminal.NativePtyTerminal
import com.wand.app.ui.terminal.NativePtyTerminalSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

private val TerminalBackground = WandTerminal.background

/**
 * PTY 会话原生壳：顶部用原生头部（返回 + provider 徽标 + 标题/工作目录），
 * 中间使用原生 libvterm/Compose 终端，直接消费 WS PTY 输出和 Render 快照；
 * 底部保留原生快捷键与输入抽屉，完全不加载网页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PtyTerminalScreen(
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
    showBack: Boolean = true,
    onBack: () -> Unit,
) {
    var snapshot by remember(sessionId) { mutableStateOf<SessionSnapshot?>(null) }
    var snapshotResolved by remember(sessionId) { mutableStateOf(false) }
    var toast by remember(sessionId) { mutableStateOf<String?>(null) }
    val terminal = remember(api, sessionId) { NativePtyTerminal(api, sessionId) { toast = it } }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(terminal, lifecycleOwner) {
        var paused = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> paused = true
                Lifecycle.Event.ON_RESUME -> if (paused) {
                    paused = false
                    terminal.reconnect()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 底部快捷栏左端的拉手：折叠时只露出快捷键栏，展开时在上方滑出输入抽屉
    // （文本框 + 发送 + 按住说话）。默认折叠，给终端留出最大可视区，对称 iOS PtySessionView。
    var inputDrawerOpen by remember(sessionId) { mutableStateOf(false) }
    var draft by remember(sessionId) { mutableStateOf("") }
    var uploadingAttachments by remember(sessionId) { mutableStateOf(false) }
    var pendingAttachments by remember(sessionId) { mutableStateOf<List<UploadedFile>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val attachmentPickers = rememberAttachmentPickerActions { uris ->
        scope.launchAttachmentUpload(
            context = context,
            api = api,
            sessionId = sessionId,
            uris = uris,
            onUploadingChange = { uploadingAttachments = it },
            onUploaded = { uploaded -> pendingAttachments = (pendingAttachments + uploaded).takeLast(5) },
            onToast = { message -> toast = message },
        )
    }
    val voiceInput = rememberVoiceInputHandle(
        isHapticEnabled = isHapticEnabled,
        onToast = { message -> toast = message },
        onCommit = { text -> draft = appendVoiceText(draft, text) },
    )
    val shortcutQueue = remember(sessionId) {
        Channel<TerminalShortcut>(capacity = 12, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }
    val quickCommit = remember(sessionId) {
        QuickCommitStore(sessionId, api) { msg -> toast = msg }
    }
    DisposableEffect(quickCommit) {
        onDispose { quickCommit.shutdown() }
    }

    // 仅为顶栏拉一次会话快照（标题 / provider / 工作目录）；终端内容由 WS init 单独恢复。
    LaunchedEffect(sessionId) {
        snapshot = try {
            val current = api.getSession(sessionId)
            if (shouldResumePtyTerminal(current.status, current.sessionKind, current.claudeSessionId)) {
                try {
                    api.resumeSession(sessionId)
                } catch (error: Exception) {
                    toast = error.message ?: "终端会话恢复失败"
                    current
                }
            } else {
                current
            }
        } catch (_: Exception) {
            null
        }
        snapshotResolved = true
    }
    LaunchedEffect(terminal, shortcutQueue) {
        for (shortcut in shortcutQueue) {
            try {
                if (!terminal.send(shortcut.bytes, "android-${shortcut.id.take(64)}")) {
                    toast = "终端未就绪，按键未发送；请重试"
                }
            } catch (error: Exception) {
                toast = error.message ?: "终端按键发送失败"
            }
        }
    }
    QuickCommitStatusRefreshEffect(
        quickCommit = quickCommit,
        sessionId = sessionId,
        enabled = snapshotResolved,
    )

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(toast) {
        val message = toast ?: return@LaunchedEffect
        snackbarHostState.showWandNotice(message)
        if (toast == message) toast = null
    }

    fun sendPtyDraft() {
        val body = draft.trim()
        val attachments = pendingAttachments
        if (body.isEmpty() && attachments.isEmpty()) return
        val text = buildAttachmentPrompt(attachments, body).trim()
        val restore = draft
        draft = ""
        pendingAttachments = emptyList()
        val blockedTitles = sessionTopicBlocklist(
            taskName = taskName,
            workspaceName = workspaceName,
            cwd = snapshot?.cwd,
        )
        applyProvisionalSessionTopic(sessionId, text, blockedTitles)?.let { title ->
            snapshot = snapshot?.copy(title = title, titleGenerating = true)
        }
        scope.launch {
            try {
                // 对齐 Web sendTerminalChunks：文本段和单独的 "\r" 都带 enter_text，
                // 服务端据此把整段提交收成短标题，而不是把回车当成主题。
                // 两个 input 之间留一点间隔，避免服务端把文本和回车粘成一条。
                val chunks = ptyComposerSubmitChunks(text, "terminal")
                for ((index, chunk) in chunks.withIndex()) {
                    if (index > 0) delay(30)
                    if (!terminal.send(chunk.input, chunk.shortcutKey)) {
                        error("终端未就绪，输入未发送；请检查连接后重试")
                    }
                }
            } catch (error: Exception) {
                toast = error.message ?: "终端命令发送失败"
                if (draft.isEmpty()) {
                    draft = restore
                    pendingAttachments = attachments
                }
            }
        }
    }

    Scaffold(
        // 终端画布使用固定暗色底，原生 chrome 保持独立合成。
        containerColor = WandColors.bgPrimary,
        snackbarHost = { WandSnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                PtyTopBar(
                    backdrop = null,
                    sessionId = sessionId,
                    snapshot = snapshot,
                    serverDisplayName = serverDisplayName,
                    workspaceName = workspaceName,
                    taskName = taskName,
                    quickCommit = quickCommit,
                    showBack = showBack,
                    onBack = onBack,
                    onOpenQuickCommit = { quickCommit.openPanel() },
                )
                // 顶部「其他会话」快捷条：任务内展示同任务工作窗口；未分组会话展示同
                // 目录的兄弟终端（深色铬下跟随终端配色）。
                if (taskId != null && onSwitchSession != null && onCreateTaskSession != null) {
                    TaskSessionTabStrip(
                        api = api,
                        taskId = taskId,
                        currentSessionId = sessionId,
                        onSelect = onSwitchSession,
                        onCreated = onCreateTaskSession,
                        onDeleted = onDeleteTaskSession,
                        terminalChrome = true,
                    )
                } else if (taskId == null && onSwitchSession != null && siblingSessions.size >= 2) {
                    StandaloneSessionTabStrip(
                        sessions = siblingSessions,
                        currentSessionId = sessionId,
                        parentNames = listOfNotNull(workspaceName?.trim()?.takeIf { it.isNotEmpty() }),
                        onSelect = onSwitchSession,
                        terminalChrome = true,
                    )
                }
            }
        },
        bottomBar = {
            PtyBottomBar(
                backdrop = null,
                inputDrawerOpen = inputDrawerOpen,
                onToggleInputDrawer = { inputDrawerOpen = !inputDrawerOpen },
                draft = draft,
                onDraftChange = { draft = it },
                onSend = { sendPtyDraft() },
                uploadingAttachments = uploadingAttachments,
                pendingAttachments = pendingAttachments,
                baseUrl = api.baseUrl,
                onRemoveAttachment = { file ->
                    pendingAttachments = pendingAttachments.filterNot { it.savedPath == file.savedPath }
                },
                onPickPhoto = attachmentPickers.pickPhoto,
                onPickFile = attachmentPickers.pickFile,
                isHapticEnabled = isHapticEnabled,
                onShortcut = { shortcutQueue.trySend(it) },
                voice = voiceInput.voice,
                onMicDown = voiceInput.onMicDown,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
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
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(TerminalBackground),
            ) {
                NativePtyTerminalSurface(terminal, onTap = { inputDrawerOpen = true })
                if (terminal.loading.value) {
                    Box(Modifier.fillMaxSize().background(TerminalBackground),
                        contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = WandColors.brand, strokeWidth = 2.dp)
                    }
                } else if (!terminal.ready.value) {
                    Column(
                        Modifier.fillMaxSize().background(TerminalBackground),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("终端连接未就绪", color = WandTerminal.text)
                        Text(
                            "重试连接",
                            color = WandColors.brand,
                            modifier = Modifier.clickable { terminal.reconnect() }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                        )
                    }
                }
            }
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

internal fun shouldResumePtyTerminal(
    status: String?,
    sessionKind: String?,
    providerSessionId: String?,
): Boolean =
    (sessionKind ?: "pty") == "pty" &&
        status != "running" &&
        !providerSessionId.isNullOrBlank()

@Composable
private fun PtyTopBar(
    backdrop: GlassBackdrop?,
    sessionId: String,
    snapshot: SessionSnapshot?,
    serverDisplayName: String,
    workspaceName: String?,
    taskName: String?,
    quickCommit: QuickCommitStore,
    showBack: Boolean,
    onBack: () -> Unit,
    onOpenQuickCommit: () -> Unit,
) {
    WandDetailTopBar(
        title = "终端会话",
        backdrop = backdrop,
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
            WandProviderMark(provider = snapshot?.provider, variant = WandProviderMarkVariant.Tinted)
            Column(modifier = Modifier.weight(1f)) {
                ChatTopicTitle(
                    text = sessionChromeTitle(
                        title = snapshot?.title,
                        liveTitle = SessionTitleStore.titleOf(sessionId),
                        blockedTitles = sessionTopicBlocklist(
                            taskName = taskName,
                            workspaceName = workspaceName,
                            cwd = snapshot?.cwd,
                        ),
                        fallback = "终端会话",
                    ),
                    generating = snapshot?.titleGenerating == true ||
                        SessionTitleStore.isGenerating(sessionId),
                )
                val workspaceTitle = workspaceName?.trim().takeUnless { it.isNullOrEmpty() }
                TailMarqueePathText(
                    path = if (workspaceTitle != null) {
                        "$serverDisplayName · $workspaceTitle"
                    } else {
                        snapshot?.cwd?.takeIf { it.isNotBlank() }?.let {
                            "$serverDisplayName · $it"
                        } ?: serverDisplayName
                    },
                    fontSize = 11.sp,
                    color = WandColors.textMuted,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        actions = {
            GitChangesButton(quickCommit, compact = true) { onOpenQuickCommit() }
        },
    )
}

@Composable
private fun PtyBottomBar(
    backdrop: GlassBackdrop?,
    inputDrawerOpen: Boolean,
    onToggleInputDrawer: () -> Unit,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    uploadingAttachments: Boolean,
    pendingAttachments: List<UploadedFile>,
    baseUrl: String,
    onRemoveAttachment: (UploadedFile) -> Unit,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
    isHapticEnabled: () -> Boolean,
    onShortcut: (TerminalShortcut) -> Unit,
    voice: VoiceInputController,
    onMicDown: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val shortcutScroll = rememberScrollState()
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(
                    backdrop,
                    RoundedCornerShape(0.dp),
                    WandGlass.regular.copy(refractionHeight = 0.dp, shadowElevation = 0.dp),
                    edgeToEdge = true,
                )
                .imePadding()
                .navigationBarsPadding(),
        ) {
            AnimatedVisibility(
                visible = inputDrawerOpen,
                enter = if (reduceMotionEnabled()) {
                    EnterTransition.None
                } else {
                    fadeIn(WandMotion.tweenEnter()) +
                        expandVertically(WandMotion.tweenEnter(), expandFrom = Alignment.Top)
                },
                exit = if (reduceMotionEnabled()) {
                    ExitTransition.None
                } else {
                    fadeOut(WandMotion.tweenExit()) +
                        shrinkVertically(WandMotion.tweenExit(), shrinkTowards = Alignment.Top)
                },
            ) {
                PtyInputDrawer(
                    draft = draft,
                    onDraftChange = onDraftChange,
                    onSend = onSend,
                    uploading = uploadingAttachments,
                    pendingAttachments = pendingAttachments,
                    baseUrl = baseUrl,
                    onRemoveAttachment = onRemoveAttachment,
                    onPickPhoto = onPickPhoto,
                    onPickFile = onPickFile,
                    voice = voice,
                    onMicDown = onMicDown,
                )
            }
            // 把快捷键收进一个连续的工具托盘：输入入口固定在左侧，只有快捷键区域横向滚动。
            // 这样在窄屏上滑动查看按键时，输入抽屉不会跟着滚走，也不会显得像一排散落的按钮。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(WandColors.bgElevated.copy(alpha = 0.92f))
                    .border(
                        0.7.dp,
                        WandColors.border.copy(alpha = 0.8f),
                        RoundedCornerShape(18.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 输入入口保持固定，避免快捷键滚动后用户找不到发送文本的位置。
                InputDrawerHandle(open = inputDrawerOpen, onClick = onToggleInputDrawer)
                val edgeFade = WandColors.bgElevated.copy(alpha = 0.98f)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .drawWithContent {
                            drawContent()
                            // 滚动两端用托盘底色渐隐，提示这里还可以横向滑动。
                            val fadeWidth = 22.dp.toPx()
                            if (shortcutScroll.value > 0) {
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        0f to edgeFade,
                                        1f to Color.Transparent,
                                        startX = 0f,
                                        endX = fadeWidth,
                                    ),
                                )
                            }
                            if (shortcutScroll.value < shortcutScroll.maxValue) {
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        0f to Color.Transparent,
                                        1f to edgeFade,
                                        startX = size.width - fadeWidth,
                                        endX = size.width,
                                    ),
                                )
                            }
                        }
                        .horizontalScroll(shortcutScroll)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    // 按使用热度分三组：高频执行（Enter/↑/Tab）· 中断控制（Esc/Ctrl+C/Shift+Tab）
                    // · 光标导航（←/→/↓），组间用细竖线分隔，最常用的永远在最顺手的位置。
                    DefaultTerminalShortcuts.forEach { shortcut ->
                        TerminalShortcutKey(shortcut) {
                            if (isHapticEnabled()) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            onShortcut(shortcut)
                        }
                    }
                }
            }
        }
    }
}

/// 输入抽屉的拉手：点击或上下拖动切换输入抽屉。
@Composable
private fun InputDrawerHandle(open: Boolean, onClick: () -> Unit) {
    val targetColor = if (open) WandColors.brand else WandColors.textPrimary
    val background = if (open) {
        WandColors.brandSoft
    } else {
        WandColors.surfaceSoft.copy(alpha = 0.62f)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(40.dp)
            .widthIn(min = 44.dp)
            .clip(WandShapes.sm)
            .background(background)
            .border(0.6.dp, WandColors.border.copy(alpha = 0.85f), WandShapes.sm)
            .clickable(
                onClickLabel = if (open) "收起输入框" else "展开输入框",
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp)
            .pointerInput(open) {
                var accumulated = 0f
                detectVerticalDragGestures(
                    onDragStart = { accumulated = 0f },
                    onDragEnd = {
                        if (accumulated < -12f && !open) onClick()
                        else if (accumulated > 12f && open) onClick()
                    },
                ) { _, dragAmount ->
                    accumulated += dragAmount
                }
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(
                WandIcons.keyboard,
                contentDescription = null,
                tint = targetColor,
                modifier = Modifier.size(16.dp),
            )
            Icon(
                if (open) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp,
                contentDescription = null,
                tint = targetColor,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/// 展开后的终端输入抽屉：多行文本框 + 按住说话 + 发送。显示在快捷键栏上方。
@Composable
private fun PtyInputDrawer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    uploading: Boolean,
    pendingAttachments: List<UploadedFile>,
    baseUrl: String,
    onRemoveAttachment: (UploadedFile) -> Unit,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
    voice: VoiceInputController,
    onMicDown: () -> Unit,
) {
    val canSend = draft.isNotBlank() || pendingAttachments.isNotEmpty()
    val focusRequester = remember { FocusRequester() }
    val expanded = pendingAttachments.isNotEmpty()
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }
    val plusMenu: @Composable () -> Unit = {
        ComposerActionsMenu(
            backdrop = null,
            uploading = uploading,
            onPickPhoto = onPickPhoto,
            onPickFile = onPickFile,
        )
    }
    val sendButton: @Composable () -> Unit = {
        FilledComposerAction(
            enabled = canSend,
            fillColor = if (canSend) WandColors.brand else WandColors.textSecondary.copy(alpha = 0.16f),
            contentDescription = "发送",
            onClick = onSend,
        ) {
            Icon(
                WandIcons.arrowUp,
                contentDescription = null,
                tint = if (canSend) WandColors.textPrimary else WandColors.textSecondary.copy(alpha = 0.55f),
                modifier = Modifier.size(ComposerActionIconSize),
            )
        }
    }
    NativeComposerSurface(
        backdrop = null,
        expanded = expanded,
        drawSurface = false,
        modifier = Modifier.padding(start = 4.dp, end = 2.dp, top = 4.dp, bottom = 2.dp),
        collapsedLeading = { plusMenu() },
        inputContent = {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 34.dp),
            ) {
                if (expanded) {
                    PendingAttachmentsPreview(
                        attachments = pendingAttachments,
                        baseUrl = baseUrl,
                        onRemove = onRemoveAttachment,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
                    )
                }
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
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = { if (canSend) onSend() },
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
                                    "输入终端命令",
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
                        .focusRequester(focusRequester),
                )
            }
        },
        collapsedTrailing = {
            VoiceMicButton(
                voice = voice,
                voiceMode = false,
                onToggleMode = { runCatching { focusRequester.requestFocus() } },
                onMicDown = onMicDown,
            )
            sendButton()
        },
        expandedControls = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ComposerActionSpacing),
                modifier = Modifier.weight(1f),
            ) {
                plusMenu()
            }
            VoiceMicButton(
                voice = voice,
                voiceMode = false,
                onToggleMode = { runCatching { focusRequester.requestFocus() } },
                onMicDown = onMicDown,
            )
            sendButton()
        },
    )
}

/// 终端快捷键：所有按键统一高度、圆角、底色与描边，视觉重量只靠字号微调；
/// 修饰键（Ctrl/Alt/Shift）用弱化色 + 小号渲染，主键保持粗体，形成两级层次。
/// 按下时轻微缩放 + 底色提亮作为反馈，不再依赖 ripple。
@Composable
private fun TerminalShortcutKey(
    shortcut: TerminalShortcut,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 快捷键是终端页点得最频繁的控件，按压反馈统一走 WandMotion，不再用默认弹簧。
    val motionEnabled = !reduceMotionEnabled()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.settleSpringSpec()),
        label = "shortcutKeyScale",
    )
    val background by animateColorAsState(
        targetValue = if (pressed) WandColors.surfaceSoft else WandColors.surfaceSoft.copy(alpha = 0.70f),
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenPress()),
        label = "shortcutKeyBackground",
    )
    val modifiers = TerminalModifier.entries.filter { it in shortcut.binding.modifiers }
    val keyLabel = TerminalSpecialKeys.firstOrNull { it.id == shortcut.binding.key }?.label
        ?: shortcut.binding.key.uppercase()
    val symbolOnly = keyLabel.length == 1

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(40.dp)
            .widthIn(min = 44.dp)
            .clip(WandShapes.sm)
            .background(background)
            .border(0.6.dp, WandColors.border.copy(alpha = 0.6f), WandShapes.sm)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = shortcut.accessibilityLabel,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = if (symbolOnly && modifiers.isEmpty()) 10.dp else 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            modifiers.forEachIndexed { index, modifierKey ->
                if (index > 0) ShortcutKeyJoin()
                Text(
                    modifierKey.label,
                    color = WandColors.textMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
            if (modifiers.isNotEmpty()) ShortcutKeyJoin()
            Text(
                keyLabel,
                color = WandColors.textPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = if (symbolOnly) 15.sp else 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/// 修饰键与主键之间的连接符（"Ctrl+C" 里的 +），刻意缩小、降透明度。
@Composable
private fun ShortcutKeyJoin() {
    Text(
        "+",
        color = WandColors.textMuted.copy(alpha = 0.55f),
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
    )
}

