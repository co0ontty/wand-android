package com.wand.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.data.AiTeamRunDetail
import com.wand.app.data.ConversationTurn
import com.wand.app.data.TurnAuthor
import com.wand.app.ui.components.TeamMessageAvatar
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandTeamReportFileCard
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.reduceMotionEnabled

/** Conversation identity and grouping adapters; all chat content belongs to TurnView. */
/** 成员工位与实时步骤来自同一份运行详情，点已开工的成员直达其会话。 */
@Composable
fun TeamOfficeStrip(detail: AiTeamRunDetail, onOpenSession: (String) -> Unit) {
    val members = teamOfficeMembers(detail)
    val working = members.count { it.state == TeamOfficeState.Working }
    val attention = members.count { it.state == TeamOfficeState.Attention }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "团队工位",
                style = MaterialTheme.typography.titleSmall,
                color = WandColors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                listOfNotNull(
                    "$working 人工作中",
                    "$attention 人待处理".takeIf { attention > 0 },
                    "${members.size} 人在组",
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = WandColors.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(members.size, key = { members[it].member.id }) { index ->
                val entry = members[index]
                val tint = when (entry.state) {
                    TeamOfficeState.Working -> WandColors.info
                    TeamOfficeState.Attention -> WandColors.warning
                    TeamOfficeState.Done -> WandColors.success
                    TeamOfficeState.Failed -> WandColors.danger
                    else -> WandColors.textMuted
                }
                val open = entry.sessionId
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier
                        .widthIn(min = 190.dp, max = 220.dp)
                        .clip(WandShapes.sm)
                        .background(WandColors.surfaceSoft)
                        .then(if (open != null) Modifier.clickable(onClickLabel = "查看${entry.member.name}的会话") {
                            onOpenSession(open)
                        } else Modifier)
                        .padding(8.dp),
                ) {
                    // 与消息署名行同一张脸（§5.3 最小对齐）：工位条不再画字母圈，
                    // 否则同一成员在工位条与消息里会是两张不同的头像。
                    TeamMessageAvatar(
                        spec = chatAvatarSpec(
                            TurnAuthor(id = entry.member.id, name = entry.member.name, avatar = entry.member.avatar),
                        ),
                        size = 26.dp,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.member.name, style = MaterialTheme.typography.labelMedium,
                            color = WandColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.task, style = MaterialTheme.typography.labelSmall,
                            color = WandColors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(entry.label, style = MaterialTheme.typography.labelSmall, color = tint)
                }
            }
        }
    }
}

/** 完整行高从第一帧保留，alpha/+4dp 只动绘制层；回收时消费资格不补播。 */
@Composable
internal fun TeamTurnArrival(
    playing: Boolean,
    own: Boolean = false,
    onConsumed: () -> Unit,
    content: @Composable () -> Unit,
) {
    val motion = !reduceMotionEnabled()
    val progress = remember(playing) { Animatable(if (playing && motion) 0f else 1f) }
    val travelPx = with(LocalDensity.current) { (if (own) 8.dp else 4.dp).toPx() }
    DisposableEffect(playing) {
        onDispose { if (playing) onConsumed() }
    }
    LaunchedEffect(playing, motion) {
        if (!playing) return@LaunchedEffect
        if (motion) progress.animateTo(1f, WandMotion.respectMotion(motion, WandMotion.tweenEnter()))
        else progress.snapTo(1f)
        onConsumed()
    }
    Box(Modifier.graphicsLayer {
        val frame = teamArrivalFrame(progress.value, motion, travelPx)
        alpha = frame.alpha
        translationY = frame.translationY
    }) { content() }
}

