package com.wand.app.ui.screens

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
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
import androidx.compose.ui.zIndex
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
import com.wand.app.ui.components.wandCardSurface
import com.wand.app.ui.components.WandBottomSheet
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.theme.glassSurface
import com.wand.app.ui.theme.isWandDarkTheme
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
    val nonSubagentContent = remember(turn.content) { turn.content.filter { it.subagentMeta() == null } }
    val parentBlocks = remember(turn.content, collapsed) {
        if (collapsed) emptyList() else nonSubagentContent
    }
    val preview = remember(nonSubagentContent, collapsed) {
        if (collapsed) replyPreview(nonSubagentContent) else ""
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
            if (parentBlocks.isNotEmpty()) {
                SegmentBlocks(
                    blocks = parentBlocks,
                    isLastTurn = isLastTurn,
                    isResponding = isResponding,
                    askSelections = askSelections,
                    onAskToggle = onAskToggle,
                    onAskSubmit = onAskSubmit,
                )
            }
        }
        val usageIsLive = isLastTurn && isResponding
        // 流式用量由输入栏上方的常驻状态坞承接；响应结束后仍在回复尾部保留完整用量。
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

/** 输入栏上方的紧凑状态坞：Agent 气泡独占上层，用量与回复状态保持纯文字。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SubagentActivityDock(
    backdrop: GlassBackdrop?,
    activities: List<SubagentActivity>,
    usage: TurnUsage?,
    taskTitle: String?,
    sessionRunning: Boolean,
    modifier: Modifier = Modifier,
    onExpandedChange: (Boolean) -> Unit = {},
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var selectedAgentId by rememberSaveable { mutableStateOf<String?>(null) }
    val pagerState = rememberPagerState(pageCount = { activities.size.coerceAtLeast(1) })
    val selectedIndex = activities.indexOfFirst { it.id == selectedAgentId }
        .takeIf { it >= 0 } ?: 0
    val activityIds = activities.map { it.id }

    LaunchedEffect(activityIds) {
        if (activities.isEmpty()) {
            expanded = false
            selectedAgentId = null
        } else {
            val currentIndex = activities.indexOfFirst { it.id == selectedAgentId }
            val fallbackIndex = activities.indexOfFirst { it.running }.takeIf { it >= 0 } ?: 0
            val targetIndex = currentIndex.takeIf { it >= 0 } ?: fallbackIndex
            selectedAgentId = activities[targetIndex].id
            if (pagerState.currentPage != targetIndex) pagerState.scrollToPage(targetIndex)
        }
    }
    LaunchedEffect(expanded, selectedAgentId, activityIds) {
        if (expanded && activities.isNotEmpty()) {
            withFrameNanos { }
            val target = activities.indexOfFirst { it.id == selectedAgentId }.takeIf { it >= 0 } ?: 0
            if (pagerState.currentPage != target) pagerState.animateScrollToPage(target)
        }
    }
    LaunchedEffect(pagerState, activityIds) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            activities.getOrNull(page)?.let { selectedAgentId = it.id }
        }
    }
    LaunchedEffect(expanded) { onExpandedChange(expanded) }
    BackHandler(enabled = expanded) { expanded = false }

    val selectAgent: (Int) -> Unit = { rawIndex ->
        activities.getOrNull(rawIndex)?.let { activity ->
            if (expanded && selectedAgentId == activity.id) {
                expanded = false
            } else {
                selectedAgentId = activity.id
                expanded = true
            }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        AnimatedVisibility(
            visible = expanded && activities.isNotEmpty(),
            enter = fadeIn(WandMotion.tweenFast()) +
                expandVertically(animationSpec = WandMotion.settleSpringSpec(), expandFrom = Alignment.Bottom),
            exit = fadeOut(WandMotion.tweenFast()) +
                shrinkVertically(animationSpec = WandMotion.settleSpringSpec(), shrinkTowards = Alignment.Bottom),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(
                        backdrop,
                        WandShapes.lg,
                        WandGlass.regular.tinted(WandColors.info, 0.12f),
                    )
                    .padding(top = 4.dp, bottom = 7.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth().height(30.dp),
                ) {
                    Text(
                        "${pagerState.currentPage + 1} / ${activities.size}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = WandColors.textMuted,
                    )
                    IconButton(
                        onClick = { expanded = false },
                        modifier = Modifier.align(Alignment.CenterEnd).size(30.dp),
                    ) {
                        Icon(
                            WandIcons.close,
                            contentDescription = "收起 Agent 卡片",
                            tint = WandColors.textSecondary,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    key = { page -> activities.getOrNull(page)?.id ?: "agent-$page" },
                    beyondViewportPageCount = 1,
                    pageSpacing = 10.dp,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.fillMaxWidth().height(276.dp),
                ) { page ->
                    activities.getOrNull(page)?.let { activity ->
                        SubagentActivityPage(activity)
                    }
                }
            }
        }

        if (activities.isNotEmpty()) {
            AgentBubbleRail(
                backdrop = backdrop,
                activities = activities,
                selectedIndex = selectedIndex,
                expanded = expanded,
                onAgentClick = selectAgent,
                onStackClick = {
                    val target = if (expanded) {
                        selectedIndex
                    } else {
                        activities.indexOfFirst { it.running }.takeIf { it >= 0 } ?: selectedIndex
                    }
                    selectAgent(target)
                },
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 18.dp).padding(horizontal = 3.dp),
        ) {
            UsageStatusCompact(usage, Modifier.weight(1f))
            ReplyStatusCompact(taskTitle, sessionRunning, Modifier.weight(1f))
        }
    }
}

internal data class AgentLogoVariant(val paletteIndex: Int, val facetIndex: Int)

/** task id 派生稳定伪随机外观，避免流式重组或重开卡片时 Logo 跳变。 */
internal fun agentLogoVariant(id: String): AgentLogoVariant {
    val seed = id.hashCode()
    return AgentLogoVariant(
        paletteIndex = Math.floorMod(seed, 5),
        facetIndex = Math.floorMod(seed * 31 + 17, 3),
    )
}

