package com.wand.app.ui.screens

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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import com.wand.app.data.EscalationRequest
import com.wand.app.data.PermissionRequestInfo
import com.wand.app.data.SubagentMeta
import com.wand.app.data.str
import com.wand.app.data.summaryText
import com.wand.app.ui.AskUserSelectionState
import com.wand.app.ui.components.StatusDot
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.toolIcon
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import org.json.JSONObject

/**
 * 聊天内容块渲染（重设计规范 v1 第 3.3 节）：
 * TurnView / UserBubble / ToolCard（工具调用 + 结果配对，三态）/ ThinkingBlock /
 * MarkdownText / PermissionCard。
 * 工具调用与其结果在渲染层配对成一张卡片，对齐 Web 端 tool-card 结构。
 */

// MARK: - 单条消息

@Composable
fun TurnView(
    turn: ConversationTurn,
    isLastTurn: Boolean = false,
    isResponding: Boolean = false,
    askSelections: Map<String, AskUserSelectionState> = emptyMap(),
    onAskToggle: (String, Int, Int, Boolean) -> Unit = { _, _, _, _ -> },
    onAskSubmit: (String, String) -> Unit = { _, _ -> },
) {
    if (turn.role == "user") {
        UserBubble(turn)
    } else {
        val items = remember(turn.content) { pairToolBlocks(turn.content) }
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items.forEachIndexed { index, item ->
                when (item) {
                    is DisplayItem.Tool -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SubagentTag(item.use.subagent)
                            // 工具卡分流（对齐 Web 端 renderToolUseCard）：
                            // AskUserQuestion → 交互卡；Edit/Write/MultiEdit → diff 卡；
                            // Bash → 终端卡；其余 → 通用卡。
                            val use = item.use
                            val askQuestions = if (use.name == "AskUserQuestion") {
                                remember(use.input) { AskUserQuestionData.parse(use.input) }
                            } else {
                                emptyList()
                            }
                            when {
                                askQuestions.isNotEmpty() -> AskUserQuestionCard(
                                    toolUseId = use.id,
                                    questions = askQuestions,
                                    result = item.result,
                                    selection = askSelections[use.id] ?: AskUserSelectionState(),
                                    onToggle = { qIdx, optIdx, multi ->
                                        onAskToggle(use.id, qIdx, optIdx, multi)
                                    },
                                    onSubmit = { answerText -> onAskSubmit(use.id, answerText) },
                                )
                                use.name in setOf("Edit", "Write", "MultiEdit") -> DiffCard(
                                    toolName = use.name,
                                    input = use.input,
                                    result = item.result,
                                )
                                use.name == "Bash" -> TerminalCard(
                                    input = use.input,
                                    result = item.result,
                                    running = item.result == null && isLastTurn && isResponding,
                                )
                                else -> ToolCard(
                                    use = use,
                                    result = item.result,
                                    // 最后一轮回复中、且还没有结果的工具 → 运行中
                                    //（并行工具调用时可同时有多个在转）
                                    running = item.result == null && isLastTurn && isResponding,
                                )
                            }
                        }
                    }
                    is DisplayItem.Plain -> BlockView(
                        item.block,
                        // 最后一轮回复的末尾块视为流式中（Thinking 块图标呼吸用）
                        streaming = isLastTurn && isResponding && index == items.lastIndex,
                    )
                }
            }
        }
    }
}

@Composable
private fun UserBubble(turn: ConversationTurn) {
    val text = turn.content
        .filterIsInstance<ContentBlock.Text>()
        .joinToString("\n") { it.text }
    val bubbleShape = RoundedCornerShape(
        topStart = WandShapes.radiusLg,
        topEnd = WandShapes.radiusLg,
        bottomEnd = WandShapes.radiusXs, // 右下小圆角"尾巴"
        bottomStart = WandShapes.radiusLg,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 44.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        SelectionContainer {
            Text(
                text,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                color = Color.White,
                modifier = Modifier
                    .clip(bubbleShape)
                    .background(WandColors.brand)
                    .padding(horizontal = 13.dp, vertical = 8.dp),
            )
        }
    }
}

// MARK: - 工具调用与结果的渲染层配对

private sealed class DisplayItem {
    class Plain(val block: ContentBlock) : DisplayItem()
    class Tool(val use: ContentBlock.ToolUse, val result: ContentBlock.ToolResult?) : DisplayItem()
}

/**
 * 把 ToolUse 与对应 ToolResult 配成一张卡片，对齐 Web 端 buildToolResultMap：
 * 优先按 tool_use_id 精确配对（并行工具调用时 use 与 result 顺序会交错，
 * 邻接配对会把别的工具的结果挂错卡片）；id 缺失时退回「紧随其后的第一个结果」
 * 邻接兜底。没配上的 ToolResult 原样透传（走 OrphanResultBlock）。
 */
