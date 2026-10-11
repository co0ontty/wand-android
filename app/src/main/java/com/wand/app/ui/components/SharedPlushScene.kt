package com.wand.app.ui.components

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wand.app.ui.theme.reduceMotionEnabled
import org.json.JSONObject

/** A single, offline shared Web scene in chat chrome. Lists keep bounded PNGs.
 * No network, external navigation, cookies, JavaScript bridge or untrusted HTML.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun SharedPlushScene(asset: String, size: Dp, activity: AvatarFileActivity, thinking: Boolean, speaking: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val reduced = reduceMotionEnabled()
    var ready by remember(asset) { mutableStateOf(false) }
    var loaded by remember(asset) { mutableStateOf(false) }
    var failed by remember(asset) { mutableStateOf(false) }
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val config = remember(asset) { plushSceneConfig(asset) }
    val view = remember(asset, context) {
        WebView(context).apply {
            setBackgroundColor(AndroidColor.TRANSPARENT)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isFocusable = false
            isClickable = false
            settings.javaScriptEnabled = true
            settings.blockNetworkLoads = true
            settings.allowContentAccess = false
            settings.allowFileAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.domStorageEnabled = false
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = request?.url?.scheme !in listOf("about", "data")
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    if (request?.url?.scheme in listOf("about", "data")) return null
                    // The entire trusted document is inline; every fetch is denied.
                    return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), java.io.ByteArrayInputStream(byteArrayOf()))
                }
                override fun onPageFinished(view: WebView?, url: String?) { loaded = true; android.util.Log.d("WandAvatarScene", "document-loaded") }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                    if (request?.isForMainFrame == true) { ready = false; loaded = false; failed = true }
                }
                override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                    ready = false
                    loaded = false
                    failed = true
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    ready = title == "scene-ready"
                    if (title == "scene-ready" || title == "scene-fallback") android.util.Log.d("WandAvatarScene", "state=$title")
                }
            }
            val runtime = context.assets.open("plush-scene/runtime.js").bufferedReader().use { it.readText() }
            val html = context.assets.open("plush-scene/index.html").bufferedReader().use { it.readText() }
                .replace("<script src=\"runtime.js\"></script>", "<script>$runtime</script>")
            // Base64 avoids fragment/percent decoding in an opaque data document.
            loadData(android.util.Base64.encodeToString(html.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP), "text/html", "base64")
        }
    }
    DisposableEffect(view, lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!failed) { if (resumed) view.onResume() else view.onPause() }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (!failed) {
                view.evaluateJavascript("window.disposeScene&&window.disposeScene()", null)
                view.stopLoading()
            }
            view.destroy()
        }
    }
    Box(Modifier.size(size)) {
        // Keep the AndroidView composition slot stable when the scene first becomes ready.
        Box(Modifier.alpha(if (ready) 0f else 1f)) { PlushAvatar(asset, size, floating = false) }
        if (!failed) AndroidView(factory = { view }, modifier = Modifier.size(size), update = {
            // A zero-alpha Android WebView can suppress its first composited frame.
            // Keep the transparent scene visible while the native portrait backs it.
            it.alpha = 1f
            if (loaded && config != null) {
                val options = JSONObject().put("config", config).put("size", size.value)
                    .put("speaking", speaking && resumed).put("thinking", thinking && resumed)
                    .put("interactive", false).put("reducedMotion", reduced || !resumed)
                    .put("fileActivity", if (resumed) activity.wire else "idle")
                it.evaluateJavascript("window.setScene(${options})", null)
            }
        })
    }
}

internal fun plushSceneConfig(asset: String): JSONObject? {
    if (asset == PlushAvatarIdentity.dogAsset) {
        return JSONObject().put("version", 1).put("kind", "dog").put("breed", "shiba").put("scarf", "red")
    }
    if (asset == "plush_cat_silver" || asset == "plush_cat_orange") {
        return JSONObject().put("version", 1).put("kind", "cat").put("coat", asset.removePrefix("plush_cat_"))
    }
    val parts = asset.removePrefix("plush_").split("_")
    if (parts.size != 4) return null
    return JSONObject().put("version", 1).put("shape", parts[0]).put("color", parts[1]).put("glasses", parts[2]).put("hat", parts[3])
}