private fun agentBubbleTitle(activity: SubagentActivity): String =
    activity.meta.agentType?.trim().takeUnless { it.isNullOrEmpty() } ?: "Agent"

@Composable
private fun AgentBubbleRail(
    backdrop: GlassBackdrop?,
    activities: List<SubagentActivity>,
    selectedIndex: Int,
    expanded: Boolean,
    onAgentClick: (Int) -> Unit,
    onStackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 3.dp),
    ) {
        Text(
            "Agent:",
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Bold,
            color = WandColors.textPrimary,
            maxLines = 1,
        )
        SubcomposeLayout(modifier = Modifier.weight(1f)) { constraints ->
            val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
            val probes = subcompose("agent-bubble-probes") {
                activities.forEach { activity ->
                    AgentBubbleBody(
                        backdrop = null,
                        activity = activity,
                        selected = false,
                        animateLogo = false,
                    )
                }
            }.map { it.measure(looseConstraints) }
            val gap = 6.dp.roundToPx()
            val normalWidth = probes.sumOf { it.width } + gap * (probes.size - 1).coerceAtLeast(0)

            if (normalWidth <= constraints.maxWidth) {
                val bubbles = subcompose("agent-bubbles") {
                    activities.forEachIndexed { index, activity ->
                        AgentBubble(
                            backdrop = backdrop,
                            activity = activity,
                            selected = expanded && index == selectedIndex,
                            expanded = expanded && index == selectedIndex,
                            onClick = { onAgentClick(index) },
                        )
                    }
                }.map { it.measure(looseConstraints) }
                val height = bubbles.maxOfOrNull { it.height } ?: 0
                layout(constraints.maxWidth, height) {
                    var x = 0
                    bubbles.forEach { placeable ->
                        placeable.placeRelative(x, (height - placeable.height) / 2)
                        x += placeable.width + gap
                    }
                }
            } else {
                val stack = subcompose("agent-stack") {
                    StackedAgentCluster(
                        backdrop = backdrop,
                        activities = activities,
                        selectedAgentId = activities.getOrNull(selectedIndex)?.id,
                        expanded = expanded,
                        onClick = onStackClick,
                    )
                }.single().measure(looseConstraints)
                layout(constraints.maxWidth, stack.height) {
                    stack.placeRelative(0, 0)
                }
            }
        }
    }
}

