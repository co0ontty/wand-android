package com.wand.app.ui.screens

import com.wand.app.ui.ViewerImagePage
import com.wand.app.ui.WandImage
import java.net.URI
import java.net.URLDecoder

/** Inline scanner shared by reply rendering and the gallery; fenced code is excluded by the block parser. */
internal sealed class MarkdownImagePiece {
    data class Text(val text: String) : MarkdownImagePiece()
    data class Image(val alt: String, val source: String) : MarkdownImagePiece()
}

internal fun splitMarkdownImages(raw: String): List<MarkdownImagePiece> {
    val pieces = mutableListOf<MarkdownImagePiece>()
    var start = 0
    var index = 0
    while (index < raw.length) {
        if (raw[index] == '\\') {
            val escapedImage = if (index + 1 < raw.length && raw[index + 1] == '!') parseMarkdownImage(raw, index + 1) else null
            index = escapedImage?.end ?: (index + 2).coerceAtMost(raw.length)
            continue
        }
        if (raw[index] == '`') {
            var end = index
            while (end < raw.length && raw[end] == '`') end++
            val count = end - index
            var cursor = end
            var closing: Int? = null
            while (cursor < raw.length) {
                if (raw[cursor] != '`') { cursor++; continue }
                val run = cursor
                while (cursor < raw.length && raw[cursor] == '`') cursor++
                if (cursor - run == count) { closing = cursor; break }
            }
            index = closing ?: end
            continue
        }
        val image = parseMarkdownImage(raw, index)
        if (image != null) {
            if (start < index) pieces.add(MarkdownImagePiece.Text(raw.substring(start, index)))
            pieces.add(MarkdownImagePiece.Image(image.alt, image.source))
            index = image.end
            start = index
        } else index++
    }
    if (start < raw.length) pieces.add(MarkdownImagePiece.Text(raw.substring(start)))
    return pieces.ifEmpty { listOf(MarkdownImagePiece.Text(raw)) }
}

private data class ParsedMarkdownImage(val alt: String, val source: String, val end: Int)

private fun parseMarkdownImage(raw: String, start: Int): ParsedMarkdownImage? {
    val explicitImage = raw[start] == '!'
    val bracket = start + if (explicitImage) 1 else 0
    if (bracket >= raw.length || raw[bracket] != '[') return null
    var index = bracket + 1
    var depth = 1
    val alt = StringBuilder()
    while (index < raw.length) {
        val ch = raw[index]
        if (ch == '\\' && index + 1 < raw.length) { alt.append(raw[index + 1]); index += 2; continue }
        if (ch == '[') depth++
        if (ch == ']') { depth--; if (depth == 0) break }
        alt.append(ch); index++
    }
    if (depth != 0 || index + 1 >= raw.length || raw[index + 1] != '(') return null
    index += 2
    while (index < raw.length && raw[index].isWhitespace()) index++
    val source = StringBuilder()
    if (index < raw.length && raw[index] == '<') {
        index++
        while (index < raw.length && raw[index] != '>') {
            if (raw[index] == '\n') return null
            if (raw[index] == '\\' && index + 1 < raw.length) index++
            source.append(raw[index]); index++
        }
        if (index >= raw.length) return null
        index++
    } else {
        depth = 0
        while (index < raw.length) {
            val ch = raw[index]
            if (ch == '\\' && index + 1 < raw.length) { source.append(raw[index + 1]); index += 2; continue }
            if (ch == ')' && depth == 0 || ch.isWhitespace() && depth == 0) break
            if (ch == '(') depth++
            if (ch == ')') depth--
            source.append(ch); index++
        }
        if (depth != 0) return null
    }
    if (source.isEmpty()) return null
    val beforeSpace = index
    while (index < raw.length && raw[index].isWhitespace()) index++
    if (index > beforeSpace && index < raw.length && raw[index] in "\"'") {
        val quote = raw[index++]
        while (index < raw.length && raw[index] != quote) {
            if (raw[index] == '\\' && index + 1 < raw.length) index++
            index++
        }
        if (index >= raw.length) return null
        index++
        while (index < raw.length && raw[index].isWhitespace()) index++
    }
    if (index >= raw.length || raw[index] != ')') return null
    if (!explicitImage && !markdownLinkIsImage(source.toString())) return null
    return ParsedMarkdownImage(alt.toString(), source.toString(), index + 1)
}

/** Filesystem paths belong to the connected server, not the phone. Never guess a relative cwd. */
internal fun markdownImagePage(raw: String): ViewerImagePage? {
    val source = raw.trim()
    if (source.isEmpty() || source.any { it.code < 32 } || source.startsWith("//")) return null
    val lower = source.lowercase()
    if (lower.startsWith("data:image/") && lower.contains(";base64,")) return ViewerImagePage("data:" + source.substring(5), true)
    if (source.startsWith("/api/")) return ViewerImagePage(source, true)
    if (source.startsWith("/")) {
        val decoded = runCatching { URLDecoder.decode(source.replace("+", "%2B"), "UTF-8") }.getOrDefault(source)
        return ViewerImagePage(decoded, false)
    }
    val uri = runCatching { URI(source) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in listOf("http", "https") || uri.host.isNullOrEmpty() || uri.rawUserInfo != null) return null
    return ViewerImagePage(uri.scheme.lowercase() + source.substring(uri.scheme.length), true)
}

/** Destination-based classification; documents and unknown web pages retain their link behavior. */
private fun markdownLinkIsImage(source: String): Boolean {
    val page = markdownImagePage(source) ?: return false
    if (page.source.startsWith("data:image/", ignoreCase = true)) return true
    if (!page.directUrl) return WandImage.isImagePath(page.source)
    val uri = runCatching { URI(page.source) }.getOrNull() ?: return false
    if (uri.path == "/api/file-raw") {
        val path = uri.rawQuery?.split('&')?.firstOrNull { it.substringBefore('=') == "path" }
            ?.substringAfter('=', "")?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        if (path != null) return WandImage.isImagePath(path)
    }
    return WandImage.isImagePath(uri.path)
}
