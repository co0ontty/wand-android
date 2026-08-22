package com.wand.app.ui.screens

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import com.wand.app.data.CardExpandDefaults
import com.wand.app.data.EscalationRequest
import com.wand.app.data.PermissionRequestInfo
import com.wand.app.data.SubagentMeta
import com.wand.app.data.ToolUseSemantic
import com.wand.app.data.TurnUsage
import com.wand.app.data.WandApi
import com.wand.app.data.arrayField
import com.wand.app.data.int
import com.wand.app.data.str
import com.wand.app.data.summaryText
import com.wand.app.ui.AskUserSelectionState
import com.wand.app.ui.LocalServerBaseUrl
import com.wand.app.ui.WandAsyncImage
import com.wand.app.ui.WandFileChip
import com.wand.app.ui.WandImage
import com.wand.app.ui.WandServerFileLink
import com.wand.app.ui.parseUserAttachmentText
import com.wand.app.ui.components.StatusDot
import com.wand.app.ui.components.NoOverscroll
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.clickableWithoutRipple
import com.wand.app.ui.components.toolIcon
import com.wand.app.ui.theme.GlassBackdrop
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandGlass
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.glassCard
import com.wand.app.ui.theme.glassSurface
import com.wand.app.ui.theme.tinted
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

/** 结构化聊天使用的 Markdown 解析与 Compose 渲染。 */

// MARK: - Markdown

private sealed class MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class ListItem(
        val marker: String,
        val text: String,
        val indent: Int,
        val checked: Boolean? = null,
    ) : MarkdownBlock()
    data class Quote(val text: String) : MarkdownBlock()
    data class Code(val text: String, val language: String?) : MarkdownBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock()
    data object Divider : MarkdownBlock()
}

/** 原生 Markdown 渲染：块级结构独立布局，内联标记使用 AnnotatedString。 */
@Composable
fun MarkdownText(text: String) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    val subtleInset = WandColors.textPrimary.copy(alpha = 0.045f)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> SelectionContainer {
                    Text(
                        inlineMarkdown(block.text),
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = WandColors.textPrimary,
                        textAlign = TextAlign.Start,
                    )
                }
                is MarkdownBlock.Heading -> SelectionContainer {
                    Text(
                        inlineMarkdown(block.text),
                        fontSize = when (block.level) {
                            1 -> 20.sp
                            2 -> 18.sp
                            3 -> 16.sp
                            else -> 15.sp
                        },
                        lineHeight = when (block.level) {
                            1 -> 26.sp
                            2 -> 24.sp
                            else -> 22.sp
                        },
                        fontWeight = FontWeight.SemiBold,
                        color = WandColors.textPrimary,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.padding(top = if (block.level <= 2) 8.dp else 6.dp),
                    )
                }
                is MarkdownBlock.ListItem -> Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.padding(start = (block.indent * 14).dp),
                ) {
                    if (block.checked != null) {
                        Icon(
                            if (block.checked) WandIcons.statusDone else WandIcons.statusPending,
                            contentDescription = if (block.checked) "已完成" else "未完成",
                            tint = if (block.checked) WandColors.success else WandColors.textMuted,
                            modifier = Modifier.padding(top = 2.dp).size(15.dp),
                        )
                    } else {
                        Text(
                            block.marker,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WandColors.brand,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    SelectionContainer {
                        Text(
                            inlineMarkdown(block.text),
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            color = WandColors.textPrimary,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                is MarkdownBlock.Quote -> Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(WandShapes.sm)
                        .background(WandColors.textPrimary.copy(alpha = 0.035f))
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(IntrinsicSize.Max)
                            .background(WandColors.brand, WandShapes.full),
                    )
                    SelectionContainer {
                        Text(
                            inlineMarkdown(block.text),
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                            color = WandColors.textSecondary,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                is MarkdownBlock.Code -> MarkdownCodeBlock(block, subtleInset)
                is MarkdownBlock.Table -> MarkdownTable(block.headers, block.rows)
                MarkdownBlock.Divider -> HorizontalDivider(
                    thickness = 0.5.dp,
                    color = WandColors.border,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}

/** 代码块：语言标题常驻，原生复制操作提供明确触控反馈。 */
@Composable
private fun MarkdownCodeBlock(block: MarkdownBlock.Code, background: Color) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(WandShapes.sm)
            .background(background),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(start = 10.dp),
        ) {
            Text(
                block.language?.takeIf { it.isNotBlank() } ?: "代码",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = WandColors.textMuted,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("code", block.text))
                    Toast.makeText(context, "代码已复制", Toast.LENGTH_SHORT).show()
                },
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) {
                Text("复制", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = WandColors.border.copy(alpha = 0.65f))
        Box(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 9.dp),
        ) {
            SelectionContainer {
                Text(
                    block.text,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    fontFamily = FontFamily.Monospace,
                    color = WandColors.textPrimary,
                )
            }
        }
    }
}

/**
 * Markdown 表格（对齐 iOS markdownTable）：表头品牌弱底、行色交替、
 * 列间/行间分隔线，整体可横向滚动。
 */
@Composable
private fun MarkdownTable(headers: List<String>, rows: List<List<String>>) {
    Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .border(0.55.dp, WandColors.border.copy(alpha = 0.58f), RoundedCornerShape(10.dp)),
        ) {
            MarkdownTableRow(headers, header = true, background = WandColors.brand.copy(alpha = 0.09f))
            rows.forEachIndexed { index, row ->
                HorizontalDivider(thickness = 0.5.dp, color = WandColors.border)
                MarkdownTableRow(
                    normalizedTableRow(row, headers.size),
                    header = false,
                    background = if (index % 2 == 0) {
                        WandColors.surface
                    } else {
                        WandColors.bgPrimary.copy(alpha = 0.45f)
                    },
                )
            }
        }
    }
}