@Composable
private fun AgentBubble(
    backdrop: GlassBackdrop?,
    activity: SubagentActivity,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val title = agentBubbleTitle(activity)
    val state = when {
        activity.running -> "正在运行"
        activity.failed -> "执行失败"
        else -> "已完成"
    }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClickLabel = if (expanded) "收起 $title" else "查看 $title") { onClick() }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = "$title，$state，${if (expanded) "详情已展开" else "详情已收起"}"
            }
            .padding(vertical = 3.dp),
    ) {
        AgentBubbleBody(
            backdrop = backdrop,
            activity = activity,
            selected = selected,
            animateLogo = true,
        )
    }
}

@Composable
private fun AgentBubbleBody(
    backdrop: GlassBackdrop?,
    activity: SubagentActivity,
    selected: Boolean,
    animateLogo: Boolean,
) {
    val accent = agentIdentityColor(activity)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .height(34.dp)
            .glassSurface(
                backdrop,
                CircleShape,
                WandGlass.clear.tinted(accent, if (activity.running) 0.20f else 0.08f),
            )
            .border(
                0.8.dp,
                if (selected) accent.copy(alpha = 0.72f) else Color.Transparent,
                CircleShape,
            )
            .padding(horizontal = if (activity.running) 6.dp else 5.dp),
    ) {
        GeneratedAgentLogo(activity, size = 24.dp, animate = animateLogo)
        if (activity.running) {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(
                    agentBubbleTitle(activity),
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WandColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 104.dp),
                )
                Text(
                    "正在运行",
                    fontSize = 9.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun StackedAgentCluster(
    backdrop: GlassBackdrop?,
    activities: List<SubagentActivity>,
    selectedAgentId: String?,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val visible = remember(activities) {
        val prioritized = activities.filter { it.running } + activities.filterNot { it.running }
        prioritized.take(4).let { chosen ->
            chosen.filterNot { it.running } + chosen.filter { it.running }
        }
    }
    val overlap = 18.dp
    val stackWidth = 28.dp + overlap * (visible.size - 1).coerceAtLeast(0) + 12.dp
    val runningCount = activities.count { it.running }
    val badgeText = if (activities.size > 99) "99+" else activities.size.toString()
    val runningActivity = activities.firstOrNull { it.running }
    val accent = if (runningActivity != null) agentIdentityColor(runningActivity) else WandColors.textMuted
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClickLabel = if (expanded) "收起 Agent 卡片" else "查看 Agent 卡片") { onClick() }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = "${activities.size} 个子 Agent，$runningCount 个正在运行，${if (expanded) "详情已展开" else "详情已收起"}"
            }
            .padding(vertical = 3.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(34.dp)
                .glassSurface(backdrop, CircleShape, WandGlass.clear.tinted(accent, 0.18f))
                .border(
                    0.8.dp,
                    if (expanded) accent.copy(alpha = 0.68f) else Color.Transparent,
                    CircleShape,
                )
                .padding(start = 4.dp, end = if (runningCount > 0) 8.dp else 4.dp),
        ) {
            Box(Modifier.width(stackWidth).height(30.dp)) {
                visible.forEachIndexed { index, activity ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = overlap * index)
                            .zIndex(index.toFloat())
                            .size(28.dp)
                            .glassSurface(
                                backdrop,
                                CircleShape,
                                WandGlass.clear.tinted(agentIdentityColor(activity), 0.12f),
                            )
                            .border(
                                0.8.dp,
                                if (expanded && activity.id == selectedAgentId) {
                                    agentIdentityColor(activity).copy(alpha = 0.72f)
                                } else {
                                    WandColors.border.copy(alpha = 0.58f)
                                },
                                CircleShape,
                            ),
                    ) {
                        GeneratedAgentLogo(activity, size = 22.dp, animate = true)
                    }
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .zIndex(8f)
                        .heightIn(min = 17.dp)
                        .widthIn(min = 17.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF267EDB), Color(0xFF0B9B78)),
                            ),
                        )
                        .border(0.7.dp, Color.White.copy(alpha = 0.48f), CircleShape)
                        .padding(horizontal = 4.dp),
                ) {
                    Text(
                        badgeText,
                        fontSize = 8.sp,
                        lineHeight = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                    )
                }
            }
            if (runningCount > 0) {
                Text(
                    "$runningCount 正在运行",
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun GeneratedAgentLogo(
    activity: SubagentActivity,
    size: Dp,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    val variant = remember(activity.id) { agentLogoVariant(activity.id) }
    val palette = agentGemPalette(activity, variant)
    val tint = agentIdentityColor(activity)
    val motionEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    val haloAlpha: Float
    val haloScale: Float
    if (activity.running && animate && motionEnabled) {
        val transition = rememberInfiniteTransition(label = "agentLogoBreath-${activity.id}")
        val phase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = WandMotion.breath(),
            label = "agentLogoHalo-${activity.id}",
        )
        haloAlpha = 0.14f + phase * 0.18f
        haloScale = 0.96f + phase * 0.16f
    } else {
        haloAlpha = if (activity.running) 0.24f else 0f
        haloScale = 1f
    }
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(size)) {
        if (activity.running) {
            Box(
                Modifier
                    .size(size)
                    .graphicsLayer {
                        alpha = haloAlpha
                        scaleX = haloScale
                        scaleY = haloScale
                    }
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(tint.copy(alpha = 0.70f), tint.copy(alpha = 0.16f), Color.Transparent),
                        ),
                    ),
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(size - 2.dp),
        ) {
            Canvas(modifier = Modifier.size(size - 2.dp)) {
                val canvasSize = this.size
                val width = canvasSize.width
                val height = canvasSize.height
                val center = Offset(width * 0.50f, height * 0.50f)
                val gem = Path().apply {
                    moveTo(width * 0.50f, height * 0.02f)
                    lineTo(width * 0.86f, height * 0.20f)
                    lineTo(width * 0.98f, height * 0.62f)
                    lineTo(width * 0.50f, height * 0.98f)
                    lineTo(width * 0.02f, height * 0.62f)
                    lineTo(width * 0.14f, height * 0.20f)
                    close()
                }
                val gradientStart = when (variant.facetIndex) {
                    0 -> Offset(0f, 0f)
                    1 -> Offset(width, 0f)
                    else -> Offset(width * 0.20f, 0f)
                }
                val gradientEnd = when (variant.facetIndex) {
                    0 -> Offset(width, height)
                    1 -> Offset(0f, height)
                    else -> Offset(width * 0.80f, height)
                }
                drawPath(
                    path = gem,
                    brush = Brush.linearGradient(palette, gradientStart, gradientEnd),
                )
                val lightFacet = Path().apply {
                    moveTo(width * 0.50f, height * 0.02f)
                    lineTo(center.x, center.y)
                    lineTo(width * 0.02f, height * 0.62f)
                    lineTo(width * 0.14f, height * 0.20f)
                    close()
                }
                drawPath(lightFacet, Color.White.copy(alpha = 0.19f))
                val depthFacet = Path().apply {
                    moveTo(center.x, center.y)
                    lineTo(width * 0.98f, height * 0.62f)
                    lineTo(width * 0.50f, height * 0.98f)
                    close()
                }
                drawPath(depthFacet, Color.Black.copy(alpha = 0.10f))
                when (variant.facetIndex) {
                    0 -> drawCircle(
                        color = Color.White.copy(alpha = 0.54f),
                        radius = width * 0.065f,
                        center = Offset(width * 0.31f, height * 0.27f),
                    )
                    1 -> drawLine(
                        color = Color.White.copy(alpha = 0.32f),
                        start = Offset(width * 0.25f, height * 0.22f),
                        end = Offset(width * 0.72f, height * 0.18f),
                        strokeWidth = 0.7.dp.toPx(),
                    )
                    else -> drawCircle(
                        color = Color.White.copy(alpha = 0.42f),
                        radius = width * 0.05f,
                        center = Offset(width * 0.67f, height * 0.24f),
                    )
                }
                drawPath(
                    path = gem,
                    color = Color.White.copy(alpha = if (activity.running) 0.46f else 0.30f),
                    style = Stroke(width = 0.7.dp.toPx()),
                )
            }
            Icon(
                WandIcons.agent,
                contentDescription = null,
                tint = Color.White.copy(alpha = if (activity.running || activity.failed) 0.96f else 0.82f),
                modifier = Modifier.size(size * 0.52f),
            )
        }
    }
}