private fun pairToolBlocks(content: List<ContentBlock>): List<DisplayItem> {
    val items = mutableListOf<DisplayItem>()
    val consumed = mutableSetOf<Int>()
    content.forEachIndexed { i, block ->
        if (i in consumed) return@forEachIndexed
        if (block is ContentBlock.ToolUse) {
            var resultIndex = -1
            if (block.id.isNotEmpty()) {
                // 1) 全局按 tool_use_id 精确配对
                for (j in i + 1 until content.size) {
                    if (j in consumed) continue
                    val next = content[j]
                    if (next is ContentBlock.ToolResult && next.toolUseId == block.id) {
                        resultIndex = j
                        break
                    }
                }
            }
            if (resultIndex < 0) {
                // 2) 邻接兜底：紧随其后的第一个未消费 ToolResult；
                //    中间隔着下一个 ToolUse 视为无结果；id 双方都有但不匹配时不抢配。
                for (j in i + 1 until content.size) {
                    if (j in consumed) continue
                    val next = content[j]
                    if (next is ContentBlock.ToolUse) break
                    if (next is ContentBlock.ToolResult) {
                        if (next.toolUseId.isEmpty() || block.id.isEmpty()) {
                            resultIndex = j
                        }
                        break
                    }
                }
            }
            val result = if (resultIndex >= 0) {
                consumed.add(resultIndex)
                content[resultIndex] as ContentBlock.ToolResult
            } else {
                null
            }
            items.add(DisplayItem.Tool(block, result))
        } else {
            items.add(DisplayItem.Plain(block))
        }
    }
    return items
}

// MARK: - 内容块

@Composable
fun BlockView(block: ContentBlock, streaming: Boolean = false) {
    when (block) {
        is ContentBlock.Text -> {
            if (block.text.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SubagentTag(block.subagent)
                    MarkdownText(block.text)
                }
            }
        }
        is ContentBlock.Thinking -> {
            if (block.thinking.isNotBlank()) {
                ThinkingBlock(block.thinking, streaming = streaming)
            }
        }
        is ContentBlock.ToolUse -> {
            // 落单的 ToolUse（正常路径已在 TurnView 配对，这里兜底）
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SubagentTag(block.subagent)
                ToolCard(use = block, result = null, running = false)
            }
        }
        is ContentBlock.ToolResult -> {
            // 落单的 ToolResult 兜底：渲染成无头工具卡的结果区样式
            if (block.text.isNotEmpty()) {
                OrphanResultBlock(block)
            }
        }
        is ContentBlock.Unknown -> Unit
    }
}

@Composable
private fun SubagentTag(meta: SubagentMeta?) {
    if (meta == null) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(WandShapes.full)
            .background(WandColors.infoSoft)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Icon(
            WandIcons.agent,
            contentDescription = null,
            tint = WandColors.info,
            modifier = Modifier.size(12.dp),
        )
        Text(
            meta.taskDescription ?: meta.agentType ?: "子任务",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = WandColors.info,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

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
    data object Divider : MarkdownBlock()
}

/** 原生 Markdown 渲染：块级结构独立布局，内联标记使用 AnnotatedString。 */
@Composable
fun MarkdownText(text: String) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> SelectionContainer {
                    Text(
                        inlineMarkdown(block.text),
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = WandColors.textPrimary,
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
                        modifier = Modifier.padding(top = if (block.level <= 2) 3.dp else 1.dp),
                    )
                }
                is MarkdownBlock.ListItem -> Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.padding(start = (block.indent * 14).dp),
                ) {
                    Text(
                        block.checked?.let { if (it) "☑" else "☐" } ?: block.marker,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (block.checked == true) WandColors.success else WandColors.brand,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    SelectionContainer {
                        Text(
                            inlineMarkdown(block.text),
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            color = WandColors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                is MarkdownBlock.Quote -> Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(WandShapes.xs)
                        .background(WandColors.surfaceSoft)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
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
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                is MarkdownBlock.Code -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(WandShapes.sm)
                        .background(WandColors.surfaceSoft)
                        .border(1.dp, WandColors.border, WandShapes.sm),
                ) {
                    if (!block.language.isNullOrEmpty()) {
                        Text(
                            block.language,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WandColors.textMuted,
                            modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 2.dp),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 8.dp),
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
                MarkdownBlock.Divider -> HorizontalDivider(
                    thickness = 1.dp,
                    color = WandColors.border,
                    modifier = Modifier.padding(vertical = 3.dp),
                )
            }
        }
    }
}

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

    text.lines().forEach { rawLine ->
        val trimmed = rawLine.trim()
        if (codeFence != null) {
            if (trimmed.startsWith(codeFence!!)) flushCode() else code.add(rawLine)
            return@forEach
        }
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            flushParagraph()
            codeFence = trimmed.take(3)
            codeLanguage = trimmed.drop(3).trim().ifEmpty { null }
            return@forEach
        }
        if (trimmed.isEmpty()) {
            flushParagraph()
            return@forEach
        }

        val headingLevel = trimmed.takeWhile { it == '#' }.length
        if (headingLevel in 1..6 && trimmed.getOrNull(headingLevel) == ' ') {
            flushParagraph()
            result.add(MarkdownBlock.Heading(headingLevel, trimmed.drop(headingLevel + 1)))
            return@forEach
        }
        if (trimmed.replace(" ", "") in setOf("---", "***", "___")) {
            flushParagraph()
            result.add(MarkdownBlock.Divider)
            return@forEach
        }
        if (trimmed.startsWith(">")) {
            flushParagraph()
            result.add(MarkdownBlock.Quote(trimmed.drop(1).trimStart()))
            return@forEach
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
            return@forEach
        }
        paragraph.add(rawLine)
    }
    if (codeFence != null) flushCode() else flushParagraph()
    return result
}

