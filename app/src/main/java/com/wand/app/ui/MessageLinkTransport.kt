package com.wand.app.ui

import com.wand.app.data.WandHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/** Read-only, bounded redirects. Authenticated requests cannot leave this endpoint's file routes. */
object WandMessageLinkTransport {
    private val fileRoute = Regex("^/api/(?:file-raw|file-preview|session-file/[^/]+)$")
    private val localFileRoute = Regex("^/api/local-file/[A-Za-z0-9_-]+(?:/.*)?$")
    private val localServiceRoute = Regex("^/api/local-preview/(?:localhost|127\\.0\\.0\\.1|::1|%3A%3A1|\\[::1])/\\d{1,5}(?:/.*)?$", RegexOption.IGNORE_CASE)

    fun isFileRoute(url: String, baseUrl: String): Boolean = WandMessageLinks.apiRoute(url, baseUrl)
        ?.substringBefore('?')?.substringBefore('#')?.let { fileRoute.matches(it) || localFileRoute.matches(it) } == true

    class NeedsDownload : IOException("文件过大，正在下载原文件。")
    class NotHtml : IOException("此链接没有返回 HTML 页面。")

    fun previewScope(url: String, baseUrl: String): String? {
        val route = WandMessageLinks.apiRoute(url, baseUrl)?.substringBefore('?')?.substringBefore('#') ?: return null
        return when {
            localFileRoute.matches(route) -> route.split('/').take(4).joinToString("/") + "/"
            localServiceRoute.matches(route) -> route.split('/').take(5).joinToString("/") + "/"
            else -> null
        }
    }

    fun isInPreviewScope(url: String, baseUrl: String, scope: String): Boolean {
        val route = WandMessageLinks.apiRoute(url, baseUrl) ?: return false
        val path = route.substringBefore('?').substringBefore('#')
        return previewScope(url, baseUrl) == scope && (path == scope.trimEnd('/') || path.startsWith(scope))
    }

    /** Reject old or permissive servers before WebView can execute their HTML. */
    fun hasIsolatedHtmlPolicy(policy: String?): Boolean {
        val directives = policy.orEmpty().lowercase().split(';').map { it.trim().split(Regex("\\s+")) }
        val sandbox = directives.firstOrNull { it.firstOrNull() == "sandbox" } ?: return false
        return sandbox.drop(1).all { it in setOf("allow-scripts", "allow-forms") } &&
            directives.any { it == listOf("connect-src", "'none'") } &&
            directives.any { it == listOf("form-action", "'none'") }
    }

    internal fun request(
        baseUrl: String, url: String, method: String = "GET",
        client: OkHttpClient = WandHttp.clientFor(baseUrl),
        allowed: (String) -> Boolean = { isFileRoute(it, baseUrl) },
    ): Response {
        var current = url
        val boundedClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        repeat(6) {
            if (!allowed(current)) throw IOException("服务器把文件重定向到了不支持的地址。")
            val response = boundedClient.newCall(Request.Builder().url(current).method(method, null).build()).execute()
            if (response.code !in setOf(301, 302, 303, 307, 308)) return response
            val location = response.header("Location")
            response.close()
            if (location.isNullOrBlank()) throw IOException("文件重定向缺少目标地址。")
            val previous = current.toHttpUrlOrNull() ?: throw IOException("文件地址无效。")
            val next = previous.resolve(location) ?: throw IOException("文件重定向地址无效。")
            current = if (!location.contains('#') && previous.encodedFragment != null) {
                next.newBuilder().encodedFragment(previous.encodedFragment).build().toString()
            } else next.toString()
        }
        throw IOException("文件重定向次数过多。")
    }

    /** Non-HTML loopback responses retain the same immutable host/port scope during download. */
    internal fun requestPreviewFile(baseUrl: String, url: String): Response {
        val scope = previewScope(url, baseUrl) ?: throw IOException("本地服务文件地址无效。")
        val response = request(baseUrl, url, allowed = { isInPreviewScope(it, baseUrl, scope) })
        if (response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase() in setOf("text/html", "application/xhtml+xml")) {
            response.close()
            throw IOException("链接已返回网页，请重新打开预览。")
        }
        return response
    }

    suspend fun resolveFilePath(baseUrl: String, target: MessageLinkTarget.File): String = withContext(Dispatchers.IO) {
        target.absolutePath?.let { return@withContext WandServerFileLink.resolvePreviewPath(baseUrl, it) }
        val metadata = target.metadataUrl ?: throw IOException("文件来源不可用。")
        request(baseUrl, metadata).use { response ->
            if (response.code == 413) throw NeedsDownload()
            if (!response.isSuccessful) throw IOException(if (response.code == 404) "文件或来源会话不存在；旧版服务器请先更新。" else "文件读取失败（HTTP ${response.code}）。")
            val source = response.body?.source() ?: throw IOException("服务器没有返回文件信息。")
            if (source.request(768 * 1024 + 1L) && source.buffer.size > 768 * 1024) throw IOException("文件信息过大。")
            val parsed = runCatching { JSONObject(source.readUtf8()) }.getOrNull()
                ?: throw IOException("服务器未返回文件信息，请更新 Wand 服务后重试。")
            parsed.optString("path").takeIf { it.startsWith('/') && !it.startsWith("//") && it.none(Char::isISOControl) }
                ?: throw IOException("服务器返回的文件路径无效。")
        }
    }

    suspend fun preparePreview(baseUrl: String, url: String): String = withContext(Dispatchers.IO) {
        val initialScope = previewScope(url, baseUrl)
        val allowed: (String) -> Boolean = { candidate ->
            if (initialScope != null) isInPreviewScope(candidate, baseUrl, initialScope)
            else isFileRoute(candidate, baseUrl) || previewScope(candidate, baseUrl) != null
        }
        val head = request(baseUrl, url, method = "HEAD", allowed = allowed)
        val result = if (head.code == 405 || head.code == 501) {
            head.close()
            request(baseUrl, url, allowed = allowed)
        } else head
        result.use { response ->
            if (!response.isSuccessful) throw IOException("预览加载失败（HTTP ${response.code}）。")
            val final = response.request.url.toString()
            if (previewScope(final, baseUrl) == null) throw IOException("服务器不支持这个预览链接，请更新 Wand 服务。")
            if (!response.header("Content-Type").orEmpty().substringBefore(';').equals("text/html", true)) throw NotHtml()
            if (!hasIsolatedHtmlPolicy(response.header("Content-Security-Policy"))) throw IOException("服务器尚未提供隔离预览，请更新 Wand 服务后重试。")
            final
        }
    }
}
