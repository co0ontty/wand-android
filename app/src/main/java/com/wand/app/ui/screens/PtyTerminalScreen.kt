package com.wand.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.view.KeyEvent as AndroidKeyEvent
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.UploadedFile
import com.wand.app.data.WandApi
import com.wand.app.data.providerDisplayName
import com.wand.app.speech.VoiceInputController
import com.wand.app.ui.QuickCommitStore
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.components.TailMarqueePathText
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandIconButton
import com.wand.app.ui.components.WandIconButtonVariant
import com.wand.app.ui.components.WandProviderMark
import com.wand.app.ui.components.WandProviderMarkVariant
import com.wand.app.ui.theme.AmbientBackground
import com.wand.app.ui.theme.GlassBackdrop
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandGlass
import com.wand.app.ui.theme.glassBackdropSource
import com.wand.app.ui.theme.glassSurface
import com.wand.app.ui.theme.rememberGlassBackdrop
import com.wand.app.ui.terminal.TerminalKeyBinding
import com.wand.app.ui.terminal.TerminalModifier
import com.wand.app.ui.terminal.TerminalShortcut
import com.wand.app.ui.terminal.buildTerminalShortcut
import com.wand.app.ui.terminal.rememberTerminalShortcutPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * PTY 会话原生壳：顶部用原生头部（返回 + provider 徽标 + 标题/工作目录），
 * 下方嵌一层加载 `embed=terminal&nativeInput=1` 的 WebView，只展示终端黑窗 + 悬浮球。
 * 对称 iOS PtySessionView——把网页终端套进原生 chrome，而不是整页跳出去。
 *
 * 鉴权沿用全局 CookieManager（ConnectActivity 登录后已写入并持久化的会话 cookie），
 * 与 MainActivity（网页版兜底）共用同一进程的 cookie，无需重新注入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PtyTerminalScreen(
    api: WandApi,
    sessionId: String,
    isHapticEnabled: () -> Boolean,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    var snapshot by remember(sessionId) { mutableStateOf<SessionSnapshot?>(null) }
    var snapshotResolved by remember(sessionId) { mutableStateOf(false) }
    var draft by remember(sessionId) { mutableStateOf("") }
    var sending by remember(sessionId) { mutableStateOf(false) }
    var toast by remember(sessionId) { mutableStateOf<String?>(null) }
    var uploadingAttachments by remember(sessionId) { mutableStateOf(false) }
    var pendingAttachments by remember(sessionId) { mutableStateOf<List<UploadedFile>>(emptyList()) }
    var showQuickStartGuide by remember(sessionId) { mutableStateOf(false) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val (shortcutStore, shortcutSnapshot) = rememberTerminalShortcutPreferences()
    // A slow or reconnecting server must not replay seconds of stale key-repeat input after the
    // user has already released the key. Keep only a small, recent interaction window.
    val shortcutQueue = remember(sessionId) {
        Channel<TerminalShortcut>(capacity = 12, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }
    val quickCommit = remember(sessionId) {
        QuickCommitStore(sessionId, api) { msg -> toast = msg }
    }
    val voiceInput = rememberVoiceInputHandle(
        isHapticEnabled = isHapticEnabled,
        onToast = { toast = it },
        onCommit = { text -> draft = appendVoiceText(draft, text) },
    )
    val voice = voiceInput.voice
    val onMicDown = voiceInput.onMicDown
    val attachmentPickers = rememberAttachmentPickerActions { uris ->
        scope.launchAttachmentUpload(
            context = context,
            api = api,
            sessionId = sessionId,
            uris = uris,
            onUploadingChange = { uploadingAttachments = it },
            onUploaded = { uploaded -> pendingAttachments = (pendingAttachments + uploaded).takeLast(5) },
            onToast = { toast = it },
        )
    }
    DisposableEffect(quickCommit) {
        onDispose { quickCommit.shutdown() }
    }

    // 仅为顶栏拉一次会话快照（标题 / provider / 工作目录）；失败就退化成最简头部。
    LaunchedEffect(sessionId) {
        snapshot = try {
            api.getSession(sessionId)
        } catch (_: Exception) {
            null
        }
        snapshotResolved = true
    }
    LaunchedEffect(shortcutSnapshot.hasSeenGuide) {
        if (!shortcutSnapshot.hasSeenGuide) showQuickStartGuide = true
    }
    LaunchedEffect(api, sessionId, shortcutQueue) {
        for (shortcut in shortcutQueue) {
            try {
                api.sendInput(
                    id = sessionId,
                    input = shortcut.bytes,
                    view = "terminal",
                    shortcutKey = "android-${shortcut.id.take(64)}",
                )
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

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2_600)
            toast = null
        }
    }

    val glassBackdrop = rememberGlassBackdrop()
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            PtyTopBar(
                backdrop = glassBackdrop,
                snapshot = snapshot,
                quickCommit = quickCommit,
                onBack = onBack,
                onOpenQuickCommit = { quickCommit.openPanel() },
            )
        },
        bottomBar = {
            PtyNativeInputBar(
                backdrop = glassBackdrop,
                draft = draft,
                sending = sending,
                uploading = uploadingAttachments,
                pendingAttachments = pendingAttachments,
                baseUrl = api.baseUrl,
                voice = voice,
                onDraftChange = { draft = it },
                onRemoveAttachment = { file ->
                    pendingAttachments = pendingAttachments.filterNot { it.savedPath == file.savedPath }
                },
                onMicDown = onMicDown,
                onPickPhoto = attachmentPickers.pickPhoto,
                onPickFile = attachmentPickers.pickFile,
                shortcuts = shortcutSnapshot.visibleShortcuts,
                shortcutsEnabled = snapshot?.status != "exited",
                onShortcut = { shortcutQueue.trySend(it) },
                onDismissKeyboard = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                },
                onShowGuide = { showQuickStartGuide = true },
                onOpenSettings = onOpenSettings,
                onSend = {
                    val body = draft
                    val attachments = pendingAttachments
                    val text = buildAttachmentPrompt(attachments, body).trim()
                    if (text.isEmpty() || sending) return@PtyNativeInputBar
                    draft = ""
                    pendingAttachments = emptyList()
                    sending = true
                    scope.launch {
                        try {
                            api.sendInput(sessionId, text, view = "chat")
                            delay(30)
                            api.sendInput(
                                id = sessionId,
                                input = "\r",
                                view = "chat",
                                shortcutKey = "enter_text",
                            )
                        } catch (e: Exception) {
                            toast = e.message ?: "发送失败"
                            draft = body
                            pendingAttachments = attachments
                        }
                        sending = false
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(focusManager) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        focusManager.clearFocus()
                    }
                }
                .glassBackdropSource(glassBackdrop),
        ) {
            AmbientBackground(Modifier.fillMaxSize())
            val terminalShape = RoundedCornerShape(18.dp)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .clip(terminalShape)
                    .background(Color.Black)
                    .border(0.55.dp, WandColors.border.copy(alpha = 0.34f), terminalShape),
            ) {
                PtyTerminalWebView(
                    serverUrl = api.baseUrl,
                    sessionId = sessionId,
                    onHardwareShortcut = { shortcutQueue.trySend(it) },
                )
            }
            if (quickCommit.panelOpen) {
                QuickCommitSheet(
                    qc = quickCommit,
                    isHapticEnabled = isHapticEnabled,
                    onDismiss = { quickCommit.closePanel() },
                )
            }
            toast?.let { message ->
                Text(
                    message,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(padding)
                        .padding(top = 10.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            if (showQuickStartGuide) {
                PtyQuickStartGuideDialog(
                    onDismiss = { showQuickStartGuide = false },
                    onFinished = {
                        shortcutStore.markGuideSeen()
                        showQuickStartGuide = false
                    },
                )
            }
        }
    }
}

@Composable
private fun PtyTopBar(
    backdrop: GlassBackdrop,
    snapshot: SessionSnapshot?,
    quickCommit: QuickCommitStore,
    onBack: () -> Unit,
    onOpenQuickCommit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(
                backdrop,
                RoundedCornerShape(0.dp),
                WandGlass.regular.copy(refractionHeight = 0.dp, shadowElevation = 0.dp),
                edgeToEdge = true,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(64.dp)
                .padding(start = 6.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuietPtyTopIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
            )
            PtyProviderBadge(snapshot?.provider)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    snapshot?.displayTitle ?: "终端会话",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WandColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TailMarqueePathText(
                    path = snapshot?.cwd.orEmpty(),
                    fontSize = 10.sp,
                    color = WandColors.textMuted,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            GitChangesButton(quickCommit) { onOpenQuickCommit() }
        }
        HorizontalDivider(thickness = 0.5.dp, color = WandColors.border)
    }
}

@Composable
private fun QuietPtyTopIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    WandIconButton(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        variant = WandIconButtonVariant.Toolbar,
        iconSize = 22.dp,
    )
}

@Composable
private fun PtyNativeInputBar(
    backdrop: GlassBackdrop,
    draft: String,
    sending: Boolean,
    uploading: Boolean,
    pendingAttachments: List<UploadedFile>,
    baseUrl: String,
    voice: VoiceInputController,
    onDraftChange: (String) -> Unit,
    onRemoveAttachment: (UploadedFile) -> Unit,
    onMicDown: () -> Unit,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
    shortcuts: List<TerminalShortcut>,
    shortcutsEnabled: Boolean,
    onShortcut: (TerminalShortcut) -> Unit,
    onDismissKeyboard: () -> Unit,
    onShowGuide: () -> Unit,
    onOpenSettings: () -> Unit,
    onSend: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }
    var refocusAfterSend by remember { mutableStateOf(false) }
    var voiceMode by remember { mutableStateOf(false) }
    var focusAfterExitVoice by remember { mutableStateOf(false) }
    var draftNeedsExpanded by remember { mutableStateOf(false) }
    // 聚焦、语音、附件或多行草稿都使用两行布局，失焦后正文仍不会被截成单行。
    val expanded = isFocused || voiceMode || draftNeedsExpanded || pendingAttachments.isNotEmpty()

    LaunchedEffect(refocusAfterSend, sending) {
        if (refocusAfterSend && !sending && !voiceMode) {
            refocusAfterSend = false
            runCatching { focusRequester.requestFocus() }
        }
    }
    LaunchedEffect(voiceMode, focusAfterExitVoice) {
        if (!voiceMode && focusAfterExitVoice) {
            focusAfterExitVoice = false
            runCatching { focusRequester.requestFocus() }
        }
    }

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
        PtyTerminalShortcutBar(
            shortcuts = shortcuts,
            enabled = shortcutsEnabled,
            keyboardVisible = isFocused,
            onShortcut = onShortcut,
            onDismissKeyboard = onDismissKeyboard,
            onShowGuide = onShowGuide,
            onOpenSettings = onOpenSettings,
        )
        HorizontalDivider(thickness = 0.5.dp, color = WandColors.border.copy(alpha = 0.72f))
        if (voice.pressed) {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                VoiceTranscriptBubble(backdrop, voice)
            }
        }
        val terminalChip: @Composable () -> Unit = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(WandColors.textSecondary.copy(alpha = 0.10f)),
            ) {
                Icon(
                    WandIcons.keyboard,
                    contentDescription = null,
                    tint = WandColors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        val inputContent: @Composable RowScope.() -> Unit = {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 34.dp),
            ) {
                if (expanded && pendingAttachments.isNotEmpty() && !voiceMode) {
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
                            focusAfterExitVoice = true
                            voiceMode = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Box(contentAlignment = Alignment.CenterStart) {
                        BasicTextField(
                            value = draft,
                            onValueChange = onDraftChange,
                            enabled = !sending,
                            textStyle = TextStyle(
                                color = WandColors.textPrimary,
                                fontSize = 16.sp,
                                lineHeight = 21.sp,
                            ),
                            cursorBrush = SolidColor(WandColors.brand),
                            minLines = 1,
                            maxLines = if (expanded) 6 else 1,
                            onTextLayout = { layout ->
                                draftNeedsExpanded = draft.isNotEmpty() &&
                                    (layout.lineCount > 1 || layout.hasVisualOverflow)
                            },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 34.dp, max = if (expanded) 132.dp else 34.dp)
                                .focusRequester(focusRequester)
                                .onFocusChanged { isFocused = it.isFocused },
                            decorationBox = { innerTextField ->
                                Box(
                                    contentAlignment = Alignment.CenterStart,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 8.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                                ) {
                                    if (draft.isEmpty()) {
                                        Text(
                                            "输入到终端…",
                                            fontSize = 16.sp,
                                            color = WandColors.textMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    innerTextField()
                                }
                            },
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
        val micButton: @Composable () -> Unit = {
            VoiceMicButton(
                voice = voice,
                voiceMode = voiceMode,
                onToggleMode = { voiceMode = !voiceMode },
                onMicDown = onMicDown,
            )
        }
        val sendButton: @Composable () -> Unit = {
            val canSend = (draft.isNotBlank() || pendingAttachments.isNotEmpty()) && !sending
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(enabled = canSend) {
                        refocusAfterSend = true
                        onSend()
                    },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(ComposerActionVisualSize)
                        .clip(CircleShape)
                        .background(if (!canSend) WandColors.textMuted.copy(alpha = 0.10f) else WandColors.textPrimary),
                ) {
                    Icon(
                        WandIcons.arrowUp,
                        contentDescription = "发送",
                        tint = if (!canSend) WandColors.textMuted.copy(alpha = 0.55f) else WandColors.surface,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        NativeComposerSurface(
            backdrop = backdrop,
            expanded = expanded,
            focused = isFocused,
            collapsedLeading = { plusMenu() },
            inputContent = inputContent,
            collapsedTrailing = {
                micButton()
                sendButton()
            },
            expandedControls = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    plusMenu()
                    terminalChip()
                    Text(
                        "终端",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = WandColors.textSecondary,
                        maxLines = 1,
                    )
                }
                micButton()
                sendButton()
            },
        )
    }
}

@Composable
private fun PtyTerminalWebView(
    serverUrl: String,
    sessionId: String,
    onHardwareShortcut: (TerminalShortcut) -> Unit,
) {
    val context = LocalContext.current
    val latestHardwareShortcut by rememberUpdatedState(onHardwareShortcut)
    val webView = remember {
        @SuppressLint("SetJavaScriptEnabled")
        val view = WebView(context)
        view.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        )
        view.setBackgroundColor(AndroidColor.BLACK)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            // 触发网页 is-wand-app / is-wand-app(Android) 行为，与 MainActivity 一致。
            userAgentString = "$userAgentString WandApp/native WandPlatform/Android"
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        view.apply {
            setOnKeyListener { _, _, event ->
                val shortcut = terminalShortcutForHardwareEvent(event) ?: return@setOnKeyListener false
                latestHardwareShortcut(shortcut)
                true
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    // 原生头部已吃掉状态栏，网页内安全区清零，避免双重顶部留白。
                    view.evaluateJavascript(
                        "(function(){try{var r=document.documentElement;" +
                            "r.classList.add('is-wand-app-native-insets');" +
                            "r.style.setProperty('--app-inset-top','0px');" +
                            "r.style.setProperty('--app-inset-bottom','0px');" +
                            "r.style.setProperty('--app-inset-left','0px');" +
                            "r.style.setProperty('--app-inset-right','0px');" +
                            "if(!document.getElementById('wand-native-terminal-compact-style')){" +
                            "var s=document.createElement('style');" +
                            "s.id='wand-native-terminal-compact-style';" +
                            "s.textContent='" +
                            ".is-wand-embed-terminal .wand-joystick-root{z-index:120;}" +
                            ".is-wand-embed-terminal .wand-joystick-root.visible{opacity:1!important;visibility:visible!important;}" +
                            ".is-wand-embed-terminal .wand-joystick-ball{opacity:1!important;transform:none;}" +
                            ".is-wand-embed-terminal .wand-joystick-panel{z-index:124;}" +
                            ".is-wand-embed-terminal .terminal-scroll-wrap{padding:8px 4px 8px!important;--term-font-family:\\\"Roboto Mono\\\",\\\"Droid Sans Mono\\\",\\\"Noto Sans Mono\\\",\\\"Noto Sans Symbols 2\\\",\\\"Noto Sans Symbols\\\",monospace!important;--term-font-size:10px!important;--term-row-height:15px!important;}" +
                            ".is-wand-embed-terminal .terminal-container{margin:0!important;border-left:0!important;border-right:0!important;border-radius:0!important;box-shadow:none!important;}" +
                            "';" +
                            "document.head.appendChild(s);}" +
                            "if(!window.__wandNativeJoystickFocusGuard){" +
                            "window.__wandNativeJoystickFocusGuard=true;" +
                            "function blurJoystickFocus(){try{var a=document.activeElement;if(a&&typeof a.blur==='function')a.blur();setTimeout(function(){try{var n=document.activeElement;if(n&&typeof n.blur==='function')n.blur();}catch(e){}},0);}catch(e){}}" +
                            "['pointerdown','pointerup','touchstart','touchend','click'].forEach(function(type){document.addEventListener(type,function(e){try{var t=e.target;if(t&&t.closest&&t.closest('.wand-joystick-root'))blurJoystickFocus();}catch(err){}},true);});" +
                            "}" +
                            "function fit(){try{window.dispatchEvent(new Event('resize'));var o=document.getElementById('output');if(o){var w=o.style.width;o.style.width='calc(100% - 0.01px)';void o.offsetWidth;o.style.width=w;}}catch(e){}}" +
                            "[0,80,220,520].forEach(function(d){setTimeout(fit,d);});" +
                            "}catch(e){}})();",
                        null,
                    )
                }
            }
            loadUrl(buildEmbedTerminalUrl(serverUrl, sessionId))
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }

    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
}

private fun buildEmbedTerminalUrl(serverUrl: String, sessionId: String): String =
    Uri.parse(serverUrl).buildUpon()
        .appendQueryParameter("session", sessionId)
        .appendQueryParameter("embed", "terminal")
        .appendQueryParameter("nativeInput", "1")
        .build()
        .toString()

@Composable
private fun PtyTerminalShortcutBar(
    shortcuts: List<TerminalShortcut>,
    enabled: Boolean,
    keyboardVisible: Boolean,
    onShortcut: (TerminalShortcut) -> Unit,
    onDismissKeyboard: () -> Unit,
    onShowGuide: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (keyboardVisible) {
            item(key = "dismiss-keyboard") {
                WandIconButton(
                    icon = WandIcons.keyboardHide,
                    contentDescription = "收起软键盘",
                    onClick = onDismissKeyboard,
                    variant = WandIconButtonVariant.Quiet,
                    iconSize = 18.dp,
                )
            }
        }
        items(shortcuts, key = TerminalShortcut::id) { shortcut ->
            PtyShortcutKeycap(
                shortcut = shortcut,
                enabled = enabled,
                onPress = { onShortcut(shortcut) },
            )
        }
        item(key = "shortcut-help") {
            WandIconButton(
                icon = WandIcons.question,
                contentDescription = "查看 PTY 快速上手",
                onClick = onShowGuide,
                variant = WandIconButtonVariant.Quiet,
                iconSize = 18.dp,
            )
        }
        item(key = "shortcut-settings") {
            WandIconButton(
                icon = WandIcons.tune,
                contentDescription = "设置终端快捷键",
                onClick = onOpenSettings,
                variant = WandIconButtonVariant.Quiet,
                iconSize = 18.dp,
            )
        }
    }
}

/**
 * Terminal keys commit on pointer-down so the PTY responds immediately. Repeatable navigation
 * keys start repeating after a deliberate hold; their visual state is direct and has no delayed
 * animation because this path can be used hundreds of times per day.
 */
@Composable
private fun PtyShortcutKeycap(
    shortcut: TerminalShortcut,
    enabled: Boolean,
    onPress: () -> Unit,
) {
    var pressed by remember(shortcut.id) { mutableStateOf(false) }
    val repeatScope = rememberCoroutineScope()
    val shape = RoundedCornerShape(9.dp)
    val background = when {
        !enabled -> WandColors.surfaceSoft.copy(alpha = 0.42f)
        pressed -> WandColors.textPrimary.copy(alpha = 0.14f)
        shortcut.builtIn -> WandColors.surfaceSoft.copy(alpha = 0.86f)
        else -> WandColors.brandSoft.copy(alpha = 0.78f)
    }
    val border = if (shortcut.builtIn) WandColors.border else WandColors.brand.copy(alpha = 0.38f)

    Box(
        modifier = Modifier
            .height(40.dp)
            .widthIn(min = 40.dp)
            .background(background, shape)
            .border(0.65.dp, border, shape)
            .semantics {
                role = Role.Button
                contentDescription = shortcut.accessibilityLabel
                if (!enabled) disabled()
                onClick {
                    if (enabled) onPress()
                    enabled
                }
            }
            .pointerInput(shortcut.id, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    pressed = true
                    onPress()
                    var repeatJob: Job? = null
                    try {
                        repeatJob = if (shortcut.repeatable) {
                            repeatScope.launch {
                                delay(380)
                                while (isActive) {
                                    onPress()
                                    delay(72)
                                }
                            }
                        } else null
                        waitForUpOrCancellation()
                    } finally {
                        repeatJob?.cancel()
                        pressed = false
                    }
                }
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            shortcut.label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = if (enabled) WandColors.textSecondary else WandColors.textMuted.copy(alpha = 0.48f),
            maxLines = 1,
        )
    }
}

private fun terminalShortcutForHardwareEvent(event: AndroidKeyEvent): TerminalShortcut? {
    if (event.action != AndroidKeyEvent.ACTION_DOWN) return null
    val modifiers = buildSet {
        if (event.isCtrlPressed) add(TerminalModifier.Ctrl)
        if (event.isAltPressed) add(TerminalModifier.Alt)
        if (event.isShiftPressed) add(TerminalModifier.Shift)
    }
    val key = when (event.keyCode) {
        AndroidKeyEvent.KEYCODE_ESCAPE -> "escape"
        AndroidKeyEvent.KEYCODE_TAB -> "tab"
        AndroidKeyEvent.KEYCODE_ENTER,
        AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
        -> "enter"
        AndroidKeyEvent.KEYCODE_DEL -> "backspace"
        AndroidKeyEvent.KEYCODE_FORWARD_DEL -> "delete"
        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> "arrowLeft"
        AndroidKeyEvent.KEYCODE_DPAD_UP -> "arrowUp"
        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> "arrowDown"
        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> "arrowRight"
        AndroidKeyEvent.KEYCODE_MOVE_HOME -> "home"
        AndroidKeyEvent.KEYCODE_MOVE_END -> "end"
        AndroidKeyEvent.KEYCODE_PAGE_UP -> "pageUp"
        AndroidKeyEvent.KEYCODE_PAGE_DOWN -> "pageDown"
        AndroidKeyEvent.KEYCODE_SPACE -> if (modifiers.isNotEmpty()) "space" else return null
        in AndroidKeyEvent.KEYCODE_A..AndroidKeyEvent.KEYCODE_Z -> {
            if (TerminalModifier.Ctrl !in modifiers && TerminalModifier.Alt !in modifiers) return null
            ('a'.code + event.keyCode - AndroidKeyEvent.KEYCODE_A).toChar().toString()
        }
        in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> {
            if (TerminalModifier.Ctrl !in modifiers && TerminalModifier.Alt !in modifiers) return null
            ('0'.code + event.keyCode - AndroidKeyEvent.KEYCODE_0).toChar().toString()
        }
        else -> return null
    }
    return buildTerminalShortcut(
        binding = TerminalKeyBinding(key, modifiers),
        id = "hardware-${event.keyCode}",
        accessibilityLabel = "硬件键盘 $key",
    )
}

@Composable
private fun PtyProviderBadge(provider: String?) {
    WandProviderMark(provider = provider, variant = WandProviderMarkVariant.Tinted)
}