/** 内联样式：粗体、斜体、删除线、链接与行内代码。未闭合标记按原文显示。 */
@Composable
private fun inlineMarkdown(raw: String): AnnotatedString {
    val linkColor = WandColors.info
    val codeBackground = WandColors.surfaceSoft
    return buildAnnotatedString {
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
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                background = codeBackground,
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
                    val closeUrl = if (openUrl == '(') raw.indexOf(')', closeText + 2) else -1
                    if (closeText > i + 1 && closeUrl > closeText + 2) {
                        withStyle(
                            SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline,
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
                    append(raw[i])
                    i++
                }
            }
        }
    }
}

private fun inlineMarkdownPlain(raw: String): String =
    raw.replace("\\*", "*").replace("\\_", "_").replace("\\`", "`")

// MARK: - 工具调用卡片（含结果区，三态）

/** 工具名 → 中文标签；未识别的工具显示原名。 */
private fun toolLabel(name: String): String {
    val lower = name.lowercase()
    return when {
        lower.contains("todo") -> "更新待办"
        lower.contains("websearch") -> "网页搜索"
        lower.contains("webfetch") || lower.contains("fetch") -> "网页获取"
        lower.contains("notebook") -> "编辑笔记本"
        lower.startsWith("multiedit") || lower.startsWith("edit") -> "编辑文件"
        lower.startsWith("write") -> "写入文件"
        lower.startsWith("read") -> "读取文件"
        lower.startsWith("grep") -> "搜索内容"
        lower.startsWith("glob") -> "查找文件"
        lower == "bash" || lower.contains("command") || lower.contains("shell") -> "执行命令"
        lower.startsWith("task") || lower.contains("agent") -> "子任务"
        else -> name
    }
}

/**
 * 工具调用卡片：图标 + 中文工具名 + 参数摘要 + 可折叠结果区。
 * 三态：运行中（图标旋转）/ 成功（左侧 2dp 绿竖线）/ 失败（红弱底 + 红边框）。
 */
@Composable
fun ToolCard(
    use: ContentBlock.ToolUse,
    result: ContentBlock.ToolResult?,
    running: Boolean = false,
) {
    val isError = result?.isError == true
    val isSuccess = result != null && !isError
    val hasBody = result != null && result.text.isNotEmpty()
    var expanded by remember { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        if (expanded) 180f else 0f,
        WandMotion.tweenNormal(),
        label = "toolArrow",
    )

    val cardBg = if (isError) WandColors.dangerSoft else WandColors.surface
    val cardBorder = if (isError) WandColors.danger.copy(alpha = 0.45f) else WandColors.border

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(WandShapes.md)
            .background(cardBg)
            .border(1.dp, cardBorder, WandShapes.md)
            .then(
                if (hasBody) {
                    Modifier.clickable { expanded = !expanded }
                } else {
                    Modifier
                }
            ),
    ) {
        // 成功态左侧 2dp 语义色竖线
        if (isSuccess) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(WandColors.success),
            )
        }
        Column(modifier = Modifier.animateContentSize(WandMotion.tweenNormal())) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 11.dp, vertical = 8.dp),
            ) {
                if (running) {
                    val spin = rememberInfiniteTransition(label = "toolSpin")
                    val angle by spin.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(tween(900)),
                        label = "toolSpinAngle",
                    )
                    Icon(
                        WandIcons.refresh,
                        contentDescription = "运行中",
                        tint = WandColors.brand,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { rotationZ = angle },
                    )
                } else {
                    Icon(
                        toolIcon(use.name),
                        contentDescription = null,
                        tint = if (isError) WandColors.danger else WandColors.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            toolLabel(use.name),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isError) WandColors.danger else WandColors.textPrimary,
                        )
                        if (isError) {
                            Text(
                                "出错",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = WandColors.danger,
                            )
                        }
                    }
                    val summary = toolSummary(use.description, use.input)
                    if (summary.isNotEmpty()) {
                        Text(
                            summary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = WandColors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (hasBody) {
                    Icon(
                        WandIcons.expand,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = WandColors.textMuted,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { rotationZ = arrowRotation },
                    )
                }
            }
            if (expanded && result != null) {
                ToolResultBody(
                    result,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                )
            }
        }
    }
}

