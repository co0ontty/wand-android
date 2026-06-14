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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
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
import com.wand.app.data.arrayField
import com.wand.app.data.str
import com.wand.app.data.summaryText
import com.wand.app.ui.AskUserSelectionState
import com.wand.app.ui.LocalServerBaseUrl
import com.wand.app.ui.WandAsyncImage
import com.wand.app.ui.WandFileChip
import com.wand.app.ui.WandImage
import com.wand.app.ui.parseUserAttachmentText
import com.wand.app.ui.components.StatusDot
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.toolIcon
import com.wand.app.ui.theme.GlassBackdrop
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandGlass
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.cardShadowColors
import com.wand.app.ui.theme.glassCard
import com.wand.app.ui.theme.glassSurface
import com.wand.app.ui.theme.layeredShadow
import com.wand.app.ui.theme.tinted
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
        UserTurnView(turn)
    } else {
        val segments = remember(turn.content) { splitBySubagent(turn.content) }
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            segments.forEachIndexed { segmentIndex, segment ->
                if (segment.subagent == null) {
                    SegmentBlocks(
                        blocks = segment.blocks,
                        isLastTurn = isLastTurn,
                        isResponding = isResponding,
                        askSelections = askSelections,
                        onAskToggle = onAskToggle,
                        onAskSubmit = onAskSubmit,
                    )
                } else {
                    SubagentPanel(
                        meta = segment.subagent,
                        running = isLastTurn && isResponding && segmentIndex == segments.lastIndex,
                    ) {
                        SegmentBlocks(
                            blocks = segment.blocks,
                            isLastTurn = isLastTurn,
                            isResponding = isResponding,
                            askSelections = askSelections,
                            onAskToggle = onAskToggle,
                            onAskSubmit = onAskSubmit,
                            showSubagentTags = false,
                        )
                    }
                }
            }
        }
    }
}

/** user turn 也可能携带 Task tool_result；保留普通用户气泡，并把子代理回包独立成面板。 */
@Composable
private fun UserTurnView(turn: ConversationTurn) {
    val parentBlocks = remember(turn.content) { turn.content.filter { it.subagentMeta() == null } }
    val subagentSegments = remember(turn.content) {
        splitBySubagent(turn.content).filter { it.subagent != null }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        if (parentBlocks.any { it is ContentBlock.Text && it.text.isNotBlank() }) {
            UserBubble(turn.copy(content = parentBlocks))
        }
        subagentSegments.forEach { segment ->
            SubagentPanel(meta = segment.subagent ?: return@forEach, running = false) {
                SegmentBlocks(
                    blocks = segment.blocks,
                    isLastTurn = false,
                    isResponding = false,
                    askSelections = emptyMap(),
                    onAskToggle = { _, _, _, _ -> },
                    onAskSubmit = { _, _ -> },
                    showSubagentTags = false,
                )
            }
        }
    }
}

private data class AssistantSegment(
    val subagent: SubagentMeta?,
    val blocks: List<ContentBlock>,
)

/** 对齐 Web splitTurnBySubagent：按连续 taskId 分段，再把每段渲染进独立面板。 */
private fun splitBySubagent(blocks: List<ContentBlock>): List<AssistantSegment> {
    val segments = mutableListOf<AssistantSegment>()
    var activeMeta: SubagentMeta? = null
    var activeKey: String? = null
    var activeBlocks = mutableListOf<ContentBlock>()

    fun flush() {
        if (activeBlocks.isNotEmpty()) {
            segments += AssistantSegment(activeMeta, activeBlocks.toList())
            activeBlocks = mutableListOf()
        }
    }

    blocks.forEach { block ->
        val meta = block.subagentMeta()
        val key = meta?.taskId
        if (activeBlocks.isNotEmpty() && key != activeKey) flush()
        activeMeta = meta
        activeKey = key
        activeBlocks += block
    }
    flush()
    return segments
}