@Composable
private fun MarkdownTableRow(cells: List<String>, header: Boolean, background: Color) {
    Row(
        modifier = Modifier
            .height(IntrinsicSize.Min)
            .background(background),
    ) {
        cells.forEachIndexed { index, cell ->
            SelectionContainer {
                Text(
                    inlineMarkdown(cell),
                    fontSize = if (header) 13.sp else 12.sp,
                    lineHeight = if (header) 18.sp else 17.sp,
                    fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (header) WandColors.textPrimary else WandColors.textSecondary,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .widthIn(min = 110.dp, max = 190.dp)
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                )
            }
            if (index < cells.lastIndex) {
                VerticalDivider(
                    thickness = 0.5.dp,
                    color = WandColors.border,
                    modifier = Modifier.fillMaxHeight(),
                )
            }
        }
    }
}

private fun normalizedTableRow(row: List<String>, count: Int): List<String> =
    if (row.size >= count) row.take(count) else row + List(count - row.size) { "" }

private fun parseMarkdownBlocks(text: String): List<MarkdownBlock> {
    val result = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    val code = mutableListOf<String>()
    var codeFence: String? = null
    var codeLanguage: String? = null

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            result.add(MarkdownBlock.Paragraph(paragraph.joinToString("\n").trim()))
            paragraph.clear()
        }
    }

    fun flushCode() {
        result.add(MarkdownBlock.Code(code.joinToString("\n").trimEnd(), codeLanguage))
        code.clear()
        codeFence = null
        codeLanguage = null
    }

    val lines = text.lines()
    var lineIndex = 0
    while (lineIndex < lines.size) {
        val rawLine = lines[lineIndex]
        val trimmed = rawLine.trim()
        if (codeFence != null) {
            if (trimmed.startsWith(codeFence!!)) flushCode() else code.add(rawLine)
            lineIndex++
            continue
        }
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            flushParagraph()
            codeFence = trimmed.take(3)
            codeLanguage = trimmed.drop(3).trim().ifEmpty { null }
            lineIndex++
            continue
        }
        if (trimmed.isEmpty()) {
            flushParagraph()
            lineIndex++
            continue
        }

        // Markdown 表格：表头行 + 分隔线，随后逐行收集（对齐 iOS parseBlocks）。
        val headers = tableCells(rawLine)
        if (headers != null &&
            lineIndex + 1 < lines.size &&
            isTableSeparator(lines[lineIndex + 1], headers.size)
        ) {
            flushParagraph()
            val rows = mutableListOf<List<String>>()
            lineIndex += 2
            while (lineIndex < lines.size) {
                val row = tableCells(lines[lineIndex]) ?: break
                if (row.isEmpty()) break
                rows.add(row)
                lineIndex++
            }
            result.add(MarkdownBlock.Table(headers, rows))
            continue
        }

        val headingLevel = trimmed.takeWhile { it == '#' }.length
        if (headingLevel in 1..6 && trimmed.getOrNull(headingLevel) == ' ') {
            flushParagraph()
            result.add(MarkdownBlock.Heading(headingLevel, trimmed.drop(headingLevel + 1)))
            lineIndex++
            continue
        }
        if (trimmed.replace(" ", "") in setOf("---", "***", "___")) {
            flushParagraph()
            result.add(MarkdownBlock.Divider)
            lineIndex++
            continue
        }
        if (trimmed.startsWith(">")) {
            flushParagraph()
            result.add(MarkdownBlock.Quote(trimmed.drop(1).trimStart()))
            lineIndex++
            continue
        }

        val indent = (rawLine.length - rawLine.trimStart().length) / 2
        val bullet = listOf("- ", "* ", "+ ").firstOrNull { trimmed.startsWith(it) }
        val orderedEnd = trimmed.indexOfFirst { it == '.' || it == ')' }
        val ordered = if (
            orderedEnd > 0 &&
            trimmed.substring(0, orderedEnd).all(Char::isDigit) &&
            trimmed.getOrNull(orderedEnd + 1) == ' '
        ) {
            trimmed.substring(0, orderedEnd + 1) to trimmed.drop(orderedEnd + 2)
        } else {
            null
        }
        if (bullet != null || ordered != null) {
            flushParagraph()
            val marker = ordered?.first ?: "•"
            var content = ordered?.second ?: trimmed.drop(2)
            val task = when {
                content.startsWith("[x] ", ignoreCase = true) -> true
                content.startsWith("[ ] ") -> false
                else -> null
            }
            if (task != null) content = content.drop(4)
            result.add(MarkdownBlock.ListItem(marker, content, indent, task))
            lineIndex++
            continue
        }
        paragraph.add(rawLine)
        lineIndex++
    }
    if (codeFence != null) flushCode() else flushParagraph()
    return result
}

