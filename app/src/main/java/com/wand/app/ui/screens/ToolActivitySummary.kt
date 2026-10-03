package com.wand.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.data.ContentBlock
import com.wand.app.data.ToolContentDetail
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.reduceMotionEnabled
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

private const val ACTIVITY_WAIT_REFRESH_MS = 1_000L
private val COMMAND_CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
internal val TOOL_ACTIVITY_TIMELINE_HEIGHT = 240.dp

internal data class ToolActivityCategory(
    val kind: String,
    val entries: List<ToolActivityEntry>,
) {
    val count: Int get() = entries.size

    val title: String
        get() = when (kind) {
            "edit_file" -> "修改了 $count 个文件"
            "read_file" -> "查看了 $count 个文件"
            "run_command" -> "运行了 $count 条命令"
            else -> "其他 $count 次调用"
        }
}

/** 同一文件的一条菜单入口，可包含多次修改或查看；命令和其他调用各自独立。 */
internal data class ToolActivityEntry(val calls: List<DisplayItem.Tool>)

/** 摘要与菜单共用同一组入口；文件按匿名键合并，详情保留每个 tool id。 */
internal fun toolActivityCategories(items: List<DisplayItem>): List<ToolActivityCategory> {
    val order = listOf("edit_file", "read_file", "run_command", "other")
    val seenIds = mutableSetOf<String>()
    val calls = items.filterIsInstance<DisplayItem.Tool>().filter { item ->
        item.use.id.isBlank() || seenIds.add(item.use.id)
    }
    return order.mapNotNull { kind ->
        val matching = calls.filter { toolActivityKind(it.use) == kind }
        if (matching.isEmpty()) return@mapNotNull null
        val entries = if (kind == "edit_file" || kind == "read_file") {
            val byFile = linkedMapOf<String, MutableList<DisplayItem.Tool>>()
            matching.forEachIndexed { index, item ->
                val fileKey = item.use.activity?.fileKey?.takeIf { it.isNotBlank() }
                    ?: item.use.input.optString("file_path").takeIf { it.isNotBlank() }
                    ?: item.use.input.optString("path").takeIf { it.isNotBlank() }
                    ?: item.use.id.takeIf { it.isNotBlank() }
                    ?: "item-$index"
                byFile.getOrPut(fileKey) { mutableListOf() }.add(item)
            }
            byFile.values.map { ToolActivityEntry(it.toList()) }
        } else {
            matching.map { ToolActivityEntry(listOf(it)) }
        }
        ToolActivityCategory(kind, entries)
    }
}

internal fun toolActivityKind(use: ContentBlock.ToolUse): String {
    use.activity?.kind?.takeIf { it in setOf("edit_file", "read_file", "run_command", "other") }
        ?.let { return it }
    val name = use.name.lowercase().substringAfterLast("__").substringAfterLast("/")
    val hasMultipleFiles = listOf("paths", "file_paths", "files").any { key ->
        (use.input.optJSONArray(key)?.length() ?: 0) > 1
    }
    val hasFilePath = !hasMultipleFiles &&
        listOf("file_path", "path", "filename", "file", "notebook_path").any { key ->
            (use.input.opt(key) as? String)?.trim()?.isNotEmpty() == true
        }
    return when {
        listOf("bash", "exec", "command", "shell", "terminal").any { it in name } -> "run_command"
        hasFilePath && listOf("edit", "write", "replace", "notebookedit").any { it in name } -> "edit_file"
        hasFilePath && (name == "read" || name == "read_file") -> "read_file"
        else -> "other"
    }
}

/** 只有普通命令仍缺结果时才保留执行中状态；不依赖它是不是段的最后一个块。 */
internal fun activityHasPendingCommand(items: List<DisplayItem>): Boolean = items.any { item ->
    item is DisplayItem.Tool && toolActivityKind(item.use) == "run_command" && item.result == null
}

/** 旧历史无 occurredAt 时返回 null，不用客户端渲染时钟伪造执行时间。 */
internal fun latestCommandOccurredAt(items: List<DisplayItem>, pendingOnly: Boolean = false): Instant? =
    items.asSequence()
        .filterIsInstance<DisplayItem.Tool>()
        .filter { toolActivityKind(it.use) == "run_command" && (!pendingOnly || it.result == null) }
        .mapNotNull { item ->
            (item.use.activity?.occurredAt ?: item.use.occurredAt)?.let { raw ->
                runCatching { Instant.parse(raw) }.getOrNull()
            }
        }
        .maxOrNull()

