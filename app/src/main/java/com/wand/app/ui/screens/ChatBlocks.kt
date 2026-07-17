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

/**
 * 聊天内容块渲染（重设计规范 v1 第 3.3 节）：
 * TurnView / UserBubble / ToolCard（工具调用 + 结果配对，三态）/ ThinkingBlock /
 * MarkdownText / PermissionCard。
 * 工具调用与其结果在渲染层配对成一张卡片，对齐 Web 端 tool-card 结构。
 */

/** ChatScreen 注入的会话上下文，用于按需加载被服务端截断的完整工具结果。 */
internal val LocalChatApi = compositionLocalOf<WandApi?> { null }
internal val LocalChatSessionId = compositionLocalOf { "" }
internal val LocalCardExpandDefaults = compositionLocalOf { CardExpandDefaults() }

// MARK: - 单条消息

/**
 * 折叠卡片统一箭头：内部跑 [animateFloatAsState]，展开态转 180°。
 * 抽出来统一所有卡片（Tool/Diff/Terminal/Thinking/Orphan/Subagent/Todo…）的展开方向与动画，
 * 避免之前各处手写 rotationZ 且方向不一致（有的展开转 180°，有的收起才转 180°）。
 */
@Composable
fun ExpandChevron(
    expanded: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    contentDescription: String? = if (expanded) "收起" else "展开",
) {
    val rotation by animateFloatAsState(
        if (expanded) 180f else 0f,
        WandMotion.tweenNormal(),
        label = "expandChevron",
    )
    Icon(
        WandIcons.expand,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size).graphicsLayer { rotationZ = rotation },
    )
}

