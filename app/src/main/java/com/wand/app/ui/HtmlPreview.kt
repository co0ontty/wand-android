package com.wand.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wand.app.data.WandHttp
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandDetailBackButton
import com.wand.app.ui.components.WandDetailTopBar
import com.wand.app.ui.theme.WandColors
import kotlinx.coroutines.CancellationException
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.util.concurrent.TimeUnit

/** The artifact has no native bridge, filesystem access, or shared browser cookies. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlPreviewDialog(baseUrl: String, url: String, label: String, onDismiss: () -> Unit, onExternalLink: (String) -> Unit, onNonHtml: (() -> Unit)? = null) {
    val context = LocalContext.current
    var error by remember(baseUrl, url) { mutableStateOf<String?>(null) }
    var progress by remember(baseUrl, url) { mutableStateOf(0) }
    var retry by remember(baseUrl, url) { mutableStateOf(0) }
    var prepared by remember(baseUrl, url) { mutableStateOf<String?>(null) }
    val browser = remember(context, baseUrl, url) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) { progress = newProgress }
            }
        }
    }
    LaunchedEffect(baseUrl, url, retry) {
        error = null
        progress = 0
        prepared = null
        browser.stopLoading()
        try {
            val finalUrl = WandMessageLinkTransport.preparePreview(baseUrl, url)
            val scope = WandMessageLinkTransport.previewScope(finalUrl, baseUrl)
                ?: throw IllegalStateException("预览地址无效。")
            browser.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url.toString()
                    if (request.method == "GET" && WandMessageLinkTransport.isInPreviewScope(target, baseUrl, scope)) return false
                    if (request.isForMainFrame && request.hasGesture() && request.url.scheme in listOf("http", "https")) {
                        onExternalLink(target)
                    }
                    return true
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    return try {
                        val target = request.url.toString()
                        val local = WandMessageLinkTransport.isInPreviewScope(target, baseUrl, scope)
                        // Settings WebViews may have Chromium cookies. Every request is intercepted;
                        // only scoped preview resources use the endpoint's OkHttp cookie jar.
                        val publicAsset = !request.isForMainFrame && request.url.scheme == "https" && !WandHttp.isSameOrigin(target, baseUrl)
                        if (request.method !in listOf("GET", "HEAD") || (!local && !publicAsset)) return deniedResponse()
                        val response = WandMessageLinkTransport.request(baseUrl, target, request.method,
                            client = if (local) WandHttp.clientFor(baseUrl) else previewPublicClient,
                            allowed = { next -> if (local) WandMessageLinkTransport.isInPreviewScope(next, baseUrl, scope)
                                else next.startsWith("https://") && !WandHttp.isSameOrigin(next, baseUrl) })
                        val body = response.body
                        val mime = response.header("Content-Type").orEmpty().substringBefore(';').trim().ifEmpty { "application/octet-stream" }
                        if ((request.isForMainFrame && !mime.equals("text/html", true)) ||
                            (mime.equals("text/html", true) && !WandMessageLinkTransport.hasIsolatedHtmlPolicy(response.header("Content-Security-Policy")))) {
                            response.close()
                            if (request.isForMainFrame) view.post { error = "服务器尚未提供隔离预览，请更新 Wand 服务后重试。" }
                            return deniedResponse()
                        }
                        val headers = response.headers.toMultimap().filterKeys { it.lowercase() !in setOf("set-cookie", "set-cookie2", "connection", "transfer-encoding") }
                            .mapValues { it.value.joinToString(", ") }
                        val stream = object : FilterInputStream(body?.byteStream() ?: ByteArrayInputStream(byteArrayOf())) {
                            override fun close() { try { super.close() } finally { response.close() } }
                        }
                        WebResourceResponse(mime, body?.contentType()?.charset()?.name(), response.code,
                            response.message.ifBlank { "Response" }, headers, stream)
                    } catch (_: Exception) {
                        if (request.isForMainFrame) view.post { error = "预览加载失败，请检查连接后重试。" }
                        deniedResponse()
                    }
                }

                override fun onPageStarted(view: WebView?, current: String?, favicon: Bitmap?) { progress = 0 }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, failure: WebResourceError?) {
                    if (request?.isForMainFrame == true) error = "预览加载失败，请检查连接后重试。"
                }
                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
                    if (request?.isForMainFrame == true && error == null) error = "预览加载失败（HTTP ${response?.statusCode}）。"
                }
                override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                    error = "网页预览已关闭，请返回后重新打开。"
                    return true
                }
            }
            prepared = finalUrl
            browser.loadUrl(finalUrl)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: WandMessageLinkTransport.NotHtml) {
            if (onNonHtml != null) onNonHtml() else error = "此链接没有返回 HTML 页面。"
        }
        catch (failure: Exception) { error = failure.message ?: "无法加载预览，请重试。" }
    }
    DisposableEffect(browser) {
        onDispose {
            browser.stopLoading()
            browser.destroy()
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(WandColors.bgElevated)) {
            WandDetailTopBar(title = label.substringAfterLast('/').ifBlank { "HTML 预览" },
                leading = { WandDetailBackButton(onClick = onDismiss) })
            if (progress < 100 && error == null) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            if (error != null) {
                Text(error.orEmpty(), Modifier.padding(16.dp), color = WandColors.danger, style = MaterialTheme.typography.bodyMedium)
                WandButton("重新加载", { retry++ }, modifier = Modifier.padding(horizontal = 16.dp))
            }
            if (prepared != null) AndroidView(factory = { browser }, modifier = Modifier.weight(1f).fillMaxWidth())
        }
    }
}

private val previewPublicClient by lazy {
    OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
}

private fun deniedResponse() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))
