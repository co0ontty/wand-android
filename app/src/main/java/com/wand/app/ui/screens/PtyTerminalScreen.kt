package com.wand.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.WandApi
import com.wand.app.ui.QuickCommitStore
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.theme.AmbientBackground
import com.wand.app.ui.theme.GlassBackdrop
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandGlass
import com.wand.app.ui.theme.glassBackdropSource
import com.wand.app.ui.theme.glassSurface
import com.wand.app.ui.theme.rememberGlassBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * PTY 会话原生壳：顶部用原生头部（返回 + provider 徽标 + 标题/工作目录），
 * 下方嵌一层加载 `embed=terminal` 的 WebView，只展示终端黑窗 + 输入栏 + 悬浮球。
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
    onBack: () -> Unit,
) {
    var snapshot by remember(sessionId) { mutableStateOf<SessionSnapshot?>(null) }
    var draft by remember(sessionId) { mutableStateOf("") }
    var sending by remember(sessionId) { mutableStateOf(false) }
    var toast by remember(sessionId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val quickCommit = remember(sessionId) {
        QuickCommitStore(sessionId, api) { msg -> toast = msg }
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
        quickCommit.loadStatus(force = true)
    }

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
                onDraftChange = { draft = it },
                onSend = {
                    val text = draft.trim()
                    if (text.isEmpty() || sending) return@PtyNativeInputBar
                    draft = ""
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
                            draft = text
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
                .glassBackdropSource(glassBackdrop),
        ) {
            AmbientBackground(Modifier.fillMaxSize())
            val terminalShape = RoundedCornerShape(18.dp)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 6.dp, vertical = 8.dp)
                    .clip(terminalShape)
                    .background(Color.Black)
                    .border(1.dp, WandColors.border.copy(alpha = 0.72f), terminalShape),
            ) {
                PtyTerminalWebView(
                    serverUrl = api.baseUrl,
                    sessionId = sessionId,
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
                .height(56.dp)
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
                Text(
                    ptyMiddleTruncate(snapshot?.cwd ?: "未设置工作目录", 44),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = WandColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = WandColors.textSecondary,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun PtyNativeInputBar(
    backdrop: GlassBackdrop,
    draft: String,
    sending: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }
    var refocusAfterSend by remember { mutableStateOf(false) }
    val expanded = isFocused || draft.isNotBlank()

    LaunchedEffect(refocusAfterSend, sending) {
        if (refocusAfterSend && !sending) {
            refocusAfterSend = false
            runCatching { focusRequester.requestFocus() }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(
                backdrop,
                RoundedCornerShape(0.dp),
                WandGlass.regular.copy(refractionHeight = 0.dp, shadowElevation = 0.dp),
                edgeToEdge = true,
            )
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!expanded) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
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
        BasicTextField(
            value = draft,
            onValueChange = onDraftChange,
            enabled = !sending,
            textStyle = TextStyle(
                color = WandColors.textPrimary,
                fontSize = 15.sp,
                lineHeight = 20.sp,
            ),
            cursorBrush = SolidColor(WandColors.brand),
            minLines = 1,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 40.dp, max = 108.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(WandColors.surface.copy(alpha = 0.72f))
                .border(
                    0.7.dp,
                    if (isFocused) WandColors.brand.copy(alpha = 0.72f) else WandColors.border,
                    RoundedCornerShape(16.dp),
                )
                .focusRequester(focusRequester)
                .onFocusChanged { isFocused = it.isFocused }
                .padding(horizontal = 13.dp, vertical = 9.dp),
            decorationBox = { innerTextField ->
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (draft.isEmpty()) {
                        Text(
                            "输入到终端会话",
                            fontSize = 15.sp,
                            color = WandColors.textMuted,
                        )
                    }
                    innerTextField()
                }
            },
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (draft.isBlank() || sending) WandColors.surfaceSoft.copy(alpha = 0.72f) else WandColors.brand)
                .clickable(enabled = draft.isNotBlank() && !sending) {
                    refocusAfterSend = true
                    onSend()
                },
        ) {
            Icon(
                WandIcons.arrowUp,
                contentDescription = "发送",
                tint = if (draft.isBlank() || sending) WandColors.textMuted else Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun PtyTerminalWebView(serverUrl: String, sessionId: String) {
    val context = LocalContext.current
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
                            ".is-wand-embed-terminal .input-panel{display:none!important;}" +
                            ".is-wand-embed-terminal .terminal-container{margin:0!important;border-left:0!important;border-right:0!important;border-radius:0!important;box-shadow:none!important;}" +
                            "';" +
                            "document.head.appendChild(s);}" +
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
private fun PtyProviderBadge(provider: String?) {
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

/** 中间截断（与 ChatScreen middleTruncate 同款，避免改其可见性）。 */
private fun ptyMiddleTruncate(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val head = (maxChars - 1) / 2
    val tail = maxChars - 1 - head
    return text.take(head) + "…" + text.takeLast(tail)
}
