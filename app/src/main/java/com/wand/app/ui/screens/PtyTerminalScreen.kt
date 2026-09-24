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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
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
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.reduceMotionEnabled
import com.wand.app.ui.terminal.DefaultTerminalShortcuts
import com.wand.app.ui.terminal.TerminalKeyBinding
import com.wand.app.ui.terminal.TerminalModifier
import com.wand.app.ui.terminal.directPtyInput
import com.wand.app.ui.terminal.normalizeTerminalScale
import com.wand.app.ui.terminal.stepTerminalScale
import com.wand.app.ui.terminal.terminalFontSp
import com.wand.app.ui.terminal.encodeTerminalKey
import com.wand.app.ui.terminal.terminalTypeface
import com.wand.app.ui.terminal.TerminalSpecialKeys
import com.wand.app.ui.terminal.TerminalShortcut
import com.wand.app.ui.terminal.NativePtyTerminal
import com.wand.app.ui.terminal.NativePtyTerminalSurface
import com.wand.app.ui.terminal.TerminalPalette
import com.wand.app.ui.terminal.terminalSoftKeyboardEnabled
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

private val TerminalBackground = Color(TerminalPalette.backgroundArgb)
private val TerminalForeground = Color(TerminalPalette.foregroundArgb)
private val TerminalTray = Color(TerminalPalette.trayArgb)
private val TerminalCapsule = Color(TerminalPalette.capsuleArgb)
private val TerminalKey = Color(TerminalPalette.keyArgb)
private val TerminalKeyPressed = Color(TerminalPalette.keyPressedArgb)
private val TerminalMuted = Color(TerminalPalette.mutedArgb)
private val TerminalHairline = Color(TerminalPalette.hairlineArgb)