@Composable
internal fun ConversationInstanceTurn(turn: ConversationTurn, baseUrl: String, onOpenSession: (String) -> Unit,
    group: Boolean = true, joined: Boolean = false, tail: Boolean = true, onAvatarClick: (() -> Unit)? = null,
    fallbackAuthor: TurnAuthor? = null, mentionNames: List<String> = emptyList(),
    protocol: com.wand.app.ui.ChatStore? = null,
    toolResults: Map<String, com.wand.app.data.ContentBlock.ToolResult> = conversationToolResults(listOf(turn)),
    expanded: Boolean = false, onExpandedChange: (Boolean) -> Unit = {},
    expandRequester: FocusRequester = remember { FocusRequester() }) {
    if (turn.notice) { TurnView(turn); return }
    val own = turn.role == "user"
    val visibleAuthor = turn.author ?: fallbackAuthor
    val text = chatTurnText(turn)
    val truncated = conversationNeedsCollapse(text)
    val nativeBlocks = turn.content.any { it !is com.wand.app.data.ContentBlock.Text }
    val askSelections = turn.content.filterIsInstance<com.wand.app.data.ContentBlock.ToolUse>().associate { use ->
        val selection = protocol?.askUserSelections?.get(use.id) ?: com.wand.app.ui.AskUserSelectionState()
        val unavailable = when {
            protocol == null -> "无法确认提问来源，请打开执行窗口核对。"
            protocol.loading -> "正在核对当前提问…"
            protocol.canAnswerAskUser(use.id) || selection.submitted -> null
            else -> "此提问当前不可回答，请查看最新执行状态。"
        }
        use.id to selection.copy(unavailableReason = unavailable)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val bubbleWidth = minOf(560.dp, if (own) maxWidth * .86f else maxWidth)
    TeamMessageRow(own, if (own) ChatAvatarSpec.Brand else chatAvatarSpec(visibleAuthor),
        showAvatar = !own && !joined, reserveAvatar = !own, onAvatarClick = onAvatarClick, alignAvatarTop = true) {
        Column(Modifier.widthIn(max = bubbleWidth), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!own && !joined) {
                val name = visibleAuthor?.name ?: "助手"
                val sessionId = turn.author?.sessionId
                Row(Modifier.heightIn(min = if (sessionId != null) 48.dp else 24.dp).then(if (sessionId != null) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "查看${name}的执行过程") { onOpenSession(sessionId) } else Modifier),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(name, fontSize = 14.sp, fontWeight = FontWeight.Normal, color = WandColors.textSecondary)
                    if (turn.author?.leader == true) Text("负责人", fontSize = 11.sp, color = WandColors.brandText)
                    if (sessionId != null) Icon(WandIcons.expand, null, Modifier.size(14.dp), tint = WandColors.textMuted)
                }
            }
            if (turn.reportFile != null) WandTeamReportFileCard(turn.reportFile, baseUrl)
            else {
                // 内容统一走普通会话 TurnView；此适配层只持有身份、合组和跳转。
                CompositionLocalProvider(LocalMarkdownInlineDecoration provides teamChatMarkdownDecoration(mentionNames)) {
                TurnView(turn, showHeader = false, compactUser = own, userTail = tail,
                    foldScope = conversationMessageKey(turn), toolResultsById = toolResults,
                    currentReplyExpandedOverride = if (!own && truncated && !nativeBlocks) expanded else true,
                    isLastTurn = protocol?.messages?.lastOrNull()?.createdAt == turn.createdAt && turn.createdAt != null,
                    isResponding = protocol?.isResponding == true,
                    askSelections = askSelections,
                    onAskToggle = { tool, question, option, multi -> protocol?.toggleAskOption(tool, question, option, multi) },
                    onAskSubmit = { tool, answer -> protocol?.submitAskUser(tool, answer) })
                }
                if (!own && truncated && !nativeBlocks) TextButton(onClick = { onExpandedChange(!expanded) },
                    modifier = Modifier.heightIn(min = 48.dp).focusRequester(expandRequester), contentPadding = PaddingValues(0.dp)) {
                    Text(if (expanded) "收起全文" else "展开全文", fontSize = 13.sp, color = WandColors.brand)
                }
            }
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                val clock = conversationBubbleClock(turn)
                if (clock.isNotBlank()) Text(clock, fontSize = 12.sp, lineHeight = 16.sp, color = WandColors.textSecondary)
                if (own) Icon(WandIcons.check, "已发送到服务端", Modifier.size(14.dp), tint = WandColors.textSecondary)
            }
        }
    }
    }
}

/** Stable DTO identity, never derived from changing content or its hash. */
internal fun conversationMessageKey(turn: ConversationTurn): String = turn.messageId
    ?: listOf(turn.requestId.orEmpty(), turn.role, turn.createdAt.orEmpty(), turn.author?.sessionId.orEmpty()).joinToString(":")

/**
 * 一条消息的外框：头像是内容列外侧、署名行在上、正文块在下
 * （设计 §3.1 / §9.1）。自己的发言整行镜像靠右（对齐 Web 的 `flex-direction: row-reverse`）。
 * Compose 的 `Row` 没有 `reverseLayout`，所以按位置分两支摆放同一个头像；
 * `own` 在同一条消息上是恒定的（由发言角色决定），不会在两处各建一份状态。
 */
@Composable
private fun TeamMessageRow(
    own: Boolean,
    avatar: ChatAvatarSpec,
    modifier: Modifier = Modifier,
    showAvatar: Boolean = true,
    reserveAvatar: Boolean = false,
    onAvatarClick: (() -> Unit)? = null,
    alignAvatarTop: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val avatarSlot: @Composable () -> Unit = {
        if (showAvatar) Box((if (reserveAvatar) Modifier.size(48.dp) else Modifier).then(if (onAvatarClick != null) Modifier.size(48.dp).clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "查看员工资料", onClick = onAvatarClick) else Modifier), contentAlignment = Alignment.TopCenter) { TeamMessageAvatar(avatar, size = if (alignAvatarTop) 34.dp else 32.dp) }
        else if (reserveAvatar) Spacer(Modifier.size(48.dp))
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = if (reserveAvatar && !alignAvatarTop) Alignment.Bottom else Alignment.Top,
    ) {
        if (!own) avatarSlot()
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = if (own) Alignment.End else Alignment.Start,
            content = content,
        )
        if (own) avatarSlot()
    }
}

/** 群聊单独启用的 Markdown inline 装饰；默认 MarkdownText 调用方不受影响。 */
@Composable
fun teamChatMarkdownDecoration(names: List<String>): InlineMarkdownDecoration? {
    val color = WandColors.brand
    val background = WandColors.brandSoft
    return remember(names, color, background) {
        if (names.isEmpty()) null else {
            val snapshot = names.toList()
            val style = SpanStyle(color = color, background = background, fontWeight = FontWeight.Medium)
            val decorator: InlineMarkdownDecoration = { value, protected ->
                decorateTeamMarkdown(value, snapshot, protected, style)
            }
            decorator
        }
    }
}