private fun ContentBlock.subagentMeta(): SubagentMeta? = when (this) {
    is ContentBlock.Text -> subagent
    is ContentBlock.Thinking -> subagent
    is ContentBlock.ToolUse -> subagent
    is ContentBlock.ToolResult -> subagent
    is ContentBlock.Unknown -> null
}

@Composable
private fun SegmentBlocks(
    blocks: List<ContentBlock>,
    isLastTurn: Boolean,
    isResponding: Boolean,
    askSelections: Map<String, AskUserSelectionState>,
    onAskToggle: (String, Int, Int, Boolean) -> Unit,
    onAskSubmit: (String, String) -> Unit,
    showSubagentTags: Boolean = true,
) {
    val items = remember(blocks) { pairToolBlocks(blocks) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            when (item) {
                is DisplayItem.Tool -> {
                    val use = item.use
                    // Task/Agent 自身只表达派遣关系，面板头已经承接该语义。
                    if (use.subagent?.taskId == use.id && use.name in setOf("Task", "Agent")) {
                        return@forEachIndexed
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (showSubagentTags) SubagentTag(use.subagent)
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
                                running = item.result == null && isLastTurn && isResponding,
                            )
                        }
                    }
                }
                is DisplayItem.Plain -> BlockView(
                    item.block,
                    streaming = isLastTurn && isResponding && index == items.lastIndex,
                    showSubagentTag = showSubagentTags,
                )
                is DisplayItem.Exploration -> ExplorationGroupCard(
                    tools = item.tools,
                    running = isLastTurn && isResponding && item.tools.any { it.result == null },
                )
            }
        }
    }
}