private fun agentGemPalette(
    activity: SubagentActivity,
    variant: AgentLogoVariant,
): List<Color> = when {
    activity.failed -> listOf(Color(0xFFFF7891), Color(0xFFB82A56), Color(0xFF621C3C))
    !activity.running -> listOf(
        Color(0xFFC2CED8).copy(alpha = 0.86f),
        Color(0xFF7F8D9B).copy(alpha = 0.84f),
        Color(0xFF4F5B68).copy(alpha = 0.88f),
    )
    else -> when (variant.paletteIndex) {
        0 -> listOf(Color(0xFF75D8FF), Color(0xFF2878F0), Color(0xFF153D98))
        1 -> listOf(Color(0xFF72E7BB), Color(0xFF12A879), Color(0xFF075A46))
        2 -> listOf(Color(0xFF6AE3E8), Color(0xFF159BB5), Color(0xFF16577C))
        3 -> listOf(Color(0xFF6DBBFF), Color(0xFF2B67D1), Color(0xFF159A82))
        else -> listOf(Color(0xFF59E4C4), Color(0xFF16879C), Color(0xFF1D50B6))
    }
}

@Composable
private fun agentIdentityColor(activity: SubagentActivity): Color {
    if (activity.failed) return WandColors.danger
    if (!activity.running) return WandColors.textMuted
    val base = when (agentLogoVariant(activity.id).paletteIndex) {
        0 -> Color(0xFF246CCB)
        1 -> Color(0xFF087C5C)
        2 -> Color(0xFF0B7688)
        3 -> Color(0xFF2862B8)
        else -> Color(0xFF0C776C)
    }
    return if (isWandDarkTheme()) lerp(base, Color.White, 0.30f) else base
}

