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
import okhttp3.Request
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

        value = value.replace(COLON_LINE_SUFFIX, "")
        return value.takeIf { it.startsWith("/") }
    }

    /** Download only: the caller presents in-place status, without launching an external app or a Toast. */
    suspend fun download(context: Context, baseUrl: String, serverPath: String, token: String? = null): String =
        downloadToDownloads(context, baseUrl, serverPath, token).fileName

    private suspend fun downloadToDownloads(
        context: Context, baseUrl: String, serverPath: String, token: String? = null,
    ): DownloadedFile = withContext(Dispatchers.IO) {
        val endpoint = WandHttp.normalizeBaseUrl(baseUrl)
        val url = Uri.parse("$endpoint/api/file-raw")
            .buildUpon()
            .appendQueryParameter("download", "1")
            .appendQueryParameter("path", serverPath)
            .build()
            .toString()
        val request = Request.Builder().url(url).get().build()

        val client = WandHttp.clientFor(endpoint)
        val first = client.newCall(request).execute()
        val authenticated = if (first.code == 401 && !token.isNullOrBlank()) {
            first.close()
            WandAuth.loginWithToken(endpoint, token, client)
            client.newCall(request).execute()
        } else first
        authenticated.use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("服务端返回 HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("服务端没有返回文件内容")
            val fileName = safeFileName(serverPath.substringAfterLast('/'))
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
