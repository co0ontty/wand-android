package com.wand.app.ui

import androidx.compose.runtime.compositionLocalOf
import com.wand.app.data.WandHttp
import com.wand.app.data.ConversationTurn
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/** Context is supplied by the message owner, never by the currently selected screen. */
data class MessageLinkContext(val sessionId: String? = null, val cwd: String? = null)
val LocalMessageLinkContext = compositionLocalOf { MessageLinkContext() }

/** A forwarded author/session overrides the owning page; aggregate task previews have no single cwd. */
fun messageLinkContextForTurn(turn: ConversationTurn, inherited: MessageLinkContext): MessageLinkContext {
    if (turn.conversationLink != null) return MessageLinkContext()
    val source = turn.sessionLink?.sessionId?.takeIf { it.isNotBlank() }
        ?: turn.author?.sessionId?.takeIf { it.isNotBlank() }
    return if (source != null && source != inherited.sessionId) MessageLinkContext(source) else inherited
}

sealed interface MessageLinkTarget {
    data class File(
        val path: String,
        val rawUrl: String,
        val downloadUrl: String,
        val previewUrl: String?,
        val metadataUrl: String?,
        val absolutePath: String?,
    ) : MessageLinkTarget
    data class Preview(val url: String, val label: String, val file: File? = null) : MessageLinkTarget
    data class External(val url: String) : MessageLinkTarget
    data class Unavailable(val message: String) : MessageLinkTarget
}