/** 按 "|" 拆一行表格单元格；不是表格行返回 null（对齐 iOS tableCells）。 */
private fun tableCells(line: String): List<String>? {
    var trimmed = line.trim()
    if (!trimmed.contains("|")) return null
    if (trimmed.startsWith("|")) trimmed = trimmed.drop(1)
    if (trimmed.endsWith("|")) trimmed = trimmed.dropLast(1)
    val cells = trimmed.split("|").map { it.trim() }
    return if (cells.size >= 2) cells else null
}

/** 表头下一行是否是 `---|:---:` 形式的分隔线。 */
private fun isTableSeparator(line: String, columnCount: Int): Boolean {
    val cells = tableCells(line) ?: return false
    if (cells.size != columnCount) return false
    return cells.all { cell ->
        val marker = cell.replace(":", "")
        marker.length >= 3 && marker.all { it == '-' }
    }
}

/** 内联样式：粗体、斜体、删除线、链接与行内代码。未闭合标记按原文显示。 */
@Composable
private fun inlineMarkdown(raw: String): AnnotatedString {
    val linkColor = WandColors.info
    val codeColor = WandColors.brand.copy(alpha = 0.82f)
    val context = LocalContext.current
    val baseUrl = LocalServerBaseUrl.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    // 字符级解析循环不便宜；重组但文本未变时直接复用上一次的 AnnotatedString。
    // 流式输出期间每个 chunk 只在文本真正变化时重建一次，而不是每次重组都重建。
    return remember(raw, linkColor, codeColor, context, baseUrl, scope, uriHandler) {
        buildAnnotatedString {
        var i = 0
        while (i < raw.length) {
            when {
                raw[i] == '\\' && i + 1 < raw.length -> {
                    append(raw[i + 1])
                    i += 2
                }
                raw.startsWith("**", i) || raw.startsWith("__", i) -> {
                    val marker = raw.substring(i, i + 2)
                    val end = raw.indexOf(marker, i + 2)
                    if (end > i + 2) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(inlineMarkdownPlain(raw.substring(i + 2, end)))
                        }
                        i = end + 2
                    } else {
                        append(marker)
                        i += 2
                    }
                }
                raw.startsWith("~~", i) -> {
                    val end = raw.indexOf("~~", i + 2)
                    if (end > i + 2) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            append(raw.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append("~~")
                        i += 2
                    }
                }
                raw[i] == '`' -> {
                    val end = raw.indexOf('`', i + 1)
                    if (end > i + 1) {
                        withStyle(
                            SpanStyle(
                                color = codeColor,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        ) {
                            append(raw.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(raw[i])
                        i++
                    }
                }
                raw[i] == '[' -> {
                    val closeText = raw.indexOf(']', i + 1)
                    val openUrl = if (closeText >= 0) raw.getOrNull(closeText + 1) else null
                    val closeUrl = if (openUrl == '(') markdownLinkDestinationEnd(raw, closeText + 2) else -1
                    if (closeText > i + 1 && closeUrl > closeText + 2) {
                        val url = raw.substring(closeText + 2, closeUrl).trim()
                        withLink(
                            LinkAnnotation.Url(
                                url = url,
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        color = linkColor,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                ),
                                linkInteractionListener = { link ->
                                    (link as? LinkAnnotation.Url)?.url?.let { target ->
                                        val serverPath = WandServerFileLink.serverPath(target)
                                        if (serverPath != null && baseUrl.isNotBlank()) {
                                            Toast.makeText(context, "正在从服务端下载…", Toast.LENGTH_SHORT).show()
                                            scope.launch {
                                                runCatching {
                                                    WandServerFileLink.downloadAndOpen(context, baseUrl, serverPath)
                                                }.onFailure { error ->
                                                    Toast.makeText(
                                                        context,
                                                        "文件下载失败：${error.message ?: "未知错误"}",
                                                        Toast.LENGTH_LONG,
                                                    ).show()
                                                }
                                            }
                                        } else {
                                            runCatching { uriHandler.openUri(target) }
                                        }
                                    }
                                },
                            )
                        ) {
                            append(raw.substring(i + 1, closeText))
                        }
                        i = closeUrl + 1
                    } else {
                        append(raw[i])
                        i++
                    }
                }
                raw[i] == '*' || raw[i] == '_' -> {
                    val marker = raw[i]
                    val end = raw.indexOf(marker, i + 1)
                    if (end > i + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(raw.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(marker)
                        i++
                    }
                }
                else -> {
                    val next = nextInlineMarkerIndex(raw, i + 1)
                    append(raw.substring(i, next))
                    i = next
                }
            }
        }
        }
    }
}

/** Finds the closing parenthesis while preserving angle-wrapped paths and parentheses in file names. */
private fun markdownLinkDestinationEnd(raw: String, start: Int): Int {
    if (start !in raw.indices) return -1
    if (raw[start] == '<') {
        val closeAngle = raw.indexOf('>', start + 1)
        return if (closeAngle >= 0 && raw.getOrNull(closeAngle + 1) == ')') closeAngle + 1 else -1
    }
    var depth = 0
    var index = start
    while (index < raw.length) {
        when (raw[index]) {
            '\\' -> index++
            '(' -> depth++
            ')' -> if (depth == 0) return index else depth--
        }
        index++
    }
    return -1
}

private fun inlineMarkdownPlain(raw: String): String =
    raw.replace("\\*", "*").replace("\\_", "_").replace("\\`", "`")

private fun nextInlineMarkerIndex(raw: String, start: Int): Int {
    var next = raw.length
    for (marker in charArrayOf('\\', '`', '[', '*', '_', '~')) {
        val at = raw.indexOf(marker, start)
        if (at >= 0 && at < next) next = at
    }
    return next
}
