package com.wand.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.wand.app.data.ServerProfile
import com.wand.app.data.SettingsWebAuthentication
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandDetailBackButton
import com.wand.app.ui.components.WandDetailTopBar
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandSpacing
import com.wand.app.ui.theme.WandTheme
import java.io.ByteArrayInputStream

/** A settings-only embedded browser; native sessions/terminal continue to use their existing runtime. */
class WebSettingsActivity : AppCompatActivity() {
    private lateinit var profile: ServerProfile
    private lateinit var session: SettingsWebSession
    private var webView: WebView? = null
    private var loadingProgress by mutableStateOf(0)
    private var loadError by mutableStateOf<String?>(null)
    private var fileResult: ValueCallback<Array<Uri>>? = null
    private var disposed = false
    private var authentication: Job? = null
    private var authenticationGeneration = 0L
    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileResult?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileResult = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ServerStore(this)
        val selected = intent.getStringExtra(EXTRA_SERVER_ID)?.let(store::getServerProfile)
        if (selected == null || store.activeServerProfile?.id != selected.id) {
            finish()
            return
        }
        profile = selected
        session = SettingsWebSession(profile.baseUrl)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val browser = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
            settings.userAgentString += " WandApp/$version WandPlatform/Android"
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (session.accepts(request.url.toString())) return false
                    if (request.isForMainFrame && request.hasGesture() &&
                        request.url.scheme in listOf("http", "https")) {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    }
                    return true
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (session.accepts(request.url.toString())) return null
                    return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(),
                        ByteArrayInputStream(ByteArray(0)))
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    // Matches native WandHttp's selected-server self-signed policy, never foreign origins.
                    if (session.accepts(error.url)) handler.proceed() else handler.cancel()
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    loadingProgress = 0
                    loadError = null
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) loadError = "设置页面加载失败，请检查连接后重试"
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (!request.isForMainFrame) return
                    loadingProgress = 100
                    loadError = if (response.statusCode == 404)
                        "服务器尚不支持完整设置页面，请更新 Wand 服务后重试"
                    else "设置页面加载失败（HTTP ${response.statusCode}），请重试"
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) { loadingProgress = newProgress }
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>,
                    params: FileChooserParams): Boolean {
                    fileResult?.onReceiveValue(null)
                    fileResult = callback
                    return runCatching { filePicker.launch(params.createIntent()); true }.getOrElse {
                        fileResult?.onReceiveValue(null)
                        fileResult = null
                        false
                    }
                }
            }
        }
        webView = browser
        setContent {
            WandTheme {
                Column(Modifier.fillMaxSize()) {
                    WandDetailTopBar(title = "完整 Web 设置",
                        leading = { WandDetailBackButton(onClick = { finish() }) })
                    if (loadingProgress < 100) LinearProgressIndicator(progress = { loadingProgress / 100f }, modifier = Modifier.fillMaxWidth())
                    loadError?.let { error ->
                        Text(error, color = WandColors.danger, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(WandSpacing.md))
                        WandButton(label = "重新加载", onClick = { loadSettings() },
                            modifier = Modifier.padding(horizontal = WandSpacing.md))
                    }
                    AndroidView(factory = { browser }, modifier = Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
        loadSettings()
    }

    private fun loadSettings() {
        val browser = webView ?: return
        authentication?.cancel()
        val generation = ++authenticationGeneration
        loadingProgress = 0
        loadError = null
        authentication = lifecycleScope.launch {
            try {
                val values = SettingsWebAuthentication.cookies(session.baseUrl, profile.token)
                if (disposed || generation != authenticationGeneration) return@launch
                val store = ServerStore(this@WebSettingsActivity)
                if (!settingsConnectionUnchanged(profile, store.getServerProfile(profile.id), store.activeServerProfile?.id)) {
                    finish()
                    return@launch
                }
                // Chromium is not port-isolated. Only this endpoint's client cookie and bound proof are seeded.
                val cookies = CookieManager.getInstance()
                cookies.setAcceptCookie(true)
                cookies.removeAllCookies {
                    if (disposed || generation != authenticationGeneration) return@removeAllCookies
                    var remaining = values.size
                    values.forEach { cookie ->
                        cookies.setCookie(session.startUrl, cookie) {
                            if (disposed || generation != authenticationGeneration) return@setCookie
                            remaining -= 1
                            if (remaining == 0) browser.loadUrl(session.startUrl)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (disposed || generation != authenticationGeneration) return@launch
                loadingProgress = 100
                loadError = if (error is IllegalStateException) error.message else "无法连接服务器，请检查网络后重试"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::profile.isInitialized) return
        val store = ServerStore(this)
        if (!settingsConnectionUnchanged(profile, store.getServerProfile(profile.id), store.activeServerProfile?.id)) finish()
        else webView?.onResume()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        disposed = true
        authentication?.cancel()
        fileResult?.onReceiveValue(null)
        fileResult = null
        webView?.apply {
            stopLoading()
            (parent as? android.view.ViewGroup)?.removeView(this)
            destroy()
        }
        webView = null
        CookieManager.getInstance().removeAllCookies(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SERVER_ID = "server_id"
    }
}