/**
 * PTY 会话原生壳：顶部用原生头部，中间是 libvterm 终端。
 * 软键盘确认的文字立刻写入 PTY，输入法组词留在键盘上方的一行里。
 * 底部快捷键发送 Esc / Ctrl / 方向键；草稿框用于长文本、语音和附件。
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
    // 软键盘直接写 PTY。草稿抽屉默认收起，只在整段提示、语音或附件时打开。
    var inputDrawerOpen by remember(sessionId) { mutableStateOf(false) }
    var keyboardRequested by remember(sessionId) { mutableStateOf(true) }
    var draft by remember(sessionId) { mutableStateOf("") }
    var uploadingAttachments by remember(sessionId) { mutableStateOf(false) }
    var pendingAttachments by remember(sessionId) { mutableStateOf<List<UploadedFile>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val directKeyboard = terminalSoftKeyboardEnabled(
        ready = terminal.ready.value,
        composerOpen = inputDrawerOpen,
        requested = keyboardRequested,
    )
    fun requestDirectKeyboard() {
        inputDrawerOpen = false
        if (keyboardRequested && !imeVisible) {
            keyboardRequested = false
            scope.launch {
                delay(40)
                keyboardRequested = true
            }
        } else {
            keyboardRequested = true
        }
    }
    val context = LocalContext.current
    val terminalScalePrefs = remember(context) {
        context.getSharedPreferences("wand.terminal", android.content.Context.MODE_PRIVATE)
    }
    var terminalScale by remember {
        mutableFloatStateOf(normalizeTerminalScale(terminalScalePrefs.getFloat("scale", 1f)))
    }
    fun updateTerminalScale(next: Float) {
        val value = normalizeTerminalScale(next)
        if (value == terminalScale) return
        terminalScale = value
        terminalScalePrefs.edit().putFloat("scale", value).apply()
    }
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
                inputDrawerOpen = inputDrawerOpen,
                directKeyboard = directKeyboard,
                onToggleKeyboard = {
                    if (directKeyboard && imeVisible) {
                        keyboardRequested = false
                    } else {
                        requestDirectKeyboard()
                    }
                },
                onToggleInputDrawer = {
                    val open = !inputDrawerOpen
                    inputDrawerOpen = open
                    if (open) {
                        keyboardRequested = false
                    } else {
                        keyboardRequested = true
                    }
                },
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
                sessionId = sessionId,
                onDirectInput = { text ->
                    val sent = terminal.send(text)
                    if (!sent) toast = "终端未就绪，输入未发送；请检查连接后重试"
                    sent
                },
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
                NativePtyTerminalSurface(
                    terminal,
                    fontSize = terminalFontSp(terminalScale).sp,
                    onTerminalTap = { requestDirectKeyboard() },
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
                if (terminal.ready.value) {
                    TerminalScaleBar(
                        scale = terminalScale,
                        onShrink = { updateTerminalScale(stepTerminalScale(terminalScale, -0.25f)) },
                        onReset = { updateTerminalScale(stepTerminalScale(terminalScale, 0f)) },
                        onGrow = { updateTerminalScale(stepTerminalScale(terminalScale, 0.25f)) },
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp),
                    )
                }
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
                        Text("终端连接未就绪", color = TerminalForeground)
                        Text(
                            "重试连接",
                            color = WandColors.brand,
                            modifier = Modifier.clickable { terminal.reconnect() }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                        )
                    }
                }
            }
            if (voiceInput.voice.showModelDialog) {
                SttModelDownloadDialog(onDismiss = { voiceInput.voice.showModelDialog = false })
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
    inputDrawerOpen: Boolean,
    directKeyboard: Boolean,
    onToggleKeyboard: () -> Unit,
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
    sessionId: String,
    onDirectInput: (String) -> Boolean,
    voice: VoiceInputController,
    onMicDown: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val shortcutScroll = rememberScrollState()
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(TerminalTray)
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
                Box(
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(WandColors.bgElevated),
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
            }
            // 键盘和草稿固定在左侧，快捷键单独横向滚动，窄屏滑动时入口不会跟着走。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(TerminalCapsule)
                    .border(0.6.dp, TerminalHairline, RoundedCornerShape(14.dp))
                    .padding(horizontal = 5.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TerminalTrayButton(
                    icon = WandIcons.keyboard,
                    active = directKeyboard,
                    label = if (directKeyboard) "收起键盘" else "直接输入到终端",
                    onClick = onToggleKeyboard,
                )
                TerminalTrayButton(
                    icon = WandIcons.edit,
                    active = inputDrawerOpen,
                    label = if (inputDrawerOpen) "收起整段输入" else "整段输入",
                    onClick = onToggleInputDrawer,
                )
                val edgeFade = TerminalCapsule
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .drawWithContent {
                            drawContent()
                            // 滚动两端用托盘底色渐隐，提示这里还可以横向滑动。
                            val fadeWidth = 16.dp.toPx()
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
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
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
            if (directKeyboard) {
                PtyDirectField(sessionId = sessionId, onSend = onDirectInput)
            }
        }
    }
}

@Composable
private fun TerminalScaleBar(
    scale: Float,
    onShrink: () -> Unit,
    onReset: () -> Unit,
    onGrow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(TerminalCapsule.copy(alpha = 0.94f))
            .border(0.6.dp, TerminalHairline, RoundedCornerShape(10.dp))
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TerminalScaleButton("−", "缩小终端", onShrink)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .height(28.dp)
                .widthIn(min = 44.dp)
                .clip(WandShapes.sm)
                .clickable(
                    onClickLabel = "恢复终端缩放",
                    role = Role.Button,
                    onClick = onReset,
                ),
        ) {
            Text(
                "${kotlin.math.round(scale * 100).toInt()}%",
                color = TerminalForeground,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
        }
        TerminalScaleButton("+", "放大终端", onGrow)
    }
}

@Composable
private fun TerminalScaleButton(label: String, description: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(28.dp)
            .widthIn(min = 28.dp)
            .clip(WandShapes.sm)
            .clickable(onClickLabel = description, role = Role.Button, onClick = onClick),
    ) {
        Text(label, color = TerminalForeground, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TerminalTrayButton(
    icon: ImageVector,
    active: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    val background = if (active) WandColors.brand.copy(alpha = 0.22f) else TerminalKey
    val border = if (active) WandColors.brand.copy(alpha = 0.7f) else TerminalHairline
    val tint = if (active) WandColors.brand else TerminalForeground
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(40.dp)
            .widthIn(min = 44.dp)
            .clip(WandShapes.sm)
            .background(background)
            .border(0.6.dp, border, WandShapes.sm)
            .clickable(
                onClickLabel = label,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/**
 * Focus target for the soft keyboard. Committed text is written to the PTY immediately.
 * An open IME composition stays in this line until the candidate is chosen.
 */