@Composable
fun TurnView(
    turn: ConversationTurn,
    isLastTurn: Boolean = false,
    isResponding: Boolean = false,
    compactUser: Boolean = false,
    initiallyCollapsed: Boolean = false,
    currentReplyExpandedOverride: Boolean? = null,
    showHeader: Boolean = true,
    showContent: Boolean = true,
    onUserExpand: () -> Unit = {},
    onCurrentReplyExpandedChange: (Boolean) -> Unit = {},
    onCurrentReplyExpandToBottom: () -> Unit = {},
    askSelections: Map<String, AskUserSelectionState> = emptyMap(),
    onAskToggle: (String, Int, Int, Boolean) -> Unit = { _, _, _, _ -> },
    onAskSubmit: (String, String) -> Unit = { _, _ -> },
) {
    if (turn.role == "user") {
        UserTurnView(turn, compact = compactUser)
        return
    }
    // 历史回复默认收起，但折叠状态属于每一条 turn；点击某条不影响其他回复。
    // initiallyCollapsed 改变意味着当前回复刚转为历史，此时应立即回到默认收起态。
    var localCollapsed by rememberSaveable(initiallyCollapsed) {
        mutableStateOf(initiallyCollapsed)
    }
    val collapsed = currentReplyExpandedOverride?.let { !it } ?: localCollapsed
    val segments = remember(turn.content, collapsed) {
        if (collapsed) emptyList() else splitBySubagent(turn.content)
    }
    val preview = remember(turn.content, collapsed) {
        if (collapsed) replyPreview(turn.content) else ""
    }
    val setCollapsed: (Boolean) -> Unit = { next ->
        if (currentReplyExpandedOverride == null) {
            localCollapsed = next
        }
        onCurrentReplyExpandedChange(!next)
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (showHeader) {
            // 左上角：头像 + 名字 + 折叠开关；其下沿是「收起临界线」。
            // 用户手动展开时通知上层把这条的第一行滚到顶部区域来读（不被顶出屏幕上沿）。
            AssistantReplyHeader(
                collapsed = collapsed,
                preview = preview,
                onToggle = {
                    val next = !collapsed
                    setCollapsed(next)
                    if (!next) {
                        if (isLastTurn) onCurrentReplyExpandToBottom() else onUserExpand()
                    }
                },
            )
        }
        if (showContent && (!showHeader || !collapsed)) {
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
        val usageIsLive = isLastTurn && isResponding
        // 流式用量与「正在思考」合并到列表底部的 LiveTurnStatusRow，避免两个
        // 左对齐状态行同时更新时在视觉上重叠。响应结束后仍在回复尾部展示完整用量。
        if (!usageIsLive && (!showHeader || !collapsed) && turn.usage?.hasVisibleValue == true) {
            UsageSummaryRow(turn.usage, isLive = false)
        }
    }
}

/**
 * 助手回复折叠头：收起时用弱底色和一行正文预览交代内容，展开时回到
 * 透明标题行。不再在每条回复下画贯穿整屏的分隔线，层级由留白和局部底色表达。
 */
@Composable
private fun AssistantReplyHeader(
    collapsed: Boolean,
    preview: String,
    onToggle: () -> Unit,
) {
    val background by animateColorAsState(
        targetValue = if (collapsed) {
            WandColors.surfaceSoft.copy(alpha = 0.58f)
        } else {
            Color.Transparent
        },
        animationSpec = WandMotion.tweenFast(),
        label = "assistantReplyHeaderBackground",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(WandShapes.sm)
            .background(background)
            .clickableWithoutRipple(
                onClickLabel = if (collapsed) "展开回复" else "收起回复",
                onClick = onToggle,
            )
            .semantics(mergeDescendants = true) {
                stateDescription = if (collapsed) "已收起" else "已展开"
            }
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(WandColors.brand.copy(alpha = 0.14f)),
        ) {
            Icon(
                WandIcons.sparkle,
                contentDescription = null,
                tint = WandColors.brand,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            "Wand",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = WandColors.textPrimary,
        )
        if (collapsed && preview.isNotBlank()) {
            Text(
                preview,
                fontSize = 12.sp,
                color = WandColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
        Text(
            if (collapsed) "展开" else "收起",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = WandColors.textPrimary,
            maxLines = 1,
        )
        ExpandChevron(
            expanded = !collapsed,
            tint = WandColors.textSecondary,
            size = 16.dp,
            contentDescription = null,
        )
    }
}

/** 折叠态下名字后的一行正文预览：优先取文本，纯工具调用时给「N 个工具调用」线索。 */
private fun replyPreview(content: List<ContentBlock>): String = conversationTurnPreview(
    ConversationTurn(role = "assistant", content = content),
)

/** Codex/Claude 单轮 token 与费用摘要；文本可换行，窄屏不会横向溢出。 */
@Composable
private fun UsageSummaryRow(usage: TurnUsage?, isLive: Boolean) {
    val parts = remember(usage) {
        buildList {
            usage?.inputTokens?.takeIf { it > 0 }?.let { add("输入 ${formatTokenCount(it)}") }
            usage?.cacheReadInputTokens?.takeIf { it > 0 }?.let { add("缓存命中 ${formatTokenCount(it)}") }
            usage?.cacheCreationInputTokens?.takeIf { it > 0 }?.let { add("缓存写入 ${formatTokenCount(it)}") }
            usage?.outputTokens?.takeIf { it > 0 }?.let {
                add("输出 ${if (usage.estimated == true) "≈" else ""}${formatTokenCount(it)}")
            }
            usage?.reasoningOutputTokens?.takeIf { it > 0 }?.let {
                add("推理 ${if (usage.estimated == true) "≈" else ""}${formatTokenCount(it)}")
            }
            usage?.totalCostUsd?.takeIf { it > 0 }?.let { add("\$${formatUsd(it)}") }
        }
    }
    val visibleParts = parts.takeIf { it.isNotEmpty() }
        ?: if (isLive || usage?.estimated == true) listOf("正在统计用量…") else return
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 1.dp, start = 2.dp, end = 2.dp),
    ) {
        Icon(
            WandIcons.usage,
            contentDescription = null,
            tint = WandColors.textMuted,
            modifier = Modifier.padding(top = 1.dp).size(13.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f),
        ) {
            visibleParts.forEach { part ->
                Text(
                    part,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    color = WandColors.textMuted,
                )
            }
        }
    }
}

/**
 * 运行中的单行状态：用量固定在左，当前思考/任务固定在右。
 * 两侧各自单行省略，窄屏不会换行互相挤压；响应结束后由 UsageSummaryRow 展示完整用量。
 */
@Composable
internal fun LiveTurnStatusRow(usage: TurnUsage?, taskTitle: String?) {
    val usageText = remember(usage) {
        buildList {
            usage?.inputTokens?.takeIf { it > 0 }?.let { add("输入 ${formatTokenCount(it)}") }
            usage?.cacheReadInputTokens?.takeIf { it > 0 }?.let { add("缓存 ${formatTokenCount(it)}") }
            usage?.outputTokens?.takeIf { it > 0 }?.let {
                add("输出 ${if (usage.estimated == true) "≈" else ""}${formatTokenCount(it)}")
            }
            usage?.reasoningOutputTokens?.takeIf { it > 0 }?.let {
                add("推理 ${if (usage.estimated == true) "≈" else ""}${formatTokenCount(it)}")
            }
            usage?.totalCostUsd?.takeIf { it > 0 }?.let { add("\$${formatUsd(it)}") }
        }.joinToString(" · ").ifEmpty { "正在统计用量…" }
    }
    val activityText = taskTitle?.trim().takeUnless { it.isNullOrEmpty() } ?: "正在思考…"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                stateDescription = "$usageText，$activityText"
            }
            .padding(top = 1.dp, start = 2.dp, end = 2.dp, bottom = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                WandIcons.usage,
                contentDescription = null,
                tint = WandColors.textMuted,
                modifier = Modifier.size(13.dp),
            )
            Text(
                usageText,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                fontFamily = FontFamily.Monospace,
                color = WandColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            modifier = Modifier.weight(1f),
        ) {
            StatusDot("running")
            Text(
                activityText,
                fontSize = 13.sp,
                color = WandColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

private fun formatTokenCount(value: Int): String = when {
    value < 1_000 -> NumberFormat.getIntegerInstance().format(value)
    value < 1_000_000 -> String.format(Locale.US, "%.1fk", value / 1_000.0).replace(".0k", "k")
    else -> String.format(Locale.US, "%.1fM", value / 1_000_000.0).replace(".0M", "M")
}

private fun formatUsd(value: Double): String = when {
    value >= 0.01 -> String.format(Locale.US, "%.2f", value)
    else -> String.format(Locale.US, "%.4f", value)
}

// 历史用户消息只承担“话题提示”，约三行手机正文就进入两行摘要态。
private const val COMPACT_USER_MIN_CHARS = 72

/** user turn 也可能携带 Task tool_result；保留普通用户气泡，并把子代理回包独立成面板。 */
@Composable
private fun UserTurnView(turn: ConversationTurn, compact: Boolean) {
    val parentBlocks = remember(turn.content) { turn.content.filter { it.subagentMeta() == null } }
    val subagentSegments = remember(turn.content) {
        splitBySubagent(turn.content).filter { it.subagent != null }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        if (parentBlocks.any { it is ContentBlock.Text && it.text.isNotBlank() }) {
            UserBubble(turn.copy(content = parentBlocks), compact = compact)
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
    val cardDefaults = LocalCardExpandDefaults.current
    val items = remember(blocks) { pairToolBlocks(blocks) }
    val renderItems = remember(items, isLastTurn, isResponding) {
        collapseActivityItems(items, isLastTurn, isResponding)
    }
    var openActivityKey by remember { mutableStateOf<String?>(null) }
    val openActivity = remember(renderItems, openActivityKey) {
        val key = openActivityKey ?: return@remember null
        renderItems
            .filterIsInstance<SegmentRenderItem.Activity>()
            .firstOrNull { it.group.key == key }
            ?.group
    }

    openActivity?.let { group ->
        ActivityDetailSheet(
            group = group,
            isLastTurn = isLastTurn,
            isResponding = isResponding,
            askSelections = askSelections,
            onAskToggle = onAskToggle,
            onAskSubmit = onAskSubmit,
            showSubagentTags = showSubagentTags,
            onDismiss = { openActivityKey = null },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        renderItems.forEach { renderItem ->
            when (renderItem) {
                is SegmentRenderItem.Item -> RenderDisplayItem(
                    item = renderItem.item,
                    itemIndex = renderItem.index,
                    itemCount = items.size,
                    isLastTurn = isLastTurn,
                    isResponding = isResponding,
                    askSelections = askSelections,
                    onAskToggle = onAskToggle,
                    onAskSubmit = onAskSubmit,
                    showSubagentTags = showSubagentTags,
                )
                is SegmentRenderItem.Activity -> {
                    if (cardDefaults.toolGroup) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            renderItem.group.items.forEachIndexed { index, item ->
                                RenderDisplayItem(
                                    item = item,
                                    itemIndex = index,
                                    itemCount = renderItem.group.items.size,
                                    isLastTurn = isLastTurn,
                                    isResponding = isResponding,
                                    askSelections = askSelections,
                                    onAskToggle = onAskToggle,
                                    onAskSubmit = onAskSubmit,
                                    showSubagentTags = showSubagentTags,
                                )
                            }
                        }
                    } else {
                        ActivitySummaryRow(
                            group = renderItem.group,
                            onClick = { openActivityKey = renderItem.group.key },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RenderDisplayItem(
    item: DisplayItem,
    itemIndex: Int,
    itemCount: Int,
    isLastTurn: Boolean,
    isResponding: Boolean,
    askSelections: Map<String, AskUserSelectionState>,
    onAskToggle: (String, Int, Int, Boolean) -> Unit,
    onAskSubmit: (String, String) -> Unit,
    showSubagentTags: Boolean,
) {
    val cardDefaults = LocalCardExpandDefaults.current
    when (item) {
        is DisplayItem.Tool -> {
            val use = item.use
            // Task/Agent 自身只表达派遣关系，面板头已经承接该语义。
            if (isHiddenDispatchTool(use)) return
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (showSubagentTags) SubagentTag(use.subagent)
                val askQuestions = when (val semantic = use.semantic) {
                    is ToolUseSemantic.QuestionRequest -> semantic.questions.map { question ->
                        AskUserQuestionData(
                            question = question.question,
                            header = question.header,
                            multiSelect = question.multiSelect,
                            options = question.options.map { option ->
                                AskUserQuestionData.Option(option.label, option.description)
                            },
                        )
                    }
                    else -> if (use.name == "AskUserQuestion") {
                        remember(use.input) { AskUserQuestionData.parse(use.input) }
                    } else {
                        emptyList()
                    }
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
                        running = item.result == null && isLastTurn && isResponding,
                        initiallyExpanded = cardDefaults.editCards,
                    )
                    use.name == "Bash" -> TerminalCard(
                        input = use.input,
                        result = item.result,
                        running = item.result == null && isLastTurn && isResponding,
                        initiallyExpanded = cardDefaults.terminal,
                    )
                    else -> ToolCard(
                        use = use,
                        result = item.result,
                        running = item.result == null && isLastTurn && isResponding,
                        initiallyExpanded = cardDefaults.shouldExpandTool(use.name),
                    )
                }
            }
        }
        is DisplayItem.Plain -> BlockView(
            item.block,
            streaming = isLastTurn && isResponding && itemIndex == itemCount - 1,
            showSubagentTag = showSubagentTags,
            initiallyExpanded = when (val block = item.block) {
                is ContentBlock.Thinking -> cardDefaults.thinking
                is ContentBlock.ToolUse -> cardDefaults.shouldExpandTool(block.name)
                is ContentBlock.ToolResult -> cardDefaults.editCards
                else -> false
            },
        )
        is DisplayItem.Exploration -> ExplorationDetailCard(
            tools = item.tools,
            running = isLastTurn && isResponding && item.tools.any { it.result == null },
            initiallyExpanded = cardDefaults.toolGroup,
        )
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

    // 只让标题保持子代理语义卡；展开内容回归普通消息流，避免长任务被一条
    // 贯穿多屏的蓝色大边框包住。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(WandMotion.tweenNormal()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            modifier = Modifier
                .fillMaxWidth()
                .glassCard(WandShapes.lg, tint = WandColors.infoSoft, rimTint = WandColors.info)
                .clickableWithoutRipple { expanded = !expanded }
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
            ExpandChevron(
                expanded = expanded,
                tint = WandColors.info,
                size = 16.dp,
                contentDescription = if (expanded) "收起子任务" else "查看子任务",
            )
        }
        if (expanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun UserBubble(turn: ConversationTurn, compact: Boolean) {
    val rawText = turn.content
        .filterIsInstance<ContentBlock.Text>()
        .joinToString("\n") { it.text }
    // 剥离「[附件已上传，请查看以下文件:…]」前缀：图片渲缩略图、其余渲文件块，正文留在气泡里。
    // 无前缀时 paths 为空、body 即原文，行为与旧版完全一致（对齐网页 renderUserText）。
    val parsed = remember(rawText) { parseUserAttachmentText(rawText) }
    val baseUrl = LocalServerBaseUrl.current
    val canCompact = compact && shouldCompactUserBody(parsed.body)
    var expanded by rememberSaveable(parsed.body, compact) { mutableStateOf(!canCompact) }
    val collapsed = canCompact && !expanded
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
                val brand = WandColors.brand
                val tonalBackground = lerp(WandColors.surface, brand, 0.13f)
                Column(
                    modifier = Modifier
                        .clip(bubbleShape)
                        .background(tonalBackground)
                        .border(0.55.dp, brand.copy(alpha = 0.24f), bubbleShape)
                        .padding(horizontal = 13.dp, vertical = 8.dp),
                ) {
                    SelectionContainer {
                        Text(
                            parsed.body,
                            fontSize = 15.sp,
                            lineHeight = 21.sp,
                            color = WandColors.textPrimary,
                            maxLines = if (collapsed) 2 else Int.MAX_VALUE,
                            overflow = if (collapsed) TextOverflow.Ellipsis else TextOverflow.Clip,
                        )
                    }
                    if (canCompact) {
                        TextButton(
                            onClick = { expanded = !expanded },
                            modifier = Modifier
                                .align(Alignment.End)
                                .semantics {
                                    stateDescription =
                                        if (collapsed) "用户消息已收起" else "用户消息已展开"
                                },
                        ) {
                            Text(
                                if (collapsed) "展开" else "收起",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = WandColors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun shouldCompactUserBody(text: String): Boolean =
    text.length > COMPACT_USER_MIN_CHARS ||
        text.lineSequence()
            .filter { it.isNotBlank() }
            .take(3)
            .count() > 2

// MARK: - 工具调用与结果的渲染层配对

private sealed class DisplayItem {
    class Plain(val block: ContentBlock) : DisplayItem()
    class Tool(val use: ContentBlock.ToolUse, val result: ContentBlock.ToolResult?) : DisplayItem()
    class Exploration(val tools: List<ExplorationToolItem>) : DisplayItem()
}

private data class ActivityGroup(
    val key: String,
    val summary: String,
    val items: List<DisplayItem>,
    val running: Boolean,
    val failed: Boolean,
)

private sealed class SegmentRenderItem {
    data class Item(val index: Int, val item: DisplayItem) : SegmentRenderItem()
    data class Activity(val group: ActivityGroup) : SegmentRenderItem()
}

/** 探索卡里的一个工具（配对后的 use + 可选 result）。 */
data class ExplorationToolItem(
    val use: ContentBlock.ToolUse,
    val result: ContentBlock.ToolResult?,
)

/** 少量调用逐张展示；第 4 个连续工具调用起才收进聚合卡。 */
private const val TOOL_CALL_GROUP_THRESHOLD = 4

/** 跨消息分组后的渲染单元（对齐 iOS MessageDisplayItem）。 */
sealed class MessageDisplayItem {
    data class Turn(val index: Int, val turn: ConversationTurn) : MessageDisplayItem()
    data class Exploration(
        val tools: List<ExplorationToolItem>,
        val lastTurnIndex: Int,
    ) : MessageDisplayItem()
}

/** 取出一个 MessageDisplayItem 归属的 turn 下标，用于钉顶定位等。 */
fun messageItemTurnIndex(item: MessageDisplayItem): Int = when (item) {
    is MessageDisplayItem.Turn -> item.index
    is MessageDisplayItem.Exploration -> item.lastTurnIndex
}

/**
 * 将相邻、且内容完全由只读探索工具组成的 assistant turn 跨消息合并。
 * 用户消息、正式文本、编辑/命令等操作都会立即终止分组（对齐 iOS groupExplorationTurns）。
 */
fun groupExplorationTurns(turns: List<ConversationTurn>): List<MessageDisplayItem> {
    val items = mutableListOf<MessageDisplayItem>()
    val pending = mutableListOf<ExplorationToolItem>()
    val pendingTurns = mutableListOf<Pair<Int, ConversationTurn>>()
    var pendingLastIndex = -1

    fun flushPending() {
        if (pending.isNotEmpty()) {
            if (pending.size >= TOOL_CALL_GROUP_THRESHOLD) {
                items.add(MessageDisplayItem.Exploration(pending.toList(), pendingLastIndex))
            } else {
                pendingTurns.forEach { (index, turn) ->
                    items.add(MessageDisplayItem.Turn(index, turn))
                }
            }
            pending.clear()
            pendingTurns.clear()
            pendingLastIndex = -1
        }
    }

    turns.forEachIndexed { index, turn ->
        val tools = explorationToolsOnly(turn)
        if (tools != null) {
            pending += tools
            pendingTurns += index to turn
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
    val operation = lower.substringAfterLast("__")
    return operation.startsWith("read") ||
        operation.startsWith("grep") ||
        operation.startsWith("glob") ||
        operation.startsWith("search") ||
        operation.startsWith("find") ||
        lower == "tool_search" ||
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

private fun collapseActivityItems(
    items: List<DisplayItem>,
    isLastTurn: Boolean,
    isResponding: Boolean,
): List<SegmentRenderItem> {
    val renderItems = mutableListOf<SegmentRenderItem>()
    val pending = mutableListOf<DisplayItem>()
    var pendingStartIndex = -1

    fun flushPending() {
        if (pending.isNotEmpty()) {
            val groupItems = pending.toList()
            if (groupItems.size >= TOOL_CALL_GROUP_THRESHOLD) {
                val groupRunning = groupItems.any { isDisplayItemRunning(it, isLastTurn, isResponding) }
                renderItems += SegmentRenderItem.Activity(
                    ActivityGroup(
                        key = activityGroupKey(groupItems, pendingStartIndex),
                        summary = activitySummary(groupItems),
                        items = groupItems,
                        running = groupRunning,
                        failed = groupItems.any(::isDisplayItemFailed),
                    )
                )
            } else {
                groupItems.forEachIndexed { offset, item ->
                    renderItems += SegmentRenderItem.Item(pendingStartIndex + offset, item)
                }
            }
            pending.clear()
            pendingStartIndex = -1
        }
    }

    items.forEachIndexed { index, item ->
        if (shouldSkipDisplayItem(item)) return@forEachIndexed
        if (isCollapsibleActivityItem(item)) {
            if (pending.isEmpty()) pendingStartIndex = index
            pending += item
        } else {
            flushPending()
            renderItems += SegmentRenderItem.Item(index, item)
        }
    }
    flushPending()
    return renderItems
}

private fun activityGroupKey(items: List<DisplayItem>, startIndex: Int): String {
    val first = items.firstOrNull()
    val firstId = first?.let(::displayItemStableKey) ?: "empty"
    return "$startIndex:$firstId"
}

private fun displayItemStableKey(item: DisplayItem): String = when (item) {
    is DisplayItem.Tool -> "tool:${item.use.id.ifBlank { item.use.name }}"
    is DisplayItem.Exploration -> "explore:${item.tools.firstOrNull()?.use?.id ?: item.tools.size}"
    is DisplayItem.Plain -> when (val block = item.block) {
        is ContentBlock.Thinking -> "thinking:${block.subagent?.taskId ?: block.thinking.take(24)}"
        is ContentBlock.ToolResult -> "result:${block.toolUseId.ifBlank { block.text.take(24) }}"
        is ContentBlock.Text -> "text:${block.text.take(24)}"
        is ContentBlock.Unknown -> "unknown:${block.type}:${block.payload.take(24)}"
        is ContentBlock.ToolUse -> "plain-tool:${block.id.ifBlank { block.name }}"
    }
}

private fun shouldSkipDisplayItem(item: DisplayItem): Boolean =
    item is DisplayItem.Tool && isHiddenDispatchTool(item.use)

private fun isHiddenDispatchTool(use: ContentBlock.ToolUse): Boolean =
    use.subagent?.taskId == use.id && use.name in setOf("Task", "Agent")

/**
 * 待办更新承载当前任务的主进度，不应和文件编辑、命令等执行活动一起折叠。
 * 同时兼容 Claude 的 TodoWrite 与 Codex 风格的 update_plan 命名。
 */
internal fun isTodoUpdateToolName(name: String): Boolean {
    val operation = name.lowercase().substringAfterLast("__")
    return operation == "update_plan" ||
        (operation.contains("todo") &&
            !operation.contains("read") &&
            !operation.contains("get") &&
            !operation.contains("list"))
}

internal fun shouldCollapseToolInActivity(name: String): Boolean =
    name != "AskUserQuestion" && !isTodoUpdateToolName(name)

private fun isCollapsibleActivityItem(item: DisplayItem): Boolean = when (item) {
    is DisplayItem.Plain -> item.block !is ContentBlock.Text && item.block !is ContentBlock.Unknown
    is DisplayItem.Exploration -> true
    is DisplayItem.Tool -> shouldCollapseToolInActivity(item.use.name)
}

private fun isDisplayItemRunning(
    item: DisplayItem,
    isLastTurn: Boolean,
    isResponding: Boolean,
): Boolean {
    if (!isLastTurn || !isResponding) return false
    return when (item) {
        is DisplayItem.Tool -> item.result == null
        is DisplayItem.Exploration -> item.tools.any { it.result == null }
        is DisplayItem.Plain -> item.block is ContentBlock.Thinking
    }
}

private fun isDisplayItemFailed(item: DisplayItem): Boolean = when (item) {
    is DisplayItem.Tool -> item.result?.isError == true
    is DisplayItem.Exploration -> item.tools.any { it.result?.isError == true }
    is DisplayItem.Plain -> (item.block as? ContentBlock.ToolResult)?.isError == true
}

private data class ActivityOutcomeCounts(
    val succeeded: Int,
    val failed: Int,
)

private fun activityOutcomeCounts(items: List<DisplayItem>): ActivityOutcomeCounts {
    var succeeded = 0
    var failed = 0

    fun count(result: ContentBlock.ToolResult?) {
        if (result == null) return
        if (result.isError) failed += 1 else succeeded += 1
    }

    items.forEach { item ->
        when (item) {
            is DisplayItem.Tool -> count(item.result)
            is DisplayItem.Exploration -> item.tools.forEach { count(it.result) }
            is DisplayItem.Plain -> count(item.block as? ContentBlock.ToolResult)
        }
    }
    return ActivityOutcomeCounts(succeeded = succeeded, failed = failed)
}

private fun activityStatusLabel(group: ActivityGroup): String {
    if (group.running) return "运行中"
    val outcome = activityOutcomeCounts(group.items)
    return when {
        outcome.succeeded > 0 && outcome.failed > 0 -> "成功 ${outcome.succeeded}，失败 ${outcome.failed}"
        outcome.failed > 0 -> "失败 ${outcome.failed}"
        outcome.succeeded > 0 -> "完成 ${outcome.succeeded}"
        else -> "已完成"
    }
}

@Composable
private fun activityStatusColor(group: ActivityGroup): Color = when {
    group.running -> WandColors.brand
    group.failed -> WandColors.danger
    else -> WandColors.success
}

private fun activityStatusIcon(group: ActivityGroup): ImageVector = when {
    group.running -> WandIcons.refresh
    group.failed -> WandIcons.statusFail
    else -> WandIcons.statusDone
}

@Composable
private fun ActivitySummaryRow(group: ActivityGroup, onClick: () -> Unit) {
    val statusLabel = activityStatusLabel(group)
    val tint = activityStatusColor(group)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(WandShapes.sm)
            .clickableWithoutRipple { onClick() }
            .semantics(mergeDescendants = true) { stateDescription = statusLabel }
            .heightIn(min = 48.dp)
            .padding(horizontal = 2.dp, vertical = 5.dp),
    ) {
        Icon(
            activityStatusIcon(group),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = tint, fontWeight = FontWeight.SemiBold)) {
                    append(statusLabel)
                }
                if (group.summary.isNotBlank()) {
                    withStyle(SpanStyle(color = WandColors.textSecondary)) {
                        append(" · ")
                        append(group.summary)
                    }
                }
            },
            fontSize = 14.sp,
            lineHeight = 19.sp,
            color = WandColors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            WandIcons.chevronRight,
            contentDescription = "查看详情",
            tint = WandColors.textMuted,
            modifier = Modifier.size(18.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityDetailSheet(
    group: ActivityGroup,
    isLastTurn: Boolean,
    isResponding: Boolean,
    askSelections: Map<String, AskUserSelectionState>,
    onAskToggle: (String, Int, Int, Boolean) -> Unit,
    onAskSubmit: (String, String) -> Unit,
    showSubagentTags: Boolean,
    onDismiss: () -> Unit,
) {
    // 关闭拖拽后直接以完整详情态打开，避免停在一个无法手势扩展的半展开状态。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val statusLabel = activityStatusLabel(group)
    val statusColor = activityStatusColor(group)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 详情里同时存在纵向长内容、横向表格和可展开卡片。禁用整张 sheet 的
        // 拖拽后，纵向手势只由内容滚动消费，横向手势只由表格消费，轻触标题
        // 才会展开卡片，避免三层手势在触摸阈值附近互相抢占。
        sheetGesturesEnabled = false,
        containerColor = WandColors.bgElevated.copy(alpha = 0.98f),
        scrimColor = Color.Black.copy(alpha = 0.46f),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        dragHandle = null,
    ) {
        NoOverscroll {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 680.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(bottom = 18.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "执行详情",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = WandColors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        statusLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(statusColor.copy(alpha = 0.12f))
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            WandIcons.close,
                            contentDescription = "关闭执行详情",
                            tint = WandColors.textSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                group.items.forEachIndexed { index, item ->
                    RenderDisplayItem(
                        item = item,
                        itemIndex = index,
                        itemCount = group.items.size,
                        isLastTurn = isLastTurn,
                        isResponding = isResponding,
                        askSelections = askSelections,
                        onAskToggle = onAskToggle,
                        onAskSubmit = onAskSubmit,
                        showSubagentTags = showSubagentTags,
                    )
                }
            }
        }
    }
}

private fun activitySummary(items: List<DisplayItem>): String {
    val tools = activityTools(items)
    if (items.size == 1 && tools.size == 1) {
        val tool = tools.first()
        val detail = toolSummary(tool.use.description, tool.use.input)
        val label = activityVerb(tool.use.name)
        return if (detail.isNotEmpty()) {
            "$label $detail"
        } else {
            label
        }
    }
    if (items.size == 1 && items.first() is DisplayItem.Plain) {
        val block = (items.first() as DisplayItem.Plain).block
        return when (block) {
            is ContentBlock.Thinking -> "思考过程"
            is ContentBlock.ToolResult -> if (block.isError) "1 条执行错误" else "1 条执行结果"
            else -> "1 项活动"
        }
    }

    val readCount = tools.count { activityKind(it.use.name) == "read" }
    val commandCount = tools.count { activityKind(it.use.name) == "command" }
    val searchCount = tools.count { activityKind(it.use.name) == "search" }
    val editCount = tools.count { activityKind(it.use.name) == "edit" }
    val webCount = tools.count { activityKind(it.use.name) == "web" }
    val todoCount = tools.count { activityKind(it.use.name) == "todo" }
    val otherToolCount = tools.size - readCount - commandCount - searchCount - editCount - webCount - todoCount
    val thinkingCount = items.count { it is DisplayItem.Plain && it.block is ContentBlock.Thinking }
    val resultCount = items.count { it is DisplayItem.Plain && it.block is ContentBlock.ToolResult }

    val parts = mutableListOf<String>()
    if (readCount > 0) parts += "浏览 $readCount 个文件"
    if (commandCount > 0) parts += "运行 $commandCount 条命令"
    if (searchCount > 0) parts += "搜索 $searchCount 次"
    if (editCount > 0) parts += "修改 $editCount 个文件"
    if (webCount > 0) parts += "访问 $webCount 个网页"
    if (todoCount > 0) parts += "更新 $todoCount 次待办"
    if (thinkingCount > 0) parts += "思考 $thinkingCount 段"
    if (resultCount > 0) parts += "生成 $resultCount 条结果"
    if (otherToolCount > 0) parts += "调用 $otherToolCount 个工具"

    return if (parts.isEmpty()) {
        "${items.size} 项活动"
    } else {
        parts.joinToString("，")
    }
}

private fun activityTools(items: List<DisplayItem>): List<ExplorationToolItem> =
    items.flatMap { item ->
        when (item) {
            is DisplayItem.Tool -> listOf(ExplorationToolItem(item.use, item.result))
            is DisplayItem.Exploration -> item.tools
            is DisplayItem.Plain -> emptyList()
        }
    }

private fun activityVerb(name: String): String = when (activityKind(name)) {
    "read" -> "浏览"
    "command" -> "运行"
    "search" -> "搜索代码"
    "edit" -> "修改"
    "web" -> "访问网页"
    "todo" -> "更新待办"
    else -> toolLabel(name)
}

private fun activityKind(name: String): String {
    val lower = name.lowercase()
    return when {
        isTodoUpdateToolName(name) -> "todo"
        lower.startsWith("read") || lower.contains("notebook") -> "read"
        lower == "bash" || lower.contains("command") || lower.contains("shell") -> "command"
        lower.contains("grep") || lower.contains("glob") ||
            lower.contains("search") || lower.contains("find") -> "search"
        lower.contains("edit") || lower.contains("write") -> "edit"
        lower.contains("web") || lower.contains("fetch") || lower.contains("http") -> "web"
        lower.contains("todo") -> "todo"
        else -> "other"
    }
}

/**
 * 连续读取、搜索、网页获取通常只是模型探索上下文，不需要逐张占满对话流。
 * 连续 4 次及以上才合并；1～3 次操作仍保留完整工具卡。
 */
private fun collapseConsecutiveExplorationTools(paired: List<DisplayItem>): List<DisplayItem> {
    val items = mutableListOf<DisplayItem>()
    val exploration = mutableListOf<ExplorationToolItem>()

    fun flushExploration() {
        if (exploration.size >= TOOL_CALL_GROUP_THRESHOLD) {
            items.add(DisplayItem.Exploration(exploration.toList()))
        } else {
            exploration.forEach { tool ->
                items.add(DisplayItem.Tool(tool.use, tool.result))
            }
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
    initiallyExpanded: Boolean = false,
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
                ThinkingBlock(block.thinking, streaming = streaming, initiallyExpanded = initiallyExpanded)
            }
        }
        is ContentBlock.ToolUse -> {
            // 落单的 ToolUse（正常路径已在 TurnView 配对，这里兜底）
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (showSubagentTag) SubagentTag(block.subagent)
                ToolCard(use = block, result = null, running = false, initiallyExpanded = initiallyExpanded)
            }
        }
        is ContentBlock.ToolResult -> {
            // 落单的 ToolResult 兜底：渲染成无头工具卡的结果区样式
            if (block.text.isNotEmpty()) {
                OrphanResultBlock(block, initiallyExpanded = initiallyExpanded)
            }
        }
        is ContentBlock.Unknown -> UnknownBlockCard(block, initiallyExpanded)
    }
}

/**
 * 新协议块的显式兼容态。原始载荷保留可检查，避免 Codex 升级后内容无声消失。
 */
@Composable
private fun UnknownBlockCard(block: ContentBlock.Unknown, initiallyExpanded: Boolean) {
    var expanded by remember(block.type, block.payload, initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(WandShapes.md)
            .background(WandColors.warningSoft)
            .border(0.55.dp, WandColors.warning.copy(alpha = 0.32f), WandShapes.md)
            .animateContentSize(WandMotion.tweenNormal()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickableWithoutRipple { expanded = !expanded }
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 11.dp),
        ) {
            Icon(
                WandIcons.genericTool,
                contentDescription = null,
                tint = WandColors.warning,
                modifier = Modifier.size(17.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "暂未适配的内容块",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WandColors.textPrimary,
                )
                Text(
                    block.type,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = WandColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("兼容显示", fontSize = 10.sp, color = WandColors.warning)
            ExpandChevron(expanded = expanded, tint = WandColors.warning, size = 16.dp)
        }
        if (expanded && block.payload.isNotBlank()) {
            HorizontalDivider(color = WandColors.warning.copy(alpha = 0.20f))
            SelectionContainer(modifier = Modifier.padding(12.dp)) {
                Text(
                    block.payload.take(8_000) + if (block.payload.length > 8_000) "\n…（已截断）" else "",
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    fontFamily = FontFamily.Monospace,
                    color = WandColors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun SubagentTag(meta: SubagentMeta?) {
    if (meta == null) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.padding(start = 2.dp, top = 1.dp, bottom = 2.dp),
    ) {
        Icon(
            WandIcons.agent,
            contentDescription = null,
            tint = WandColors.info.copy(alpha = 0.82f),
            modifier = Modifier.size(11.dp),
        )
        Text(
            meta.taskDescription ?: meta.agentType ?: "子任务",
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = WandColors.info.copy(alpha = 0.82f),
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
    val subtleInset = WandColors.textPrimary.copy(alpha = 0.045f)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        modifier = Modifier.padding(top = if (block.level <= 2) 3.dp else 1.dp),
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
                    thickness = 1.dp,
                    color = WandColors.border,
                    modifier = Modifier.padding(vertical = 3.dp),
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
        HorizontalDivider(thickness = 1.dp, color = WandColors.border.copy(alpha = 0.65f))
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
                    textAlign = TextAlign.Start,
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
    val codeColor = WandColors.brand.copy(alpha = 0.82f)
    val context = LocalContext.current
    val baseUrl = LocalServerBaseUrl.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
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

// MARK: - 工具调用卡片（含结果区，三态）

/** 工具名 → 中文标签；未识别的工具显示原名。 */
private fun toolLabel(name: String): String {
    val lower = name.lowercase()
    return when {
        isTodoUpdateToolName(name) -> "更新待办"
        lower.startsWith("codex/") -> when (lower.substringAfter('/')) {
            "spawn_agent" -> "启动子代理"
            "send_input", "send_message" -> "发送子任务消息"
            "wait", "wait_agent" -> "等待子代理"
            "close_agent" -> "关闭子代理"
            else -> "多 Agent 协作"
        }
        lower == "tool_search" || lower.contains("toolsearch") -> "查找可用工具"
        lower.contains("apply_patch") || lower.contains("patch_apply") -> "应用补丁"
        lower.contains("view_image") || lower.contains("imagegen") -> "处理图片"
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
        "__" in name -> humanizeToolName(name.substringAfterLast("__"))
        lower.startsWith("task") || lower.contains("agent") -> "子任务"
        else -> name
    }
}

private fun humanizeToolName(name: String): String = name
    .replace('-', ' ')
    .replace('_', ' ')
    .trim()
    .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

/** 工具来源单独成标签，避免把 MCP server/Codex 调度信息挤进主标题。 */
private fun toolSourceLabel(name: String): String? = when {
    name.startsWith("Codex/", ignoreCase = true) -> "Codex"
    "__" in name -> {
        val parts = name.split("__")
        val source = if (parts.firstOrNull().equals("mcp", ignoreCase = true)) {
            parts.getOrNull(1)
        } else {
            parts.firstOrNull()
        }
        source?.take(18)?.ifBlank { "MCP" } ?: "MCP"
    }
    name.startsWith("node_repl", ignoreCase = true) -> "REPL"
    else -> null
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
    initiallyExpanded: Boolean = false,
) {
    val compactTodoUpdate = isTodoUpdateToolName(use.name)
    val isError = result?.isError == true
    val declaredStatus = use.input.str("status")?.lowercase()
    val completedWithoutResult = result == null && !running && (
        declaredStatus in setOf("completed", "success", "succeeded", "done") ||
            compactTodoUpdate
        )
    val isSuccess = (result != null && !isError) || completedWithoutResult
    val hasInput = use.input.length() > 0
    val hasBody = hasInput || result != null
    val sourceLabel = remember(use.name) { toolSourceLabel(use.name) }
    var expanded by remember(use.id, initiallyExpanded) { mutableStateOf(initiallyExpanded) }
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
        else -> "无结果"
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
                .then(if (hasBody) Modifier.clickableWithoutRipple { expanded = !expanded } else Modifier)
                .then(if (compactTodoUpdate) Modifier.heightIn(min = 48.dp) else Modifier)
                .padding(
                    horizontal = 11.dp,
                    vertical = if (compactTodoUpdate) 6.dp else 10.dp,
                ),
        ) {
            ToolStatusIconBox(
                statusColor = statusColor,
                running = running,
                boxSize = if (compactTodoUpdate) 28.dp else 34.dp,
            ) {
                Icon(
                    toolIcon(use.name),
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(if (compactTodoUpdate) 15.dp else 16.dp),
                )
            }
            if (compactTodoUpdate) {
                val summary = remember(use.input) {
                    todoUpdateSummary(todoUpdateItemCount(use.input))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        toolLabel(use.name),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isError) WandColors.danger else WandColors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (summary.isNotEmpty()) {
                        Text(
                            "· $summary",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = WandColors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
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
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        sourceLabel?.let { source ->
                            Text(
                                source,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = WandColors.info,
                                maxLines = 1,
                                modifier = Modifier
                                    .clip(WandShapes.full)
                                    .background(WandColors.infoSoft)
                                    .padding(horizontal = 5.dp, vertical = 2.dp),
                            )
                        }
                    }
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
            }
            Text(
                statusText,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = statusColor,
                modifier = Modifier
                    .clip(WandShapes.full)
                    .background(statusColor.copy(alpha = 0.10f))
                    .padding(
                        horizontal = if (compactTodoUpdate) 6.dp else 7.dp,
                        vertical = if (compactTodoUpdate) 3.dp else 4.dp,
                    ),
            )
            if (hasBody) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(if (compactTodoUpdate) 22.dp else 24.dp)
                        .clip(CircleShape)
                        .background(WandColors.bgPrimary),
                ) {
                    ExpandChevron(
                        expanded = expanded,
                        tint = WandColors.textSecondary,
                        size = 14.dp,
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
        if (expanded && hasBody) {
            HorizontalDivider(
                thickness = 1.dp,
                color = WandColors.border.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
            ) {
                if (hasInput) ToolInputBody(use.input)
                result?.let { toolResult ->
                    ToolResultBody(toolResult, showSectionLabel = hasInput)
                }
            }
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
    boxSize: Dp = 34.dp,
    icon: @Composable () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(boxSize)
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
    val cardDefaults = LocalCardExpandDefaults.current
    val items = remember(tools) { tools.map { DisplayItem.Tool(it.use, it.result) } }
    val group = remember(items, running) {
        ActivityGroup(
            key = "exploration:${items.firstOrNull()?.let(::displayItemStableKey) ?: tools.size}",
            summary = activitySummary(items),
            items = items,
            running = running,
            failed = items.any(::isDisplayItemFailed),
        )
    }
    if (cardDefaults.toolGroup) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEachIndexed { index, item ->
                RenderDisplayItem(
                    item = item,
                    itemIndex = index,
                    itemCount = items.size,
                    isLastTurn = running,
                    isResponding = running,
                    askSelections = emptyMap(),
                    onAskToggle = { _, _, _, _ -> },
                    onAskSubmit = { _, _ -> },
                    showSubagentTags = true,
                )
            }
        }
    } else {
        var open by remember { mutableStateOf(false) }
        if (open) {
            ActivityDetailSheet(
                group = group,
                isLastTurn = running,
                isResponding = running,
                askSelections = emptyMap(),
                onAskToggle = { _, _, _, _ -> },
                onAskSubmit = { _, _ -> },
                showSubagentTags = true,
                onDismiss = { open = false },
            )
        }
        ActivitySummaryRow(group = group, onClick = { open = true })
    }
}

@Composable
private fun ExplorationDetailCard(
    tools: List<ExplorationToolItem>,
    running: Boolean,
    initiallyExpanded: Boolean = false,
) {
    var expanded by remember(tools, initiallyExpanded) { mutableStateOf(initiallyExpanded) }
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
            .clip(WandShapes.md)
            .background(WandColors.textPrimary.copy(alpha = 0.035f))
            .border(0.55.dp, tint.copy(alpha = 0.16f), WandShapes.md)
            .animateContentSize(WandMotion.tweenNormal()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickableWithoutRipple { expanded = !expanded }
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
            ExpandChevron(
                expanded = expanded,
                tint = WandColors.textSecondary,
                size = 14.dp,
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

private data class ToolInputEntry(val key: String, val value: String)

/** 任意 JSON 参数都保留为可读结构；字符串里的 JSON 也会再次格式化。 */
private fun structuredDisplayText(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> try { value.toString(2) } catch (_: Exception) { value.toString() }
    is JSONArray -> try { value.toString(2) } catch (_: Exception) { value.toString() }
    is String -> prettyStructuredText(value)
    else -> value.toString()
}

private fun prettyStructuredText(raw: String): String {
    val trimmed = raw.trim()
    return try {
        when {
            trimmed.startsWith("{") && trimmed.endsWith("}") -> JSONObject(trimmed).toString(2)
            trimmed.startsWith("[") && trimmed.endsWith("]") -> JSONArray(trimmed).toString(2)
            else -> raw
        }
    } catch (_: Exception) {
        raw
    }
}

@Composable
private fun ToolInputBody(input: JSONObject) {
    val entries = remember(input.toString()) {
        input.keys().asSequence().toList().sorted().take(24).map { key ->
            ToolInputEntry(key, structuredDisplayText(input.opt(key)))
        }
    }
    if (entries.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            "输入参数",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = WandColors.textMuted,
        )
        entries.forEach { entry ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    entry.key,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = WandColors.info,
                )
                SelectionContainer {
                    Text(
                        entry.value.take(4_000) + if (entry.value.length > 4_000) "\n…（字段已截断）" else "",
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                        fontFamily = FontFamily.Monospace,
                        color = WandColors.textPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(WandShapes.sm)
                            .background(WandColors.textPrimary.copy(alpha = 0.045f))
                            .padding(horizontal = 9.dp, vertical = 7.dp),
                    )
                }
            }
        }
        if (input.length() > entries.size) {
            Text(
                "另有 ${input.length() - entries.size} 个参数未展开",
                fontSize = 10.sp,
                color = WandColors.textMuted,
            )
        }
    }
}

/** 工具结果正文：结构化 JSON 格式化、移动端自动换行，并可按需加载完整内容。 */
@Composable
private fun ToolResultBody(
    result: ContentBlock.ToolResult,
    modifier: Modifier = Modifier,
    showSectionLabel: Boolean = false,
) {
    val api = LocalChatApi.current
    val sessionId = LocalChatSessionId.current
    val scope = rememberCoroutineScope()
    var fullText by remember(result.toolUseId, result.text) { mutableStateOf(result.text) }
    var truncated by remember(result.toolUseId, result.truncated) { mutableStateOf(result.truncated) }
    var loading by remember(result.toolUseId) { mutableStateOf(false) }
    var loadError by remember(result.toolUseId) { mutableStateOf<String?>(null) }
    val formatted = remember(fullText) { prettyStructuredText(fullText) }
    val displayLimit = 24_000
    val displayText = remember(formatted) {
        formatted.take(displayLimit) + if (formatted.length > displayLimit) "\n…（本页仅展示前 $displayLimit 字）" else ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        if (showSectionLabel) {
            Text(
                if (result.isError) "错误输出" else "工具输出",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (result.isError) WandColors.danger else WandColors.textMuted,
            )
        }
        if (displayText.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(WandShapes.sm)
                    .background(
                        if (result.isError) WandColors.dangerSoft
                        else WandColors.textPrimary.copy(alpha = 0.045f)
                    )
                    .padding(10.dp),
            ) {
                SelectionContainer {
                    Text(
                        displayText,
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (result.isError) WandColors.danger else WandColors.textPrimary,
                    )
                }
            }
        }
        if (truncated) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    loadError ?: "服务端为保证传输速度省略了部分内容",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = if (loadError != null) WandColors.danger else WandColors.textMuted,
                    modifier = Modifier.weight(1f),
                )
                if (api != null && sessionId.isNotBlank() && result.toolUseId.isNotBlank()) {
                    TextButton(
                        enabled = !loading,
                        onClick = {
                            loading = true
                            loadError = null
                            scope.launch {
                                try {
                                    val loaded = api.fetchToolContent(sessionId, result.toolUseId)
                                    fullText = loaded.text
                                    truncated = false
                                } catch (error: Exception) {
                                    loadError = error.message ?: "加载失败，请重试"
                                } finally {
                                    loading = false
                                }
                            }
                        },
                    ) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = WandColors.brand,
                            )
                        } else {
                            Text("加载完整内容", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        } else if (displayText.isEmpty()) {
            Text(
                if (result.isError) "工具执行失败，未返回错误详情" else "工具已完成，没有文本输出",
                fontSize = 11.sp,
                color = if (result.isError) WandColors.danger else WandColors.textMuted,
            )
        }
    }
}

/** 落单 ToolResult 的兜底渲染：可折叠的结果块。 */
@Composable
private fun OrphanResultBlock(
    result: ContentBlock.ToolResult,
    initiallyExpanded: Boolean = false,
) {
    var expanded by remember(result.toolUseId, initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    val tint = if (result.isError) WandColors.danger else WandColors.textSecondary
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.animateContentSize(WandMotion.tweenNormal()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .clickableWithoutRipple { expanded = !expanded }
                .heightIn(min = 48.dp),
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
            ExpandChevron(
                expanded = expanded,
                tint = tint,
                size = 14.dp,
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
private fun ThinkingBlock(
    text: String,
    streaming: Boolean = false,
    initiallyExpanded: Boolean = false,
) {
    var expanded by remember(text, initiallyExpanded) { mutableStateOf(initiallyExpanded) }
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
            modifier = Modifier
                .clickableWithoutRipple { expanded = !expanded }
                .heightIn(min = 48.dp),
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
            ExpandChevron(
                expanded = expanded,
                tint = WandColors.thinking.copy(alpha = 0.7f),
                size = 16.dp,
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

    val permissionGlass = WandGlass.regular.tinted(WandColors.permission, 0.22f)
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(backdrop, WandShapes.md, permissionGlass)
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
            Spacer(modifier = Modifier.weight(1f))
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
            Spacer(modifier = Modifier.weight(1f))
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

private fun todoUpdateItemCount(input: JSONObject): Int? =
    input.arrayField("todos")?.length() ?: input.arrayField("plan")?.length()

/** 待办更新的默认态只展示数量，避免把 todos JSON 撑成第二行。 */
internal fun todoUpdateSummary(itemCount: Int?): String = itemCount?.let { "$it 项" }.orEmpty()

/** 摘要优先级：常见关键参数 > 有意义的 description > 第一个参数。 */
private fun toolSummary(description: String?, input: JSONObject): String {
    val preferredKeys =
        listOf("command", "file_path", "path", "pattern", "query", "prompt", "url", "description")
    for (key in preferredKeys) {
        if (input.has(key) && !input.isNull(key)) {
            val text = summaryText(input.opt(key))
            if (text.isNotEmpty()) return text
        }
    }
    description?.takeIf {
        it.isNotBlank() && it.lowercase() !in setOf(
            "running", "searching", "completed", "in_progress", "success", "done",
        )
    }?.let { return it }
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
                .clickableWithoutRipple { expanded = !expanded }
                .heightIn(min = 48.dp)
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
            ExpandChevron(
                expanded = expanded,
                tint = WandColors.textMuted,
                size = 18.dp,
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
            .border(if (chosen) 1.dp else 0.55.dp, border.copy(alpha = if (chosen) 0.72f else 0.58f), WandShapes.sm)
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
                    1.5.dp,
                    if (chosen) tint.copy(alpha = 0.86f) else WandColors.borderStrong.copy(alpha = 0.72f),
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
    running: Boolean = false,
    initiallyExpanded: Boolean = false,
) {
    val path = input.str("file_path") ?: input.str("path") ?: ""
    val fileName = path.substringAfterLast('/').ifEmpty { "未命名文件" }
    val isWrite = toolName == "Write" || toolName == "MultiEdit"
    val oldText = input.str("old_string") ?: ""
    val newText = input.str("new_string") ?: input.str("content") ?: ""
    val unifiedDiff = input.str("unified_diff") ?: ""
    val kind = (input.str("kind") ?: if (isWrite) "add" else "update").lowercase()
    val movePath = input.str("move_path") ?: ""
    val diffUnavailableReason = input.str("diff_unavailable_reason")
    val hasDiffBody = oldText.isNotEmpty() || newText.isNotEmpty() || unifiedDiff.isNotEmpty() || movePath.isNotEmpty()

    val statusText = when {
        running -> "执行中"
        result == null -> "无结果"
        result.isError ->
            if (result.text.contains("haven't granted") || result.text.contains("permission")) {
                "等待授权"
            } else {
                "失败"
            }
        kind == "add" -> "已新增"
        kind == "delete" -> "已删除"
        movePath.isNotEmpty() -> "已移动"
        else -> "已修改"
    }
    val statusColor = when {
        running -> WandColors.brand
        result == null -> WandColors.textMuted
        result.isError -> WandColors.danger
        else -> WandColors.success
    }

    // 命令/文件详情默认不展示；运行状态只体现在摘要行，用户点开后保持展开。
    var expanded by remember(toolName, path, initiallyExpanded) { mutableStateOf(initiallyExpanded) }

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
                .clickableWithoutRipple { expanded = !expanded }
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Icon(
                WandIcons.edit,
                contentDescription = null,
                tint = statusColor,
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
            ExpandChevron(
                expanded = expanded,
                tint = WandColors.textMuted,
                size = 18.dp,
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
                if (unifiedDiff.isNotEmpty()) {
                    UnifiedDiffBlock(unifiedDiff)
                }
                if (movePath.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("移动到", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = WandColors.textMuted)
                        SelectionContainer {
                            Text(
                                movePath,
                                fontSize = 11.sp,
                                lineHeight = 17.sp,
                                fontFamily = FontFamily.Monospace,
                                color = WandColors.info,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(WandShapes.sm)
                                    .background(WandColors.infoSoft)
                                    .padding(8.dp),
                            )
                        }
                    }
                }
                if (!hasDiffBody && result != null && !result.isError) {
                    Text(
                        diffUnavailableReason
                            ?: "Codex 已返回文件变更状态，但本次事件未包含差异正文。",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = WandColors.textMuted,
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

/** Codex patch_apply_end 的 unified_diff 专用渲染：保留代码横向滚动并区分增删/区块。 */
@Composable
private fun UnifiedDiffBlock(diff: String) {
    val clipped = remember(diff) {
        if (diff.length > 16_000) diff.take(16_000) + "\n…（差异已截断）" else diff
    }
    val lines = remember(clipped) { clipped.lines() }
    val annotated = buildAnnotatedString {
        lines.forEachIndexed { index, line ->
            val color = when {
                line.startsWith("@@") -> WandColors.info
                line.startsWith("+++") || line.startsWith("---") -> WandColors.textMuted
                line.startsWith("+") -> WandColors.success
                line.startsWith("-") -> WandColors.danger
                else -> WandColors.textSecondary
            }
            withStyle(SpanStyle(color = color)) { append(line) }
            if (index < lines.lastIndex) append('\n')
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            "统一差异",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = WandColors.textMuted,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(WandShapes.sm)
                .background(WandColors.textPrimary.copy(alpha = 0.045f))
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            SelectionContainer {
                Text(
                    annotated,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    fontFamily = FontFamily.Monospace,
                )
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
    initiallyExpanded: Boolean = false,
) {
    val command = input.str("command") ?: input.str("cmd") ?: ""
    val workdir = input.str("workdir") ?: ""
    val upstreamStatus = input.str("status") ?: ""
    val exitCode = input.int("exit_code")
    val statusColor = when {
        running -> WandColors.brand
        result == null -> WandColors.textMuted
        result.isError -> WandColors.danger
        else -> WandColors.success
    }
    val statusText = when {
        running -> "运行中"
        result?.isError == true && upstreamStatus == "declined" -> "已拒绝"
        result?.isError == true && exitCode != null -> "失败 · $exitCode"
        result?.isError == true -> "失败"
        result != null -> "完成"
        else -> "待执行"
    }
    val api = LocalChatApi.current
    val sessionId = LocalChatSessionId.current
    val scope = rememberCoroutineScope()
    var outputText by remember(result?.toolUseId, result?.text) { mutableStateOf(result?.text.orEmpty()) }
    var truncated by remember(result?.toolUseId, result?.truncated) { mutableStateOf(result?.truncated == true) }
    var loadingFullOutput by remember(result?.toolUseId) { mutableStateOf(false) }
    var outputLoadError by remember(result?.toolUseId) { mutableStateOf<String?>(null) }
    var expanded by remember(command, initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(WandShapes.md)
            .background(TermBg)
            .border(0.55.dp, Color.White.copy(alpha = 0.09f), WandShapes.md)
            .animateContentSize(WandMotion.tweenNormal()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickableWithoutRipple { expanded = !expanded }
                .heightIn(min = 48.dp)
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
                "$ " + if (command.length > 80) command.take(77) + "…" else command.ifBlank { "命令" },
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = TermText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                statusText,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = statusColor,
                maxLines = 1,
                modifier = Modifier
                    .clip(WandShapes.full)
                    .background(statusColor.copy(alpha = 0.14f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
            ExpandChevron(
                expanded = expanded,
                tint = TermText.copy(alpha = 0.6f),
                size = 18.dp,
            )
        }
        if (expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            ) {
                if (workdir.isNotEmpty()) {
                    Text(
                        "目录  $workdir",
                        fontSize = 10.sp,
                        lineHeight = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TermText.copy(alpha = 0.60f),
                    )
                }
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
                if (result != null && outputText.isNotEmpty()) {
                    Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        SelectionContainer {
                            Text(
                                if (outputText.length > 12_000) {
                                    outputText.take(12_000) + "\n…（本页仅展示前 12000 字）"
                                } else {
                                    outputText
                                },
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (result.isError) TermErrorText else TermText.copy(alpha = 0.85f),
                            )
                        }
                    }
                }
                if (truncated && result != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            outputLoadError ?: "输出过长，服务端已省略部分内容",
                            fontSize = 10.sp,
                            lineHeight = 15.sp,
                            color = if (outputLoadError != null) TermErrorText else TermText.copy(alpha = 0.60f),
                            modifier = Modifier.weight(1f),
                        )
                        if (api != null && sessionId.isNotBlank() && result.toolUseId.isNotBlank()) {
                            TextButton(
                                enabled = !loadingFullOutput,
                                onClick = {
                                    loadingFullOutput = true
                                    outputLoadError = null
                                    scope.launch {
                                        try {
                                            outputText = api.fetchToolContent(sessionId, result.toolUseId).text
                                            truncated = false
                                        } catch (error: Exception) {
                                            outputLoadError = error.message ?: "加载失败，请重试"
                                        } finally {
                                            loadingFullOutput = false
                                        }
                                    }
                                },
                            ) {
                                if (loadingFullOutput) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = TermText,
                                    )
                                } else {
                                    Text("加载完整输出", fontSize = 10.sp, color = TermText)
                                }
                            }
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
    // Wand protocol v2：provider 的 TodoWrite / Task* 已由服务端归一化。
    for (i in messages.indices.reversed()) {
        if (i < startIdx) break
        for (block in messages[i].content.reversed()) {
            val semantic = (block as? ContentBlock.ToolUse)?.semantic as? ToolUseSemantic.TaskList ?: continue
            val todos = semantic.items.map { TodoEntry(it.content, it.status, it.activeForm) }
            if (todos.isEmpty()) continue
            return if (todos.all { it.status == "completed" }) emptyList() else todos
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

/**
 * 当前执行项：协议明确标记 in_progress 时优先使用；Codex 只有 pending/completed
 * 二态时，把首个 pending 推导为正在执行，避免列表与实际运行状态相互矛盾。
 */
internal fun activeTodoIndex(todos: List<TodoEntry>): Int? {
    val explicit = todos.indexOfFirst { it.status == "in_progress" }
    if (explicit >= 0) return explicit
    val inferred = todos.indexOfFirst { it.status == "pending" }
    return inferred.takeIf { it >= 0 }
}

/** 输入栏上方的悬浮任务状态：执行中 + 第 N/M 步 + 当前任务，点击展开任务列表。 */
@Composable
fun TodoProgressBar(todos: List<TodoEntry>, backdrop: GlassBackdrop? = null) {
    if (todos.isEmpty()) return
    val completed = todos.count { it.status == "completed" }
    val activeIndex = activeTodoIndex(todos)
    val currentStep = activeIndex?.plus(1) ?: minOf(completed + 1, todos.size)
    fun TodoEntry.label(): String? =
        (activeForm?.ifEmpty { null } ?: content).ifEmpty { null }
    val activeTask = activeIndex?.let { todos[it].label() }
        ?: "准备中…"
    var expanded by remember { mutableStateOf(false) }
    val motionEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    val activityAlpha = if (motionEnabled) {
        val transition = rememberInfiniteTransition(label = "todoActivityBreath")
        val alpha by transition.animateFloat(
            initialValue = 0.72f,
            targetValue = 1f,
            animationSpec = WandMotion.breath(),
            label = "todoActivityAlpha",
        )
        alpha
    } else {
        1f
    }

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
                .clickableWithoutRipple { expanded = !expanded }
                .semantics(mergeDescendants = true) {
                    stateDescription = "已完成 $completed 项，共 ${todos.size} 项；正在执行第 $currentStep 步：$activeTask"
                }
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.graphicsLayer { alpha = activityAlpha },
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(WandColors.brand, CircleShape),
                )
                Text(
                    "执行中",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WandColors.brand,
                )
            }
            Text(
                "· 第 $currentStep/${todos.size} 步",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = WandColors.brand,
            )
            Text(
                activeTask,
                fontSize = 12.sp,
                color = WandColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ExpandChevron(
                expanded = expanded,
                tint = WandColors.textMuted,
                size = 18.dp,
            )
        }
        if (expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            ) {
                todos.forEachIndexed { index, todo ->
                    val isActive = index == activeIndex
                    val rowBackground by animateColorAsState(
                        targetValue = if (isActive) WandColors.brandSoft else Color.Transparent,
                        animationSpec = WandMotion.tweenFast(),
                        label = "todoRowBackground",
                    )
                    val rowTextColor by animateColorAsState(
                        targetValue = if (isActive) WandColors.textPrimary else WandColors.textSecondary,
                        animationSpec = WandMotion.tweenFast(),
                        label = "todoRowText",
                    )
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(rowBackground)
                            .semantics(mergeDescendants = true) {
                                stateDescription = when {
                                    todo.status == "completed" -> "已完成：${todo.content}"
                                    isActive -> "进行中：${todo.content}"
                                    else -> "待处理：${todo.content}"
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.width(14.dp).height(17.dp),
                        ) {
                            when {
                                todo.status == "completed" -> Icon(
                                    WandIcons.check,
                                    contentDescription = null,
                                    tint = WandColors.success,
                                    modifier = Modifier.size(14.dp),
                                )
                                isActive -> Box(
                                    Modifier
                                        .size(7.dp)
                                        .background(WandColors.brand, CircleShape),
                                )
                                else -> Box(
                                    Modifier
                                        .size(7.dp)
                                        .border(1.dp, WandColors.textMuted, CircleShape),
                                )
                            }
                        }
                        Text(
                            todo.content,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = rowTextColor,
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
