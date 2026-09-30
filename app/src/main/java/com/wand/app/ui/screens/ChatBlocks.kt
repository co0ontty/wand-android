package com.wand.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import com.wand.app.data.TurnAuthor
import com.wand.app.data.providerDisplayName
import com.wand.app.data.CardExpandDefaults
import com.wand.app.data.EscalationRequest
import com.wand.app.data.PermissionRequestInfo
import com.wand.app.data.SubagentMeta
import com.wand.app.data.ToolUseSemantic
import com.wand.app.data.TurnUsage
import com.wand.app.data.WandApi
import com.wand.app.data.arrayField
import com.wand.app.data.str
import com.wand.app.data.summaryText
import com.wand.app.ui.AskUserSelectionState
import com.wand.app.ui.LocalServerBaseUrl
import com.wand.app.ui.WandAsyncImage
import com.wand.app.ui.WandAsyncToolImage
import com.wand.app.ui.WandFileChip
import com.wand.app.ui.WandImage
import com.wand.app.ui.parseUserAttachmentText
import com.wand.app.ui.components.StatusDot
import com.wand.app.ui.components.EmployeeAvatar
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.clickableWithoutRipple
import com.wand.app.ui.components.toolIcon
import com.wand.app.ui.components.WandInPlaceSwap
import com.wand.app.ui.components.WandStatusIconSlot
import com.wand.app.ui.theme.GlassBackdrop
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandGlass
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.reduceMotionEnabled
import com.wand.app.ui.components.wandCardSurface
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

/** ChatScreen 注入的会话上下文，用于点开具体调用时获取完整工具参数和结果。 */
internal val LocalChatApi = compositionLocalOf<WandApi?> { null }
internal val LocalActivityFoldCompact = compositionLocalOf { false }
internal val LocalChatSessionId = compositionLocalOf { "" }

/**
 * 当前卡片所属容器的结构性 fold scope（消息 → 段 → 活动摘要逐层拼）。
 * 卡片只读它拼自己的 fold key，不再自己拼 scope；scope 里不得出现内容片段。
 */
internal val LocalCardFoldScope = compositionLocalOf { "" }

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
        WandMotion.respectMotion(!reduceMotionEnabled(), WandMotion.tweenNormal()),
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
    employeeId: String? = null,
    employeeName: String? = null,
    employeeAvatar: String? = null,
    isLastTurn: Boolean = false,
    isResponding: Boolean = false,
    activeCommandIds: Set<String>? = null,
    toolResultsById: Map<String, ContentBlock.ToolResult> = emptyMap(),
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
    /** 消息级 fold scope（列表 item key）。空串时退化结构 token，见 [cardMessageScope]。 */
    foldScope: String = "",
) {
    if (turn.notice) {
        ChatNoticeView(turn)
        return
    }
    if (turn.role == "user") {
        UserTurnView(turn, compact = compactUser)
        return
    }
    val messageScope = cardMessageScope(
        sessionId = LocalChatSessionId.current,
        role = turn.role,
        createdAt = turn.createdAt,
        foldScope = foldScope,
    )
    // 历史回复默认展开，和当前轮一起展示；折叠状态仍属于每一条 turn。
    var localCollapsed by rememberSaveable(initiallyCollapsed) {
        mutableStateOf(false)
    }
    val collapsed = currentReplyExpandedOverride?.let { !it } ?: localCollapsed
    val nonSubagentContent = remember(turn.content) { turn.content.filter { it.subagentMeta() == null } }
    val activityOnly = remember(nonSubagentContent) { isToolActivityOnly(nonSubagentContent) }
    val parentBlocks = remember(turn.content, collapsed, activityOnly) {
        if (collapsed && !activityOnly) emptyList() else nonSubagentContent
    }
    val preview = remember(nonSubagentContent, collapsed) {
        if (collapsed) replyPreview(nonSubagentContent) else ""
    }
    val copyText = remember(turn.content) { conversationTurnCopyText(turn) }
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
        if (showHeader && !activityOnly) {
            ChatMessageTime(conversationTurnClock(turn), alignEnd = false)
            // 左上角：头像 + 名字 + 折叠开关；其下沿是「收起临界线」。
            // 用户手动展开时通知上层把这条的第一行滚到顶部区域来读（不被顶出屏幕上沿）。
            AssistantReplyHeader(
                collapsed = collapsed,
                preview = preview,
                copyText = copyText,
                author = turn.author,
                employeeId = employeeId,
                employeeName = employeeName,
                employeeAvatar = employeeAvatar,
                onToggle = {
                    val next = !collapsed
                    setCollapsed(next)
                    if (!next) {
                        if (isLastTurn) onCurrentReplyExpandToBottom() else onUserExpand()
                    }
                },
            )
        }
        if (showContent && (!showHeader || !collapsed || activityOnly)) {
            if (parentBlocks.isNotEmpty()) {
                SegmentBlocks(
                    blocks = parentBlocks,
                    isLastTurn = isLastTurn,
                    isResponding = isResponding,
                    activeCommandIds = activeCommandIds,
                    toolResultsById = toolResultsById,
                    askSelections = askSelections,
                    onAskToggle = onAskToggle,
                    onAskSubmit = onAskSubmit,
                    segmentScope = messageScope,
                )
            }
        }
        val usageIsLive = isLastTurn && isResponding
        // 流式用量由输入栏上方的常驻状态坞承接；响应结束后仍在回复尾部保留完整用量。
        if (!usageIsLive && (!showHeader || !collapsed || activityOnly) && turn.usage?.hasVisibleValue == true) {
            UsageSummaryRow(turn.usage, isLive = false)
        }
    }
}

/**
 * 助手回复折叠头：收起时用弱底色和一行正文预览交代内容，展开时回到
 * 透明标题行。不再在每条回复下画贯穿整屏的分隔线，层级由留白和局部底色表达。
 *
 * 群聊（团队运行 relay 会话）里助手回复带署名：负责人（主任务）用品牌底色强调，
 * 成员（子任务）照常透明 —— 这样主任务与子任务不再长得一模一样。
 */
