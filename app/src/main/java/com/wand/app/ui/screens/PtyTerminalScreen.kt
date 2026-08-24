package com.wand.app.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.view.KeyEvent as AndroidKeyEvent
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.UploadedFile
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.WandApi
import com.wand.app.data.WandWebSession
import com.wand.app.data.providerDisplayName
import com.wand.app.ui.QuickCommitStore
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.components.TailMarqueePathText
import com.wand.app.ui.components.WandDetailBackButton
import com.wand.app.ui.components.WandDetailTopBar
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandProviderMark
import com.wand.app.ui.components.WandProviderMarkVariant
import com.wand.app.speech.VoiceInputController
import com.wand.app.ui.theme.GlassBackdrop
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandGlass
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.WandTerminal
import com.wand.app.ui.theme.isWandDarkTheme
import com.wand.app.ui.theme.glassSurface
import com.wand.app.ui.terminal.DefaultTerminalShortcuts
import com.wand.app.ui.terminal.TerminalKeyBinding
import com.wand.app.ui.terminal.TerminalModifier
import com.wand.app.ui.terminal.TerminalSpecialKeys
import com.wand.app.ui.terminal.TerminalShortcut
import com.wand.app.ui.terminal.buildTerminalShortcut
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

private val TerminalBackground = WandTerminal.background
private val TerminalBackgroundArgb = WandTerminal.backgroundArgb

