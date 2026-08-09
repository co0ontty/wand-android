package com.wand.app.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.view.KeyEvent as AndroidKeyEvent
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.WandApi
import com.wand.app.data.WandWebSession
import com.wand.app.data.providerDisplayName
import com.wand.app.ui.QuickCommitStore
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.components.TailMarqueePathText
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
import com.wand.app.ui.terminal.DefaultTerminalShortcuts
import com.wand.app.ui.terminal.TerminalKeyBinding
import com.wand.app.ui.terminal.TerminalModifier
import com.wand.app.ui.terminal.TerminalShortcut
import com.wand.app.ui.terminal.buildTerminalShortcut
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

private const val TERMINAL_BACKGROUND_ARGB = 0xFF17120F.toInt()
private val TerminalBackground = Color(TERMINAL_BACKGROUND_ARGB)

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
    isHapticEnabled: () -> Boolean,
    onBack: () -> Unit,
) {
    var snapshot by remember(sessionId) { mutableStateOf<SessionSnapshot?>(null) }
    var snapshotResolved by remember(sessionId) { mutableStateOf(false) }
    var toast by remember(sessionId) { mutableStateOf<String?>(null) }
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

    val glassBackdrop = rememberGlassBackdrop()
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            PtyTopBar(
                backdrop = glassBackdrop,
                snapshot = snapshot,
                serverDisplayName = serverDisplayName,
                quickCommit = quickCommit,
                onBack = onBack,
                onOpenQuickCommit = { quickCommit.openPanel() },
            )
        },
        bottomBar = {
            PtyShortcutBar(
                backdrop = glassBackdrop,
                isHapticEnabled = isHapticEnabled,
                onShortcut = { shortcutQueue.trySend(it) },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .glassBackdropSource(glassBackdrop),
        ) {
            AmbientBackground(Modifier.fillMaxSize())
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(TerminalBackground),
            ) {
                if (snapshotResolved) {
                    PtyTerminalWebView(
                        serverUrl = api.baseUrl,
                        token = api.token,
                        sessionId = sessionId,
                        onHardwareShortcut = { shortcutQueue.trySend(it) },
                    )
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
    backdrop: GlassBackdrop,
    snapshot: SessionSnapshot?,
    serverDisplayName: String,
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
                    path = snapshot?.cwd?.takeIf { it.isNotBlank() }?.let {
                        "$serverDisplayName · $it"
                    } ?: serverDisplayName,
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
private fun PtyShortcutBar(
    backdrop: GlassBackdrop,
    isHapticEnabled: () -> Boolean,
    onShortcut: (TerminalShortcut) -> Unit,
) {
    val haptic = LocalHapticFeedback.current

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
        HorizontalDivider(thickness = 0.5.dp, color = WandColors.border.copy(alpha = 0.72f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp),
        ) {
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

@Composable
private fun TerminalShortcutKey(
    shortcut: TerminalShortcut,
    onClick: () -> Unit,
) {
    val compact = shortcut.label.length == 1
    val emphasized = shortcut.binding.modifiers.isNotEmpty()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(40.dp)
            .widthIn(min = if (compact) 42.dp else 52.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(
                if (emphasized) WandColors.brandSoft
                else WandColors.surfaceSoft.copy(alpha = 0.76f),
            )
            .border(
                0.6.dp,
                if (emphasized) WandColors.brand.copy(alpha = 0.24f) else WandColors.borderStrong,
                RoundedCornerShape(11.dp),
            )
            .clickable(
                onClickLabel = shortcut.accessibilityLabel,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = if (compact) 10.dp else 12.dp),
    ) {
        Text(
            shortcut.label,
            color = if (emphasized) WandColors.brand else WandColors.textPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = if (compact) 18.sp else 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun PtyTerminalWebView(
    serverUrl: String,
    token: String?,
    sessionId: String,
    onHardwareShortcut: (TerminalShortcut) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val webSessionOwnerId = remember { "pty-${UUID.randomUUID()}" }
    val activeWebView = remember { AtomicReference<WebView?>(null) }
    val latestHardwareShortcut by rememberUpdatedState(onHardwareShortcut)
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
                    preparationAttempt += 1
                }
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                -> {
                    lifecycleResumed = false
                    prepared = false
                    activeWebView.getAndSet(null)?.let(::disposePtyWebView)
                    WandWebSession.release(webSessionOwnerId)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            activeWebView.getAndSet(null)?.let(::disposePtyWebView)
            WandWebSession.release(webSessionOwnerId)
        }
    }
    LaunchedEffect(serverUrl, token, preparationAttempt, lifecycleResumed) {
        if (!lifecycleResumed) {
            prepared = false
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
        view.setBackgroundColor(TERMINAL_BACKGROUND_ARGB)
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
                    // PTY 页面只有一个输入面：xterm 的 InputConnection。新服务直接识别
                    // passthrough=1；旧服务则通过现有交互开关进入同一模式，保证混合版本可用。
                    view.evaluateJavascript(EnableTerminalPassthroughScript, null)
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

    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
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

private val EnableTerminalPassthroughScript =
    """
    (function() {
      try {
        document.documentElement.classList.add('is-wand-terminal-passthrough');
        function enablePassthrough() {
          try {
            var toggle = document.getElementById('terminal-interactive-toggle-top');
            if (toggle && toggle.getAttribute('aria-pressed') !== 'true') toggle.click();
          } catch (e) {}
        }
        enablePassthrough();
        if (window.__wandNativePassthroughTimer) {
          clearInterval(window.__wandNativePassthroughTimer);
        }
        window.__wandNativePassthroughTimer = setInterval(enablePassthrough, 750);
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
