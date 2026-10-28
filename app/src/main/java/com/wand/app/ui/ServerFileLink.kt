package com.wand.app.ui

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import com.wand.app.data.WandAuth
import com.wand.app.data.WandHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.coroutines.coroutineContext

/** Resolves Markdown links that point at files on the connected Wand server. */
object WandServerFileLink {
    private val WEB_ROUTE_PREFIX = Regex("^/(?:api|android|macos)(?:/|$)")
    private val HASH_LINE_SUFFIX = Regex("#L\\d+(?:C\\d+)?$", RegexOption.IGNORE_CASE)
    private val COLON_LINE_SUFFIX = Regex(":\\d+(?::\\d+)?$")
    private val UNSAFE_FILE_NAME = Regex("[\\u0000-\\u001f/\\\\]")

    /**
     * Accepts the absolute paths emitted by CLI agents, including the clickable
     * `:line[:column]`, `#LxCy`, and `file://` forms. Ordinary web URLs stay null.
     */
    fun serverPath(target: String?): String? {
        var value = target?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.startsWith("<") && value.endsWith(">")) {
            value = value.drop(1).dropLast(1).trim()
        }

        if (value.startsWith("file://", ignoreCase = true)) {
            value = runCatching {
                val uri = URI(value)
                if (!uri.host.isNullOrEmpty() && !uri.host.equals("localhost", ignoreCase = true)) {
                    return null
                }
                uri.path.orEmpty()
            }.getOrElse {
                value.replace(Regex("^file://(?:localhost)?", RegexOption.IGNORE_CASE), "")
            }
        } else {
            if (!value.startsWith("/") || value.startsWith("//") || WEB_ROUTE_PREFIX.containsMatchIn(value)) {
                return null
            }
            value = value.replace(HASH_LINE_SUFFIX, "")
            value = decodePercentPath(value)
        }