/** Pure URL/path routing. Decode each filename once, after separating URL query/fragment. */
object WandMessageLinks {
    private val scheme = Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE)
    private val editorLocation = Regex("^[^:/?#]+\\.[^:/?#]+:\\d+(?::\\d+)?(?:[?#]|$)")
    private val lineHash = Regex("#L\\d+(?:C\\d+)?", RegexOption.IGNORE_CASE)
    private val html = Regex("\\.html?(?::\\d+(?::\\d+)?)?$", RegexOption.IGNORE_CASE)
    private val webRoute = Regex("^/(?:api|android|ios|macos)(?:/|$)")
    private val loopbacks = setOf("localhost", "127.0.0.1", "::1", "[::1]")

    fun resolve(input: String, baseUrl: String, context: MessageLinkContext = MessageLinkContext()): MessageLinkTarget {
        val value = input.trim().removeSurrounding("<", ">").trim()
        if (value.isEmpty() || value.any(Char::isISOControl)) return unavailable("链接格式无效。")
        val endpoint = WandHttp.normalizeBaseUrl(baseUrl).takeIf { it.toHttpUrlOrNull() != null }.orEmpty()
        if (Regex("^(?:localhost|127\\.0\\.0\\.1|\\[::1])(?::\\d{1,5})(?:[/\\?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(value)) {
            return resolve("http://$value", baseUrl, context)
        }
        val web = value.toHttpUrlOrNull()
        val api = apiRoute(value, endpoint)
        if (api != null) return resolveApi(api, endpoint)
        if (web != null) {
            if (web.username.isNotEmpty() || web.password.isNotEmpty()) return unavailable("链接不能包含用户名或密码。")
            if (web.scheme == "http" && web.host.lowercase() in loopbacks) {
                if (endpoint.isBlank()) return unavailable("当前消息没有可用的服务器连接。")
                val host = if (web.host.contains(':')) "::1" else "127.0.0.1"
                return MessageLinkTarget.Preview(endpoint + "/api/local-preview/$host/${web.port}" + web.encodedPath +
                    web.encodedQuery?.let { "?$it" }.orEmpty() + web.encodedFragment?.let { "#$it" }.orEmpty(), value)
            }
            return MessageLinkTarget.External(value)
        }
        if (value.startsWith("//") || value.startsWith("#")) return unavailable("此链接缺少可确定的目标地址。")
        var reference = split(value)
        if (value.startsWith("file:", ignoreCase = true)) {
            val uri = runCatching { URI(value.replace(" ", "%20")) }.getOrNull()
                ?: return unavailable("文件链接格式无效。")
            if (uri.rawUserInfo != null || (uri.host?.lowercase() ?: "") !in setOf("", "localhost")) {
                return unavailable("文件链接必须指向连接的服务器。")
            }
            reference = Reference(decodePath(uri.rawPath.orEmpty()), uri.rawQuery?.let { "?$it" }.orEmpty(), uri.rawFragment?.let { "#$it" }.orEmpty())
        } else {
            if (scheme.containsMatchIn(value) && !editorLocation.containsMatchIn(value)) {
                return if (value.startsWith("mailto:", true) || value.startsWith("tel:", true)) MessageLinkTarget.External(value)
                else unavailable("不支持此链接格式。")
            }
            reference = reference.copy(path = decodePath(reference.path))
        }
        return file(reference, endpoint, context)
    }

    private fun resolveApi(route: String, endpoint: String): MessageLinkTarget {
        val url = ("https://wand.invalid" + route).toHttpUrlOrNull() ?: return unavailable("文件链接格式无效。")
        val path = url.encodedPath
        if (path.startsWith("/api/local-file/")) {
            val parts = path.split('/')
            val encoded = parts.getOrNull(3).orEmpty()
            val root = runCatching { String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8) }.getOrNull()
            if (root == null || !(root.startsWith('/') || root.startsWith("~/")) || root.any(Char::isISOControl) || !Regex("[A-Za-z0-9_-]+").matches(encoded)) {
                return unavailable("本地预览链接格式无效。")
            }
            val fixed = if (parts.size == 4) "/$encoded/" else null
            val suffix = if (fixed != null) "/api/local-file$fixed" + url.encodedQuery?.let { "?$it" }.orEmpty() + url.encodedFragment?.let { "#$it" }.orEmpty() else route
            val resource = parts.drop(4).joinToString("/").let(::decodePath).ifEmpty { root }
            val target = ("https://wand.invalid" + suffix).toHttpUrlOrNull() ?: return unavailable("本地预览链接格式无效。")
            fun withMode(mode: String) = target.newBuilder().setQueryParameter("__wand_file_action", mode).build().let {
                endpoint + it.encodedPath + it.encodedQuery?.let { query -> "?$query" }.orEmpty()
            }
            val preview = if (html.containsMatchIn(resource) || path.endsWith('/') || parts.size == 4) endpoint + suffix else null
            return MessageLinkTarget.File(resource, endpoint + suffix, withMode("download"), preview, withMode("metadata"), null)
        }
        if (path.startsWith("/api/local-preview/")) {
            val match = Regex("^/api/local-preview/([^/]+)/(\\d{1,5})(/.*)?$").matchEntire(path)
                ?: return unavailable("本地服务预览链接格式无效。")
            val host = decodePath(match.groupValues[1]).lowercase()
            val port = match.groupValues[2].toIntOrNull()
            if (host !in loopbacks || port == null || port !in 1..65535) return unavailable("只支持服务器本机 HTTP 预览。")
            val normalized = if (host.contains(':')) "::1" else "127.0.0.1"
            return MessageLinkTarget.Preview(endpoint + "/api/local-preview/$normalized/$port" + match.groupValues[3].ifEmpty { "/" } +
                url.encodedQuery?.let { "?$it" }.orEmpty() + url.encodedFragment?.let { "#$it" }.orEmpty(), "本地服务 :$port")
        }
        if (path == "/api/file-raw" || Regex("^/api/session-file/[^/]+$").matches(path)) {
            if (url.queryParameterValues("path").size != 1) return unavailable("文件链接缺少唯一的路径。")
            val filePath = url.queryParameter("path").orEmpty()
            val search = url.queryParameter("search").orEmpty()
            if (search.isNotEmpty() && !search.startsWith('?')) return unavailable("文件预览参数无效。")
            val source = if (path.startsWith("/api/session-file/")) decodePath(path.substringAfterLast('/')) else null
            if (source != null && (filePath.startsWith('/') || source.isBlank() || source.contains('/'))) return unavailable("会话文件链接格式无效。")
            if (source == null && !filePath.startsWith('/')) return unavailable("文件链接缺少绝对路径。")
            return file(Reference(filePath, search, url.encodedFragment?.let { "#$it" }.orEmpty()), endpoint, MessageLinkContext(source))
        }
        return unavailable("此服务器链接不是可预览的文件。")
    }

    private fun file(reference: Reference, endpoint: String, context: MessageLinkContext): MessageLinkTarget {
        var path = reference.path
        if (path.isEmpty() || path.any(Char::isISOControl) || path.startsWith("//") || webRoute.containsMatchIn(path)) return unavailable("文件路径无效。")
        if (reference.search.any(Char::isISOControl) || reference.hash.any(Char::isISOControl)) return unavailable("文件预览参数无效。")
        if (endpoint.isBlank()) return unavailable("当前消息没有可用的服务器连接。")
        val fragment = reference.hash.takeUnless { lineHash.matches(it) }.orEmpty()
        val isHtml = html.containsMatchIn(path) || path.endsWith('/')
        if (!path.startsWith('/') && !path.startsWith("~/")) {
            if (scheme.containsMatchIn(path) && !editorLocation.containsMatchIn(path)) return unavailable("文件相对路径无效。")
            context.sessionId?.takeIf { it.isNotBlank() }?.let { source ->
                val route = "$endpoint/api/session-file/${encode(source)}?path=${encode(path)}"
                return MessageLinkTarget.File(path, route, "$route&mode=download", if (isHtml) "$route&mode=preview" +
                    reference.search.takeIf { it.isNotEmpty() }?.let { "&search=${encode(it)}" }.orEmpty() + fragment else null,
                    "$route&mode=metadata", null)
            }
            val cwd = context.cwd?.takeIf { it.startsWith('/') }
                ?: return unavailable("这条消息没有文件来源会话，无法解析相对路径。请提供绝对路径。")
            val parts = mutableListOf<String>()
            for (part in path.split('/')) when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return unavailable("相对路径超出了消息来源目录。") else parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
            path = cwd.trimEnd('/') + "/" + parts.joinToString("/") + if (reference.path.endsWith('/')) "/" else ""
        }
        val raw = "$endpoint/api/file-raw?path=${encode(path)}"
        val previewBase = "$endpoint/api/local-file/${Base64.getUrlEncoder().withoutPadding().encodeToString(path.toByteArray(Charsets.UTF_8))}/"
        val preview = if (isHtml) previewBase + reference.search + fragment else null
        val download = if (reference.path.endsWith('/')) previewBase + "?__wand_file_action=download" else "$raw&download=1"
        return MessageLinkTarget.File(path, raw, download, preview, null, path)
    }

    /** Return a logical API route only inside this exact endpoint namespace. */
    internal fun apiRoute(value: String, baseUrl: String): String? {
        val endpoint = baseUrl.toHttpUrlOrNull() ?: return null
        val uri = runCatching { URI(value.replace(" ", "%20").replace("/api/local-preview/[::1]/", "/api/local-preview/%5B::1%5D/")) }.getOrNull() ?: return null
        if (uri.rawUserInfo != null || uri.rawPath == null || ambiguousPath(uri.rawPath)) return null
        val prefix = endpoint.encodedPath.trimEnd('/')
        val routePath = when {
            uri.scheme != null -> {
                if (!WandHttp.isSameOrigin(value, baseUrl) || !uri.rawPath.startsWith("$prefix/api/")) return null
                uri.rawPath.removePrefix(prefix)
            }
            uri.rawAuthority != null -> return null
            uri.rawPath.startsWith("/api/") -> uri.rawPath
            prefix.isNotEmpty() && uri.rawPath.startsWith("$prefix/api/") -> uri.rawPath.removePrefix(prefix)
            else -> return null
        }
        return routePath + uri.rawQuery?.let { "?$it" }.orEmpty() + uri.rawFragment?.let { "#$it" }.orEmpty()
    }

    internal fun ambiguousPath(raw: String): Boolean {
        var value = raw
        repeat(4) {
            if (value.contains('\\') || value.split('/').any { it == "." || it == ".." } || value.any(Char::isISOControl)) return true
            val decoded = decodePath(value)
            if (decoded == value) return false
            value = decoded
        }
        return true
    }

    private data class Reference(val path: String, val search: String, val hash: String)
    private fun split(value: String): Reference {
        val hash = value.indexOf('#')
        val beforeHash = if (hash < 0) value else value.substring(0, hash)
        val query = beforeHash.indexOf('?')
        return Reference(if (query < 0) beforeHash else beforeHash.substring(0, query), if (query < 0) "" else beforeHash.substring(query), if (hash < 0) "" else value.substring(hash))
    }
    internal fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    private fun decodePath(value: String) = runCatching { URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name()) }.getOrDefault(value)
    private fun unavailable(message: String) = MessageLinkTarget.Unavailable(message)
}