@Composable
private fun UsageStatusCompact(usage: TurnUsage?, modifier: Modifier = Modifier) {
    val text = remember(usage) {
        buildList {
            usage?.inputTokens?.takeIf { it > 0 }?.let { add("输入 ${formatTokenCount(it)}") }
            usage?.outputTokens?.takeIf { it > 0 }?.let { add("输出 ${formatTokenCount(it)}") }
            usage?.reasoningOutputTokens?.takeIf { it > 0 }?.let { add("推理 ${formatTokenCount(it)}") }
            usage?.totalCostUsd?.takeIf { it > 0 }?.let { add("\$${formatUsd(it)}") }
        }.joinToString(" · ").ifEmpty { "正在统计用量…" }
    }
    Text(
        text,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        fontFamily = FontFamily.Monospace,
        color = WandColors.textMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun ReplyStatusCompact(
    taskTitle: String?,
    sessionRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    val text = if (sessionRunning) {
        taskTitle?.trim().takeUnless { it.isNullOrEmpty() } ?: "正在思考…"
    } else {
        "回复完成"
    }
    Text(
        text,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        color = WandColors.textMuted,
        textAlign = TextAlign.End,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            stateDescription = text
        },
    )
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

/** user turn 只保留父对话内容；subagent 输出统一交给底部常驻 Agent 状态坞。 */
@Composable
private fun UserTurnView(turn: ConversationTurn, compact: Boolean) {
    val parentBlocks = remember(turn.content) { turn.content.filter { it.subagentMeta() == null } }
    if (parentBlocks.any { it is ContentBlock.Text && it.text.isNotBlank() }) {
        UserBubble(turn.copy(content = parentBlocks), compact = compact)
    }
}

internal data class SubagentActivity(
    val id: String,
    val meta: SubagentMeta,
    val blocks: List<ContentBlock>,
    val running: Boolean,
    val failed: Boolean,
)

/**
 * 整个会话里的 subagent 聚合为稳定卡片模型，保证移出消息正文后仍可回看。
 * 父 Task 的最终 tool_result 以 toolUseId == taskId 标记完成；内层工具结果
 * 不会误结束整个 agent。只有最后一条用户消息之后的未完成任务会显示运行态。
 */
internal fun collectSubagentActivities(
    messages: List<ConversationTurn>,
    sessionRunning: Boolean,
): List<SubagentActivity> {
    val lastHumanTurn = messages.indexOfLast { turn ->
        turn.role == "user" && turn.content.any { block ->
            block is ContentBlock.Text && block.subagent == null
        }
    }
    data class MutableActivity(
        var meta: SubagentMeta,
        val blocks: MutableList<ContentBlock> = mutableListOf(),
        var completed: Boolean = false,
        var failed: Boolean = false,
        var lastSeenTurn: Int = -1,
    )

    val byId = linkedMapOf<String, MutableActivity>()
    messages.forEachIndexed { turnIndex, turn ->
        turn.content.forEach { block ->
            val meta = block.subagentMeta() ?: return@forEach
            val id = meta.taskId?.takeIf { it.isNotBlank() } ?: return@forEach
            val activity = byId.getOrPut(id) { MutableActivity(meta) }
            activity.meta = meta
            activity.blocks += block
            activity.lastSeenTurn = turnIndex
            if (block is ContentBlock.ToolResult && block.toolUseId == id) {
                activity.completed = true
                activity.failed = block.isError
            }
        }
    }

    return byId.map { (id, activity) ->
        SubagentActivity(
            id = id,
            meta = activity.meta,
            blocks = activity.blocks.toList(),
            running = sessionRunning && activity.lastSeenTurn > lastHumanTurn && !activity.completed,
            failed = activity.failed,
        )
    }
}

private fun ContentBlock.subagentMeta(): SubagentMeta? = when (this) {
    is ContentBlock.Text -> subagent
    is ContentBlock.Thinking -> subagent
    is ContentBlock.ToolUse -> subagent
    is ContentBlock.ToolResult -> subagent
    is ContentBlock.Unknown -> null
}

/** 数量/高度不变的流式替换也要驱动角色窗口重新跟尾。 */
private fun subagentTailRefreshToken(blocks: List<ContentBlock>): Int {
    var token = 1
    fun mix(value: Any?) {
        token = 31 * token + (value?.hashCode() ?: 0)
    }
    blocks.forEach { block ->
        when (block) {
            is ContentBlock.Text -> mix(block.text)
            is ContentBlock.Thinking -> mix(block.thinking)
            is ContentBlock.ToolUse -> {
                mix(block.id)
                mix(block.name)
                mix(block.description)
                mix(block.input.toString())
            }
            is ContentBlock.ToolResult -> {
                mix(block.toolUseId)
                mix(block.text)
                mix(block.isError)
                mix(block.truncated)
            }
            is ContentBlock.Unknown -> {
                mix(block.type)
                mix(block.payload)
            }
        }
    }
    return token
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
    collapseActivities: Boolean = true,
) {
    val cardDefaults = LocalCardExpandDefaults.current
    val items = remember(blocks) { pairToolBlocks(blocks) }
    val renderItems = remember(items, isLastTurn, isResponding, collapseActivities) {
        if (collapseActivities) {
            collapseActivityItems(items, isLastTurn, isResponding)
        } else {
            items.mapIndexed { index, item -> SegmentRenderItem.Item(index, item) }
        }
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
private fun SubagentActivityPage(activity: SubagentActivity) {
    val rawTitle = activity.meta.agentType?.takeIf { it.isNotBlank() } ?: "子 Agent"
    val title = if (rawTitle.startsWith("猫猫")) rawTitle else "猫猫 $rawTitle"
    val description = activity.meta.taskDescription?.takeIf { it.isNotBlank() }
    val scrollState = rememberScrollState()
    val itemCount = remember(activity.blocks) { pairToolBlocks(activity.blocks).size }
    val refreshToken = remember(activity.blocks) { subagentTailRefreshToken(activity.blocks) }
    val statusColor = when {
        activity.failed -> WandColors.danger
        activity.running -> agentIdentityColor(activity)
        else -> WandColors.textMuted
    }

    // 内容高度变化意味着流式文本或新的工具结果已经到达。只在这种刷新发生时
    // 重回尾部；两次刷新之间，用户仍可自由向上滚动查看窗口内历史。
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.maxValue }.collect { maxValue ->
            scrollState.scrollTo(maxValue)
        }
    }
    LaunchedEffect(refreshToken) {
        withFrameNanos { }
        scrollState.scrollTo(scrollState.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .clip(WandShapes.md)
            .background(WandColors.bgPrimary.copy(alpha = 0.58f))
            .border(0.7.dp, statusColor.copy(alpha = 0.30f), WandShapes.md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 11.dp),
        ) {
            GeneratedAgentLogo(activity, size = 28.dp, animate = true)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WandColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    description ?: if (activity.running) "正在处理子任务" else "子任务输出",
                    fontSize = 11.sp,
                    color = WandColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                when {
                    activity.running -> "正在运行"
                    activity.failed -> "执行失败"
                    else -> "${itemCount.coerceAtLeast(1)} 条内容"
                },
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = statusColor,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(statusColor.copy(alpha = 0.18f), statusColor.copy(alpha = 0.05f)),
                        ),
                    )
                    .border(0.6.dp, statusColor.copy(alpha = 0.26f), CircleShape)
                    .padding(horizontal = 7.dp, vertical = 4.dp),
            )
        }
        HorizontalDivider(color = WandColors.border)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(WandColors.bgPrimary.copy(alpha = 0.45f))
                .verticalScroll(scrollState),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
            ) {
                SegmentBlocks(
                    blocks = activity.blocks,
                    isLastTurn = true,
                    isResponding = activity.running,
                    askSelections = emptyMap(),
                    onAskToggle = { _, _, _, _ -> },
                    onAskSubmit = { _, _ -> },
                    showSubagentTags = false,
                    collapseActivities = false,
                )
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
    WandBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 详情里同时存在纵向长内容、横向表格和可展开卡片。禁用整张 sheet 的
        // 拖拽后，纵向手势只由内容滚动消费，横向手势只由表格消费，轻触标题
        // 才会展开卡片，避免三层手势在触摸阈值附近互相抢占。
        gesturesEnabled = false,
        showDragHandle = false,
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
            .wandCardSurface(WandShapes.md, rimTint = if (isError) statusColor else null)
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
            WandButton(
                label = "拒绝",
                onClick = { onResolve("deny") },
                variant = WandButtonVariant.DangerText,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (escalation != null) {
                WandButton(
                    label = "本轮放行",
                    onClick = { onResolve("approve_turn") },
                    variant = WandButtonVariant.Secondary,
                )
            }
            WandButton(
                label = "允许",
                onClick = { onResolve(if (escalation != null) "approve_once" else "approve") },
                variant = WandButtonVariant.Success,
            )
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