@Composable
private fun PtyDirectField(
    sessionId: String,
    onSend: (String) -> Boolean,
) {
    val context = LocalContext.current
    val typeface = remember { terminalTypeface(context.assets) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var value by remember(sessionId) { mutableStateOf(TextFieldValue("")) }
    var suppress by remember(sessionId) { mutableStateOf<String?>(null) }
    LaunchedEffect(sessionId) {
        delay(60)
        runCatching { focusRequester.requestFocus() }
        delay(40)
        keyboard?.show()
    }
    val composing = value.text.isNotEmpty()
    fun sendDirect(text: String): Boolean {
        if (text.isEmpty()) return true
        return onSend(text)
    }
    fun sendDirectKey(key: String, fieldEmpty: Boolean): Boolean {
        if (!fieldEmpty) return false
        val bytes = encodeTerminalKey(TerminalKeyBinding(key)) ?: return false
        sendDirect(bytes)
        return true
    }
    BasicTextField(
        value = value,
        onValueChange = { next ->
            if (suppress != null && next.text == suppress) {
                suppress = null
                value = TextFieldValue("")
                return@BasicTextField
            }
            val step = directPtyInput(next.text, composing = next.composition != null)
            if (step.send != null) {
                if (sendDirect(step.send)) {
                    value = TextFieldValue("")
                } else {
                    value = next
                }
                return@BasicTextField
            }
            value = next
        },
        textStyle = TextStyle(
            fontFamily = FontFamily(typeface),
            fontSize = 16.sp,
            lineHeight = 22.sp,
            color = TerminalForeground,
        ),
        cursorBrush = SolidColor(if (composing) Color(0xFFD88D60) else TerminalTray),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.None,
        ),
        singleLine = true,
        decorationBox = { inner ->
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                inner()
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(if (composing) 36.dp else 1.dp)
            .semantics { contentDescription = "终端输入" }
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.isCtrlPressed) {
                    val key = when (event.key) {
                        Key.A -> "a"
                        Key.C -> "c"
                        Key.D -> "d"
                        Key.E -> "e"
                        Key.K -> "k"
                        Key.L -> "l"
                        Key.R -> "r"
                        Key.U -> "u"
                        Key.W -> "w"
                        Key.Z -> "z"
                        else -> null
                    }
                    val bytes = key?.let {
                        encodeTerminalKey(TerminalKeyBinding(it, setOf(TerminalModifier.Ctrl)))
                    }
                    if (bytes != null) {
                        value = TextFieldValue("")
                        sendDirect(bytes)
                        return@onPreviewKeyEvent true
                    }
                }
                when (event.key) {
                    Key.Enter -> {
                        val pending = value.text
                        value = TextFieldValue("")
                        if (pending.isNotEmpty()) {
                            suppress = pending
                            sendDirect(pending)
                        }
                        sendDirect("\r")
                        true
                    }
                    Key.Tab -> {
                        sendDirect("\t")
                        true
                    }
                    Key.Escape -> {
                        value = TextFieldValue("")
                        sendDirect("\u001b")
                        true
                    }
                    Key.Backspace -> {
                        if (value.text.isEmpty()) sendDirect("\u007f")
                        value.text.isEmpty()
                    }
                    Key.DirectionUp -> sendDirectKey("arrowUp", value.text.isEmpty())
                    Key.DirectionDown -> sendDirectKey("arrowDown", value.text.isEmpty())
                    Key.DirectionLeft -> sendDirectKey("arrowLeft", value.text.isEmpty())
                    Key.DirectionRight -> sendDirectKey("arrowRight", value.text.isEmpty())
                    else -> false
                }
            },
    )
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
        // Let the terminal IME finish hiding before this field takes focus.
        delay(80)
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
                                    "整段文字、语音或附件",
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
        targetValue = if (pressed) TerminalKeyPressed else TerminalKey,
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
            .border(0.6.dp, TerminalHairline, WandShapes.sm)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = shortcut.accessibilityLabel,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = if (symbolOnly && modifiers.isEmpty()) 8.dp else 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            modifiers.forEachIndexed { index, modifierKey ->
                if (index > 0) ShortcutKeyJoin()
                Text(
                    modifierKey.label,
                    color = TerminalMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
            if (modifiers.isNotEmpty()) ShortcutKeyJoin()
            Text(
                keyLabel,
                color = TerminalForeground,
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
        color = TerminalMuted.copy(alpha = 0.7f),
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
    )
}