/** 工具结果正文：次级底色代码框 + 4000 字截断。 */
@Composable
private fun ToolResultBody(result: ContentBlock.ToolResult, modifier: Modifier = Modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(WandShapes.sm)
                .background(WandColors.surfaceSoft)
                .horizontalScroll(rememberScrollState())
                .padding(10.dp),
        ) {
            SelectionContainer {
                Text(
                    if (result.text.length > 4000) {
                        result.text.take(4000) + "\n…（已截断）"
                    } else {
                        result.text
                    },
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (result.isError) WandColors.danger else WandColors.textPrimary,
                )
            }
        }
        if (result.truncated) {
            Text(
                "内容过长，已截断",
                fontSize = 11.sp,
                color = WandColors.textMuted,
            )
        }
    }
}

/** 落单 ToolResult 的兜底渲染：可折叠的结果块。 */
@Composable
private fun OrphanResultBlock(result: ContentBlock.ToolResult) {
    var expanded by remember { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        if (expanded) 180f else 0f,
        WandMotion.tweenNormal(),
        label = "orphanArrow",
    )
    val tint = if (result.isError) WandColors.danger else WandColors.textSecondary
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.animateContentSize(WandMotion.tweenNormal()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.clickable { expanded = !expanded },
        ) {
            Icon(
                if (result.isError) WandIcons.error else WandIcons.toolResult,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(14.dp),
            )
            Text(
                if (result.isError) "执行出错" else "执行结果",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = tint,
            )
            Icon(
                WandIcons.expand,
                contentDescription = if (expanded) "收起" else "展开",
                tint = tint,
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer { rotationZ = arrowRotation },
            )
        }
        if (expanded) {
            ToolResultBody(result)
        }
    }
}

// MARK: - 思考块

/** Thinking 块：收起态一行（紫灰，流式时图标呼吸），展开态弱紫底 + 左侧 2dp 竖线 + 斜体。 */
@Composable
private fun ThinkingBlock(text: String, streaming: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        if (expanded) 180f else 0f,
        WandMotion.tweenNormal(),
        label = "thinkArrow",
    )
    val iconAlpha: Float
    if (streaming) {
        val breath = rememberInfiniteTransition(label = "thinkBreath")
        val animated by breath.animateFloat(
            initialValue = 1f,
            targetValue = WandMotion.breathAlphaMin,
            animationSpec = WandMotion.breath(),
            label = "thinkBreathAlpha",
        )
        iconAlpha = animated
    } else {
        iconAlpha = 1f
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.animateContentSize(WandMotion.tweenNormal()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.clickable { expanded = !expanded },
        ) {
            Icon(
                WandIcons.thinking,
                contentDescription = null,
                tint = WandColors.thinking,
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { alpha = iconAlpha },
            )
            Text(
                "深度思考",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = WandColors.thinking,
            )
            Icon(
                WandIcons.expand,
                contentDescription = if (expanded) "收起" else "展开",
                tint = WandColors.thinking.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = arrowRotation },
            )
        }
        if (expanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .clip(WandShapes.sm)
                    .background(WandColors.thinkingSoft),
            ) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(WandColors.thinking),
                )
                SelectionContainer(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        fontStyle = FontStyle.Italic,
                        color = WandColors.textSecondary,
                    )
                }
            }
        }
    }
}

// MARK: - 权限审批卡片