@Composable
private fun AssistantReplyHeader(
    collapsed: Boolean,
    preview: String,
    copyText: String,
    author: TurnAuthor?,
    employeeId: String?,
    employeeName: String?,
    employeeAvatar: String?,
    onToggle: () -> Unit,
) {
    val employeeReply = author == null && employeeId != null
    val color by animateColorAsState(
        targetValue = when {
            author?.leader == true -> WandColors.brandSoft.copy(alpha = 0.45f)
            collapsed -> WandColors.surfaceSoft.copy(alpha = 0.58f)
            else -> Color.Transparent
        },
        animationSpec = WandMotion.tweenFast(),
        label = "assistantReplyHeaderBackground",
    )
    val background = color
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
        if (employeeReply) {
            EmployeeAvatar(employeeId, employeeName, employeeAvatar, size = 26.dp)
        } else {
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
        }
        Text(
            author?.name?.takeIf { it.isNotBlank() }
                ?: employeeName?.takeIf { employeeReply && it.isNotBlank() }
                ?: if (employeeReply) "硅基员工" else "Wand",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = WandColors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when {
            author?.leader == true -> ChatAuthorBadge("负责人")
            !author?.provider.isNullOrBlank() -> Text(
                providerDisplayName(author?.provider),
                fontSize = 11.sp,
                color = WandColors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
        if (copyText.isNotBlank()) {
            MessageCopyButton(copyText = copyText)
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

/** 群聊署名里的小标签（目前只有「负责人」）。 */
@Composable
private fun ChatAuthorBadge(text: String) {
    Text(
        text,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = WandColors.brand,
        maxLines = 1,
        modifier = Modifier
            .clip(WandShapes.xs)
            .background(WandColors.brandSoft.copy(alpha = 0.5f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** 团队 relay 的系统事件：一行居中文字，长文本视觉省略、无障碍保留全文。 */
@Composable
private fun ChatNoticeView(turn: ConversationTurn) {
    val text = teamNoticeLine(turn)
    if (text.isBlank()) return
    val clock = conversationTurnClock(turn)
    val line = if (clock.isBlank()) text else "$text · $clock"
    Text(
        line,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = WandColors.textSecondary,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { contentDescription = line },
    )
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
            .padding(top = 2.dp, start = 2.dp, end = 2.dp),
    ) {
        Icon(
            WandIcons.usage,
            contentDescription = null,
            tint = WandColors.textMuted,
            modifier = Modifier.padding(top = 1.dp).size(13.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
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
 * 输入栏上方的紧凑状态坞，作为底部栏的最后一项紧贴输入框。收起态是「用量/回复状态」一行
 * 加下面的摘要行（图标 + 标题 + 计数 + 状态词）；展开后先看结论，
 * 气泡选择器移进面板，用量与回复状态只在流式期间以纯文字展示。
 */
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
    // 系统关闭动画时展开/收起退化瞬时；收起即展开的倒放，不引入第二套曲线。
    val motionEnabled = !reduceMotionEnabled()

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
            if (pagerState.currentPage != target) {
                if (motionEnabled) pagerState.animateScrollToPage(target) else pagerState.scrollToPage(target)
            }
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        AnimatedVisibility(
            visible = expanded && activities.isNotEmpty(),
            enter = if (motionEnabled) {
                fadeIn(WandMotion.tweenFast()) +
                    expandVertically(animationSpec = WandMotion.settleSpringSpec(), expandFrom = Alignment.Bottom)
            } else {
                fadeIn(snap()) + expandVertically(snap(), expandFrom = Alignment.Bottom)
            },
            exit = if (motionEnabled) {
                fadeOut(WandMotion.tweenFast()) +
                    shrinkVertically(animationSpec = WandMotion.settleSpringSpec(), shrinkTowards = Alignment.Bottom)
            } else {
                fadeOut(snap()) + shrinkVertically(snap(), shrinkTowards = Alignment.Bottom)
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(
                        backdrop,
                        WandShapes.lg,
                        WandGlass.regular.tinted(WandColors.info, 0.12f),
                    )
                    .border(1.dp, WandColors.info.copy(alpha = 0.26f), WandShapes.lg)
                    .padding(top = 4.dp, bottom = 7.dp),
            ) {
                if (activities.size > 1) {
                    // 多 Agent：气泡即选择器（身份色区分谁是谁），替代原来只有「1 / N」的占位行。
                    AgentBubbleRail(
                        backdrop = backdrop,
                        activities = activities,
                        selectedIndex = selectedIndex,
                        expanded = expanded,
                        sessionRunning = sessionRunning,
                        onAgentClick = selectAgent,
                        onStackClick = { selectAgent(selectedIndex) },
                    )
                } else {
                    Box(
                        contentAlignment = Alignment.CenterEnd,
                        modifier = Modifier.fillMaxWidth().height(30.dp),
                    ) {
                        IconButton(
                            onClick = { expanded = false },
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(
                                WandIcons.close,
                                contentDescription = "收起 Agent 卡片",
                                tint = WandColors.textSecondary,
                                modifier = Modifier.size(15.dp),
                            )
                        }
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

        // 用量行排在摘要卡上方：卡是 Column 的最后一项，才能贴着输入框而不是被自己的状态行顶开。
        if (sessionRunning) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 18.dp).padding(horizontal = 3.dp),
            ) {
                UsageStatusCompact(usage, Modifier.weight(1f))
                ReplyStatusCompact(taskTitle, Modifier.weight(1f))
            }
        }
        if (activities.isNotEmpty()) {
            SubagentSummaryRow(
                backdrop = backdrop,
                activities = activities,
                sessionRunning = sessionRunning,
                expanded = expanded,
                onClick = { expanded = !expanded },
            )
        }
    }
}

/** 收起态摘要行：图标 + 标题 + 计数 + 状态词（完成态只图标 + contentDescription）+ 副行动作。 */
@Composable
private fun SubagentSummaryRow(
    backdrop: GlassBackdrop?,
    activities: List<SubagentActivity>,
    sessionRunning: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = SubagentActivity.overallStatus(activities)
    val statusLabel = subagentStatusLabel(status, sessionRunning)
    val title = subagentCardTitle(activities)
    val topic = subagentCardTopic(activities)
    val typeChip = subagentTypeChip(activities)
    val latest = subagentPrimaryActivity(activities)?.let { subagentLastAction(it) }.orEmpty()
    val motionEnabled = !reduceMotionEnabled()
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.morph()),
        label = "subagentChevron",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(WandShapes.lg)
            .glassSurface(
                backdrop,
                WandShapes.lg,
                WandGlass.regular.tinted(subagentStatusColor(status), if (expanded) 0.18f else 0.10f),
            )
            // 玻璃底不带描边（drawRim 默认关），紧贴输入栏时会和输入栏的低对比玻璃糊成一片；
            // 补一圈状态色描边，让这张卡读起来是独立的一层。
            .border(1.dp, subagentStatusColor(status).copy(alpha = 0.32f), WandShapes.lg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = if (expanded) "收起 Agent 卡片" else "查看 Agent 卡片",
            ) { onClick() }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = "$title，$statusLabel，${if (expanded) "详情已展开" else "详情已收起"}"
            }
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        SubagentStatusIcon(status, size = 17.dp)
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    title,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WandColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (topic.isNotBlank()) {
                    Text(
                        "· $topic",
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = WandColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (typeChip.isNotBlank()) {
                    Text(
                        typeChip,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = WandColors.textMuted,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(WandColors.textMuted.copy(alpha = 0.10f))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
                // 状态词只在需要解释时出现，一屏只说一次。
                if (subagentStatusNeedsText(status)) {
                    Text(
                        statusLabel,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = subagentStatusColor(status),
                        maxLines = 1,
                    )
                }
            }
            if (latest.isNotBlank()) {
                Text(
                    latest,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    color = WandColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            WandIcons.expand,
            contentDescription = null,
            tint = WandColors.textSecondary,
            modifier = Modifier.size(16.dp).rotate(chevronRotation),
        )
    }
}

internal data class AgentLogoVariant(val paletteIndex: Int, val facetIndex: Int)

private const val AGENT_PALETTE_SIZE = 5
private const val AGENT_FACET_SIZE = 3

/** task id 派生稳定伪随机外观，避免流式重组或重开卡片时 Logo 跳变。 */
internal fun agentLogoVariant(id: String): AgentLogoVariant {
    val seed = id.hashCode()
    return AgentLogoVariant(
        paletteIndex = Math.floorMod(seed, AGENT_PALETTE_SIZE),
        facetIndex = Math.floorMod(seed * 31 + 17, AGENT_FACET_SIZE),
    )
}

/**
 * 卡内身份色去重（同 Web `agentRunAccent`）：按出现顺序先让前面的 Agent 占好色位，
 * 后来的撞色就顺延一格。种子只用 taskId —— 同批次并行 Agent 的 agentType 往往相同，
 * 用类型取色会让整卡撞成一色。
 */
internal fun dedupeAgentLogoVariants(ids: List<String>): List<AgentLogoVariant> {
    val taken = mutableListOf<Int>()
    return ids.map { id ->
        var slot = Math.floorMod(id.hashCode(), AGENT_PALETTE_SIZE)
        var shifts = 0
        while (taken.contains(slot) && shifts < AGENT_PALETTE_SIZE) {
            slot = (slot + 1) % AGENT_PALETTE_SIZE
            shifts++
        }
        taken += slot
        AgentLogoVariant(slot, Math.floorMod(id.hashCode() * 31 + 17, AGENT_FACET_SIZE))
    }
}

/**
 * 标题取名（Web 设计 A）：任务描述优先（能区分谁在干什么），其次类型，最后兜底「子 Agent」。
 * 类型兜底保留既有的「猫猫」品牌前缀；任务描述是用户自己的文字，不加前缀。
 */
internal fun agentBubbleTitle(activity: SubagentActivity): String {
    activity.meta.taskDescription?.trim().takeUnless { it.isNullOrEmpty() }?.let { return it }
    val fallback = activity.meta.agentType?.trim().takeUnless { it.isNullOrEmpty() } ?: "子 Agent"
    return if (fallback.startsWith("猫猫")) fallback else "猫猫 $fallback"
}

/** 整卡标题：单 Agent 用任务本身；多 Agent 用计数 + 主 Agent 描述，卡名不再占位。 */
internal fun subagentCardTitle(activities: List<SubagentActivity>): String {
    val single = activities.firstOrNull() ?: return "子 Agent"
    if (activities.size < 2) return agentBubbleTitle(single)
    return "${activities.size} 个子 Agent"
}

/** 多 Agent 卡的主 Agent：正在跑/转后台的优先，否则第一条（保持稳定，不随刷新跳字）。 */
internal fun subagentPrimaryActivity(activities: List<SubagentActivity>): SubagentActivity? {
    return activities.firstOrNull {
        it.status == SubagentStatus.Running || it.status == SubagentStatus.Background
    } ?: activities.firstOrNull()
}

internal fun subagentCardTopic(activities: List<SubagentActivity>): String {
    if (activities.size < 2) return ""
    val primary = subagentPrimaryActivity(activities) ?: return ""
    return primary.meta.taskDescription?.trim().takeUnless { it.isNullOrEmpty() }
        ?: primary.meta.agentType?.trim().takeUnless { it.isNullOrEmpty() }
        ?: ""
}

/** 这些类型名不携带信息（“通用 Agent”），不配占一个 chip 位；集合与 Web `AGENT_RUN_DEFAULT_TYPES` 逐字相同。 */
private val subagentDefaultTypes = setOf("general", "general-purpose", "generalist", "default")

internal fun isDefaultSubagentType(type: String?): Boolean {
    val value = type?.trim().orEmpty()
    return value.isEmpty() || value.lowercase() in subagentDefaultTypes
}

/** 类型 chip：整卡单一类型且非默认时才出现一次；多个类型混在一行等于没写。 */
internal fun subagentTypeChip(activities: List<SubagentActivity>): String {
    val types = activities.mapNotNull { it.meta.agentType?.trim() }.filter { it.isNotEmpty() }.distinct()
    if (types.size != 1) return ""
    return if (isDefaultSubagentType(types.first())) "" else types.first()
}

/**
 * 副行「最后一步动作」：从后往前找第一个能说明在干什么的块（工具步 / 非空正文 / 思考），
 * 压平成一行纯文本。整份最终报告不进副行——那是展开体的结论。
 * 顺序与 Web `agentRunLastActionText` 一致：先扫过程块，扫不到才用回执文案，再回落结果正文。
 */
internal fun subagentLastAction(activity: SubagentActivity): String {
    for (block in activity.blocks.asReversed()) {
        if (block is ContentBlock.ToolResult) continue
        if (block is ContentBlock.ToolUse) {
            val summary = toolSummary(block.description, block.input)
            val label = listOf(block.name, summary).filter { it.isNotBlank() }.joinToString(" ")
            if (label.isNotBlank()) return truncateInlinePreview(compactPreviewText(label), 150)
            continue
        }
        val text = when (block) {
            is ContentBlock.Text -> block.text
            is ContentBlock.Thinking -> block.thinking
            else -> ""
        }
        if (text.isNotBlank()) return truncateInlinePreview(compactPreviewText(text), 150)
    }
    // 子 Agent 一句话都还没说就被派去后台：这时才用回执文案。
    if (activity.receipt != null) return "已交给后台执行"
    val resultText = activity.result?.text.orEmpty()
    if (resultText.isNotBlank()) return truncateInlinePreview(compactPreviewText(resultText), 150)
    return if (activity.status == SubagentStatus.Running) "等待子 Agent 输出…" else ""
}

private fun truncateInlinePreview(value: String, max: Int): String =
    if (value.length <= max) value else value.take(max - 1).trimEnd() + "…"

@Composable
private fun AgentBubbleRail(
    backdrop: GlassBackdrop?,
    activities: List<SubagentActivity>,
    selectedIndex: Int,
    expanded: Boolean,
    sessionRunning: Boolean,
    onAgentClick: (Int) -> Unit,
    onStackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 3.dp)
            // 标签和气泡右侧的空白也要能点：不可点的死区正是「子 Agent 点不开」的来源。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = if (expanded) "收起 Agent 卡片" else "查看 Agent 卡片",
            ) { onStackClick() },
    ) {
        // 「Agent:」只在展开面板里给气泡选择器当段落标签；收起态的摘要行不再出现卡名。
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
                            sessionRunning = sessionRunning,
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
                        sessionRunning = sessionRunning,
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
    sessionRunning: Boolean,
    onClick: () -> Unit,
) {
    val title = agentBubbleTitle(activity)
    val state = subagentStatusLabel(activity.status, sessionRunning)
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
            .height(36.dp)
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
        // 气泡只带身份（颜色 + 任务名），状态词交给摘要行与页面头，不再一屏重复。
        Column {
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
        }
    }
}

@Composable
private fun StackedAgentCluster(
    backdrop: GlassBackdrop?,
    activities: List<SubagentActivity>,
    selectedAgentId: String?,
    expanded: Boolean,
    sessionRunning: Boolean,
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
                stateDescription = "${activities.size} 个子 Agent，" +
                    subagentStatusLabel(SubagentActivity.overallStatus(activities), sessionRunning) +
                    "，$runningCount 个正在运行，${if (expanded) "详情已展开" else "详情已收起"}"
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
            Box(Modifier.width(stackWidth).height(32.dp)) {
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
    // 外观来自聚合时算好的卡内去重结果，不在绘制路径上调 @Composable（remember 的键也不允许）。
    val variant = activity.logoVariant
    val palette = agentGemPalette(activity, variant)
    val tint = agentIdentityColor(activity)
    // 统一走 reduceMotion，和 StatusDot / WandMotion 呼吸灯保持同一套开关。
    val motionEnabled = !reduceMotionEnabled()
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
            // 运行中的 agent 带 60fps 呼吸动画：Path 在组合期按像素尺寸构建并缓存，
            // 避免每帧绘制都分配三个新对象造成 GC 压力（remember 不能进 draw 相位）。
            val sidePx = with(LocalDensity.current) { (size - 2.dp).toPx() }
            val paths = remember(sidePx) {
                val width = sidePx
                val height = sidePx
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
                val lightFacet = Path().apply {
                    moveTo(width * 0.50f, height * 0.02f)
                    lineTo(center.x, center.y)
                    lineTo(width * 0.02f, height * 0.62f)
                    lineTo(width * 0.14f, height * 0.20f)
                    close()
                }
                val depthFacet = Path().apply {
                    moveTo(center.x, center.y)
                    lineTo(width * 0.98f, height * 0.62f)
                    lineTo(width * 0.50f, height * 0.98f)
                    close()
                }
                Triple(gem, lightFacet, depthFacet)
            }
            Canvas(modifier = Modifier.size(size - 2.dp)) {
                val canvasSize = this.size
                val width = canvasSize.width
                val height = canvasSize.height
                val center = Offset(width * 0.50f, height * 0.50f)
                val gem = paths.first
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
                drawPath(paths.second, Color.White.copy(alpha = 0.19f))
                drawPath(paths.third, Color.Black.copy(alpha = 0.10f))
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
    // 身份色是 Agent 的属性、不是状态的属性：完成 / 后台 / 未完成也要能区分是谁（对齐 Web）。
    val base = when (activity.logoVariant.paletteIndex) {
        0 -> Color(0xFF246CCB)
        1 -> Color(0xFF087C5C)
        2 -> Color(0xFF0B7688)
        3 -> Color(0xFF2862B8)
        else -> Color(0xFF0C776C)
    }
    return if (isWandDarkTheme()) lerp(base, Color.White, 0.30f) else base
}

/** 状态色：与收起行的图标、展开头的状态点共用。 */
@Composable
private fun subagentStatusColor(status: SubagentStatus): Color = when (status) {
    SubagentStatus.Failed -> WandColors.danger
    SubagentStatus.Running -> WandColors.info
    SubagentStatus.Background -> WandColors.info
    SubagentStatus.Interrupted -> WandColors.warning
    SubagentStatus.Pending -> WandColors.textMuted
    SubagentStatus.Completed -> WandColors.success
}

@Composable
private fun SubagentStatusIcon(
    status: SubagentStatus,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val icon = when (status) {
        SubagentStatus.Failed -> WandIcons.statusFail
        SubagentStatus.Running -> WandIcons.refresh
        SubagentStatus.Background -> WandIcons.keepAlive
        SubagentStatus.Interrupted -> WandIcons.error
        SubagentStatus.Pending -> WandIcons.statusPending
        SubagentStatus.Completed -> WandIcons.statusDone
    }
    Icon(
        icon,
        contentDescription = null,
        tint = subagentStatusColor(status),
        modifier = modifier.size(size),
    )
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
    modifier: Modifier = Modifier,
) {
    val text = taskTitle?.trim().takeUnless { it.isNullOrEmpty() } ?: "正在思考…"
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

@Composable
private fun ChatMessageTime(
    clock: String,
    alignEnd: Boolean,
    trailing: (@Composable () -> Unit)? = null,
) {
    if (clock.isBlank() && trailing == null) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (clock.isNotBlank()) {
            Text(
                clock,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                color = WandColors.textSecondary,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
        trailing?.invoke()
    }
}

/** user turn 只保留父对话内容；subagent 输出统一交给底部常驻 Agent 状态坞。 */
@Composable
private fun UserTurnView(turn: ConversationTurn, compact: Boolean) {
    val parentBlocks = remember(turn.content) { turn.content.filter { it.subagentMeta() == null } }
    if (parentBlocks.any { it is ContentBlock.Text && it.text.isNotBlank() }) {
        UserBubble(turn.copy(content = parentBlocks), compact = compact)
    }
}

/** 子 Agent 状态，与 Web `agent-runs.ts` 的 `AgentRunStatus` 同一套口径与优先级。 */
internal enum class SubagentStatus {
    Failed,
    Running,
    Background,
    Interrupted,
    Pending,
    Completed,
}

/** 异步派发（pi / qoder）的「回执」：只证明任务交给了后台，不证明子 Agent 跑完并给出了结论。 */
internal data class AsyncDispatchReceipt(val runId: String, val outputPath: String)

/**
 * 派发回执判据，与 Web `agent-runs.ts` 同构的三层，顺序不可调换。形状来自 271 条真实
 * `Pi/subagent` toolResult 的全量聚类，形状表见 `docs/subagent-display.md` §6。
 *
 * 第一层 否决：状态查询与转录报告正文里同样含 `Run fan-out:` 和 `Output:`
 * （真实样本 130 + 12 条），只按「含什么关键字」判必然误判，所以先按首行前缀排除。
 */
private val asyncReceiptDenyHead = Regex(
    """^(Status target:|Transcript target:|Steering queued|Revived async subagent|Background task completed)""",
)

/** 第二层 认形状。pi 的两种异步派发回执首行都是 fan-out 预算行。 */
private val asyncReceiptPiHead = Regex("""^Run fan-out:\s*\d+/\d+ used\b""")
private val asyncReceiptPiSingle = Regex("""^Async:\s*(\S.*)$""")
private val asyncReceiptPiWorkflow = Regex("""^Async workflow\b(.*)$""")

/** qoder 的启动 ack 没有 fan-out 行，id 在 `agentId:` 且不是 uuid（`aExplore-060c…`）。 */
private val asyncReceiptQoderHead = Regex("""^Async agent launched successfully\.""")
private val asyncReceiptQoderAgentId = Regex("""^agentId:\s*([^\s(]+)""", RegexOption.MULTILINE)

/** 取行内最后一个方括号段：类型名自己带 `[general]` 时不能只取第一个。 */
private val asyncReceiptBracketId = Regex("""\[([0-9a-fA-F-]{8,64})]""")

/**
 * `Output:` / `output_file:` **只用于字段提取，绝不作为判据条件**：真实 pi 派发回执根本没有
 * `Output:` 行（只有 `details.asyncDir` 目录），当必要条件会让真实语料里的 pi 回执一条不命中。
 */
private val asyncReceiptOutputField = Regex("""^Output:\s*(\S+)""", RegexOption.MULTILINE)
private val asyncReceiptQoderOutputField = Regex("""^output_file:\s*(\S+)""", RegexOption.MULTILINE)

/**
 * 第三层 兜底。provider 文案会变，白名单必然漏新形状；漏的时候宁可判中性「后台运行中」
 * 也不能标绿「最终结论」——后者是用户认定过的 bug，前者只是不够精确。正文一律照常渲染。
 */
private val asyncReceiptBackgroundPhrases = listOf(
    "detached and running in the background",
    "is working in the background",
    "will be notified automatically when it completes",
)

private fun lastAsyncReceiptRunId(line: String): String {
    var found = ""
    for (match in asyncReceiptBracketId.findAll(line)) found = match.groupValues[1]
    return found
}

internal fun parseAsyncDispatchReceipt(text: String?): AsyncDispatchReceipt? {
    val value = text?.trim().orEmpty()
    if (value.isEmpty()) return null
    if (asyncReceiptDenyHead.containsMatchIn(value)) return null

    val lines = value.split("\n")
    if (asyncReceiptPiHead.containsMatchIn(lines[0])) {
        // 派发行通常在预算行之后，但中间可能插一整段 Preflight 计划表：真实语料里
        // 57 条紧跟第二行、9 条在第 6 行、最远一条在第 14 行（10 lanes 的表）。
        // 所以扫预算行之后的前 24 行，而不是硬写「第二行」——只认行首，正文里提到不算。
        for (i in 1 until minOf(lines.size, 25)) {
            val line = lines[i].trim()
            val tail = asyncReceiptPiSingle.find(line)?.groupValues?.getOrNull(1)
                ?: asyncReceiptPiWorkflow.find(line)?.groupValues?.getOrNull(1)
                ?: continue
            return AsyncDispatchReceipt(
                runId = lastAsyncReceiptRunId(tail),
                outputPath = asyncReceiptOutputField.find(value)?.groupValues?.getOrNull(1).orEmpty(),
            )
        }
    }
    if (asyncReceiptQoderHead.containsMatchIn(lines[0])) {
        return AsyncDispatchReceipt(
            runId = asyncReceiptQoderAgentId.find(value)?.groupValues?.getOrNull(1).orEmpty(),
            outputPath = asyncReceiptQoderOutputField.find(value)?.groupValues?.getOrNull(1).orEmpty(),
        )
    }
    if (asyncReceiptBackgroundPhrases.any { value.contains(it, ignoreCase = true) }) {
        // 认不出形状，但正文明确说它已进后台：字段留空，由渲染侧回落到正文。
        return AsyncDispatchReceipt(runId = "", outputPath = "")
    }
    return null
}

internal data class SubagentActivity(
    val id: String,
    val meta: SubagentMeta,
    val blocks: List<ContentBlock>,
    val running: Boolean,
    val failed: Boolean,
    val interrupted: Boolean,
    /** 无结果且不在最新一轮：多半是分页窗口截断了父级 tool_result，不能报「已中断」。 */
    val pending: Boolean = false,
    val result: ContentBlock.ToolResult? = null,
    val receipt: AsyncDispatchReceipt? = null,
    /** 卡内去重后的外观，避免同批次并行 Agent 撞成同一个颜色。 */
    val logoVariant: AgentLogoVariant = agentLogoVariant(id),
    /** 会话当时在不在跑：回执要说「后台运行中」还是「后台已结束」，面板里也要能单独判定。 */
    val sessionRunning: Boolean = false,
) {
    val background: Boolean get() = receipt != null

    val status: SubagentStatus
        get() = when {
            failed -> SubagentStatus.Failed
            running -> SubagentStatus.Running
            receipt != null -> SubagentStatus.Background
            interrupted -> SubagentStatus.Interrupted
            pending -> SubagentStatus.Pending
            else -> SubagentStatus.Completed
        }

    companion object {
        /** 整卡状态：failed > running > background > interrupted > pending > completed。 */
        fun overallStatus(activities: List<SubagentActivity>): SubagentStatus {
            val statuses = activities.map { it.status }
            return listOf(
                SubagentStatus.Failed,
                SubagentStatus.Running,
                SubagentStatus.Background,
                SubagentStatus.Interrupted,
                SubagentStatus.Pending,
            ).firstOrNull { statuses.contains(it) } ?: SubagentStatus.Completed
        }
    }
}

internal fun subagentStatusLabel(status: SubagentStatus, sessionRunning: Boolean): String = when (status) {
    SubagentStatus.Failed -> "执行失败"
    SubagentStatus.Running -> "正在运行"
    // 「后台已结束」不是第七种状态：回执证明不了后台跑完没有，所以不给绿色「已完成」。
    SubagentStatus.Background -> if (sessionRunning) "后台运行中" else "后台已结束"
    SubagentStatus.Interrupted -> "已中断"
    SubagentStatus.Pending -> "未完成"
    SubagentStatus.Completed -> "已完成"
}

/** 完成态由图标 + contentDescription 表达，不再重复状态词（对齐 Web `agentRunStatusNeedsText`）。 */
internal fun subagentStatusNeedsText(status: SubagentStatus): Boolean = status != SubagentStatus.Completed

/**
 * 整个会话里的 subagent 聚合为稳定卡片模型，保证移出消息正文后仍可回看。
 * 父 Task 的最终 tool_result 以 toolUseId == taskId 标记完成；内层工具结果
 * 不会误结束整个 agent。只有最后一条**真人**用户消息之后的未完成任务才参与
 * 运行/中断判定，更早的无结果任务落 `pending`（与 Web `agentRunInLatestWindow` 同一语义）。
 */
internal fun collectSubagentActivities(
    messages: List<ConversationTurn>,
    sessionRunning: Boolean,
): List<SubagentActivity> {
    val lastHumanTurn = messages.indexOfLast { turn ->
        turn.role == "user" && turn.content.any { block ->
            // 空 text 不算真人轮；子 Agent 轨迹（含回传的结果消息）也不算，
            // 否则窗口会被 Agent 自己的输出越推越后，历史 run 永远显示「运行中」。
            block is ContentBlock.Text && block.subagent == null && block.text.isNotBlank()
        }
    }
    data class MutableActivity(
        var meta: SubagentMeta,
        val blocks: MutableList<ContentBlock> = mutableListOf(),
        var completed: Boolean = false,
        var failed: Boolean = false,
        var result: ContentBlock.ToolResult? = null,
        var receipt: AsyncDispatchReceipt? = null,
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
                activity.result = block
                // 失败的结果不是「已交给后台」，判据不参与，保持「失败原因」展示。
                activity.receipt =
                    if (block.isError) null else parseAsyncDispatchReceipt(block.text)
            }
        }
    }

    val collected = byId.map { (id, activity) ->
        val inLatestWindow = activity.lastSeenTurn > lastHumanTurn
        SubagentActivity(
            id = id,
            meta = activity.meta,
            blocks = activity.blocks.toList(),
            running = sessionRunning && inLatestWindow && !activity.completed,
            failed = activity.failed,
            interrupted = !sessionRunning && inLatestWindow && !activity.completed,
            pending = !activity.completed && !inLatestWindow,
            result = activity.result,
            receipt = activity.receipt,
            sessionRunning = sessionRunning,
        )
    }
    val variants = dedupeAgentLogoVariants(collected.map { it.id })
    return collected.mapIndexed { index, activity -> activity.copy(logoVariant = variants[index]) }
}

/**
 * 状态坞只承接「还要看一眼」的 run：正在运行、被中断、失败的才常驻底部。
 * 已完成和分页截断的历史 run 已经有自己的消息流卡片，再常驻就是一堆绿色的「已完成」；
 * 后台回执在会话停止后也只剩「后台已结束」这个既成事实，同样还给消息流。
 */
internal fun subagentDockActivities(activities: List<SubagentActivity>): List<SubagentActivity> =
    activities.filter { activity ->
        when (activity.status) {
            SubagentStatus.Completed, SubagentStatus.Pending -> false
            SubagentStatus.Background -> activity.sessionRunning
            else -> true
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

internal fun shouldExpandChatCard(isLastTurn: Boolean, configured: Boolean): Boolean {
    // 当前轮也只按用户配置展开；步骤标题始终由卡片头部显示。
    return configured
}

// MARK: - 折叠卡状态派生（用户显式收放 ⟷ 派生默认值）

/**
 * 「用户显式收放过」的三态编码。`rememberSaveable` 存不了 null，所以用 0/1/2。
 */
internal const val FOLD_OVERRIDE_NONE = 0
internal const val FOLD_OVERRIDE_EXPANDED = 1
internal const val FOLD_OVERRIDE_COLLAPSED = 2

/**
 * 卡片是否展开：用户显式收放永远优先，没手动操作过才用派生默认值。
 * 对齐 Web `resolveCardExpanded(persisted, …)`。
 */
internal fun resolveCardExpanded(userOverride: Boolean?, derivedDefault: Boolean): Boolean =
    userOverride ?: derivedDefault

internal fun foldOverrideFromCode(code: Int): Boolean? = when (code) {
    FOLD_OVERRIDE_EXPANDED -> true
    FOLD_OVERRIDE_COLLAPSED -> false
    else -> null
}

/**
 * 展开态 = override ?: 派生默认。
 * 派生默认值必须以参数形式在**每次读取时**传入，不能存进 remember 初始化块：
 * 那样会捕获首次组合的旧值，等于把「历史卡不自动收起」的 bug 原样留下。
 */
internal fun foldExpanded(overrideCode: Int, derivedDefault: Boolean): Boolean =
    resolveCardExpanded(foldOverrideFromCode(overrideCode), derivedDefault)

/** 用户点击后的新 override 编码：按当前实际展开态取反。 */
internal fun foldToggleCode(overrideCode: Int, derivedDefault: Boolean): Int =
    if (foldExpanded(overrideCode, derivedDefault)) FOLD_OVERRIDE_COLLAPSED else FOLD_OVERRIDE_EXPANDED

/** fold key 只描述「这张卡是谁」：会话 + 卡片身份，不含内容与派生默认值。 */
internal fun cardFoldKey(sessionId: String, cardId: String): String = "$sessionId|$cardId"

/**
 * v2：cardId = 容器 scope + 容器内位置。
 *
 * scope **不得**出现任何内容片段（`take(n)` / thinking / text / payload / command / path）：
 * 内容会在流式追加期间每帧变化，key 一漂移 `rememberSaveable` 就会重建，
 * 用户手动收放的 override 被清掉（设计规格 §8.5 反例 12）。
 */
internal fun cardFoldId(scope: String, position: String): String = "$scope#$position"

/**
 * 卡片在容器内的位置 id：有稳定 id（`use.id` / `toolUseId`）时优先用 id，缺失才回落到
 * **容器原始（未过滤）列表里的绝对下标**。两者都不含内容。
 */
internal fun cardPositionId(kind: String, stableId: String, itemIndex: Int): String =
    if (stableId.isNotBlank()) "$kind:$stableId" else "$kind#$itemIndex"

/** 未知块的位置 id：类型 + 下标（同类型的两个块不会撞 key）。 */
internal fun unknownCardPositionId(type: String, itemIndex: Int): String =
    "unknown:$type#$itemIndex"

/**
 * 消息级 fold scope。优先用调用方下传的列表 item key（设计规格 v2 §8.2 的 scope 链）；
 * 调用方没传时退化成「角色 + 服务端 createdAt」这种结构 token（不含内容）。
 * 同一 LazyColumn item 的 `rememberSaveable` 已按 item key 分命名空间，所以这里只影响 key 字面唯一性。
 */
internal fun cardMessageScope(
    sessionId: String,
    role: String,
    createdAt: String?,
    foldScope: String,
): String = foldScope.ifBlank { "$sessionId#msg:$role:${createdAt.orEmpty()}" }

/** rememberSaveable 里只存三态编码；派生默认值每次组合从参数重算。 */
@Composable
internal fun rememberFoldOverrideCode(foldKey: String): MutableState<Int> =
    rememberSaveable(foldKey) { mutableStateOf(FOLD_OVERRIDE_NONE) }

/** 参数区是否有可显示的条目（`input = {}` 时为 false，展开区不该只有一条分隔线）。 */
internal fun toolInputHasEntries(input: JSONObject): Boolean = input.keys().hasNext()

/** 工具卡是否有可展开的内容（`hasBody == false` 时不画箭头、不可点）。 */
internal fun toolCardHasBody(input: JSONObject, result: ContentBlock.ToolResult?): Boolean =
    toolInputHasEntries(input) || result != null

@Composable
private fun SegmentBlocks(
    blocks: List<ContentBlock>,
    isLastTurn: Boolean,
    isResponding: Boolean,
    activeCommandIds: Set<String>? = null,
    toolResultsById: Map<String, ContentBlock.ToolResult> = emptyMap(),
    askSelections: Map<String, AskUserSelectionState>,
    onAskToggle: (String, Int, Int, Boolean) -> Unit,
    onAskSubmit: (String, String) -> Unit,
    /** 本段所属容器的 fold scope（消息级 item key；子代理页传 `sub-<id>`）。 */
    segmentScope: String,
    showSubagentTags: Boolean = true,
) {
    val items = remember(blocks, toolResultsById) {
        pairToolBlocks(blocks, toolResultsById)
    }
    val renderItems = remember(items, isLastTurn, isResponding, activeCommandIds) {
        collapseActivityItems(items, isLastTurn, isResponding, activeCommandIds)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        renderItems.forEachIndexed { renderIndex, renderItem ->
            // 段 scope 只拼结构性的下标：`renderItems` 只追加不重排 ⇒ 稳定。
            val itemScope = "$segmentScope/seg$renderIndex"
            when (renderItem) {
                is SegmentRenderItem.Item -> CompositionLocalProvider(
                    LocalCardFoldScope provides itemScope,
                ) {
                    RenderDisplayItem(
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
                }
                is SegmentRenderItem.Activity -> key(renderItem.group.key) {
                    val activityScope = "$segmentScope/activity:${renderItem.group.key}"
                    CompositionLocalProvider(
                        LocalCardFoldScope provides activityScope,
                    ) {
                        ToolActivitySummary(
                            group = renderItem.group,
                            scope = activityScope,
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
    val sessionId = LocalChatSessionId.current
    // 结构化 fold scope：容器逐层下传，卡片只拼「scope + 位置」。
    val scope = LocalCardFoldScope.current
    val expandDefault = { configured: Boolean ->
        shouldExpandChatCard(isLastTurn, configured)
    }
    when (item) {
        is DisplayItem.Tool -> {
            val use = item.use
            // Task/Agent 自身只表达派遣关系，面板头已经承接该语义。
            if (isHiddenDispatchTool(use)) return
            // 稳定 id 优先，缺 id 回落到容器内绝对下标（两者都不含内容）。
            val toolFoldKey = cardFoldKey(
                sessionId,
                cardFoldId(scope, cardPositionId("tool", use.id, itemIndex)),
            )
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
                        questions = askQuestions,
                        result = item.result,
                        selection = askSelections[use.id] ?: AskUserSelectionState(),
                        expandAll = false,
                        foldKey = cardFoldKey(
                            sessionId,
                            cardFoldId(scope, cardPositionId("ask", use.id, itemIndex)),
                        ),
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
                        expandDefault = expandDefault(cardDefaults.editCards),
                        foldKey = toolFoldKey,
                    )
                    use.name == "Bash" -> TerminalCard(
                        input = use.input,
                        result = item.result,
                        running = item.result == null && isLastTurn && isResponding,
                        expandDefault = expandDefault(cardDefaults.terminal),
                        foldKey = toolFoldKey,
                    )
                    else -> ToolCard(
                        use = use,
                        result = item.result,
                        running = item.result == null && isLastTurn && isResponding,
                        expandDefault = expandDefault(cardDefaults.shouldExpandTool(use.name)),
                        foldKey = toolFoldKey,
                    )
                }
            }
        }
        is DisplayItem.Plain -> BlockView(
            item.block,
            streaming = isLastTurn && isResponding && itemIndex == itemCount - 1,
            showSubagentTag = showSubagentTags,
            expandDefault = when (val block = item.block) {
                is ContentBlock.Thinking -> expandDefault(cardDefaults.thinking)
                is ContentBlock.ToolUse -> expandDefault(cardDefaults.shouldExpandTool(block.name))
                is ContentBlock.ToolResult -> expandDefault(cardDefaults.editCards)
                else -> false
            },
            foldKey = when (val block = item.block) {
                // 勘误 2：思考块只用「容器 scope + 绝对下标」，不得再用 `thinking.take(24)`；
                // 否则流式追加期间 key 逐 delta 变化，用户手动收放会被反复清掉。
                is ContentBlock.Thinking -> cardFoldKey(
                    sessionId,
                    cardFoldId(scope, cardPositionId("thinking", "", itemIndex)),
                )
                is ContentBlock.ToolUse -> cardFoldKey(
                    sessionId,
                    cardFoldId(scope, cardPositionId("tool", block.id, itemIndex)),
                )
                is ContentBlock.ToolResult -> cardFoldKey(
                    sessionId,
                    cardFoldId(scope, cardPositionId("result", block.toolUseId, itemIndex)),
                )
                is ContentBlock.Unknown -> cardFoldKey(
                    sessionId,
                    cardFoldId(scope, unknownCardPositionId(block.type, itemIndex)),
                )
                is ContentBlock.Text -> cardFoldKey(
                    sessionId,
                    cardFoldId(scope, cardPositionId("text", "", itemIndex)),
                )
            },
        )
    }
}

@Composable
private fun SubagentActivityPage(activity: SubagentActivity) {
    val description = activity.meta.taskDescription?.trim().takeUnless { it.isNullOrEmpty() }
    val agentType = activity.meta.agentType?.trim().takeUnless { it.isNullOrEmpty() }
    // 标题必须是任务本身；只有拿不到描述时才退回类型（保留既有的「猫猫」品牌前缀）。
    val title = description ?: agentBubbleTitle(activity)
    val typeChip = if (description != null && !isDefaultSubagentType(agentType)) agentType.orEmpty() else ""
    val status = activity.status
    val statusLabel = subagentStatusLabel(status, activity.sessionRunning)
    val statusColor = subagentStatusColor(status)
    val scrollState = rememberScrollState()
    val refreshToken = remember(activity.blocks, activity.result) {
        subagentTailRefreshToken(activity.blocks)
    }
    val stepCount = remember(activity.blocks) { subagentStepCount(activity.blocks) }
    val motionEnabled = !reduceMotionEnabled()
    // 运行中默认摊开过程（那时过程就是正文）；有结论时默认只看结论。
    var processExpanded by remember(activity.id) {
        mutableStateOf(status == SubagentStatus.Running)
    }
    val chevronRotation by animateFloatAsState(
        targetValue = if (processExpanded) 90f else 0f,
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.morph()),
        label = "subagentProcessChevron",
    )

    // 摊开过程时内容高度变化意味着新的流式文本或工具结果到达，只在这种刷新发生时
    // 重回尾部；收起成「结论 + 一行过程」时正文在顶部，不再抢滚动位置。
    // 键仍是 refreshToken（契约锚点）：processExpanded 在效果体内实时读，行为不变。
    LaunchedEffect(scrollState, processExpanded) {
        snapshotFlow { scrollState.maxValue }.collect { maxValue ->
            if (processExpanded) scrollState.scrollTo(maxValue)
        }
    }
    LaunchedEffect(refreshToken) {
        withFrameNanos { }
        if (processExpanded) scrollState.scrollTo(scrollState.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .clip(WandShapes.md)
            .background(WandColors.bgPrimary.copy(alpha = 0.58f)),
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
                // 类型只在能区分时出现一次；状态词交给右边的点 + contentDescription。
                if (typeChip.isNotBlank()) {
                    Text(
                        typeChip,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = WandColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(statusColor)
                    .semantics { contentDescription = statusLabel },
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = WandColors.border)
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(WandColors.bgPrimary.copy(alpha = 0.45f))
                .verticalScroll(scrollState)
                .padding(10.dp),
        ) {
            // 结论在前、过程在后（原来是过程铺完才给结论，结论被挤到几十屏之下）。
            when {
                activity.receipt != null -> SubagentReceiptSection(
                    receipt = activity.receipt,
                    body = activity.result?.text.orEmpty(),
                )
                activity.result != null -> SubagentConclusionSection(
                    failed = activity.failed,
                    text = activity.result.text,
                )
                else -> Text(
                    when (status) {
                        SubagentStatus.Running -> "等待子 Agent 输出…"
                        SubagentStatus.Interrupted -> "会话已停止，未收到最终结果。"
                        SubagentStatus.Pending -> "更早轮次的任务，结果未随分页窗口返回。"
                        else -> "未收到子 Agent 输出。"
                    },
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = WandColors.textMuted,
                )
            }
            if (stepCount > 0) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(WandShapes.sm)
                            .background(WandColors.surface.copy(alpha = 0.7f))
                            .clickable { processExpanded = !processExpanded }
                            .semantics { stateDescription = if (processExpanded) "过程已展开" else "过程已收起" }
                            .padding(horizontal = 9.dp, vertical = 7.dp),
                    ) {
                        Icon(
                            WandIcons.chevronRight,
                            contentDescription = null,
                            tint = WandColors.textSecondary,
                            modifier = Modifier.size(14.dp).rotate(chevronRotation),
                        )
                        Text(
                            "过程",
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WandColors.textPrimary,
                        )
                        Text(
                            "$stepCount 步",
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = WandColors.textMuted,
                        )
                    }
                    AnimatedVisibility(
                        visible = processExpanded,
                        enter = if (motionEnabled) {
                            fadeIn(WandMotion.tweenFast()) +
                                expandVertically(animationSpec = WandMotion.settleSpringSpec())
                        } else {
                            fadeIn(snap()) + expandVertically(snap())
                        },
                        exit = if (motionEnabled) {
                            fadeOut(WandMotion.tweenExit()) +
                                shrinkVertically(animationSpec = WandMotion.settleSpringSpec())
                        } else {
                            fadeOut(snap()) + shrinkVertically(snap())
                        },
                    ) {
                        Box(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                            SegmentBlocks(
                                blocks = activity.blocks,
                                isLastTurn = true,
                                isResponding = activity.running,
                                askSelections = emptyMap(),
                                onAskToggle = { _, _, _, _ -> },
                                onAskSubmit = { _, _ -> },
                                segmentScope = "sub-${activity.id}",
                                showSubagentTags = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 结论体用原文渲染 markdown：压平会让标题/列表/代码块全塌成一段。 */
@Composable
private fun SubagentConclusionSection(failed: Boolean, text: String) {
    val accent = if (failed) WandColors.danger else WandColors.success
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(WandShapes.md)
            .background(accent.copy(alpha = 0.08f))
            .border(0.6.dp, accent.copy(alpha = 0.22f), WandShapes.md)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SubagentStatusIcon(if (failed) SubagentStatus.Failed else SubagentStatus.Completed, size = 13.dp)
            Text(
                if (failed) "失败原因" else "最终结论",
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
        }
        if (text.isBlank()) {
            Text(
                "子 Agent 没有返回正文。",
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = WandColors.textMuted,
            )
        } else {
            SelectionContainer {
                MarkdownText(text)
            }
        }
    }
}

/**
 * 异步派发的回执不是结论：中性说明 + 后台任务 id + 输出文件路径，
 * 不标绿（否则用户会当成跑完）。两个字段都提不到时回落到正文，不出空卡（与 Web 同一路）。
 */
@Composable
private fun SubagentReceiptSection(
    receipt: AsyncDispatchReceipt,
    body: String,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(WandShapes.md)
            .background(WandColors.surface.copy(alpha = 0.8f))
            .border(0.6.dp, WandColors.border, WandShapes.md)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        // 状态词只属于摘要行：展开回执时「后台运行中」在摘要行和这里各说一次，
        // 等于同一屏说两遍（与 Web 同一条理由，两端同改）。头部留图标 + 说明文字。
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SubagentStatusIcon(SubagentStatus.Background, size = 13.dp)
            Text(
                "这只是派发回执，不是最终结论；子 Agent 仍在后台执行，结果会另行送达。",
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = WandColors.textSecondary,
            )
        }
        if (receipt.runId.isNotBlank()) {
            SubagentReceiptRow("后台任务", receipt.runId)
        }
        if (receipt.outputPath.isNotBlank()) {
            SubagentReceiptRow("输出文件", receipt.outputPath)
        }
        if (receipt.runId.isBlank() && receipt.outputPath.isBlank() && body.isNotBlank()) {
            // 兜底层只证明「在后台」，什么字段都没提到：正文照常渲染，别留一张空卡。
            SelectionContainer {
                MarkdownText(body)
            }
        }
    }
}

@Composable
private fun SubagentReceiptRow(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            label,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            color = WandColors.textMuted,
            maxLines = 1,
        )
        Text(
            value,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = WandColors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 「过程 · N 步」的 N 是渲染出来的步骤数：结果块、空正文/空思考、派遣块不占位。 */
internal fun subagentStepCount(blocks: List<ContentBlock>): Int =
    pairToolBlocks(blocks).count { item ->
        when (item) {
            is DisplayItem.Tool -> !isHiddenDispatchTool(item.use)
            is DisplayItem.Plain -> when (val block = item.block) {
                is ContentBlock.Text -> block.text.isNotBlank()
                is ContentBlock.Thinking -> block.thinking.isNotBlank()
                is ContentBlock.ToolResult -> false
                else -> true
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
        if (parsed.body.isNotBlank()) {
            ChatMessageTime(conversationTurnClock(turn), alignEnd = true) {
                MessageCopyButton(copyText = parsed.body)
            }
        } else {
            ChatMessageTime(conversationTurnClock(turn), alignEnd = true)
        }
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

internal sealed class DisplayItem {
    class Plain(val block: ContentBlock) : DisplayItem()
    class Tool(val use: ContentBlock.ToolUse, val result: ContentBlock.ToolResult?) : DisplayItem()
}

internal data class ActivityGroup(
    val key: String,
    val items: List<DisplayItem>,
    val running: Boolean,
)

internal sealed class SegmentRenderItem {
    data class Item(val index: Int, val item: DisplayItem) : SegmentRenderItem()
    data class Activity(val group: ActivityGroup) : SegmentRenderItem()
}

/** 结果可能在下一条 assistant turn 到达；按调用 id 汇总供旧活动段刷新状态和详情。 */
internal fun conversationToolResults(turns: List<ConversationTurn>): Map<String, ContentBlock.ToolResult> =
    buildMap {
        turns.forEach { turn ->
            turn.content.filterIsInstance<ContentBlock.ToolResult>().forEach { result ->
                if (result.toolUseId.isNotBlank()) put(result.toolUseId, result)
            }
        }
    }

/** 当前用户输入之后最后一条尚无结果的命令；旧 turn 的未完成调用也保持活跃。 */
internal fun latestPendingCommandToolId(
    turns: List<ConversationTurn>,
    lastUserTurnIndex: Int,
    results: Map<String, ContentBlock.ToolResult>,
): String? {
    for (turnIndex in turns.lastIndex downTo lastUserTurnIndex + 1) {
        val turn = turns[turnIndex]
        if (turn.role != "assistant") continue
        for (block in turn.content.asReversed()) {
            if (block is ContentBlock.ToolUse && block.id.isNotBlank() &&
                toolActivityKind(block) == "run_command" && block.id !in results
            ) return block.id
        }
    }
    return null
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
            item is DisplayItem.Tool && isCollapsibleExplorationTool(item.use, item.result) ->
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
private fun isCollapsibleExplorationTool(use: ContentBlock.ToolUse, result: ContentBlock.ToolResult? = null): Boolean {
    if (!isExplorationTool(use.name)) return false
    // 结果带内联图片的工具单卡常驻（缩略图不能被折叠藏起来）。
    if (!result?.images.isNullOrEmpty()) return false
    if (use.name == "Read" && readImagePath(use.input) != null) return false
    return true
}

/** 连续思考与普通工具调用共用一条行内活动摘要；正文仍切开活动段。 */
internal fun collapseActivityItems(
    items: List<DisplayItem>,
    isLastTurn: Boolean,
    isResponding: Boolean,
    activeCommandIds: Set<String>? = null,
): List<SegmentRenderItem> {
    val renderItems = mutableListOf<SegmentRenderItem>()
    val pending = mutableListOf<Pair<Int, DisplayItem>>()

    fun flushPending() {
        if (pending.isEmpty()) return
        val groupItems = pending.map { it.second }
        renderItems += SegmentRenderItem.Activity(
            ActivityGroup(
                key = activityGroupKey(groupItems, pending.first().first),
                items = groupItems,
                running = false,
            ),
        )
        pending.clear()
    }

    items.forEachIndexed { index, item ->
        if (shouldSkipDisplayItem(item)) return@forEachIndexed
        if (isCollapsibleActivityItem(item)) {
            pending += index to item
        } else {
            flushPending()
            renderItems += SegmentRenderItem.Item(index, item)
        }
    }
    flushPending()

    if (isResponding) {
        renderItems.indices.forEach { index ->
            val activity = renderItems[index] as? SegmentRenderItem.Activity ?: return@forEach
            // 旧思考/已完成工具段不再误亮；未返回命令即使后面已有正文仍保持运行态。
            val latestThinking = isLastTurn && index == renderItems.lastIndex &&
                (activity.group.items.lastOrNull() as? DisplayItem.Plain)?.block is ContentBlock.Thinking
            val pendingCommand = if (activeCommandIds == null) {
                isLastTurn && activityHasPendingCommand(activity.group.items)
            } else {
                activity.group.items.any { item ->
                    item is DisplayItem.Tool && item.use.id in activeCommandIds && item.result == null
                }
            }
            val running = latestThinking || pendingCommand
            if (running) renderItems[index] = activity.copy(group = activity.group.copy(running = true))
        }
    }
    return renderItems
}

private fun activityGroupKey(items: List<DisplayItem>, startIndex: Int): String {
    // 思考先到、工具后到是常见流式顺序，位置锚点在同一 turn 内不随追加变化。
    if (items.firstOrNull() is DisplayItem.Plain) return "thinking-position:$startIndex"
    val firstTool = items.firstOrNull { it is DisplayItem.Tool } as? DisplayItem.Tool
    // 纯工具段用首个调用 id，头部分页后仍保持相同身份。
    return firstTool?.use?.id?.takeIf { it.isNotBlank() }?.let { "tool:$it" }
        ?: "thinking-position:$startIndex"
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
    return operation in setOf("update_plan", "taskcreate", "task_create", "taskupdate", "task_update", "tasklist", "task_list") ||
        (operation.contains("todo") &&
            !operation.contains("read") &&
            !operation.contains("get") &&
            !operation.contains("list"))
}

/**
 * 待办更新是一次性会话事件而不是可等待的外部调用；provider 通常不回传 tool_result。
 * 之后的回复仍在流式生成时，代办卡片也应立即稳定为完成态。
 */
internal fun isToolCardRunning(name: String, sessionReportsRunning: Boolean): Boolean =
    sessionReportsRunning && !isTodoUpdateToolName(name)

internal fun shouldCollapseToolInActivity(name: String): Boolean =
    name != "AskUserQuestion"

private fun isCollapsibleActivityItem(item: DisplayItem): Boolean = when (item) {
    is DisplayItem.Plain -> item.block is ContentBlock.Thinking
    is DisplayItem.Tool -> item.use.activity != null && shouldCollapseToolInActivity(item.use.name)
}

/**
 * 工具调用卡体系的尺寸基线（设计规格 §2.2 / §3.5）。
 * 四列骨架：① 图标槽 ② 标题 / 摘要列 ③ 状态胶囊 ④ 箭头槽。
 * 页面里不得再散落这些数字。
 */
internal object ChatCardMetrics {
    val iconBoxRegular = 34.dp
    val iconBoxCompact = 28.dp
    val iconSizeRegular = 16.dp
    val iconSizeCompact = 15.dp
    val iconCornerRegular = 9.dp
    val iconCornerCompact = 8.dp
    val titleRegular = 13.sp
    val titleCompact = 12.sp
    val pillRegular = 10.sp
    val pillCompact = 9.sp
    val pillMinWidthRegular = 46.dp
    val pillMinWidthCompact = 40.dp
    val summarySizeRegular = 11.sp
    val summarySizeCompact = 10.sp
    val summaryLineHeightRegular = 15.sp
    val summaryLineHeightCompact = 14.sp
    val summaryLinesRegular = 2
    val summaryLinesCompact = 1
    val chevronSlotRegular = 24.dp
    val chevronSlotCompact = 20.dp
    val chevronRegular = 14.dp
    val chevronCompact = 12.dp
    val headerPaddingHRegular = 12.dp
    val headerPaddingVRegular = 10.dp
    val headerPaddingHCompact = 10.dp
    val headerPaddingVCompact = 9.dp
    val headerMinHeight = 48.dp
    val iconToText = 8.dp
    val titleColumnSpacing = 4.dp

    /** 折叠头（活动状态条）最小触控高度与内边距。 */
    val foldHeaderMinHeight = 44.dp
    val foldHeaderPaddingH = 10.dp
    val foldHeaderPaddingV = 8.dp
}

/** 卡头四列骨架的通用最小高度 / 内边距（密档由 `LocalActivityFoldCompact` 决定）。 */
@Composable
internal fun cardHeaderModifier(compact: Boolean): Modifier =
    Modifier
        .heightIn(min = ChatCardMetrics.headerMinHeight)
        .padding(
            horizontal = if (compact) ChatCardMetrics.headerPaddingHCompact else ChatCardMetrics.headerPaddingHRegular,
            vertical = if (compact) ChatCardMetrics.headerPaddingVCompact else ChatCardMetrics.headerPaddingVRegular,
        )

/** 两层折叠共用的展开容器：从头部下沿向下长出，收起是同一段动画的倒放。 */
@Composable
internal fun FoldableCardBody(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduceMotion = reduceMotionEnabled()
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (reduceMotion) {
            fadeIn(snap()) + expandVertically(expandFrom = Alignment.Top, animationSpec = snap())
        } else {
            fadeIn(WandMotion.tweenEnter()) +
                expandVertically(expandFrom = Alignment.Top, animationSpec = WandMotion.tweenEnter())
        },
        exit = if (reduceMotion) {
            fadeOut(snap()) + shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = snap())
        } else {
            fadeOut(WandMotion.tweenExit()) +
                shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = WandMotion.tweenExit())
        },
    ) {
        Column(content = content)
    }
}

/**
 * 列③ 状态胶囊：宽度有下限（同屏多卡右缘对齐），文字切换用同位置交叉淡入，
 * 底色 / 文字色走 tweenFast（reduce-motion 下颜色保留、位移退化）。
 */
@Composable
internal fun CardStatusPill(
    text: String,
    color: Color,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val motionEnabled = !reduceMotionEnabled()
    val container by animateColorAsState(
        targetValue = color.copy(alpha = 0.11f),
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenFast()),
        label = "cardStatusPillContainer",
    )
    val labelColor by animateColorAsState(
        targetValue = color,
        animationSpec = WandMotion.respectMotion(motionEnabled, WandMotion.tweenFast()),
        label = "cardStatusPillLabel",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .widthIn(min = if (compact) ChatCardMetrics.pillMinWidthCompact else ChatCardMetrics.pillMinWidthRegular)
            .clip(WandShapes.full)
            .background(container)
            .padding(
                horizontal = if (compact) 6.dp else 7.dp,
                vertical = if (compact) 3.dp else 4.dp,
            ),
    ) {
        WandInPlaceSwap(contentKey = text, enterScale = 1f, exitScale = 1f) { key ->
            Text(
                key as String,
                fontSize = if (compact) ChatCardMetrics.pillCompact else ChatCardMetrics.pillRegular,
                fontWeight = FontWeight.SemiBold,
                color = labelColor,
                maxLines = 1,
            )
        }
    }
}

/**
 * 列④ 箭头槽：尺寸恒定（Compact 20dp / Regular 24dp），只换 rotation。
 * 有卡底的行传浅底，无卡底的流内行传 `Color.Transparent` 只为占位对齐。
 */
@Composable
internal fun CardChevronSlot(
    expanded: Boolean,
    tint: Color,
    compact: Boolean,
    containerColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(if (compact) ChatCardMetrics.chevronSlotCompact else ChatCardMetrics.chevronSlotRegular)
            .clip(CircleShape)
            .background(containerColor),
    ) {
        ExpandChevron(
            expanded = expanded,
            tint = tint,
            size = if (compact) ChatCardMetrics.chevronCompact else ChatCardMetrics.chevronRegular,
        )
    }
}

/**
 * 把 ToolUse 与对应 ToolResult 配成一张卡片，对齐 Web 端 buildToolResultMap：
 * 优先按 tool_use_id 精确配对（并行工具调用时 use 与 result 顺序会交错，
 * 邻接配对会把别的工具的结果挂错卡片）；id 缺失时退回「紧随其后的第一个结果」
 * 邻接兜底。没配上的 ToolResult 原样透传（走 OrphanResultBlock）。
 */
internal fun pairToolBlocks(
    content: List<ContentBlock>,
    externalResults: Map<String, ContentBlock.ToolResult> = emptyMap(),
): List<DisplayItem> {
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
                externalResults[block.id]
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
fun BlockView(
    block: ContentBlock,
    streaming: Boolean = false,
    showSubagentTag: Boolean = true,
    expandDefault: Boolean = false,
    foldKey: String = "",
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
                ThinkingBlock(
                    block.thinking,
                    streaming = streaming,
                    expandDefault = expandDefault,
                    foldKey = foldKey,
                )
            }
        }
        is ContentBlock.ToolUse -> {
            // 落单的 ToolUse（正常路径已在 TurnView 配对，这里兜底）
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (showSubagentTag) SubagentTag(block.subagent)
                ToolActivitySummary(
                    group = ActivityGroup("orphan", listOf(DisplayItem.Tool(block, null)), streaming),
                    scope = foldKey,
                )
            }
        }
        is ContentBlock.ToolResult -> {
            // 落单的 ToolResult 兜底：渲染成无头工具卡的结果区样式
            if (block.text.isNotEmpty() || block.truncated) {
                OrphanResultBlock(block, expandDefault = expandDefault, foldKey = foldKey)
            }
        }
        is ContentBlock.Unknown -> UnknownBlockCard(block, expandDefault = expandDefault, foldKey = foldKey)
    }
}

/**
 * 新协议块的显式兼容态。原始载荷保留可检查，避免 Codex 升级后内容无声消失。
 */
@Composable
private fun UnknownBlockCard(
    block: ContentBlock.Unknown,
    expandDefault: Boolean = false,
    foldKey: String = "",
) {
    var foldOverride by rememberFoldOverrideCode(foldKey)
    val expanded = foldExpanded(foldOverride, expandDefault)
    // payload 变化不再重置展开态：fold key 只描述「这张卡是谁」。
    val hasBody = block.payload.isNotBlank()
    val compact = LocalActivityFoldCompact.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(WandShapes.md)
            .background(WandColors.warningSoft),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ChatCardMetrics.iconToText),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (hasBody) Modifier.clickableWithoutRipple {
                    foldOverride = foldToggleCode(foldOverride, expandDefault)
                } else Modifier)
                .then(cardHeaderModifier(compact)),
        ) {
            WandStatusIconSlot(
                indicatorColor = WandColors.warning,
                containerColor = WandColors.warningSoft,
                running = false,
                icon = WandIcons.genericTool,
                boxSize = if (compact) ChatCardMetrics.iconBoxCompact else ChatCardMetrics.iconBoxRegular,
                iconSize = if (compact) ChatCardMetrics.iconSizeCompact else ChatCardMetrics.iconSizeRegular,
                cornerRadius = if (compact) ChatCardMetrics.iconCornerCompact else ChatCardMetrics.iconCornerRegular,
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(ChatCardMetrics.titleColumnSpacing),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    "暂未适配的内容块",
                    fontSize = if (compact) ChatCardMetrics.titleCompact else ChatCardMetrics.titleRegular,
                    fontWeight = FontWeight.SemiBold,
                    color = WandColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    block.type,
                    fontSize = ChatCardMetrics.summarySizeCompact,
                    fontFamily = FontFamily.Monospace,
                    color = WandColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CardStatusPill(text = "兼容显示", color = WandColors.warning, compact = compact)
            if (hasBody) {
                CardChevronSlot(
                    expanded = expanded,
                    tint = WandColors.warning,
                    compact = compact,
                    containerColor = WandColors.warning.copy(alpha = 0.14f),
                )
            }
        }
        FoldableCardBody(visible = expanded && hasBody) {
            HorizontalDivider(thickness = 0.5.dp, color = WandColors.warning.copy(alpha = 0.20f))
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
        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
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
/** 工具卡标题：普通形态与 todo 折叠形态共用同一套字号 / 配色（占满 Row 剩余宽度）。 */
@Composable
private fun RowScope.ToolCardTitle(
    name: String,
    isError: Boolean,
    foldCompact: Boolean,
) {
    Text(
        toolLabel(name),
        fontSize = if (foldCompact) ChatCardMetrics.titleCompact else ChatCardMetrics.titleRegular,
        fontWeight = FontWeight.SemiBold,
        color = if (isError) WandColors.danger else if (foldCompact) WandColors.textMuted else WandColors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
    )
}

@Composable
fun ToolCard(
    use: ContentBlock.ToolUse,
    result: ContentBlock.ToolResult?,
    running: Boolean = false,
    expandDefault: Boolean = false,
    foldKey: String = "",
) {
    val compact = LocalActivityFoldCompact.current
    val compactTodoUpdate = isTodoUpdateToolName(use.name)
    // TodoWrite / TaskUpdate 只是把新的待办快照写入会话流，不会产生 tool_result。
    // 不能把后续模型仍在生成，误显示成这一次待办更新仍在执行。
    val toolRunning = isToolCardRunning(use.name, running)
    val isError = result?.isError == true
    val declaredStatus = use.input.str("status")?.lowercase()
    val completedWithoutResult = result == null && !toolRunning && (
        declaredStatus in setOf("completed", "success", "succeeded", "done") ||
            compactTodoUpdate
        )
    val isSuccess = (result != null && !isError) || completedWithoutResult
    val noResult = result == null && !toolRunning && !completedWithoutResult
    // `input = {}` 时不再画出「只有一条分隔线的空白展开区」，也不画箭头。
    val hasInput = toolInputHasEntries(use.input)
    val hasBody = toolCardHasBody(use.input, result)
    val sourceLabel = remember(use.name) { toolSourceLabel(use.name) }
    // 展开态 = 用户显式收放 ?: RenderDisplayItem 传入的卡片偏好。
    var foldOverride by rememberFoldOverrideCode(foldKey)
    val expanded = foldExpanded(foldOverride, expandDefault)
    val statusColor = when {
        isError -> WandColors.danger
        toolRunning -> WandColors.brand
        isSuccess -> WandColors.success
        else -> WandColors.textSecondary
    }
    // 状态词表只有一套：运行中 / 完成 / 失败 / 未返回。
    val statusText = when {
        isError -> "失败"
        toolRunning -> "运行中"
        isSuccess -> "完成"
        else -> "未返回"
    }
    // 摘要按输入缓存：可见工具卡每次重组不必重算。todo 卡只把摘要换成「· N 项」，几何与普通卡一致。
    val todoSummary = remember(use.input) { todoUpdateSummary(todoUpdateItemCount(use.input)) }
    val toolSummaryText = remember(use.description, use.input) {
        toolSummary(use.description, use.input)
    }
    val summary = if (compactTodoUpdate) {
        if (todoSummary.isEmpty()) "" else "· $todoSummary"
    } else {
        toolSummaryText
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wandCardSurface(WandShapes.md, rimTint = if (isError) statusColor else null),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ChatCardMetrics.iconToText),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (hasBody) Modifier.clickableWithoutRipple {
                    foldOverride = foldToggleCode(foldOverride, expandDefault)
                } else Modifier)
                .then(cardHeaderModifier(compact)),
        ) {
            WandStatusIconSlot(
                indicatorColor = statusColor,
                containerColor = statusColor.copy(alpha = 0.11f),
                running = toolRunning,
                icon = toolIcon(use.name),
                boxSize = if (compact) ChatCardMetrics.iconBoxCompact else ChatCardMetrics.iconBoxRegular,
                iconSize = if (compact) ChatCardMetrics.iconSizeCompact else ChatCardMetrics.iconSizeRegular,
                cornerRadius = if (compact) ChatCardMetrics.iconCornerCompact else ChatCardMetrics.iconCornerRegular,
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(ChatCardMetrics.titleColumnSpacing),
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ToolCardTitle(name = use.name, isError = isError, foldCompact = compact)
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
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                }
                if (summary.isNotEmpty()) {
                    Text(
                        summary,
                        fontSize = if (compact) ChatCardMetrics.summarySizeCompact else ChatCardMetrics.summarySizeRegular,
                        lineHeight = if (compact) {
                            ChatCardMetrics.summaryLineHeightCompact
                        } else {
                            ChatCardMetrics.summaryLineHeightRegular
                        },
                        fontFamily = FontFamily.Monospace,
                        color = WandColors.textSecondary,
                        maxLines = if (compact) {
                            ChatCardMetrics.summaryLinesCompact
                        } else {
                            ChatCardMetrics.summaryLinesRegular
                        },
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            CardStatusPill(text = statusText, color = statusColor, compact = compact)
            if (hasBody) {
                CardChevronSlot(
                    expanded = expanded,
                    tint = WandColors.textSecondary,
                    compact = compact,
                    containerColor = WandColors.bgPrimary,
                )
            }
        }
        // 工具结果内联图片（服务端归一化后的取图 URL / data URI）优先：常驻缩略图，
        // 不藏在展开区里。没有内联图时退回 Read 按路径取图（对齐网页）。
        val baseUrl = LocalServerBaseUrl.current
        val resultImages = result?.images.orEmpty()
        if (resultImages.isNotEmpty()) {
            resultImages.forEach { source ->
                WandAsyncToolImage(
                    source = source,
                    baseUrl = baseUrl,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                )
            }
        } else if (use.name == "Read") {
            val imgPath = readImagePath(use.input)
            if (imgPath != null && baseUrl.isNotEmpty()) {
                WandAsyncImage(
                    path = imgPath,
                    baseUrl = baseUrl,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                )
            }
        }
        FoldableCardBody(visible = expanded && hasBody) {
            HorizontalDivider(
                thickness = 0.5.dp,
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
                // 「未返回」不再是一片空白：头部已经给了状态，展开区补一句原因。
                if (noResult) {
                    Text(
                        "本次调用没有返回结果",
                        fontSize = 11.sp,
                        color = WandColors.textMuted,
                    )
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

// MARK: - 探索上下文紧凑卡（连续只读探索工具合并，对齐 iOS ExplorationGroupCard）

@Composable
fun ExplorationGroupCard(
    tools: List<ExplorationToolItem>,
    running: Boolean,
    /** 消息级 fold scope（列表 item key）；空串时用首张工具卡的稳定 id 兜底。 */
    foldScope: String = "",
) {
    val items = remember(tools) { tools.map { DisplayItem.Tool(it.use, it.result) } }
    val group = remember(items, running) {
        ActivityGroup(
            // 分组键也用「类型 + 稳定 id」，不用内容指纹：它是 key 的一部分，流式期间不许漂移。
            key = "exploration:${items.firstOrNull()?.use?.id?.takeIf { it.isNotBlank() } ?: items.size}",
            items = items,
            running = running,
        )
    }
    val messageScope = foldScope.takeIf { it.isNotBlank() }
        ?: "explore:${items.firstOrNull()?.use?.id.orEmpty()}"
    ToolActivitySummary(group = group, scope = cardFoldId(messageScope, "explore"))
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
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    entry.key,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = WandColors.info,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
    // 同一份错误语义在标签、配色与兜底文案里反复用到，先取出来。
    val isError = result.isError
    val formatted = remember(fullText) { prettyStructuredText(fullText) }
    val displayLimit = 24_000
    val displayText = remember(formatted) {
        formatted.take(displayLimit) + if (formatted.length > displayLimit) "\n…（本页仅展示前 $displayLimit 字）" else ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        if (showSectionLabel) {
            Text(
                if (isError) "错误输出" else "工具输出",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isError) WandColors.danger else WandColors.textMuted,
            )
        }
        if (displayText.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(WandShapes.sm)
                    .background(
                        if (isError) WandColors.dangerSoft
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
                        color = if (isError) WandColors.danger else WandColors.textPrimary,
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
                if (isError) "工具执行失败，未返回错误详情" else "工具已完成，没有文本输出",
                fontSize = 11.sp,
                color = if (isError) WandColors.danger else WandColors.textMuted,
            )
        }
    }
}

/** 落单 ToolResult 的兜底渲染：可折叠的结果块。 */
@Composable
private fun OrphanResultBlock(
    result: ContentBlock.ToolResult,
    expandDefault: Boolean = false,
    foldKey: String = "",
) {
    var foldOverride by rememberFoldOverrideCode(foldKey)
    val expanded = foldExpanded(foldOverride, expandDefault)
    val tint = if (result.isError) WandColors.danger else WandColors.textSecondary
    val compact = LocalActivityFoldCompact.current
    // 流内行没有卡底，箭头槽用透明容器只为把 ④ 列对齐。
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ChatCardMetrics.iconToText),
            modifier = Modifier
                .clickableWithoutRipple {
                    foldOverride = foldToggleCode(foldOverride, expandDefault)
                }
                .then(cardHeaderModifier(compact)),
        ) {
            WandStatusIconSlot(
                indicatorColor = tint,
                containerColor = tint.copy(alpha = 0.10f),
                running = false,
                icon = if (result.isError) WandIcons.error else WandIcons.toolResult,
                boxSize = if (compact) ChatCardMetrics.iconBoxCompact else ChatCardMetrics.iconBoxRegular,
                iconSize = if (compact) ChatCardMetrics.iconSizeCompact else ChatCardMetrics.iconSizeRegular,
                cornerRadius = if (compact) ChatCardMetrics.iconCornerCompact else ChatCardMetrics.iconCornerRegular,
            )
            Text(
                if (result.isError) "执行出错" else "执行结果",
                fontSize = if (compact) ChatCardMetrics.titleCompact else ChatCardMetrics.titleRegular,
                fontWeight = FontWeight.Medium,
                color = tint,
                modifier = Modifier.weight(1f),
            )
            CardChevronSlot(
                expanded = expanded,
                tint = tint,
                compact = compact,
                containerColor = Color.Transparent,
            )
        }
        FoldableCardBody(visible = expanded) {
            ToolResultBody(result)
        }
    }
}

// MARK: - 思考块

/** Thinking 块：收起态一行（紫灰，流式时图标呼吸），展开态弱紫底 + 左侧 2dp 竖线 + 斜体。 */
@Composable
internal fun ThinkingBlock(
    text: String,
    streaming: Boolean = false,
    expandDefault: Boolean = false,
    foldKey: String = "",
) {
    // fold key 只描述「这段思考是谁」：内容每个流式 delta 都变，不能当 key（否则每段思考都会把
    // 用户展开的历史思考块收回）。
    var foldOverride by rememberFoldOverrideCode(foldKey)
    val expanded = foldExpanded(foldOverride, expandDefault)
    val iconAlpha: Float
    if (streaming && !reduceMotionEnabled()) {
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ChatCardMetrics.iconToText),
            modifier = Modifier
                .clickableWithoutRipple {
                    foldOverride = foldToggleCode(foldOverride, expandDefault)
                }
                .then(cardHeaderModifier(LocalActivityFoldCompact.current)),
        ) {
            val foldCompact = LocalActivityFoldCompact.current
            WandStatusIconSlot(
                indicatorColor = WandColors.thinking,
                containerColor = WandColors.thinkingSoft,
                running = false,
                icon = WandIcons.thinking,
                boxSize = if (foldCompact) ChatCardMetrics.iconBoxCompact else ChatCardMetrics.iconBoxRegular,
                iconSize = if (foldCompact) ChatCardMetrics.iconSizeCompact else ChatCardMetrics.iconSizeRegular,
                cornerRadius = if (foldCompact) ChatCardMetrics.iconCornerCompact else ChatCardMetrics.iconCornerRegular,
                iconAlpha = iconAlpha,
            )
            Text(
                "深度思考",
                fontSize = if (foldCompact) ChatCardMetrics.titleCompact else ChatCardMetrics.titleRegular,
                fontWeight = FontWeight.Medium,
                color = if (foldCompact) WandColors.textMuted else WandColors.thinking,
                modifier = Modifier.weight(1f),
            )
            CardChevronSlot(
                expanded = expanded,
                tint = WandColors.thinking.copy(alpha = 0.7f),
                compact = foldCompact,
                containerColor = Color.Transparent,
            )
        }
        FoldableCardBody(visible = expanded) {
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
                        fontSize = if (LocalActivityFoldCompact.current) 11.sp else 13.sp,
                        lineHeight = if (LocalActivityFoldCompact.current) 16.sp else 20.sp,
                        fontStyle = FontStyle.Italic,
                        color = if (LocalActivityFoldCompact.current) WandColors.textMuted else WandColors.textSecondary,
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
    var expanded by remember(scopeTitle, detail, target) { mutableStateOf(false) }
    val canExpand = (detail.isNotEmpty() && detail != scopeTitle) || !target.isNullOrEmpty()
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(backdrop, WandShapes.md, permissionGlass)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                WandIcons.permission,
                contentDescription = null,
                tint = WandColors.permission,
                modifier = Modifier.size(16.dp),
            )
            Text(
                scopeTitle,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = WandColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StatusDot("permission")
        }
        if (detail.isNotEmpty() && detail != scopeTitle) {
            Text(
                detail,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = WandColors.textSecondary,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!target.isNullOrEmpty()) {
            Text(
                target,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = WandColors.textSecondary,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(WandShapes.xs)
                    .background(WandColors.surface.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
        if (canExpand) {
            Text(
                if (expanded) "收起详情" else "展开详情",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = WandColors.brand,
                modifier = Modifier
                    .clickableWithoutRipple { expanded = !expanded }
                    .padding(vertical = 4.dp),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            WandButton(
                label = "拒绝",
                onClick = { onResolve("deny") },
                variant = WandButtonVariant.DangerText,
                compact = true,
                modifier = Modifier.weight(1f),
            )
            if (escalation != null) {
                WandButton(
                    label = "本轮放行",
                    onClick = { onResolve("approve_turn") },
                    variant = WandButtonVariant.Secondary,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
            }
            WandButton(
                label = "允许",
                onClick = { onResolve(if (escalation != null) "approve_once" else "approve") },
                variant = WandButtonVariant.Success,
                compact = true,
                modifier = Modifier.weight(1f),
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