/**
 * PTY 会话原生壳：顶部用原生头部（返回 + provider 徽标 + 标题/工作目录），
 * 中间嵌一层纯透传终端，底部只保留高频 PTY 快捷键，不再叠加聊天式草稿输入框。
 * 对称 iOS PtySessionView——把网页终端套进原生 chrome，而不是整页跳出去。
 *
 * 内嵌 WebView 加载前显式执行 clear → 当前 endpoint login → load，避免进程全局
 * CookieManager 在同 host 不同端口之间串用 session。
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
    onSwitchTaskSession: ((WorkspaceSessionSummary) -> Unit)? = null,
    isHapticEnabled: () -> Boolean,
    showBack: Boolean = true,
    onBack: () -> Unit,
) {
    var snapshot by remember(sessionId) { mutableStateOf<SessionSnapshot?>(null) }
    var snapshotResolved by remember(sessionId) { mutableStateOf(false) }
    var webViewReady by remember(sessionId) { mutableStateOf(false) }
    var toast by remember(sessionId) { mutableStateOf<String?>(null) }
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
    // A slow or reconnecting server must not replay seconds of stale key-repeat input after the
    // user has already released the key. Keep only a small, recent interaction window.
    val shortcutQueue = remember(sessionId) {
        Channel<TerminalShortcut>(capacity = 12, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }
    val quickCommit = remember(sessionId) {
        QuickCommitStore(sessionId, api) { msg -> toast = msg }
    }
    DisposableEffect(quickCommit) {
        onDispose { quickCommit.shutdown() }
    }

    // 仅为顶栏拉一次会话快照（标题 / provider / 工作目录）；失败就退化成最简头部。
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

    fun sendPtyDraft() {
        val body = draft.trim()
        val attachments = pendingAttachments
        if (body.isEmpty() && attachments.isEmpty()) return
        val text = buildAttachmentPrompt(attachments, body).trim()
        val restore = draft
        draft = ""
        pendingAttachments = emptyList()
        scope.launch {
            try {
                // 对齐 iOS sendPtyInput：先发文本，再发回车（带 enter_text 标记），
                // 两个 input 之间留一点间隔，避免服务端把文本和回车粘成一条。
                // 附件已先经 /api/sessions/:id/uploads 落盘，正文里只带路径前缀。
                api.sendInput(id = sessionId, input = text, view = "terminal")
                delay(30)
                api.sendInput(
                    id = sessionId,
                    input = "\r",
                    view = "terminal",
                    shortcutKey = "enter_text",
                )
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
        // WebView 不能放进动态玻璃的 backdrop 捕获层：平台视图与离屏采样会互相争用
        // 合成缓冲，部分 Android GPU 上会表现为整块终端反复闪烁。PTY 的 chrome 使用
        // 稳定的半透明降级材质，终端本体保持直接合成。
        containerColor = WandColors.bgPrimary,
        topBar = {
            Column {
                PtyTopBar(
                    backdrop = null,
                    snapshot = snapshot,
                    serverDisplayName = serverDisplayName,
                    workspaceName = workspaceName,
                    taskName = taskName,
                    quickCommit = quickCommit,
                    showBack = showBack,
                    onBack = onBack,
                    onOpenQuickCommit = { quickCommit.openPanel() },
                )
                // 任务内「其他终端」快捷 Tab（对齐 iOS sessionStrip）：非任务会话不显示。
                if (taskId != null && onSwitchTaskSession != null) {
                    TaskSessionTabStrip(
                        api = api,
                        taskId = taskId,
                        currentSessionId = sessionId,
                        onSelect = onSwitchTaskSession,
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
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(TerminalBackground),
            ) {
                if (snapshotResolved) {
                    if (snapshot == null) {
                        PtyConnectionBanner(
                            message = "未能加载会话状态，终端仍可继续使用。",
                        )
                    }
                    PtyTerminalWebView(
                        serverUrl = api.baseUrl,
                        token = api.token,
                        sessionId = sessionId,
                        onHardwareShortcut = { shortcutQueue.trySend(it) },
                        onReadyChange = { webViewReady = it },
                    )
                    // Cookie 就绪不等于网页首帧就绪。等页面初始化脚本真正执行完成后再
                    // 移除不透明遮罩，避免 WebView 的空白帧、首屏和终端画面来回闪现。
                    if (!webViewReady) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(TerminalBackground),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = WandColors.brand, strokeWidth = 2.dp)
                        }
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = WandColors.brand, strokeWidth = 2.dp)
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
            PtyProviderBadge(snapshot?.provider)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    taskName?.trim().takeUnless { it.isNullOrEmpty() }
                        ?: snapshot?.displayTitle
                        ?: "终端会话",
                    style = MaterialTheme.typography.titleMedium,
                    color = WandColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
private fun PtyConnectionBanner(message: String) {
    Text(
        message,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = Color.White.copy(alpha = 0.86f),
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.42f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
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
        // 顶缘渐隐：暗色用轻黑，浅色用页面底色，避免米色 chrome 上出现脏黑带。
        val fadeTop = if (isWandDarkTheme()) {
            Color.Black.copy(alpha = 0.18f)
        } else {
            WandColors.bgPrimary.copy(alpha = 0.55f)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(fadeTop, Color.Transparent),
                    ),
                ),
        )
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
            // 玻璃底色之上的发丝分隔线：亮暗主题下都保持清晰但不刺眼的边界。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.6.dp)
                    .background(WandColors.border.copy(alpha = 0.55f)),
            )
            AnimatedVisibility(visible = inputDrawerOpen) {
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
            val edgeFade = WandColors.bgElevated.copy(alpha = 0.96f)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .drawWithContent {
                        drawContent()
                        // 滚动两端用主题色叠一层渐隐，避免 Offscreen+DstIn 在浅色玻璃上发灰。
                        val fadeWidth = 26.dp.toPx()
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
                    .padding(horizontal = 12.dp),
            ) {
                // 第一个固定项是输入抽屉的拉手：键盘图标 + 上/下箭头，点击或上拉展开输入框。
                InputDrawerHandle(open = inputDrawerOpen, onClick = onToggleInputDrawer)
                ShortcutGroupDivider()
                // 按使用热度分三组：高频执行（Enter/↑/Tab）· 中断控制（Esc/Ctrl+C/Shift+Tab）
                // · 光标导航（←/→/↓），组间用细竖线分隔，最常用的永远在最顺手的位置。
                DefaultTerminalShortcuts.forEachIndexed { index, shortcut ->
                    TerminalShortcutKey(shortcut) {
                        if (isHapticEnabled()) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        onShortcut(shortcut)
                    }
                    if (index == 2 || index == 5) ShortcutGroupDivider()
                }
            }
        }
    }
}

/// 输入抽屉的拉手：快捷键栏的第一个固定项。折叠态显示键盘 + 向上箭头，
/// 展开态翻转成向下箭头。点击切换；带一个纵向拖动手势，上拉展开、下拉收起。
/// 快捷键栏的组间分隔线：细竖线，弱存在感，只负责把逻辑分组切开。
@Composable
private fun ShortcutGroupDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .width(1.dp)
            .height(22.dp)
            .background(WandColors.border.copy(alpha = 0.5f), RoundedCornerShape(1.dp)),
    )
}

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
            .widthIn(min = 72.dp)
            .clip(WandShapes.sm)
            .background(background)
            .border(0.6.dp, WandColors.border.copy(alpha = 0.85f), WandShapes.sm)
            .clickable(
                onClickLabel = if (open) "收起输入框" else "展开输入框",
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp)
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
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                WandIcons.keyboard,
                contentDescription = null,
                tint = targetColor,
                modifier = Modifier.size(16.dp),
            )
            Text(
                if (open) "收起" else "输入",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = targetColor,
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
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (pendingAttachments.isNotEmpty()) {
            PendingAttachmentsPreview(
                attachments = pendingAttachments,
                baseUrl = baseUrl,
                onRemove = onRemoveAttachment,
            )
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ComposerActionsMenu(
                backdrop = null,
                uploading = uploading,
                onPickPhoto = onPickPhoto,
                onPickFile = onPickFile,
            )
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
                            .heightIn(min = 40.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(WandColors.surfaceSoft.copy(alpha = 0.7f))
                            .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
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
                    .weight(1f)
                    .focusRequester(focusRequester),
            )
            VoiceMicButton(
                voice = voice,
                voiceMode = false,
                onToggleMode = {},
                onMicDown = onMicDown,
            )
            PtyDrawerSendButton(enabled = canSend, onClick = onSend)
        }
    }
}
@Composable
private fun PtyDrawerSendButton(enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) WandColors.textPrimary else WandColors.textSecondary.copy(alpha = 0.55f)
    val background = if (enabled) WandColors.brand else WandColors.textSecondary.copy(alpha = 0.16f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(
                enabled = enabled,
                onClickLabel = "发送",
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        Icon(
            WandIcons.arrowUp,
            contentDescription = "发送",
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
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
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        label = "shortcutKeyScale",
    )
    val background by animateColorAsState(
        targetValue = if (pressed) WandColors.surfaceSoft else WandColors.surfaceSoft.copy(alpha = 0.70f),
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

@Composable
private fun PtyTerminalWebView(
    serverUrl: String,
    token: String?,
    sessionId: String,
    onHardwareShortcut: (TerminalShortcut) -> Unit,
    onReadyChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val webSessionOwnerId = remember { "pty-${UUID.randomUUID()}" }
    val activeWebView = remember { AtomicReference<WebView?>(null) }
    val latestHardwareShortcut by rememberUpdatedState(onHardwareShortcut)
    val latestOnReadyChange by rememberUpdatedState(onReadyChange)
    var lifecycleResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var preparationAttempt by remember(serverUrl, token) { mutableStateOf(0) }
    var prepared by remember(serverUrl, token, preparationAttempt) { mutableStateOf(false) }
    var preparationError by remember(serverUrl, token, preparationAttempt) {
        mutableStateOf<String?>(null)
    }
    DisposableEffect(lifecycleOwner, serverUrl, token) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    lifecycleResumed = true
                    activeWebView.get()?.onResume()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    lifecycleResumed = false
                    activeWebView.get()?.onPause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            latestOnReadyChange(false)
            activeWebView.getAndSet(null)?.let(::disposePtyWebView)
            WandWebSession.release(webSessionOwnerId)
        }
    }
    LaunchedEffect(serverUrl, token, preparationAttempt, lifecycleResumed) {
        if (!lifecycleResumed) {
            prepared = false
            latestOnReadyChange(false)
            return@LaunchedEffect
        }
        preparationError = null
        try {
            WandWebSession.prepare(
                webSessionOwnerId,
                serverUrl,
                token,
                WandWebSession.OwnerRevocation {
                    activeWebView.getAndSet(null)?.let(::disposePtyWebView)
                    prepared = false
                    latestOnReadyChange(false)
                },
            )
            if (lifecycleResumed) prepared = true
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            if (lifecycleResumed) preparationError = error.message ?: "网页版认证失败"
        }
    }
    if (!prepared) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (preparationError == null) {
                CircularProgressIndicator(color = WandColors.brand, strokeWidth = 2.dp)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        preparationError ?: "网页版认证失败",
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 13.sp,
                    )
                    Text(
                        "重试",
                        color = WandColors.brand,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .clickable { preparationAttempt += 1 }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
        return
    }

    val webView = remember(serverUrl, sessionId) {
        @SuppressLint("SetJavaScriptEnabled")
        val view = WebView(context)
        activeWebView.getAndSet(view)?.let(::disposePtyWebView)
        view.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        )
        view.setBackgroundColor(TerminalBackgroundArgb)
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
                    // 悬浮球的显隐、拖动和面板展开完全由网页端管理。原生层不再强制
                    // 覆盖 opacity / transform，也不再重复注册焦点手势监听。
                    view.evaluateJavascript(NativeTerminalSetupScript) {
                        if (activeWebView.get() === view && lifecycleResumed) {
                            // 新服务直接识别 passthrough=1；旧服务只做有限次数的兼容启用，
                            // 成功后立即停止，避免永久定时器反复切换终端交互状态。
                            view.evaluateJavascript(EnableTerminalPassthroughScript) {
                                if (activeWebView.get() === view && lifecycleResumed) {
                                    latestOnReadyChange(true)
                                }
                            }
                        }
                    }
                }
            }
            loadUrl(buildEmbedTerminalUrl(serverUrl, sessionId))
        }
    }

    DisposableEffect(webView) {
        onDispose {
            if (activeWebView.compareAndSet(webView, null)) disposePtyWebView(webView)
        }
    }

    AndroidView(
        factory = { webView },
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged {
                if (lifecycleResumed) {
                    webView.evaluateJavascript(RefitTerminalScript, null)
                }
            },
    )
}

private fun disposePtyWebView(webView: WebView) {
    runCatching { webView.settings.blockNetworkLoads = true }
    runCatching { webView.settings.javaScriptEnabled = false }
    runCatching { webView.stopLoading() }
    runCatching { webView.onPause() }
    runCatching { (webView.parent as? android.view.ViewGroup)?.removeView(webView) }
    runCatching { webView.removeAllViews() }
    runCatching { webView.destroy() }
}

private fun buildEmbedTerminalUrl(serverUrl: String, sessionId: String): String =
    Uri.parse(serverUrl).buildUpon()
        .appendQueryParameter("session", sessionId)
        .appendQueryParameter("embed", "terminal")
        .appendQueryParameter("nativeInput", "1")
        .appendQueryParameter("passthrough", "1")
        .build()
        .toString()

private val NativeTerminalSetupScript =
    """
    (function() {
      try {
        var root = document.documentElement;
        root.classList.add('is-wand-app-native-insets');
        root.style.setProperty('--app-inset-top', '0px');
        root.style.setProperty('--app-inset-bottom', '0px');
        root.style.setProperty('--app-inset-left', '0px');
        root.style.setProperty('--app-inset-right', '0px');
        if (!document.getElementById('wand-native-terminal-compact-style')) {
          var style = document.createElement('style');
          style.id = 'wand-native-terminal-compact-style';
          style.textContent =
            'html.is-wand-embed-terminal,html.is-wand-embed-terminal body,' +
            '.is-wand-embed-terminal .main-content{background:#17120f!important;}' +
            '.is-wand-embed-terminal .terminal-scroll-wrap{' +
            'padding:0!important;' +
            '--term-font-family:"Roboto Mono","Droid Sans Mono","Noto Sans Mono","Noto Sans Symbols 2","Noto Sans Symbols",monospace!important;' +
            '--term-font-size:10px!important;--term-row-height:15px!important;}' +
            '.is-wand-embed-terminal .terminal-scroll-wrap .xterm{padding:8px 4px 6px!important;}' +
            '.is-wand-embed-terminal .terminal-container{' +
            'margin:0!important;border:0!important;border-radius:0!important;box-shadow:none!important;}';
          document.head.appendChild(style);
        }
        function fit() {
          try { window.dispatchEvent(new Event('resize')); } catch (e) {}
        }
        requestAnimationFrame(fit);
        setTimeout(fit, 180);
      } catch (e) {}
    })();
    """.trimIndent()

private val RefitTerminalScript =
    """
    (function() {
      try { window.dispatchEvent(new Event('resize')); } catch (e) {}
    })();
    """.trimIndent()

private val EnableTerminalPassthroughScript =
    """
    (function() {
      try {
        document.documentElement.classList.add('is-wand-terminal-passthrough');
        var attempts = 0;
        function enablePassthrough() {
          attempts += 1;
          try {
            var toggle = document.getElementById('terminal-interactive-toggle-top');
            if (toggle && toggle.getAttribute('aria-pressed') !== 'true') toggle.click();
            return !!toggle && toggle.getAttribute('aria-pressed') === 'true';
          } catch (e) {}
          return false;
        }
        if (window.__wandNativePassthroughTimer) {
          clearInterval(window.__wandNativePassthroughTimer);
        }
        window.__wandNativePassthroughTimer = null;
        if (!enablePassthrough()) {
          window.__wandNativePassthroughTimer = setInterval(function() {
            if (enablePassthrough() || attempts >= 12) {
              clearInterval(window.__wandNativePassthroughTimer);
              window.__wandNativePassthroughTimer = null;
            }
          }, 250);
        }
      } catch (e) {}
    })();
    """.trimIndent()

private fun terminalShortcutForHardwareEvent(event: AndroidKeyEvent): TerminalShortcut? {
    // 软件输入法也可能合成 Enter / Del KeyEvent；让它们继续进入 xterm 的
    // InputConnection，避免候选确认被提前当成 PTY 回车，或出现重复退格。
    if (event.action != AndroidKeyEvent.ACTION_DOWN || event.device?.isVirtual != false) return null
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
