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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.WandApi
import com.wand.app.ui.components.BrandLogos
import com.wand.app.ui.theme.WandColors

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
    onBack: () -> Unit,
) {
    var snapshot by remember(sessionId) { mutableStateOf<SessionSnapshot?>(null) }

    // 仅为顶栏拉一次会话快照（标题 / provider / 工作目录）；失败就退化成最简头部。
    LaunchedEffect(sessionId) {
        snapshot = try {
            api.getSession(sessionId)
        } catch (_: Exception) {
            null
        }
    }

    Scaffold(
        containerColor = WandColors.bgPrimary,
        topBar = {
            CenterAlignedTopAppBar(
                modifier = Modifier.background(WandColors.bgPrimary.copy(alpha = 0.98f)),
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            snapshot?.displayTitle ?: "终端会话",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WandColors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 190.dp),
                        )
                        Text(
                            ptyMiddleTruncate(snapshot?.cwd ?: "未设置工作目录", 44),
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            color = WandColors.textSecondary,
                            maxLines = 1,
                            modifier = Modifier.widthIn(max = 190.dp),
                        )
                    }
                },
                navigationIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = WandColors.textSecondary,
                            )
                        }
                        PtyProviderBadge(snapshot?.provider)
                    }
                },
                actions = { Spacer(modifier = Modifier.size(48.dp)) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 终端输入栏要避开软键盘：imePadding 让 WebView 在键盘弹起时收缩，
                // 网页据更新后的 window.innerHeight 把输入栏顶到键盘上方。
                .imePadding()
                .navigationBarsPadding(),
        ) {
            PtyTerminalWebView(serverUrl = api.baseUrl, sessionId = sessionId)
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
                            ".is-wand-embed-terminal .terminal-scale-overlay{transform:scale(.86);transform-origin:top right;opacity:.24;transition:opacity .16s ease,transform .16s ease;}" +
                            ".is-wand-embed-terminal .terminal-scale-overlay:hover,.is-wand-embed-terminal .terminal-scale-overlay:focus-within,.is-wand-embed-terminal .terminal-scale-overlay:active{opacity:1;transform:scale(.94);}" +
                            ".is-wand-embed-terminal .wand-joystick-root{opacity:.26;transform:scale(.82);transform-origin:bottom right;transition:opacity .16s ease,transform .16s ease;}" +
                            ".is-wand-embed-terminal .wand-joystick-root:has(.panel-open),.is-wand-embed-terminal .wand-joystick-root:active{opacity:1;transform:scale(.94);}" +
                            "';" +
                            "document.head.appendChild(s);}" +
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