        return value.takeIf { it.startsWith("/") }
    }

    /** File URLs from the active endpoint use its authenticated downloader, never browser cookies.
     * Different hosts/ports/base paths are not implicitly trusted aliases.
     */
    fun downloadPath(target: String, baseUrl: String): String? {
        serverPath(target)?.let { return it }
        if (baseUrl.isBlank()) return null
        return runCatching {
            val uri = URI(target)
            val base = URI(WandHttp.normalizeBaseUrl(baseUrl))
            if (uri.rawUserInfo != null) return null
            if (uri.scheme == null) {
                if (uri.host != null || !target.startsWith("/api/file-raw?") || uri.rawPath != "/api/file-raw") return null
            } else {
                if (!WandHttp.isSameOrigin(target, baseUrl) || uri.rawPath != base.rawPath.trimEnd('/') + "/api/file-raw") return null
            }
            val paths = uri.rawQuery.orEmpty().split('&').map { it.split('=', limit = 2) }
                .filter { URLDecoder.decode(it[0], StandardCharsets.UTF_8.name()) == "path" }
            if (paths.size != 1 || paths[0].size != 2) return null
            // Decode exactly once, retaining literal percent characters in filenames.
            val path = URLDecoder.decode(paths[0][1], StandardCharsets.UTF_8.name())
            path.takeIf { it.startsWith('/') && !it.startsWith("//") && it.none { ch -> ch.isISOControl() } }
        }.getOrNull()
    }

    /** Download only: the caller presents in-place status, without launching an external app or a Toast. */
    suspend fun download(context: Context, baseUrl: String, serverPath: String, token: String? = null): String =
        downloadToDownloads(context, baseUrl, serverPath, token).fileName

    /** Only classify the editor suffix here; the real filename is always requested first. */
    fun previewTypePath(serverPath: String): String = serverPath.replace(COLON_LINE_SUFFIX, "")

    /** Preserve in-app text/image preview for editor links, including older servers. */
    suspend fun resolvePreviewPath(baseUrl: String, serverPath: String): String {
        if (previewTypePath(serverPath) == serverPath) return serverPath
        return withContext(Dispatchers.IO) {
            val result = requestFile(baseUrl, serverPath, method = "HEAD")
            result.response.use { response ->
                if (!response.isSuccessful) throw IllegalStateException("服务端返回 HTTP ${response.code}")
                result.path
            }
        }
    }

    internal data class FileResponse(val path: String, val response: Response)

    /** A numeric colon suffix can be a real filename. Retry only a missing/non-file path. */
    internal suspend fun requestFile(
        baseUrl: String, serverPath: String, method: String = "GET", token: String? = null,
        client: OkHttpClient = WandHttp.clientFor(baseUrl),
    ): FileResponse {
        val endpoint = WandHttp.normalizeBaseUrl(baseUrl)
        val stripped = previewTypePath(serverPath)
        val candidates = if (stripped == serverPath) listOf(serverPath) else listOf(serverPath, stripped)
        for ((index, candidate) in candidates.withIndex()) {
            coroutineContext.ensureActive()
            val url = "$endpoint/api/file-raw".toHttpUrl().newBuilder()
                .addQueryParameter("download", "1")
                .addQueryParameter("path", candidate)
                .build()
            val request = Request.Builder().url(url).method(method, null).build()
            val first = client.newCall(request).execute()
            val response = if (first.code == 401 && !token.isNullOrBlank()) {
                first.close()
                WandAuth.loginWithToken(endpoint, token, client)
                client.newCall(request).execute()
            } else first
            if ((response.code == 400 || response.code == 404) && index + 1 < candidates.size) {
                response.close()
                continue
            }
            return FileResponse(candidate, response)
        }
        error("没有可下载的文件路径")
    }

    private suspend fun downloadToDownloads(
        context: Context, baseUrl: String, serverPath: String, token: String? = null,
    ): DownloadedFile = withContext(Dispatchers.IO) {
        val result = requestFile(baseUrl, serverPath, token = token)
        saveDownload(context, result.response, result.path)
    }

    private suspend fun saveDownload(context: Context, response: Response, fallbackPath: String): DownloadedFile =
        withContext(Dispatchers.IO) {
        response.use {
            if (!response.isSuccessful) {
                throw IllegalStateException("服务端返回 HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("服务端没有返回文件内容")
            val fileName = safeFileName(downloadFileName(response, fallbackPath))
            val mime = resolveMimeType(fileName, response.header("Content-Type"))
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Wand")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建本地下载文件")
            try {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var received = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            received += count
                        }
                        if (body.contentLength() >= 0 && received != body.contentLength()) {
                            throw IOException("下载不完整，请重试")
                        }
                    }
                } ?: throw IllegalStateException("无法写入本地下载文件")
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
                DownloadedFile(uri, fileName, mime)
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
        }
    }

    /** Downloads into the public Downloads/Wand folder, then opens it when an app supports the MIME type. */
    suspend fun downloadAndOpen(context: Context, baseUrl: String, serverPath: String) {
        val downloaded = downloadToDownloads(context, baseUrl, serverPath)
        openDownloaded(context, downloaded)
    }

    /** Metadata preview limits must never remove the explicit full-file download path. */
    suspend fun downloadTargetAndOpen(context: Context, baseUrl: String, target: MessageLinkTarget.File) {
        val downloaded = withContext(Dispatchers.IO) {
            saveDownload(context, WandMessageLinkTransport.request(baseUrl, target.downloadUrl), target.path)
        }
        openDownloaded(context, downloaded)
    }

    suspend fun downloadPreviewAndOpen(context: Context, baseUrl: String, url: String) {
        val downloaded = withContext(Dispatchers.IO) {
            val response = WandMessageLinkTransport.requestPreviewFile(baseUrl, url)
            val name = response.request.url.pathSegments.lastOrNull()?.takeIf { it.isNotBlank() } ?: "wand-file"
            saveDownload(context, response, name)
        }
        openDownloaded(context, downloaded)
    }

    private suspend fun openDownloaded(context: Context, downloaded: DownloadedFile) {
        withContext(Dispatchers.Main) {
            val openMime = downloaded.mime.takeUnless { it == "application/octet-stream" } ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(downloaded.uri, openMime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val opened = runCatching { context.startActivity(intent) }.isSuccess
            val message = if (opened) {
                "已下载并打开 ${downloaded.fileName}"
            } else {
                "已下载到 Downloads/Wand/${downloaded.fileName}"
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun decodePercentPath(value: String): String = runCatching {
        // URLDecoder treats '+' as a space; protect literal plus signs in file names.
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    }.getOrDefault(value)

    private fun safeFileName(value: String): String {
        val cleaned = value.replace(UNSAFE_FILE_NAME, "_").trim().take(180)
        return cleaned.takeUnless { it.isEmpty() || it == "." || it == ".." } ?: "wand-file"
    }

    internal fun downloadFileName(response: Response, fallbackPath: String): String {
        // The server may itself resolve an editor suffix; its UTF-8 filename is authoritative.
        val encoded = Regex("(?:^|;)\\s*filename\\*\\s*=\\s*UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
            .find(response.header("Content-Disposition").orEmpty())?.groupValues?.get(1)
        return encoded?.let { decodePercentPath(it.trim()) }?.takeIf { it.isNotEmpty() }
            ?: fallbackPath.substringAfterLast('/')
    }

    private fun resolveMimeType(fileName: String, responseType: String?): String {
        val responseMime = responseType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (responseMime.isNotEmpty() && responseMime != "application/octet-stream") return responseMime
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: responseMime.takeIf { it.isNotEmpty() }
            ?: "application/octet-stream"
    }

    private data class DownloadedFile(val uri: Uri, val fileName: String, val mime: String)
}
