package com.wand.app.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import com.wand.app.data.EscalationRequest
import com.wand.app.data.PermissionRequestInfo
import com.wand.app.data.SubagentMeta
import com.wand.app.data.summaryText
import com.wand.app.ui.theme.WandColors
import org.json.JSONObject

/**
 * 聊天内容块渲染 —— 对称 iOS ChatView.swift 的 TurnView / BlockView /
 * MarkdownText / ToolUseCard / CollapsibleSection / PermissionCard。
 */

// MARK: - 单条消息

@Composable
fun TurnView(turn: ConversationTurn) {
    if (turn.role == "user") {
        UserBubble(turn)
    } else {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            turn.content.forEach { block -> BlockView(block) }
        }
    }
}

@Composable
private fun UserBubble(turn: ConversationTurn) {
    val text = turn.content
        .filterIsInstance<ContentBlock.Text>()
        .joinToString("\n") { it.text }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 48.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        SelectionContainer {
            Text(
                text,
                fontSize = 16.sp,
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

// MARK: - 内容块

@Composable
fun BlockView(block: ContentBlock) {
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
                CollapsibleSection(icon = "💭", title = "思考过程", tint = WandColors.textSecondary) {
                    SelectionContainer {
                        Text(
                            block.thinking,
                            fontSize = 13.sp,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        is ContentBlock.ToolUse -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SubagentTag(block.subagent)
                ToolUseCard(block.name, block.description, block.input)
            }
        }
        is ContentBlock.ToolResult -> {
            if (block.text.isNotEmpty()) {
                CollapsibleSection(
                    icon = if (block.isError) "⛔" else "📄",
                    title = if (block.isError) "执行出错" else "执行结果",
                    tint = if (block.isError) WandColors.danger else WandColors.textSecondary,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            SelectionContainer {
                                Text(
                                    if (block.text.length > 4000) {
                                        block.text.take(4000) + "\n…（已截断）"
                                    } else {
                                        block.text
                                    },
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (block.isError) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                        if (block.truncated) {
                            Text(
                                "内容过长，已截断",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
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
    ) {
        Text("👥", fontSize = 10.sp)
        Text(
            meta.taskDescription ?: meta.agentType ?: "子任务",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

// MARK: - Markdown-lite

private data class MarkdownSegment(val content: String, val isCode: Boolean)

/** 简化 Markdown 渲染：按 ``` 切分代码块，其余段落做 **bold** / `code` 内联样式。 */
@Composable
fun MarkdownText(text: String) {
    val segments = remember(text) { splitMarkdownSegments(text) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEach { segment ->
            if (segment.isCode) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(8.dp),
                        )
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    SelectionContainer {
                        Text(
                            segment.content,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            } else {
                SelectionContainer {
                    Text(
                        inlineMarkdown(segment.content),
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private fun splitMarkdownSegments(text: String): List<MarkdownSegment> {
    val parts = text.split("```")
    val result = mutableListOf<MarkdownSegment>()
    parts.forEachIndexed { index, raw ->
        val isCode = index % 2 == 1
        var content = raw
        if (isCode) {
            // 去掉语言标记行（``` 后第一行）
            val newline = content.indexOf('\n')
            if (newline >= 0) {
                val firstLine = content.substring(0, newline)
                if (firstLine.length <= 24 && !firstLine.contains(' ')) {
                    content = content.substring(newline + 1)
                }
            }
        }
        content = content.trim()
        if (content.isNotEmpty()) {
            result.add(MarkdownSegment(content, isCode))
        }
    }
    return result
}

/** 内联样式：**bold** 与 `code`。逐字符扫描，避免正则回溯。 */
private fun inlineMarkdown(raw: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < raw.length) {
        when {
            raw.startsWith("**", i) -> {
                val end = raw.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(raw.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(raw[i])
                    i++
                }
            }
            raw[i] == '`' -> {
                val end = raw.indexOf('`', i + 1)
                if (end > i + 1) {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
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
            else -> {
                append(raw[i])
                i++
            }
        }
    }
}

// MARK: - 工具调用卡片

/** 工具调用卡片：图标 + 工具名 + 参数摘要。 */
@Composable
fun ToolUseCard(name: String, description: String?, input: JSONObject) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(toolIcon(name), fontSize = 14.sp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            Text(
                name,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val summary = toolSummary(description, input)
            if (summary.isNotEmpty()) {
                Text(
                    summary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

private fun toolIcon(name: String): String {
    val lower = name.lowercase()
    return when {
        lower.contains("bash") || lower.contains("command") -> "🖥"
        lower.contains("edit") || lower.contains("write") -> "✏️"
        lower.contains("read") -> "📖"
        lower.contains("grep") || lower.contains("glob") || lower.contains("search") -> "🔍"
        lower.contains("web") || lower.contains("fetch") -> "🌐"
        lower.contains("task") || lower.contains("agent") -> "👥"
        else -> "🔧"
    }
}

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

// MARK: - 可折叠区块

/** 可折叠区块（thinking / tool_result 共用），默认折叠。 */
@Composable
fun CollapsibleSection(
    icon: String,
    title: String,
    tint: Color,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.animateContentSize(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.clickable { expanded = !expanded },
        ) {
            Text(icon, fontSize = 12.sp)
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = tint)
            Text(
                if (expanded) "▲" else "▼",
                fontSize = 9.sp,
                color = tint,
            )
        }
        if (expanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .padding(10.dp),
            ) {
                content()
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
    val title = escalation?.scopeTitle ?: "权限请求"
    val detail = escalation?.reason ?: legacy?.prompt ?: ""
    val target = escalation?.target ?: legacy?.target

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                1.5.dp,
                WandColors.permission.copy(alpha = 0.55f),
                RoundedCornerShape(14.dp),
            )
            .padding(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("🔐", fontSize = 16.sp)
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (detail.isNotEmpty()) {
            Text(
                detail,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
            )
        }
        if (!target.isNullOrEmpty()) {
            Text(
                target,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onResolve("approve_once") },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
                contentPadding = ButtonDefaults.TextButtonContentPadding,
                modifier = Modifier.weight(1f),
            ) { Text("允许", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            if (escalation != null) {
                OutlinedButton(
                    onClick = { onResolve("approve_turn") },
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        "本轮均允许",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
            OutlinedButton(
                onClick = { onResolve("deny") },
                contentPadding = ButtonDefaults.TextButtonContentPadding,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    "拒绝",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