@Composable
fun PermissionCard(
    escalation: EscalationRequest?,
    legacy: PermissionRequestInfo?,
    onResolve: (String) -> Unit,
) {
    val scopeTitle = escalation?.scopeTitle ?: "权限请求"
    val detail = escalation?.reason ?: legacy?.prompt ?: ""
    val target = escalation?.target ?: legacy?.target

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(WandShapes.md)
            .background(WandColors.permissionSoft)
            .border(1.5.dp, WandColors.permission.copy(alpha = 0.55f), WandShapes.md)
            .padding(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                WandIcons.permission,
                contentDescription = null,
                tint = WandColors.permission,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "需要授权",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = WandColors.permission,
            )
            Box(modifier = Modifier.weight(1f))
            StatusDot("permission")
        }
        Text(
            scopeTitle,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = WandColors.textPrimary,
        )
        if (detail.isNotEmpty() && detail != scopeTitle) {
            Text(
                detail,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = WandColors.textSecondary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!target.isNullOrEmpty()) {
            Text(
                target,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = WandColors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(WandShapes.xs)
                    .background(WandColors.surface.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { onResolve("deny") },
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) {
                Text(
                    "拒绝",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WandColors.danger,
                    maxLines = 1,
                )
            }
            Box(modifier = Modifier.weight(1f))
            if (escalation != null) {
                OutlinedButton(
                    onClick = { onResolve("approve_turn") },
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) {
                    Text(
                        "本轮放行",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = WandColors.textPrimary,
                        maxLines = 1,
                    )
                }
            }
            Button(
                onClick = { onResolve(if (escalation != null) "approve_once" else "approve") },
                colors = ButtonDefaults.buttonColors(
                    containerColor = WandColors.success,
                    contentColor = Color.White,
                ),
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) {
                Text("允许", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

// MARK: - 工具参数摘要

/** 摘要优先级：description > 常见关键参数 > 第一个参数。 */
private fun toolSummary(description: String?, input: JSONObject): String {
    if (!description.isNullOrEmpty()) return description
    val preferredKeys =
        listOf("command", "file_path", "path", "pattern", "query", "prompt", "url", "description")
    for (key in preferredKeys) {
        if (input.has(key) && !input.isNull(key)) {
            val text = summaryText(input.opt(key))
            if (text.isNotEmpty()) return text
        }
    }
    val firstKey = input.keys().asSequence().firstOrNull() ?: return ""
    return "$firstKey: ${summaryText(input.opt(firstKey))}"
}

// MARK: - AskUserQuestion 交互卡片（对齐 Web 端 ask-user 卡）

/** AskUserQuestion 的一道题（tool_use input.questions[i]），字段对齐 Web 端 chat-render.ts。 */
data class AskUserQuestionData(
    val question: String,
    val header: String?,
    val multiSelect: Boolean,
    val options: List<Option>,
) {
    data class Option(val label: String, val description: String?)

    companion object {
        /** 从 tool_use 的 input 解析 questions 数组；形状不符返回空列表（上层回落通用工具卡）。 */
        fun parse(input: JSONObject): List<AskUserQuestionData> {
            val items = input.optJSONArray("questions") ?: return emptyList()
            val result = mutableListOf<AskUserQuestionData>()
            for (i in 0 until items.length()) {
                val obj = items.optJSONObject(i) ?: continue
                val optionsArr = obj.optJSONArray("options") ?: continue
                val options = mutableListOf<Option>()
                for (j in 0 until optionsArr.length()) {
                    val opt = optionsArr.optJSONObject(j) ?: continue
                    val label = opt.str("label") ?: ""
                    options.add(
                        Option(
                            label = label.ifEmpty { "选项 ${options.size + 1}" },
                            description = opt.str("description"),
                        )
                    )
                }
                if (options.isEmpty()) continue
                result.add(
                    AskUserQuestionData(
                        question = obj.str("question") ?: "",
                        header = obj.str("header"),
                        multiSelect = obj.optBoolean("multiSelect", false),
                        options = options,
                    )
                )
            }
            return result
        }
    }
}

/**
 * 提问卡：头部「? 提问 · header」，body 是题目 + 选项列表 + 确认提交。
 * 未答可交互（单选/多选），已答（配对到 tool_result）转只读并高亮用户选过的项。
 */
@Composable
fun AskUserQuestionCard(
    toolUseId: String,
    questions: List<AskUserQuestionData>,
    result: ContentBlock.ToolResult?,
    selection: AskUserSelectionState,
    onToggle: (Int, Int, Boolean) -> Unit,
    onSubmit: (String) -> Unit,
) {
    val isAnswered = result != null
    // 已答时按行拆答案：每道题一行，行内 ", " 分隔多选 label（对齐 Web 的解析）。
    val answerLines = remember(result?.text) {
        result?.text?.trim()?.takeIf { it.isNotEmpty() }?.split("\n") ?: emptyList()
    }
    var expanded by remember { mutableStateOf(!isAnswered) }
    // 回答送达后自动折叠（对齐 Web 已答默认折叠）。
    LaunchedEffect(isAnswered) { if (isAnswered) expanded = false }
    val allAnswered = questions.indices.all { !selection.selected[it].isNullOrEmpty() }
    val borderColor = if (isAnswered) {
        WandColors.success.copy(alpha = 0.55f)
    } else {
        WandColors.brand.copy(alpha = 0.35f)
    }
    val arrowRotation by animateFloatAsState(
        if (expanded) 180f else 0f,
        WandMotion.tweenNormal(),
        label = "askArrow",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(WandShapes.md)
            .background(WandColors.brand.copy(alpha = 0.05f))
            .border(1.dp, borderColor, WandShapes.md)
            .animateContentSize(WandMotion.tweenNormal()),
    ) {
        // 头部
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Icon(
                if (isAnswered) WandIcons.check else WandIcons.question,
                contentDescription = null,
                tint = if (isAnswered) WandColors.success else WandColors.brand,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "提问",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = WandColors.textPrimary,
            )
            val headerLabel = questions.firstOrNull { !it.header.isNullOrEmpty() }?.header
            if (!headerLabel.isNullOrEmpty()) {
                Text(
                    headerLabel,
                    fontSize = 12.sp,
                    color = WandColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isAnswered && answerLines.isNotEmpty()) {
                Text(
                    answerLines.joinToString(", "),
                    fontSize = 12.sp,
                    color = WandColors.success,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            Icon(
                WandIcons.expand,
                contentDescription = if (expanded) "收起" else "展开",
                tint = WandColors.textMuted,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = arrowRotation },
            )
        }
        if (expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            ) {
                questions.forEachIndexed { qIdx, question ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (question.question.isNotEmpty()) {
                            Text(
                                question.question,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                lineHeight = 20.sp,
                                color = WandColors.textPrimary,
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            question.options.forEachIndexed { optIdx, option ->
                                AskUserOptionRow(
                                    option = option,
                                    multiSelect = question.multiSelect,
                                    isAnswered = isAnswered,
                                    chosen = if (isAnswered) {
                                        // 只读态：答案第 qIdx 行（缺行回落第一行），按 "," 拆出已选 label。
                                        val line = answerLines.getOrNull(qIdx)
                                            ?: answerLines.firstOrNull() ?: ""
                                        option.label in line.split(",").map { it.trim() }
                                    } else {
                                        optIdx in (selection.selected[qIdx] ?: emptySet())
                                    },
                                    enabled = !isAnswered && !selection.submitted,
                                    onClick = { onToggle(qIdx, optIdx, question.multiSelect) },
                                )
                            }
                        }
                    }
                }
                if (!isAnswered) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(
                            onClick = {
                                val lines = questions.mapIndexed { qIdx, question ->
                                    (selection.selected[qIdx] ?: emptySet())
                                        .sorted()
                                        .joinToString(", ") { question.options[it].label }
                                }
                                onSubmit(lines.joinToString("\n"))
                            },
                            enabled = allAnswered && !selection.submitted,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = WandColors.brand,
                                contentColor = Color.White,
                            ),
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                        ) {
                            Text(
                                if (selection.submitted) "已提交…" else "确认提交",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 提问卡单个选项：单选圆形 / 多选圆角方形 indicator，选中实底白点/白勾（对齐 Web）。 */
@Composable
private fun AskUserOptionRow(
    option: AskUserQuestionData.Option,
    multiSelect: Boolean,
    isAnswered: Boolean,
    chosen: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (isAnswered) WandColors.success else WandColors.brand
    val fill = when {
        isAnswered && chosen -> WandColors.successSoft
        isAnswered -> WandColors.surface
        chosen -> WandColors.brand.copy(alpha = 0.16f)
        else -> WandColors.surface
    }
    val border = if (chosen) tint else WandColors.border
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(WandShapes.sm)
            .background(fill)
            .border(if (chosen) 1.5.dp else 1.dp, border, WandShapes.sm)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .graphicsLayer { alpha = if (isAnswered && !chosen) 0.55f else 1f }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // indicator
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp)
                .clip(if (multiSelect) RoundedCornerShape(3.dp) else CircleShape)
                .background(if (chosen) tint else Color.Transparent)
                .border(
                    2.dp,
                    if (chosen) tint else WandColors.borderStrong,
                    if (multiSelect) RoundedCornerShape(3.dp) else CircleShape,
                ),
        ) {
            if (chosen) {
                if (multiSelect) {
                    Icon(
                        WandIcons.check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(11.dp),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color.White),
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                option.label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 18.sp,
                color = WandColors.textPrimary,
            )
            if (!option.description.isNullOrEmpty()) {
                Text(
                    option.description,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = WandColors.textSecondary,
                )
            }
        }
    }
}

// MARK: - Diff 卡片（Edit / Write / MultiEdit，对齐 Web 端 inline-diff）

@Composable
fun DiffCard(
    toolName: String,
    input: JSONObject,
    result: ContentBlock.ToolResult?,
) {
    val path = input.str("file_path") ?: input.str("path") ?: ""
    val fileName = path.substringAfterLast('/').ifEmpty { path }
    val isWrite = toolName == "Write" || toolName == "MultiEdit"
    val oldText = input.str("old_string") ?: ""
    val newText = input.str("new_string") ?: input.str("content") ?: ""

    val statusText = when {
        result == null -> "执行中"
        result.isError ->
            if (result.text.contains("haven't granted") || result.text.contains("permission")) {
                "等待授权"
            } else {
                "失败"
            }
        else -> "已修改"
    }
    val statusColor = when {
        result == null -> WandColors.brand
        result.isError -> WandColors.danger
        else -> WandColors.success
    }

    var expanded by remember { mutableStateOf(result == null) }
    // 默认展开态对齐 Web：执行中展开，结果到达后自动收起（手动点开不受影响）。
    LaunchedEffect(result != null) { if (result != null) expanded = false }
    val arrowRotation by animateFloatAsState(
        if (expanded) 180f else 0f,
        WandMotion.tweenNormal(),
        label = "diffArrow",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(WandShapes.md)
            .background(WandColors.surface)
            .border(1.dp, WandColors.border, WandShapes.md)
            .animateContentSize(WandMotion.tweenNormal()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Icon(
                WandIcons.edit,
                contentDescription = null,
                tint = WandColors.brand,
                modifier = Modifier.size(18.dp),
            )
            Text(
                fileName,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = WandColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                path,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = WandColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                statusText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = statusColor,
                modifier = Modifier
                    .clip(WandShapes.full)
                    .background(statusColor.copy(alpha = 0.12f))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
            Icon(
                WandIcons.expand,
                contentDescription = if (expanded) "收起" else "展开",
                tint = WandColors.textMuted,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = arrowRotation },
            )
        }
        if (expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            ) {
                if (!isWrite && oldText.isNotEmpty()) {
                    DiffColumn(label = "旧", text = oldText, prefix = "- ", tint = WandColors.danger)
                }
                if (newText.isNotEmpty()) {
                    DiffColumn(
                        label = if (isWrite) "" else "新",
                        text = newText,
                        prefix = "+ ",
                        tint = WandColors.success,
                    )
                }
                if (result != null && result.isError && result.text.isNotEmpty()) {
                    Text(
                        if (result.text.length > 600) result.text.take(600) + "…" else result.text,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = WandColors.danger,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiffColumn(label: String, text: String, prefix: String, tint: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (label.isNotEmpty()) {
            Text(
                label,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = WandColors.textMuted,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(WandShapes.sm)
                .background(tint.copy(alpha = 0.08f))
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            SelectionContainer {
                Text(
                    prefix + if (text.length > 2000) text.take(2000) + "\n…（已截断）" else text,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    color = tint,
                )
            }
        }
    }
}

// MARK: - 终端卡片（Bash，对齐 Web 端 inline-terminal）

/** 终端卡固定深色，亮暗主题一致（对齐 Web）。 */
private val TermBg = Color(0xFF1E1E1E)
private val TermText = Color(0xFFD9D9D4)
private val TermErrorText = Color(0xFFF28C82)

@Composable
fun TerminalCard(
    input: JSONObject,
    result: ContentBlock.ToolResult?,
    running: Boolean = false,
) {
    val command = input.str("command") ?: input.str("cmd") ?: ""
    val statusColor = when {
        result == null -> WandColors.brand
        result.isError -> WandColors.danger
        else -> WandColors.success
    }
    var expanded by remember { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        if (expanded) 180f else 0f,
        WandMotion.tweenNormal(),
        label = "termArrow",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(WandShapes.md)
            .background(TermBg)
            .border(1.dp, Color.White.copy(alpha = 0.12f), WandShapes.md)
            .animateContentSize(WandMotion.tweenNormal()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            if (running) {
                val spin = rememberInfiniteTransition(label = "termSpin")
                val angle by spin.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(900)),
                    label = "termSpinAngle",
                )
                Icon(
                    WandIcons.refresh,
                    contentDescription = "运行中",
                    tint = TermText,
                    modifier = Modifier
                        .size(14.dp)
                        .graphicsLayer { rotationZ = angle },
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
            }
            Text(
                "$ " + if (command.length > 80) command.take(77) + "…" else command,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = TermText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                WandIcons.expand,
                contentDescription = if (expanded) "收起" else "展开",
                tint = TermText.copy(alpha = 0.6f),
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = arrowRotation },
            )
        }
        if (expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            ) {
                Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(
                            "$ $command",
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TermText,
                        )
                    }
                }
                if (result != null && result.text.isNotEmpty()) {
                    Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        SelectionContainer {
                            Text(
                                if (result.text.length > 4000) {
                                    result.text.take(4000) + "\n…（已截断）"
                                } else {
                                    result.text
                                },
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (result.isError) TermErrorText else TermText.copy(alpha = 0.85f),
                            )
                        }
                    }
                }
            }
        }
    }
}

// MARK: - 待办进度条（TodoWrite，对齐 Web 端 todo-progress）

/** TodoWrite 的一项待办（tool_use input.todos[i]）。 */
data class TodoEntry(
    val content: String,
    val status: String,
    val activeForm: String?,
)

/**
 * 当前 turn 的待办列表：只看最后一条 user 消息之后的 TodoWrite，
 * 对齐 Web 端 updateTodoProgress 的 scoping（上一轮的进度条不跨 turn 残留）。
 * 全部完成时返回空（对齐 Web allDone 隐藏）。
 */
fun currentTodos(messages: List<ConversationTurn>): List<TodoEntry> {
    var startIdx = 0
    for (i in messages.indices.reversed()) {
        if (messages[i].role == "user") {
            startIdx = i + 1
            break
        }
    }
    for (i in messages.indices.reversed()) {
        if (i < startIdx) break
        for (block in messages[i].content.reversed()) {
            if (block is ContentBlock.ToolUse && block.name == "TodoWrite") {
                val arr = block.input.optJSONArray("todos") ?: continue
                val todos = mutableListOf<TodoEntry>()
                for (j in 0 until arr.length()) {
                    val obj = arr.optJSONObject(j) ?: continue
                    todos.add(
                        TodoEntry(
                            content = obj.str("content") ?: "",
                            status = obj.str("status") ?: "pending",
                            activeForm = obj.str("activeForm"),
                        )
                    )
                }
                if (todos.isEmpty()) continue
                val completed = todos.count { it.status == "completed" }
                return if (completed == todos.size) emptyList() else todos
            }
        }
    }
    return emptyList()
}

/** 输入栏上方的悬浮进度条：环形进度 + N/M + 当前任务，点击展开任务列表。 */
@Composable
fun TodoProgressBar(todos: List<TodoEntry>) {
    if (todos.isEmpty()) return
    val completed = todos.count { it.status == "completed" }
    // 1-indexed「正在干第 N 个」：completed+1 封顶（对齐 Web currentStep）。
    val currentStep = minOf(completed + 1, todos.size)
    val activeTask = todos.firstOrNull { it.status == "in_progress" }
        ?.let { it.activeForm?.ifEmpty { null } ?: it.content } ?: ""
    var expanded by remember { mutableStateOf(false) }
    val ringColor = WandColors.brand
    val trackColor = WandColors.border
    val progress = currentStep.toFloat() / todos.size.toFloat()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(WandShapes.lg)
            .background(WandColors.surface)
            .border(1.dp, WandColors.border, WandShapes.lg)
            .animateContentSize(WandMotion.tweenNormal()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Canvas(modifier = Modifier.size(18.dp)) {
                val strokeWidth = 3.dp.toPx()
                val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                val inset = strokeWidth / 2
                drawCircle(
                    color = trackColor,
                    radius = (size.minDimension - strokeWidth) / 2,
                    style = stroke,
                )
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(
                        size.width - strokeWidth,
                        size.height - strokeWidth,
                    ),
                    style = stroke,
                )
            }
            Text(
                "$currentStep/${todos.size}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = WandColors.brand,
            )
            if (activeTask.isNotEmpty()) {
                Text(
                    activeTask,
                    fontSize = 12.sp,
                    color = WandColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            Icon(
                WandIcons.expand,
                contentDescription = if (expanded) "收起" else "展开",
                tint = WandColors.textMuted,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = if (expanded) 0f else 180f },
            )
        }
        if (expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            ) {
                todos.forEach { todo ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            when (todo.status) {
                                "completed" -> "✓"
                                "in_progress" -> "›"
                                else -> "○"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = when (todo.status) {
                                "completed" -> WandColors.success
                                "in_progress" -> WandColors.brand
                                else -> WandColors.textMuted
                            },
                            modifier = Modifier.width(14.dp),
                        )
                        Text(
                            todo.content,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = if (todo.status == "in_progress") {
                                WandColors.textPrimary
                            } else {
                                WandColors.textSecondary
                            },
                            textDecoration = if (todo.status == "completed") {
                                TextDecoration.LineThrough
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}