internal fun commandEventClock(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    COMMAND_CLOCK_FORMAT.format(instant.atZone(zone))

internal fun commandWaitLabel(instant: Instant, nowMillis: Long): String {
    val seconds = ((nowMillis - instant.toEpochMilli()) / 1_000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "已等待 ${seconds} 秒"
        seconds < 3_600 -> "已等待 ${seconds / 60} 分 ${seconds % 60} 秒"
        else -> "已等待 ${seconds / 3_600} 小时 ${(seconds % 3_600) / 60} 分"
    }
}

internal fun activityNeedsThinkingPlaceholder(group: ActivityGroup): Boolean =
    group.running &&
        (group.items.lastOrNull() as? DisplayItem.Plain)?.block is ContentBlock.Thinking

/** 外壳与活动段共用同一资格判定，避免旧调用/待办多出一层大卡片。 */
internal fun isToolActivityOnly(
    blocks: List<ContentBlock>,
    externalResults: Map<String, ContentBlock.ToolResult> = emptyMap(),
): Boolean {
    val items = pairToolBlocks(blocks, externalResults).filterNot { item ->
        item is DisplayItem.Plain && (item.block as? ContentBlock.Text)?.text?.isBlank() == true
    }
    return items.isNotEmpty() && items.all { isCollapsibleActivityItem(it) }
}

/** 调用按原始时间线排列；同文件多次调用不合并，只去重重传的工具 id。 */
internal fun toolActivityTimeline(items: List<DisplayItem>): List<DisplayItem> {
    val seen = mutableSetOf<String>()
    return items.filter { item ->
        when (item) {
            is DisplayItem.Tool -> item.use.id.isBlank() || seen.add(item.use.id)
            is DisplayItem.Plain -> item.block is ContentBlock.Thinking
        }
    }
}

internal fun toolActivityItemLabel(use: ContentBlock.ToolUse): String {
    use.activity?.label?.takeIf { it.isNotBlank() }?.let { return it }
    val path = listOf("file_path", "path", "filename", "file", "notebook_path")
        .firstNotNullOfOrNull { (use.input.opt(it) as? String)?.trim()?.takeIf(String::isNotBlank) }
        .orEmpty().replace('\\', '/').split('/').filter(String::isNotBlank).takeLast(2).joinToString("/")
    val name = use.name.replace(Regex("\\s+"), " ").trim().take(80).ifBlank { "工具" }
    val label = when (toolActivityKind(use)) {
        "edit_file" -> "修改 $path"
        "read_file" -> "查看 $path"
        "run_command" -> "运行命令 · $name"
        else -> "调用 $name"
    }
    return if (label.length > 120) label.take(119) + "…" else label
}

/** 时间线里最新一条的下标；空列表返回 -1。 */
internal fun activityTimelineTailIndex(itemCount: Int): Int = itemCount - 1

/**
 * 是否需要把时间线视口拉到最新一条：已展开、仍贴尾、且确有内容。
 * 用户自己往上翻过（贴尾状态被翻掉）就不再抢视线。
 */
internal fun shouldFollowActivityTail(menuOpen: Boolean, pinnedToLatest: Boolean, itemCount: Int): Boolean =
    menuOpen && pinnedToLatest && itemCount > 0

/** 跟到尾部时先定位最后一条，再向前溢出一点，由列表自身 clamp 到底部。 */
private const val ACTIVITY_TAIL_SCROLL_OVERSHOOT_PX = 4_000f

/** 向上展开的面板默认贴着摘要行显示最新一条；追加新调用时继续跟到最新。 */
private suspend fun scrollActivityTimelineToTail(state: LazyListState, itemCount: Int) {
    if (itemCount <= 0) return
    state.scrollToItem(activityTimelineTailIndex(itemCount))
    state.scrollBy(ACTIVITY_TAIL_SCROLL_OVERSHOOT_PX)
}

internal const val ACTIVITY_SUMMARY_DOT = " · "

/** 收起态摘要里一段文字的强调方式；具体颜色与呼吸动效由渲染层决定。 */
internal enum class ToolActivitySummaryTone { Text, Accent, AccentPulse, Clock }

/**
 * 收起态摘要的一段：文字、强调方式，以及与下一段之间的连接符。
 * 时间列后面只留空档，其余各段之间用间隔点。
 */
internal data class ToolActivitySummaryPart(
    val text: String,
    val tone: ToolActivitySummaryTone = ToolActivitySummaryTone.Text,
    val joiner: String = ACTIVITY_SUMMARY_DOT,
)

/**
 * 收起态摘要的显示顺序。
 *
 * 最新一条命令的真实时间排在整行最前，和展开行的时间列同一个位置；
 * 旧历史没有真实时间时不插入占位，绝不补造时间。
 * 「运行中 / 已等待」仍跟在命令计数后面。
 */
internal fun toolActivitySummaryParts(
    categories: List<ToolActivityCategory>,
    hasThinking: Boolean,
    thinkingRunning: Boolean,
    thinkingPlaceholder: Boolean,
    leadClock: String?,
    pendingCommand: Boolean,
    waitLabel: String?,
): List<ToolActivitySummaryPart> {
    val parts = mutableListOf<ToolActivitySummaryPart>()
    leadClock?.let { parts += ToolActivitySummaryPart(it, ToolActivitySummaryTone.Clock, "  ") }
    if (hasThinking || thinkingPlaceholder) {
        // 思考中：无正文时整段是活动色；有正文时只有后缀「中」跟着呼吸。
        val suffix = hasThinking && thinkingRunning && categories.isEmpty()
        parts += ToolActivitySummaryPart(
            text = if (hasThinking) "深度思考" else "思考中",
            tone = if (hasThinking) ToolActivitySummaryTone.Text else ToolActivitySummaryTone.AccentPulse,
            joiner = if (suffix) "" else ACTIVITY_SUMMARY_DOT,
        )
        if (suffix) parts += ToolActivitySummaryPart("中", ToolActivitySummaryTone.AccentPulse)
    }
    categories.forEach { category ->
        parts += ToolActivitySummaryPart(category.title)
        if (category.kind == "run_command" && pendingCommand) {
            parts += ToolActivitySummaryPart("运行中", ToolActivitySummaryTone.AccentPulse)
            waitLabel?.let { parts += ToolActivitySummaryPart(it) }
        }
    }
    return parts
}

/**
 * 小字活动菜单在原位**向上**展开成时间线：面板长在摘要行之上，收起时回落到摘要行。
 * 默认贴着摘要行显示最新一条，运行中追加的调用会自动跟到最新；
 * 用户自己往上翻过就暂停跟随，翻回尾部再恢复。单条点开才请求完整内容。
 */
@Composable
internal fun ToolActivitySummary(
    group: ActivityGroup,
    scope: String,
    /** 所在聊天列表 item key（外层 LazyColumn 的 key）；空串表示不请求锚点补偿。 */
    listItemKey: String = "",
) {
    val categories = remember(group.items) { toolActivityCategories(group.items) }
    val thinking = remember(group.items) {
        group.items.mapNotNull { item ->
            ((item as? DisplayItem.Plain)?.block as? ContentBlock.Thinking)?.thinking
                ?.takeIf { it.isNotBlank() }
        }
    }
    val thinkingPlaceholder = thinking.isEmpty() && activityNeedsThinkingPlaceholder(group)
    if (categories.isEmpty() && thinking.isEmpty() && !thinkingPlaceholder) return
    val key = cardFoldKey(LocalChatSessionId.current, scope)
    var menuOpen by rememberSaveable(key) { mutableStateOf(false) }
    BackHandler(enabled = menuOpen) { menuOpen = false }
    val timeline = remember(group.items) { toolActivityTimeline(group.items) }
    val timelineState = rememberLazyListState()
    // 向上展开的面板默认贴着摘要行显示最新一条；用户自己翻离尾部才暂停跟随。
    var pinnedToLatest by rememberSaveable(key) { mutableStateOf(true) }
    val anchorKey = listItemKey
    val keepAnchor = LocalActivityAnchorKeeper.current
    // 展开/收起会改变所在列表 item 的高度：向上展开时摘要行必须留在原处。
    val holdSummaryAnchor: () -> Unit = {
        if (anchorKey.isNotBlank()) keepAnchor(anchorKey)
    }
    // 只有用户自己的滚动决定贴尾状态：程序定位（scrollToItem）不会置 isScrollInProgress。
    LaunchedEffect(timelineState) {
        snapshotFlow { timelineState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) pinnedToLatest = !timelineState.canScrollForward
        }
    }
    // 展开中追加了新调用就继续跟到最新，绝不打断正在往上翻历史的用户。
    LaunchedEffect(menuOpen, timeline.size, pinnedToLatest) {
        if (shouldFollowActivityTail(menuOpen, pinnedToLatest, timeline.size)) {
            scrollActivityTimelineToTail(timelineState, timeline.size)
        }
    }
    val latestCommandAt = remember(group.items) { latestCommandOccurredAt(group.items) }
    val pendingCommandAt = remember(group.items) { latestCommandOccurredAt(group.items, pendingOnly = true) }
    val pendingCommand = group.running && activityHasPendingCommand(group.items)
    var nowMillis by remember(key) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(pendingCommand, pendingCommandAt) {
        if (!pendingCommand || pendingCommandAt == null) return@LaunchedEffect
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(ACTIVITY_WAIT_REFRESH_MS)
        }
    }
    val motion = !reduceMotionEnabled()
    val pulse = if (group.running && motion) {
        val transition = rememberInfiniteTransition(label = "toolActivityRunning")
        val alpha by transition.animateFloat(
            initialValue = WandMotion.activityTextAlphaMin,
            targetValue = 1f,
            animationSpec = WandMotion.breath(),
            label = "toolActivityAlpha",
        )
        alpha
    } else {
        1f
    }
    val emphasis = WandColors.brand
    val parts = toolActivitySummaryParts(
        categories = categories,
        hasThinking = thinking.isNotEmpty(),
        thinkingRunning = group.running,
        thinkingPlaceholder = thinkingPlaceholder,
        leadClock = latestCommandAt?.let { commandEventClock(it) },
        pendingCommand = pendingCommand,
        waitLabel = pendingCommandAt?.let { commandWaitLabel(it, nowMillis) },
    )
    val summary = buildAnnotatedString {
        parts.forEachIndexed { index, part ->
            val style = when (part.tone) {
                ToolActivitySummaryTone.Text -> null
                ToolActivitySummaryTone.Accent -> SpanStyle(color = emphasis)
                ToolActivitySummaryTone.AccentPulse -> SpanStyle(color = emphasis.copy(alpha = pulse))
                ToolActivitySummaryTone.Clock -> SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = WandColors.textMuted,
                )
            }
            if (style == null) append(part.text) else withStyle(style) { append(part.text) }
            if (index < parts.lastIndex) append(part.joiner)
        }
    }
    val hasError = group.items.any { (it as? DisplayItem.Tool)?.result?.isError == true }
    val borderColor by animateColorAsState(
        targetValue = when {
            group.running -> WandColors.brand.copy(alpha = 0.45f)
            hasError -> WandColors.danger.copy(alpha = 0.4f)
            menuOpen -> WandColors.borderStrong
            else -> WandColors.border
        },
        animationSpec = WandMotion.respectMotion(motion, WandMotion.tweenFast()),
        label = "activityBorder",
    )
    Column(modifier = Modifier.fillMaxWidth()
        .clip(WandShapes.sm)
        .border(1.dp, borderColor, WandShapes.sm)
        .background(if (group.running) WandColors.brand.copy(alpha = 0.06f) else WandColors.surface.copy(alpha = 0.65f))) {
        // 面板排在摘要行之前：向上展开，收起是同一段动画回落到摘要行。
        ToolActivityReveal(menuOpen) {
            LazyColumn(
                state = timelineState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = TOOL_ACTIVITY_TIMELINE_HEIGHT)
                    .padding(5.dp),
            ) {
                itemsIndexed(timeline, key = { index, item ->
                    (item as? DisplayItem.Tool)?.use?.id?.takeIf { it.isNotBlank() } ?: "thinking-$index"
                }) { index, item ->
                    val itemId = (item as? DisplayItem.Tool)?.use?.id?.takeIf { it.isNotBlank() }
                        ?: "thinking-$index"
                    ToolActivityEntryRow(item, "$key/$itemId", group.running, menuOpen, listItemKey)
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .background(if (menuOpen) WandColors.brand.copy(alpha = 0.06f) else Color.Transparent)
                .clickable(role = Role.Button, onClickLabel = if (menuOpen) "收起工具时间线" else "查看工具时间线") {
                    val next = !menuOpen
                    menuOpen = next
                    // 每次展开都回到最新一条；高度变化由容器补偿滚动接住。
                    if (next) pinnedToLatest = true
                    holdSummaryAnchor()
                }
                .semantics { stateDescription = if (menuOpen) "已展开" else "已收起" }
                .padding(horizontal = 11.dp, vertical = 9.dp),
        ) {
            // 标记始终占同一槽位，运行结束时摘要文字不横向跳动。
            ToolActivityStateDot(
                color = when {
                    group.running -> WandColors.brand
                    hasError -> WandColors.danger
                    else -> WandColors.textMuted
                },
                running = group.running,
            )
            Text(
                summary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = WandColors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ExpandChevron(
                expanded = menuOpen,
                tint = WandColors.textMuted,
                size = 14.dp,
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun ToolActivityEntryRow(
    item: DisplayItem,
    key: String,
    running: Boolean,
    menuOpen: Boolean,
    listItemKey: String,
) {
    val tool = item as? DisplayItem.Tool
    val thinking = ((item as? DisplayItem.Plain)?.block as? ContentBlock.Thinking)?.thinking
    val kind = tool?.let { toolActivityKind(it.use) } ?: "thinking"
    val callRunning = tool?.let { toolActivityCallRunning(kind, it, running) } ?: false
    val status = tool?.let { toolActivityEntryStatus(kind, ToolActivityEntry(listOf(it)), running) }
    val label = tool?.let { toolActivityItemLabel(it.use) } ?: "深度思考"
    val clock = (tool?.use?.activity?.occurredAt ?: tool?.use?.occurredAt)?.let { raw ->
        runCatching { commandEventClock(Instant.parse(raw)) }.getOrNull()
    }
    var open by rememberSaveable(key) { mutableStateOf(false) }
    LaunchedEffect(menuOpen) { if (!menuOpen) open = false }
    // 单条详情就地展开也会改变面板高度，同样请容器接住摘要行的位置。
    val anchorKey = listItemKey
    val keepAnchor = LocalActivityAnchorKeeper.current
    val lineColor = WandColors.border
    val motion = !reduceMotionEnabled()
    val stateColor = when (status) {
        "失败" -> WandColors.danger
        "运行中" -> WandColors.brand
        "完成" -> WandColors.success
        else -> WandColors.textMuted
    }
    val highlight by animateColorAsState(
        targetValue = when {
            status == "失败" -> WandColors.danger.copy(alpha = 0.06f)
            callRunning -> WandColors.brand.copy(alpha = 0.09f)
            open -> WandColors.surfaceSoft.copy(alpha = 0.5f)
            else -> Color.Transparent
        },
        animationSpec = WandMotion.respectMotion(motion, WandMotion.tweenFast()),
        label = "activityEntryHighlight",
    )
    val rail = when {
        status == "失败" || callRunning -> stateColor
        open -> WandColors.borderStrong
        else -> Color.Transparent
    }
    Column(modifier = Modifier.drawBehind {
        val x = 12.dp.toPx()
        drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
    }) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(WandShapes.xs)
                .background(highlight)
                .drawBehind { drawLine(rail, Offset(0f, 0f), Offset(0f, size.height), 4.dp.toPx()) }
                .clickable(role = Role.Button, onClickLabel = if (open) "收起$label" else "查看$label") {
                    open = !open
                    if (anchorKey.isNotBlank()) keepAnchor(anchorKey)
                }
                .semantics { stateDescription = if (open) "已展开" else "已收起" }
                .padding(horizontal = 6.dp, vertical = 9.dp),
        ) {
            ToolActivityStateDot(color = stateColor, running = callRunning)
            if (clock != null) Text(clock, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                color = WandColors.textMuted)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, fontSize = 11.sp,
                    color = if (callRunning) WandColors.textPrimary else WandColors.textSecondary,
                    fontWeight = if (callRunning) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                tool?.let {
                    ToolPreviewText(toolInputCardPreview(it.use), WandColors.textPrimary)
                    ToolPreviewText(toolResultCardPreview(it.result),
                        if (it.result?.isError == true) WandColors.danger else WandColors.textMuted)
                }
            }
            if (status != null) Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.widthIn(min = 44.dp).clip(WandShapes.full)
                    .background(stateColor.copy(alpha = 0.1f)).padding(horizontal = 6.dp, vertical = 2.dp),
            ) { Text(status, fontSize = 10.sp, color = stateColor, maxLines = 1) }
            ExpandChevron(expanded = open, tint = WandColors.textMuted, size = 14.dp,
                contentDescription = null)
        }
        ToolActivityReveal(open && menuOpen) {
            Column(modifier = Modifier.padding(start = 18.dp, end = 4.dp, bottom = 8.dp)) {
                if (tool != null) {
                    ToolActivitySingleDetail(item = tool, key = key, open = open && menuOpen, running = callRunning)
                } else {
                    Text(thinking?.takeIf { it.isNotBlank() } ?: "思考内容生成中…",
                        fontSize = 11.sp, lineHeight = 17.sp, color = WandColors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun ToolActivityStateDot(color: Color, running: Boolean) {
    val motion = !reduceMotionEnabled()
    val alpha = if (running && motion) {
        val transition = rememberInfiniteTransition(label = "activityState")
        val value by transition.animateFloat(
            initialValue = WandMotion.breathAlphaMin,
            targetValue = 1f,
            animationSpec = WandMotion.breath(),
            label = "activityStateAlpha",
        )
        value
    } else 1f
    Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(12.dp).clip(CircleShape)
            .background(if (running) color.copy(alpha = 0.14f) else Color.Transparent))
        Box(Modifier.size(6.dp).graphicsLayer { this.alpha = alpha }
            .clip(CircleShape).background(color))
    }
}

internal fun toolActivityCallRunning(kind: String, item: DisplayItem.Tool, groupRunning: Boolean): Boolean =
    groupRunning && kind == "run_command" && item.result == null

internal fun toolActivityEntryStatus(kind: String, entry: ToolActivityEntry, groupRunning: Boolean): String = when {
    entry.calls.any { it.result?.isError == true } -> "失败"
    entry.calls.all { it.result != null || isTodoUpdateToolName(it.use.name) } -> "完成"
    entry.calls.any { toolActivityCallRunning(kind, it, groupRunning) } -> "运行中"
    else -> "未返回"
}

@Composable
private fun ToolActivitySingleDetail(item: DisplayItem.Tool, key: String, open: Boolean, running: Boolean) {
    val use = item.use
    val result = item.result
    val sessionId = LocalChatSessionId.current
    val api = LocalChatApi.current
    var detail by remember(sessionId, use.id) { mutableStateOf<ToolContentDetail?>(null) }
    var loading by remember(sessionId, use.id) { mutableStateOf(false) }
    var error by remember(sessionId, use.id) { mutableStateOf<String?>(null) }
    var retry by remember(sessionId, use.id) { mutableIntStateOf(0) }

    LaunchedEffect(open, result != null, retry, sessionId, use.id) {
        if (!open || api == null || sessionId.isBlank() || use.id.isBlank()) return@LaunchedEffect
        if (detail != null && (result == null || detail?.result != null)) return@LaunchedEffect
        loading = true
        error = null
        try {
            detail = api.fetchToolDetail(sessionId, use.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "加载失败，请重试"
        } finally {
            loading = false
        }
    }

    when {
        loading -> CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = WandColors.brand,
        )
        error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(error.orEmpty(), fontSize = 11.sp, color = WandColors.danger, modifier = Modifier.weight(1f))
            TextButton(onClick = { retry += 1 }) { Text("重试") }
        }
        detail != null -> ToolActivityLoadedCard(use, detail!!, key, running)
        else -> Text(
            "此调用暂时无法加载详情",
            fontSize = 11.sp,
            color = WandColors.textMuted,
        )
    }
}

@Composable
private fun ToolActivityLoadedCard(
    use: ContentBlock.ToolUse,
    detail: ToolContentDetail,
    key: String,
    running: Boolean,
) {
    val input = detail.input
    val result = detail.result
    CompositionLocalProvider(LocalActivityFoldCompact provides true) {
        when (use.name) {
            "Edit", "Write", "MultiEdit" -> DiffCard(
                toolName = use.name,
                input = input,
                result = result,
                running = running,
                expandDefault = true,
                foldKey = "$key/detail",
            )
            "Bash" -> TerminalCard(
                input = input,
                result = result,
                running = running,
                expandDefault = true,
                foldKey = "$key/detail",
            )
            else -> ToolCard(
                use = use.copy(input = input),
                result = result,
                running = running,
                expandDefault = true,
                foldKey = "$key/detail",
            )
        }
    }
}

/**
 * 工具时间线的高度过渡：从摘要行那一侧（底部）向上长出，收起时同样回落到摘要行，
 * 不做「向下推」或硬切。
 */
@Composable
private fun ToolActivityReveal(visible: Boolean, content: @Composable () -> Unit) {
    val motion = !reduceMotionEnabled()
    AnimatedVisibility(
        visible = visible,
        enter = if (motion) fadeIn(WandMotion.tweenEnter()) + expandVertically(expandFrom = Alignment.Bottom, animationSpec = WandMotion.tweenEnter())
            else fadeIn(androidx.compose.animation.core.snap()),
        exit = if (motion) fadeOut(WandMotion.tweenExit()) + shrinkVertically(shrinkTowards = Alignment.Bottom, animationSpec = WandMotion.tweenExit())
            else fadeOut(androidx.compose.animation.core.snap()),
    ) { content() }
}