@Composable
private fun SubagentPanel(
    meta: SubagentMeta,
    running: Boolean,
    content: @Composable () -> Unit,
) {
    var expanded by remember(meta.taskId) { mutableStateOf(false) }
    val title = meta.agentType?.takeIf { it.isNotBlank() } ?: "Sub-agent"
    val description = meta.taskDescription?.takeIf { it.isNotBlank() }
    val arrowRotation by animateFloatAsState(
        if (expanded) 180f else 0f,
        WandMotion.tweenNormal(),
        label = "subagentArrow",
    )

    // 子代理面板是一张可折叠卡片：走 glassCard 继承统一立体感，
    // info 弱底 + info rim 让"子任务"语义蓝保持清晰。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassCard(WandShapes.lg, tint = WandColors.infoSoft, rimTint = WandColors.info)
            .animateContentSize(WandMotion.tweenNormal()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 11.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(WandColors.info.copy(alpha = 0.14f)),
            ) {
                if (running) {
                    CircularProgressIndicator(
                        color = WandColors.info,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Icon(
                        WandIcons.agent,
                        contentDescription = null,
                        tint = WandColors.info,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = WandColors.info,
                )
                Text(
                    description ?: if (running) "正在处理子任务" else "子任务输出",
                    fontSize = 11.sp,
                    color = WandColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                if (expanded) "收起" else "查看",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = WandColors.info,
            )
            Icon(
                WandIcons.expand,
                contentDescription = if (expanded) "收起子任务" else "查看子任务",
                tint = WandColors.info,
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = arrowRotation },
            )
        }
        if (expanded) {
            HorizontalDivider(color = WandColors.info.copy(alpha = 0.18f))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 用暖中性页底做下沉内容井，而非纯白 surface —— 白底叠在半透明蓝卡上会
                    // 在蓝头下方糊出一块「白色印子」，改用 bgPrimary 让内容区读作递进的暖色凹槽。
                    .background(WandColors.bgPrimary.copy(alpha = 0.55f))
                    .padding(12.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun UserBubble(turn: ConversationTurn) {
    val rawText = turn.content
        .filterIsInstance<ContentBlock.Text>()
        .joinToString("\n") { it.text }
    // 剥离「[附件已上传，请查看以下文件:…]」前缀：图片渲缩略图、其余渲文件块，正文留在气泡里。
    // 无前缀时 paths 为空、body 即原文，行为与旧版完全一致（对齐网页 renderUserText）。
    val parsed = remember(rawText) { parseUserAttachmentText(rawText) }
    val baseUrl = LocalServerBaseUrl.current
    val bubbleShape = RoundedCornerShape(
        topStart = WandShapes.radiusLg,
        topEnd = WandShapes.radiusLg,
        bottomEnd = WandShapes.radiusXs, // 右下小圆角"尾巴"
        bottomStart = WandShapes.radiusLg,
    )
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 44.dp),
    ) {
        // 附件缩略图 / 文件块：右对齐贴在气泡上方（对齐网页 user-attachments 块在正文之上）。
        if (parsed.paths.isNotEmpty() && baseUrl.isNotEmpty()) {
            parsed.paths.forEach { path ->
                if (WandImage.isImagePath(path)) {
                    WandAsyncImage(path = path, baseUrl = baseUrl)
                } else {
                    WandFileChip(path = path)
                }
            }
        }
        if (parsed.body.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                SelectionContainer {
                    // 玻璃质感气泡：品牌色纵向渐变（上浅下深）+ 顶缘受光 rim + 品牌色软阴影。
                    val brand = WandColors.brand
                    Text(
                        parsed.body,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        color = Color.White,
                        modifier = Modifier
                            .shadow(
                                elevation = 2.dp,
                                shape = bubbleShape,
                                ambientColor = brand.copy(alpha = 0.45f),
                                spotColor = brand.copy(alpha = 0.45f),
                            )
                            .clip(bubbleShape)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        lerp(brand, Color.White, 0.10f),
                                        lerp(brand, Color.Black, 0.10f),
                                    )
                                )
                            )
                            .border(
                                1.dp,
                                Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.32f),
                                        Color.White.copy(alpha = 0.02f),
                                    )
                                ),
                                bubbleShape,
                            )
                            .padding(horizontal = 13.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

// MARK: - 工具调用与结果的渲染层配对

private sealed class DisplayItem {
    class Plain(val block: ContentBlock) : DisplayItem()
    class Tool(val use: ContentBlock.ToolUse, val result: ContentBlock.ToolResult?) : DisplayItem()
    class Exploration(val tools: List<ExplorationToolItem>) : DisplayItem()
}

/** 探索卡里的一个工具（配对后的 use + 可选 result）。 */
data class ExplorationToolItem(
    val use: ContentBlock.ToolUse,
    val result: ContentBlock.ToolResult?,
)

/** 跨消息分组后的渲染单元（对齐 iOS MessageDisplayItem）。 */
sealed class MessageDisplayItem {
    data class Turn(val index: Int, val turn: ConversationTurn) : MessageDisplayItem()
    data class Exploration(
        val tools: List<ExplorationToolItem>,
        val lastTurnIndex: Int,
    ) : MessageDisplayItem()
}

/**
 * 将相邻、且内容完全由只读探索工具组成的 assistant turn 跨消息合并。
 * 用户消息、正式文本、编辑/命令等操作都会立即终止分组（对齐 iOS groupExplorationTurns）。
 */
fun groupExplorationTurns(turns: List<ConversationTurn>): List<MessageDisplayItem> {
    val items = mutableListOf<MessageDisplayItem>()
    val pending = mutableListOf<ExplorationToolItem>()
    var pendingLastIndex = -1

    fun flushPending() {
        if (pending.isNotEmpty()) {
            items.add(MessageDisplayItem.Exploration(pending.toList(), pendingLastIndex))
            pending.clear()
            pendingLastIndex = -1
        }
    }

    turns.forEachIndexed { index, turn ->
        val tools = explorationToolsOnly(turn)
        if (tools != null) {
            pending += tools
            pendingLastIndex = index
        } else {
            flushPending()
            items.add(MessageDisplayItem.Turn(index, turn))
        }
    }
    flushPending()
    return items
}

/** turn 是否仅由探索类工具组成；是则返回这些工具，否则 null。 */
private fun explorationToolsOnly(turn: ConversationTurn): List<ExplorationToolItem>? {
    if (turn.role != "assistant") return null
    val tools = mutableListOf<ExplorationToolItem>()
    for (item in pairToolBlocks(turn.content)) {
        when {
            item is DisplayItem.Exploration -> tools += item.tools
            item is DisplayItem.Tool && isCollapsibleExplorationTool(item.use) ->
                tools += ExplorationToolItem(item.use, item.result)
            else -> return null
        }
    }
    return tools.ifEmpty { null }
}

/** 只读探索类工具：读取 / 搜索 / 网页获取 / 待办读取。 */
private fun isExplorationTool(name: String): Boolean {
    val lower = name.lowercase()
    return lower.startsWith("read") ||
        lower.startsWith("grep") ||
        lower.startsWith("glob") ||
        lower.startsWith("search") ||
        lower.startsWith("find") ||
        lower.contains("websearch") ||
        lower.contains("webfetch") ||
        lower == "todoread"
}

/**
 * 工具是否参与探索分组折叠。对齐网页 isGroupableToolBlock：
 * 读图的 Read 单独成卡（缩略图常驻可见），不并入默认折叠的探索组，
 * 否则 body 整体折叠会把内联缩略图一起藏掉。
 */
private fun isCollapsibleExplorationTool(use: ContentBlock.ToolUse): Boolean {
    if (!isExplorationTool(use.name)) return false
    if (use.name == "Read" && readImagePath(use.input) != null) return false
    return true
}

/**
 * 连续读取、搜索、网页获取通常只是模型探索上下文，不需要逐张占满对话流。
 * 至少连续两次才合并，单次操作仍保留完整工具卡（对齐 iOS collapseConsecutiveExplorationTools）。
 */
private fun collapseConsecutiveExplorationTools(paired: List<DisplayItem>): List<DisplayItem> {
    val items = mutableListOf<DisplayItem>()
    val exploration = mutableListOf<ExplorationToolItem>()

    fun flushExploration() {
        if (exploration.size >= 2) {
            items.add(DisplayItem.Exploration(exploration.toList()))
        } else {
            exploration.firstOrNull()?.let { items.add(DisplayItem.Tool(it.use, it.result)) }
        }
        exploration.clear()
    }

    for (item in paired) {
        if (item is DisplayItem.Tool && isCollapsibleExplorationTool(item.use)) {
            exploration.add(ExplorationToolItem(item.use, item.result))
        } else {
            flushExploration()
            items.add(item)
        }
    }
    flushExploration()
    return items
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
    return collapseConsecutiveExplorationTools(items)
}

// MARK: - 内容块

@Composable
fun BlockView(
    block: ContentBlock,
    streaming: Boolean = false,
    showSubagentTag: Boolean = true,
) {
    when (block) {
        is ContentBlock.Text -> {
            if (block.text.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (showSubagentTag) SubagentTag(block.subagent)
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
                if (showSubagentTag) SubagentTag(block.subagent)
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
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock()
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
                is MarkdownBlock.Table -> MarkdownTable(block.headers, block.rows)
                MarkdownBlock.Divider -> HorizontalDivider(
                    thickness = 1.dp,
                    color = WandColors.border,
                    modifier = Modifier.padding(vertical = 3.dp),
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
                .border(1.dp, WandColors.border, RoundedCornerShape(10.dp)),
        ) {
            MarkdownTableRow(headers, header = true, background = WandColors.brand.copy(alpha = 0.09f))
            rows.forEachIndexed { index, row ->
                HorizontalDivider(thickness = 1.dp, color = WandColors.border)
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
                    modifier = Modifier
                        .widthIn(min = 110.dp, max = 190.dp)
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                )
            }
            if (index < cells.lastIndex) {
                VerticalDivider(
                    thickness = 1.dp,
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
 * 工具调用卡片（对齐 iOS ToolUseCard）：34dp 彩色图标框 + 中文工具名 + 参数摘要 +
 * 状态胶囊（处理中/完成/失败/待执行）+ 可折叠结果区。
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
    val statusColor = when {
        isError -> WandColors.danger
        running -> WandColors.brand
        isSuccess -> WandColors.success
        else -> WandColors.textSecondary
    }
    val statusText = when {
        isError -> "失败"
        running -> "处理中"
        isSuccess -> "完成"
        else -> "待执行"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassCard(WandShapes.md, rimTint = if (isError) statusColor else null)
            .animateContentSize(WandMotion.tweenNormal()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (hasBody) Modifier.clickable { expanded = !expanded } else Modifier)
                .padding(horizontal = 11.dp, vertical = 10.dp),
        ) {
            ToolStatusIconBox(statusColor = statusColor, running = running) {
                Icon(
                    toolIcon(use.name),
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(16.dp),
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    toolLabel(use.name),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isError) WandColors.danger else WandColors.textPrimary,
                )
                val summary = toolSummary(use.description, use.input)
                if (summary.isNotEmpty()) {
                    Text(
                        summary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = WandColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                statusText,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = statusColor,
                modifier = Modifier
                    .clip(WandShapes.full)
                    .background(statusColor.copy(alpha = 0.10f))
                    .padding(horizontal = 7.dp, vertical = 4.dp),
            )
            if (hasBody) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(WandColors.bgPrimary),
                ) {
                    Icon(
                        WandIcons.expand,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = WandColors.textSecondary,
                        modifier = Modifier
                            .size(14.dp)
                            .graphicsLayer { rotationZ = arrowRotation },
                    )
                }
            }
        }
        // Read 读到图片：始终内联缩略图（对齐网页 inline-tool-image，不藏在展开区里），
        // 点击放大。加载失败由 WandAsyncImage 自行隐藏。
        if (use.name == "Read") {
            val imgPath = readImagePath(use.input)
            val baseUrl = LocalServerBaseUrl.current
            if (imgPath != null && baseUrl.isNotEmpty()) {
                WandAsyncImage(
                    path = imgPath,
                    baseUrl = baseUrl,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                )
            }
        }
        if (expanded && result != null) {
            HorizontalDivider(
                thickness = 1.dp,
                color = WandColors.border.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            ToolResultBody(
                result,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
            )
        }
    }
}

/** 从 Read 工具入参取图片路径（file_path / path），非图片返回 null（对齐网页 inline-tool-image 判定）。 */
private fun readImagePath(input: JSONObject): String? {
    val path = (input.str("file_path") ?: input.str("path"))?.takeIf { it.isNotEmpty() } ?: return null
    return if (WandImage.isImagePath(path)) path else null
}

/** 工具卡左侧 34dp 状态图标框：运行中转圈，否则显示传入图标（对齐 iOS 头部 ZStack）。 */
@Composable
private fun ToolStatusIconBox(
    statusColor: Color,
    running: Boolean,
    icon: @Composable () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(statusColor.copy(alpha = 0.11f)),
    ) {
        if (running) {
            CircularProgressIndicator(
                color = statusColor,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
        } else {
            icon()
        }
    }
}

// MARK: - 探索上下文紧凑卡（连续只读探索工具合并，对齐 iOS ExplorationGroupCard）

@Composable
fun ExplorationGroupCard(tools: List<ExplorationToolItem>, running: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val completedCount = tools.count { it.result != null }
    val failedCount = tools.count { it.result?.isError == true }
    val progress = if (tools.isEmpty()) 0f else completedCount.toFloat() / tools.size
    val tint = when {
        failedCount > 0 -> WandColors.danger
        running -> WandColors.brand
        else -> WandColors.success
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassCard(WandShapes.md, rimTint = if (failedCount > 0) tint else null)
            .animateContentSize(WandMotion.tweenNormal()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 11.dp, vertical = 10.dp),
        ) {
            ToolStatusIconBox(statusColor = tint, running = running) {
                Icon(
                    WandIcons.search,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(16.dp),
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "探索上下文",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = WandColors.textPrimary,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        "$completedCount/${tools.size}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = tint,
                    )
                }
                Text(
                    explorationSummary(tools),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = WandColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { progress },
                    color = tint,
                    trackColor = WandColors.border,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (failedCount > 0) {
                Text(
                    "失败 $failedCount",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WandColors.danger,
                    modifier = Modifier
                        .clip(WandShapes.full)
                        .background(WandColors.danger.copy(alpha = 0.10f))
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                )
            }
            Icon(
                WandIcons.expand,
                contentDescription = if (expanded) "收起" else "展开",
                tint = WandColors.textSecondary,
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer { rotationZ = if (expanded) 180f else 0f },
            )
        }
        if (expanded) {
            HorizontalDivider(
                thickness = 1.dp,
                color = WandColors.border.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            ) {
                tools.forEach { tool ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            when {
                                tool.result?.isError == true -> WandIcons.statusFail
                                tool.result != null -> WandIcons.statusDone
                                else -> WandIcons.statusPending
                            },
                            contentDescription = null,
                            tint = when {
                                tool.result?.isError == true -> WandColors.danger
                                tool.result != null -> WandColors.success
                                else -> WandColors.brand
                            },
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            toolLabel(tool.use.name),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WandColors.textPrimary,
                            maxLines = 1,
                            modifier = Modifier.width(54.dp),
                        )
                        Text(
                            explorationToolSummary(tool),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = WandColors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** 探索卡摘要：「读取 3 · 搜索 2 · 网页 1」（对齐 iOS activitySummary）。 */
private fun explorationSummary(tools: List<ExplorationToolItem>): String {
    val counts = mutableMapOf<String, Int>()
    for (tool in tools) {
        val label = explorationActivityLabel(tool.use.name)
        counts[label] = (counts[label] ?: 0) + 1
    }
    return listOf("读取", "搜索", "网页", "待办")
        .mapNotNull { label -> counts[label]?.let { "$label $it" } }
        .joinToString(" · ")
}

private fun explorationActivityLabel(name: String): String {
    val lower = name.lowercase()
    return when {
        lower.contains("web") -> "网页"
        lower == "todoread" -> "待办"
        lower.startsWith("read") -> "读取"
        else -> "搜索"
    }
}

/** 探索卡单行参数摘要（对齐 iOS toolSummary：路径/查询词/URL 优先）。 */
private fun explorationToolSummary(tool: ExplorationToolItem): String {
    val keys = listOf("file_path", "path", "pattern", "query", "url", "file", "filename")
    for (key in keys) {
        if (tool.use.input.has(key) && !tool.use.input.isNull(key)) {
            val text = summaryText(tool.use.input.opt(key))
            if (text.isNotEmpty()) return text
        }
    }
    tool.use.description?.takeIf { it.isNotEmpty() }?.let { return it }
    val firstKey = tool.use.input.keys().asSequence().firstOrNull() ?: return "无参数"
    return "$firstKey: ${summaryText(tool.use.input.opt(firstKey))}"
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
    backdrop: GlassBackdrop? = null,
) {
    val scopeTitle = escalation?.scopeTitle ?: "权限请求"
    val detail = escalation?.reason ?: legacy?.prompt ?: ""
    val target = escalation?.target ?: legacy?.target

    // 权限金色调的玻璃面板（悬浮在消息流上需要实底感，permissionSoft 太透看不清）。
    // glassSurface 已铺好金调实底 + 立体阴影，这里只补一道清晰的金色描边作语义锚点，
    // 不再叠一层半透明金底（与玻璃实底重复，反而把权限金冲浑）。
    val permissionGlass = WandGlass.regular.tinted(WandColors.permission, 0.22f)
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(backdrop, WandShapes.md, permissionGlass)
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
            val items = input.arrayField("questions") ?: return emptyList()
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
    val arrowRotation by animateFloatAsState(
        if (expanded) 180f else 0f,
        WandMotion.tweenNormal(),
        label = "askArrow",
    )

    // 状态色通过 glassCard 的 rimTint 表达（已答绿 / 待答品牌），
    // 不再叠手写 background + border —— 那会与 glassCard 自带的描边/底色撞成双重描边。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassCard(WandShapes.md, rimTint = if (isAnswered) WandColors.success else WandColors.brand)
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
            .glassCard(WandShapes.md)
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
    // 终端固定深色（非玻璃，对齐 Web inline-terminal），但补一层暖调层叠投影，
    // 让它与相邻工具/Diff 卡一样"浮起"，不再贴平。
    val (termKeyShadow, termAmbientShadow) = cardShadowColors()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .layeredShadow(WandShapes.md, 5.dp, termKeyShadow, termAmbientShadow)
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
    // 旧 TodoWrite 协议：最后一次写入就是完整快照，倒序取最新即可。
    for (i in messages.indices.reversed()) {
        if (i < startIdx) break
        for (block in messages[i].content.reversed()) {
            if (block is ContentBlock.ToolUse && block.name == "TodoWrite") {
                val arr = block.input.arrayField("todos") ?: continue
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

    // 新 TaskCreate / TaskUpdate 协议：创建与状态更新是增量事件，需要按时间顺序归并。
    val tasks = linkedMapOf<String, TodoEntry>()
    val pendingCreates = mutableMapOf<String, ContentBlock.ToolUse>()
    for (i in startIdx until messages.size) {
        for (block in messages[i].content) {
            when {
                block is ContentBlock.ToolUse && block.name == "TaskCreate" -> {
                    pendingCreates[block.id] = block
                }
                block is ContentBlock.ToolResult && block.toolUseId in pendingCreates -> {
                    val create = pendingCreates.remove(block.toolUseId) ?: continue
                    val taskId = Regex("""Task #([^\s]+) created""")
                        .find(block.text)?.groupValues?.getOrNull(1) ?: create.id
                    tasks[taskId] = TodoEntry(
                        content = create.input.str("subject")
                            ?: create.input.str("description")
                            ?: "Task #$taskId",
                        status = "pending",
                        activeForm = create.input.str("activeForm"),
                    )
                }
                block is ContentBlock.ToolUse && block.name == "TaskUpdate" -> {
                    val taskId = block.input.str("taskId") ?: continue
                    val existing = tasks[taskId] ?: TodoEntry(
                        content = "Task #$taskId",
                        status = "pending",
                        activeForm = null,
                    )
                    tasks[taskId] = existing.copy(
                        content = block.input.str("subject") ?: existing.content,
                        status = block.input.str("status") ?: existing.status,
                        activeForm = block.input.str("activeForm") ?: existing.activeForm,
                    )
                }
            }
        }
    }
    val derived = tasks.values.toList()
    return if (derived.isNotEmpty() && derived.all { it.status == "completed" }) emptyList() else derived
}

/** 输入栏上方的悬浮进度条：环形进度 + N/M + 当前任务，点击展开任务列表。 */
@Composable
fun TodoProgressBar(todos: List<TodoEntry>, backdrop: GlassBackdrop? = null) {
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
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(350),
        label = "todoProgress",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(backdrop, WandShapes.lg, WandGlass.regular)
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
                    sweepAngle = 360f * animatedProgress,
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (todo.status == "in_progress") {
                                    WandColors.brandSoft
                                } else {
                                    Color.Transparent
                                }
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp),
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
