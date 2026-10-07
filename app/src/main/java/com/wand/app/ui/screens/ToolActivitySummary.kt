package com.wand.app.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.ServerStore
import com.wand.app.data.ContentBlock
import com.wand.app.data.ToolContentDetail
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.reduceMotionEnabled
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlin.math.abs

private const val ACTIVITY_WAIT_REFRESH_MS = 1_000L
private val COMMAND_CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")

/** 面板高度上限（宽屏/平板）；手机上再按所在聊天视口的三分之一收口。 */
internal val TOOL_ACTIVITY_TIMELINE_HEIGHT = 240.dp

/** 视口再矮也至少留出这么高，收口后不能连一行都看不全。 */
internal val TOOL_ACTIVITY_PANEL_MIN_HEIGHT = 120.dp

/**
 * 向下展开的时间线面板最大高度：不超过所在视口的三分之一，宽屏再收在
 * [TOOL_ACTIVITY_TIMELINE_HEIGHT] 以内；极矮视口由 [TOOL_ACTIVITY_PANEL_MIN_HEIGHT] 兜底。
 */
internal fun activityPanelMaxHeight(viewportHeight: Dp): Dp =
    (viewportHeight / 3).coerceIn(TOOL_ACTIVITY_PANEL_MIN_HEIGHT, TOOL_ACTIVITY_TIMELINE_HEIGHT)

/** 时间线行的左右留白、状态点尺寸与正文缩进；尽量占满横向区域，连接线必须正好穿过点心。 */
private val ACTIVITY_PANEL_PADDING = 0.dp
private val ACTIVITY_ROW_START = 4.dp
private val ACTIVITY_ROW_END = 4.dp
private val ACTIVITY_ROW_VERTICAL = 8.dp
private val ACTIVITY_ROW_LINE_HEIGHT = 18.sp
/** 调用行两行摘录的最小高度。 */
private val ACTIVITY_CALL_ROW_MIN_HEIGHT = 78.dp
/** 推理轮次只有一行摘录：几轮思考要能同屏各自成行，不能沿用调用的行高。 */
private val ACTIVITY_THINKING_ROW_MIN_HEIGHT = 44.dp
private val ACTIVITY_DOT_BOX = 12.dp
private val ACTIVITY_DOT_SIZE = 6.dp
private val ACTIVITY_DOT_TO_CONTENT = 8.dp
private val ACTIVITY_RAIL_X = ACTIVITY_ROW_START + ACTIVITY_DOT_BOX / 2
private val ACTIVITY_CONTENT_START = ACTIVITY_ROW_START + ACTIVITY_DOT_BOX + ACTIVITY_DOT_TO_CONTENT

/** 摘要行缩进要把面板内边距算进去，展开后摘要点与时间线点才在同一条竖线上。 */
private val ACTIVITY_SUMMARY_START = ACTIVITY_PANEL_PADDING + ACTIVITY_ROW_START
private val ACTIVITY_SUMMARY_END = ACTIVITY_PANEL_PADDING + ACTIVITY_ROW_END

/** Android 聊天树提供的实时工具时间线网络范围。 */
internal val LocalActivityTrafficTimelineMode = staticCompositionLocalOf {
    ServerStore.TRAFFIC_TIMELINE_MODE_WIFI
}

@Composable
private fun rememberWifiConnected(): Boolean {
    val context = LocalContext.current
    val connectivity = remember(context) {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    fun currentIsWifi(): Boolean = runCatching {
        connectivity.activeNetwork?.let { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(
                NetworkCapabilities.TRANSPORT_WIFI,
            ) == true
        } ?: false
    }.getOrDefault(false)
    var connected by remember(connectivity) { mutableStateOf(currentIsWifi()) }
    DisposableEffect(connectivity) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun refresh() { connected = currentIsWifi() }
            override fun onAvailable(network: Network) { refresh() }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                refresh()
            }
            override fun onLost(network: Network) { refresh() }
        }
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }
    return connected
}

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

/**
 * 一轮推理的身份：段内第 [ordinal] 轮 / 共 [total] 轮。
 *
 * 模型想一轮就要调工具再接着想，段里因此堆着好几轮推理；没有轮次概念时
 * 它们既分不清先后，也无法说清现在轮到哪一轮在想。
 */
internal data class ThinkingRound(
    val item: DisplayItem,
    /** 段内稳定位置：fold key 与排序都只读它，不读流式正文。 */
    val position: Int,
    val ordinal: Int,
    val total: Int,
    val occurredAt: Instant?,
    val lastActivityAt: Instant?,
) {
    val label: String get() = if (total > 1) "思考过程 $ordinal/$total" else "思考过程"
}

/** 段内活跃条目：调用与思考轮次二选一，全段至多一个。 */
internal sealed interface ActivityLiveRow {
    data class Call(val toolId: String) : ActivityLiveRow
    data class Thinking(val round: ThinkingRound) : ActivityLiveRow
}

/**
 * 一个思考块算不算一轮：空块只有在「它就是段尾、且这一段还在跑」时才是正在进行
 * 的那一轮占位。其余空块是没产出过正文的轮次（provider 只发了边界事件），
 * 不占时间线也不占计数，否则用户会看到一条永远「思考中」又读不到内容的条目。
 */
internal fun keepThinkingRound(
    item: DisplayItem, position: Int, lastPosition: Int, running: Boolean,
): Boolean {
    val block = thinkingBlock(item) ?: return false
    return block.thinking.isNotBlank() || (running && position == lastPosition)
}

internal fun thinkingBlock(item: DisplayItem): ContentBlock.Thinking? =
    ((item as? DisplayItem.Plain)?.block as? ContentBlock.Thinking)

/** 每个思考块就是一轮，按出现顺序编号；空轮次不进来。 */
internal fun thinkingRounds(items: List<DisplayItem>, running: Boolean = true): List<ThinkingRound> {
    val lastPosition = items.lastIndex
    val positions = items.withIndex()
        .filter { (position, item) -> keepThinkingRound(item, position, lastPosition, running) }
        .map { (index, item) -> index to item }
    return positions.mapIndexed { index, (position, item) ->
        ThinkingRound(
            item = item,
            position = position,
            ordinal = index + 1,
            total = positions.size,
            occurredAt = thinkingOccurredAt(item),
            lastActivityAt = thinkingLastActivityAt(item),
        )
    }
}

internal fun thinkingOccurredAt(item: DisplayItem): Instant? =
    thinkingBlock(item)?.occurredAt?.let(::parseInstantOrNull)

internal fun thinkingLastActivityAt(item: DisplayItem): Instant? =
    thinkingBlock(item)?.lastActivityAt?.let(::parseInstantOrNull)

private fun parseInstantOrNull(raw: String?): Instant? =
    raw?.let { runCatching { Instant.parse(it) }.getOrNull() }

/** 时间线行的稳定身份：调用用调用 id，思考轮次用段内位置；缺 id 的旧调用回落到位置。 */
internal fun activityRowKey(item: DisplayItem, position: Int): String =
    (item as? DisplayItem.Tool)?.use?.id?.takeIf { it.isNotBlank() } ?: "thinking-$position"

/**
 * 全段当前活跃的那一个条目：还没回执的调用优先（它真在等外部结果），
 * 全部返回之后才是最后一轮思考。段不在运行时没有活跃条目。
 *
 * 摘要的运行标记与时间线行的 loading 都读这一份判定，因此同一时刻只会有
 * 一处在转；「最后一条就是活跃」这种位置猜测会被按时间重排、待办不回执
 * 和跨轮续想骗到，段内多个思考轮次时尤其明显。
 */
internal fun activityLiveRow(items: List<DisplayItem>, groupRunning: Boolean): ActivityLiveRow? {
    if (!groupRunning) return null
    val pending = toolActivityPendingCallId(items)
    if (pending != null) return ActivityLiveRow.Call(pending)
    val round = thinkingRounds(items, groupRunning).lastOrNull() ?: return null
    return ActivityLiveRow.Thinking(round)
}

/** 段内最后一条没有回执的普通调用（待办更新本就不回结果，不参与）。 */
internal fun toolActivityPendingCallId(items: List<DisplayItem>): String? =
    items.asReversed().firstNotNullOfOrNull { item ->
        (item as? DisplayItem.Tool)
            ?.takeIf { it.result == null && !isTodoUpdateToolName(it.use.name) }
            ?.use?.id?.takeIf { it.isNotBlank() }
    }

/** 时间线上的这一行是不是全段当前活跃的那一个条目。 */
internal fun activityRowIsLive(live: ActivityLiveRow, row: ToolActivityTimelineRow): Boolean =
    when (live) {
        is ActivityLiveRow.Call -> row.item is DisplayItem.Tool && row.item.use.id == live.toolId
        is ActivityLiveRow.Thinking -> row.item === live.round.item
    }

/** 段不在运行时（历史段）没有活跃调用：缺回执只是「未返回」。 */
internal fun toolActivityRunningCallId(items: List<DisplayItem>, groupRunning: Boolean): String? =
    if (!groupRunning) null else toolActivityPendingCallId(items)

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

internal fun activityTimelineEnabled(mode: String, wifiConnected: Boolean): Boolean =
    mode == ServerStore.TRAFFIC_TIMELINE_MODE_ALL || wifiConnected

/** 只有普通命令仍缺结果时才保留执行中状态；不依赖它是不是段的最后一个块。 */
internal fun activityHasPendingCommand(items: List<DisplayItem>): Boolean = items.any { item ->
    item is DisplayItem.Tool && toolActivityKind(item.use) == "run_command" && item.result == null
}

/** 一次调用被服务端首次观察到的时间；旧历史可能没有，绝不补造。 */
internal fun toolActivityOccurredAt(item: DisplayItem.Tool): Instant? =
    (item.use.activity?.occurredAt ?: item.use.occurredAt)
        ?.let { raw -> runCatching { Instant.parse(raw) }.getOrNull() }

/** 段内最新一次真实活动时间（任意类型）；思考轮次有自己的服务端观察时间。 */
internal fun latestActivityOccurredAt(items: List<DisplayItem>): Instant? =
    items.mapNotNull { item ->
        when (item) {
            is DisplayItem.Tool -> toolActivityOccurredAt(item)
            is DisplayItem.Plain -> thinkingOccurredAt(item)
        }
    }.maxOrNull()

/** 段内最后一次真实命令时间：收起态的「运行中 / 已等待」按它计时。 */
internal fun latestCommandOccurredAt(items: List<DisplayItem>, pendingOnly: Boolean = false): Instant? =
    items.asSequence()
        .filterIsInstance<DisplayItem.Tool>()
        .filter { toolActivityKind(it.use) == "run_command" && (!pendingOnly || it.result == null) }
        .mapNotNull(::toolActivityOccurredAt)
        .maxOrNull()

internal fun commandEventClock(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    COMMAND_CLOCK_FORMAT.format(instant.atZone(zone))

internal fun commandWaitLabel(instant: Instant, nowMillis: Long): String =
    elapsedLabel("已等待", instant, nowMillis)

/** 同一份时长措辞，思考轮次与命令共用；负值按 0 起算，绝不显示负数。 */
internal fun elapsedLabel(prefix: String, instant: Instant, nowMillis: Long): String {
    val seconds = ((nowMillis - instant.toEpochMilli()) / 1_000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "$prefix ${seconds} 秒"
        seconds < 3_600 -> "$prefix ${seconds / 60} 分 ${seconds % 60} 秒"
        else -> "$prefix ${seconds / 3_600} 小时 ${(seconds % 3_600) / 60} 分"
    }
}

/** 本轮已经想了多久；没有真实开始时间就不显示，绝不补造。 */
internal fun thinkingElapsedLabel(instant: Instant, nowMillis: Long): String =
    elapsedLabel("已思考", instant, nowMillis)

/** 距离上一次收到思考事件过了多久，超过一分钟才算「无新进展」。 */
internal fun thinkingSilentLabel(
    lastActivityAt: Instant, nowMillis: Long, thresholdMillis: Long = THINKING_SILENCE_MS,
): String? {
    val silence = nowMillis - lastActivityAt.toEpochMilli()
    return if (silence >= thresholdMillis) elapsedLabel("无新进展", lastActivityAt, nowMillis) else null
}

private const val THINKING_SILENCE_MS = 60_000L

/** 活跃条目正是这一轮思考、且正文还没到：收起态显示占位而不是空白。 */
internal fun activityNeedsThinkingPlaceholder(group: ActivityGroup): Boolean {
    val live = activityLiveRow(group.items, group.running) as? ActivityLiveRow.Thinking ?: return false
    return thinkingBlock(live.round.item)?.thinking.isNullOrBlank()
}

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

/** 保留段内位置的同一份筛选：调用按原始时间线排列，同文件多次调用不合并，只去重重传的工具 id。 */
private fun timelineEntries(items: List<DisplayItem>, running: Boolean = true): List<Pair<Int, DisplayItem>> {
    val seen = mutableSetOf<String>()
    val lastPosition = items.lastIndex
    return items.withIndex().map { (index, item) -> index to item }.filter { (position, item) ->
        when (item) {
            is DisplayItem.Tool -> item.use.id.isBlank() || seen.add(item.use.id)
            is DisplayItem.Plain -> keepThinkingRound(item, position, lastPosition, running)
        }
    }
}

/** 流式结果可能在条目数量不变时更新；用内容版本驱动时间线追尾。 */
internal fun toolActivityTimelineRevision(items: List<DisplayItem>): Int = items.fold(1) { revision, item ->
    val value = when (item) {
        is DisplayItem.Tool -> listOf(
            item.use.id,
            item.use.name,
            item.use.input.toString(),
            item.result?.toolUseId,
            item.result?.text,
            item.result?.isError,
            item.result?.truncated,
        )
        is DisplayItem.Plain -> (item.block as? ContentBlock.Thinking)?.thinking
    }
    31 * revision + (value?.hashCode() ?: 0)
}

/**
 * 时间线一行：真实事件时间只来自服务端（调用与思考轮次各读各的字段），
 * [key] 只用稳定身份，重排或流式追加都不会让展开态跳到别的行上。
 */
internal data class ToolActivityTimelineRow(
    val item: DisplayItem,
    val occurredAt: Instant?,
    /** 段内位置：轮次编号与行身份共用，重排不改变它。 */
    val position: Int,
    val key: String,
)

/**
 * 时间线的显示顺序：有真实时间的按时间排，缺时间的按原始顺序跟着相邻条目。
 * 只补位、不补造时间——从上到下时间不会往回跳，缺时间的条目仍然不显示时钟。
 */
internal fun toolActivityTimelineRows(
    items: List<DisplayItem>, running: Boolean = true,
): List<ToolActivityTimelineRow> {
    val entries = timelineEntries(items, running)
    val times = entries.map { (_, item) ->
        when (item) {
            is DisplayItem.Tool -> toolActivityOccurredAt(item)
            is DisplayItem.Plain -> thinkingOccurredAt(item)
        }
    }
    var carried: Instant? = null
    val forward = times.map { time ->
        if (time != null) carried = time
        time ?: carried
    }
    val firstKnown = forward.firstOrNull { it != null }
    val keys = forward.map { it ?: firstKnown ?: Instant.EPOCH }
    return entries.indices.sortedBy { keys[it] }
        .map { index ->
            val (position, item) = entries[index]
            ToolActivityTimelineRow(item, times[index], position, activityRowKey(item, position))
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
internal fun shouldFollowActivityTail(
    menuOpen: Boolean, pinnedToLatest: Boolean, itemCount: Int, inspectingDetail: Boolean = false,
): Boolean = menuOpen && pinnedToLatest && itemCount > 0 && !inspectingDetail

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
 * 思考按轮次计数；「运行中 / 已等待」跟在命令计数后面，
 * 「思考中 / 已思考」跟在思考轮次计数后面——两者互斥，段内只活跃一个。
 */
internal fun toolActivitySummaryParts(
    categories: List<ToolActivityCategory>,
    roundCount: Int,
    thinkingRunning: Boolean,
    thinkingPlaceholder: Boolean,
    leadClock: String?,
    pendingCommand: Boolean,
    waitLabel: String?,
    thinkingElapsed: String? = null,
    thinkingSilent: String? = null,
): List<ToolActivitySummaryPart> {
    val parts = mutableListOf<ToolActivitySummaryPart>()
    leadClock?.let { parts += ToolActivitySummaryPart(it, ToolActivitySummaryTone.Clock, "  ") }
    val rounds = roundCount.coerceAtLeast(0)
    if (rounds > 0 || thinkingPlaceholder) {
        parts += ToolActivitySummaryPart(
            text = if (rounds > 0) "思考 $rounds 次" else "思考中",
            tone = if (rounds > 0) ToolActivitySummaryTone.Text else ToolActivitySummaryTone.AccentPulse,
        )
        if (rounds > 0 && thinkingRunning) {
            parts += ToolActivitySummaryPart("思考中", ToolActivitySummaryTone.AccentPulse)
            thinkingElapsed?.let { parts += ToolActivitySummaryPart(it) }
            thinkingSilent?.let { parts += ToolActivitySummaryPart(it) }
        }
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
 * 收起态的摘要行就是触发点，展开的时间线从摘要行**向下**长出：
 * 摘要行的位置只由它上方的内容决定，展开不会把它推走，上一屏读过的内容也不会被顶出屏幕。
 * 面板高度按所在视口的三分之一收口，默认贴着摘要行显示最新一条，运行中追加的调用继续跟到最新；
 * 用户自己往上翻过就暂停跟随，翻回尾部再恢复。单条点开才请求完整内容。
 */
@Composable
internal fun ToolActivitySummary(
    group: ActivityGroup,
    scope: String,
    /** 所在聊天列表 item key（外层 LazyColumn 的 key）；空串表示不请求底部补偿。 */
    listItemKey: String = "",
    /** 当前段最后一个活动组，只有它有资格按网络策略自动展开。 */
    isLatestActivity: Boolean = true,
) {
    val categories = toolActivityCategories(group.items)
    val rounds = thinkingRounds(group.items, group.running)
    val liveRow = activityLiveRow(group.items, group.running)
    val thinking = rounds.filter { round -> thinkingBlock(round.item)?.thinking?.isNotBlank() == true }
    val thinkingPlaceholder = thinking.isEmpty() && activityNeedsThinkingPlaceholder(group)
    if (categories.isEmpty() && thinking.isEmpty() && !thinkingPlaceholder) return
    val key = cardFoldKey(LocalChatSessionId.current, scope)
    val wifiConnected = rememberWifiConnected()
    val trafficTimelineMode = LocalActivityTrafficTimelineMode.current
    // 网络策略只控制自动展开，手动查看历史不受网络与新旧活动限制。
    val autoTimelineEnabled = activityTimelineEnabled(trafficTimelineMode, wifiConnected)
    var menuOpen by rememberSaveable(key) { mutableStateOf(false) }
    var drawerManuallyToggled by rememberSaveable("$key/live-drawer") { mutableStateOf(false) }
    var inspectedEntries by remember(key) { mutableStateOf(emptySet<String>()) }
    val revealPanel = LocalActivityPanelReveal.current
    LaunchedEffect(autoTimelineEnabled, isLatestActivity, group.running) {
        if (drawerManuallyToggled) return@LaunchedEffect
        // 自动展开的活动结束后收回；用户显式打开/收起的选择保持。
        val nextOpen = autoTimelineEnabled && isLatestActivity && group.running
        // 网络策略驱动的开合不动外层列表：贴底时列表本来就在尾端，用户翻历史时更没有理由被拽走，
        // 这里再补一段滚动就是初始化与流式期间的跳动源。补偿只跟用户自己的点击。
        menuOpen = nextOpen
    }
    // 抽屉展开状态只归卡片自身；系统/顶栏返回不收起它，也不消费页面返回。
    val timeline = toolActivityTimelineRows(group.items, group.running)
    val timelineRevision = toolActivityTimelineRevision(timeline.map { it.item })
    val roundByPosition = rounds.associateBy { it.position }
    val timelineState = rememberLazyListState()
    // 抽屉首次出现就贴到最新一条；用户自己翻离尾部才暂停跟随。
    var pinnedToLatest by rememberSaveable(key) { mutableStateOf(true) }
    // 只有用户自己的滚动决定贴尾状态：程序定位（scrollToItem）不会置 isScrollInProgress。
    LaunchedEffect(timelineState) {
        snapshotFlow { timelineState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) pinnedToLatest = !timelineState.canScrollForward
        }
    }
    LaunchedEffect(timelineState) {
        timelineState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) drawerManuallyToggled = true
        }
    }
    // 展开中追加了新调用就继续跟到最新，绝不打断正在往上翻历史的用户。
    LaunchedEffect(menuOpen, timelineRevision, pinnedToLatest, inspectedEntries) {
        if (shouldFollowActivityTail(menuOpen, pinnedToLatest, timeline.size, inspectedEntries.isNotEmpty())) {
            scrollActivityTimelineToTail(timelineState, timeline.size)
            // 流式追加只在抽屉内部跟尾，不再顺手抬高外层列表；外层视口交给贴底或用户自己。
        }
    }
    val latestActivityAt = latestActivityOccurredAt(group.items)
    val pendingCommandAt = latestCommandOccurredAt(group.items, pendingOnly = true)
    val pendingCommand = group.running && activityHasPendingCommand(group.items)
    val runningCallId = toolActivityRunningCallId(group.items, group.running)
    // 活跃轮次按它自己的服务端观察时间计时；没有真实时间就一个字都不加。
    val liveRoundAt = (liveRow as? ActivityLiveRow.Thinking)?.round?.occurredAt
    var nowMillis by remember(key) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(pendingCommand, pendingCommandAt, liveRoundAt) {
        if ((!pendingCommand || pendingCommandAt == null) && liveRoundAt == null) return@LaunchedEffect
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(ACTIVITY_WAIT_REFRESH_MS)
        }
    }
    val liveRound = (liveRow as? ActivityLiveRow.Thinking)?.round
    val parts = toolActivitySummaryParts(
        categories = categories,
        roundCount = thinking.size,
        thinkingRunning = liveRound != null,
        thinkingPlaceholder = thinkingPlaceholder,
        // 时间列跟着最新一次真实活动走，不再只看最后一条命令。
        leadClock = latestActivityAt?.let { commandEventClock(it) },
        pendingCommand = pendingCommand,
        waitLabel = pendingCommandAt?.let { commandWaitLabel(it, nowMillis) },
        thinkingElapsed = liveRoundAt?.let { thinkingElapsedLabel(it, nowMillis) },
        thinkingSilent = liveRound?.lastActivityAt?.let { thinkingSilentLabel(it, nowMillis) },
    )
    val summary = buildAnnotatedString {
        parts.forEachIndexed { index, part ->
            // 状态词保持稳定可读，持续动效只由左侧标记承载。
            val style = when (part.tone) {
                ToolActivitySummaryTone.Text -> null
                ToolActivitySummaryTone.Accent, ToolActivitySummaryTone.AccentPulse -> SpanStyle(
                    color = WandColors.brand,
                    fontWeight = FontWeight.Medium,
                )
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
    // 透明状态行用字重与状态色区分正文，详情保持原位展开。
    Column(modifier = Modifier.fillMaxWidth()) {
        // 摘要行先画、面板后画：触发点在面板上方，展开按定义向下长，触发点位置天然不变。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (menuOpen) "收起工具时间线" else "查看工具时间线",
                ) {
                    menuOpen = !menuOpen
                    drawerManuallyToggled = true
                    inspectedEntries = emptySet()
                    // 手动查看历史从上次位置开始，只有当前运行组才跟到最新。
                    if (menuOpen) pinnedToLatest = group.running
                    if (listItemKey.isNotBlank()) revealPanel(listItemKey)
                }
                .semantics { stateDescription = if (menuOpen) "已展开" else "已收起" }
                .padding(start = ACTIVITY_SUMMARY_START, end = ACTIVITY_SUMMARY_END, top = 11.dp, bottom = 11.dp),
        ) {
            // 全段唯一的动态 loading 就在这行：展开与否都不让位，折叠时它还兼作折叠触发点。
            if (group.running) {
                ToolActivityMark(running = true, color = WandColors.brand)
            }
            Text(
                summary,
                fontSize = if (group.running) 12.sp else 11.sp,
                fontFamily = if (group.running) FontFamily.Default else FontFamily.Monospace,
                fontWeight = if (group.running) FontWeight.Medium else FontWeight.Normal,
                lineHeight = 18.sp,
                color = if (group.running) WandColors.textSecondary else WandColors.textMuted,
                maxLines = if (group.running) 2 else 1,
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
        ToolActivityReveal(menuOpen, from = Alignment.Top) {
            LazyColumn(
                state = timelineState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = activityPanelMaxHeight(activityPanelViewportHeight()))
                    .padding(horizontal = ACTIVITY_PANEL_PADDING, vertical = 4.dp),
            ) {
                itemsIndexed(
                    timeline,
                    key = { _, row -> row.key },
                ) { index, row ->
                    ToolActivityEntryRow(
                        item = row.item,
                        occurredAt = row.occurredAt,
                        round = roundByPosition[row.position],
                        rowKey = row.key,
                        storageKey = "$key/${row.key}",
                        runningCallId = runningCallId,
                        live = liveRow != null && activityRowIsLive(liveRow, row),
                        menuOpen = menuOpen,
                        isFirst = index == 0,
                        isLast = index == timeline.lastIndex,
                        onInspect = { entryKey, open ->
                            inspectedEntries = if (open) inspectedEntries + entryKey else inspectedEntries - entryKey
                            // 已开始阅读的时间线不因任务完成而自动收起，也不继续抢滚动位置。
                            drawerManuallyToggled = true
                            pinnedToLatest = false
                        },
                    )
                }
            }
        }
    }
}

/** 所在聊天视口高度（容器注入）；拿不到时回落到整屏高度，两者都按三分之一收口。 */
@Composable
private fun activityPanelViewportHeight(): Dp {
    val viewportPx = LocalChatViewportHeightPx.current
    if (viewportPx <= 0) return LocalConfiguration.current.screenHeightDp.dp
    return with(LocalDensity.current) { viewportPx.toDp() }
}

/** 竖线只连接首末状态点；单条活动不画贯穿整张卡片的长线。 */
internal fun activityTimelineRailBounds(
    rowHeight: Float,
    dotCenterY: Float,
    isFirst: Boolean,
    isLast: Boolean,
): Pair<Float, Float> {
    val height = rowHeight.coerceAtLeast(0f)
    val center = dotCenterY.coerceIn(0f, height)
    return (if (isFirst) center else 0f) to (if (isLast) center else height)
}

@Composable
private fun ToolActivityEntryRow(
    item: DisplayItem,
    /** 服务端给出的真实发生时间；没有真实时间的条目不带时钟。 */
    occurredAt: Instant?,
    /** 这一行属于第几轮推理；调用行为 null。 */
    round: ThinkingRound?,
    rowKey: String,
    storageKey: String,
    runningCallId: String?,
    /** 全段唯一活跃条目是否就是这一行。 */
    live: Boolean,
    menuOpen: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onInspect: (String, Boolean) -> Unit,
) {
    val tool = item as? DisplayItem.Tool
    val thinking = ((item as? DisplayItem.Plain)?.block as? ContentBlock.Thinking)?.thinking
    val status = tool?.let { toolActivityEntryStatus(ToolActivityEntry(listOf(it)), runningCallId) }
    // 多轮时带 k/N，读者才知道自己在第几轮；单轮只说「思考过程」。
    val label = tool?.let { toolActivityItemLabel(it.use) } ?: round?.label ?: "思考过程"
    // 推理轮次只有一行摘录，用自己的紧凑行高：一段里几轮思考才能同屏各自成行，
    // 不再挤在调用的两行骨架里看起来像被折叠成一条。
    val compact = tool == null
    val clock = occurredAt?.let { commandEventClock(it) }
    var open by rememberSaveable(storageKey) { mutableStateOf(false) }
    LaunchedEffect(menuOpen) { if (!menuOpen) open = false }
    val motion = !reduceMotionEnabled()
    val stateColor = when {
        status == "失败" -> WandColors.danger
        live -> WandColors.brand
        status == "完成" -> WandColors.success
        else -> WandColors.textMuted
    }
    val highlight by animateColorAsState(
        targetValue = when {
            status == "失败" -> WandColors.danger.copy(alpha = 0.05f)
            live || open -> WandColors.brand.copy(alpha = 0.05f)
            else -> Color.Transparent
        },
        animationSpec = WandMotion.respectMotion(motion, WandMotion.tweenFast()),
        label = "activityEntryHighlight",
    )
    // 点固定在首行文字中心，不随摘录/详情的高度向下漂移。竖线只占左侧 12dp 的槽位。
    val dotTop = with(LocalDensity.current) {
        ((ACTIVITY_ROW_LINE_HEIGHT.toDp() - ACTIVITY_DOT_BOX) / 2).coerceAtLeast(0.dp)
    }
    val dotCenterY = ACTIVITY_ROW_VERTICAL + dotTop + ACTIVITY_DOT_BOX / 2
    val railColor = WandColors.border
    Column(modifier = Modifier.fillMaxWidth().drawBehind {
        val x = ACTIVITY_RAIL_X.toPx()
        val (start, end) = activityTimelineRailBounds(size.height, dotCenterY.toPx(), isFirst, isLast)
        if (end > start) drawLine(railColor, Offset(x, start), Offset(x, end), strokeWidth = 1.dp.toPx())
    }) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(ACTIVITY_DOT_TO_CONTENT),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (compact) ACTIVITY_THINKING_ROW_MIN_HEIGHT else ACTIVITY_CALL_ROW_MIN_HEIGHT)
                .background(highlight)
                .clickable(role = Role.Button, onClickLabel = if (open) "收起$label" else "查看$label") {
                    open = !open
                    onInspect(storageKey, open)
                }
                .semantics {
                    // 右侧不再画状态字样：状态色与运行动效由左侧标记承载，语义里保留同一份结果。
                    stateDescription = activityEntryStateDescription(open, status, live)
                }
                .padding(start = ACTIVITY_ROW_START, end = ACTIVITY_ROW_END,
                    top = ACTIVITY_ROW_VERTICAL, bottom = ACTIVITY_ROW_VERTICAL),
        ) {
            ToolActivityMark(
                // 摘要行已经在转了；行只表达状态（颜色），不再来一份动态 loading。
                running = false,
                color = stateColor,
                modifier = Modifier.padding(top = dotTop),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (clock != null) Text(clock, fontSize = 10.sp, lineHeight = ACTIVITY_ROW_LINE_HEIGHT,
                        fontFamily = FontFamily.Monospace, color = WandColors.textMuted)
                    Text(label, fontSize = 11.5.sp, lineHeight = ACTIVITY_ROW_LINE_HEIGHT,
                        color = if (open || live) WandColors.textPrimary else WandColors.textSecondary,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                }
                // 摘录行数按内容给：调用留两行（输入 + 结果），推理轮次只有一行，
                // 展开与结果迟到都不改变触发区尺寸。
                ToolActivityExcerpt(tool?.let { toolInputCardPreview(it.use) }
                    ?: thinking.orEmpty().replace(Regex("\\s+"), " ").take(180), WandColors.textPrimary)
                if (tool != null) ToolActivityExcerpt(toolResultCardPreview(tool.result).orEmpty(),
                    if (tool.result?.isError == true) WandColors.danger else WandColors.textMuted)
            }
            ExpandChevron(expanded = open, tint = WandColors.textMuted, size = 14.dp,
                modifier = Modifier.padding(top = dotTop), contentDescription = null)
        }
        ToolActivityReveal(open && menuOpen) {
            Column(modifier = Modifier.fillMaxWidth()
                .padding(start = ACTIVITY_CONTENT_START, end = ACTIVITY_ROW_END, bottom = 8.dp)) {
                if (tool != null) ToolActivitySingleDetail(item = tool, open = open && menuOpen)
                else SelectionContainer {
                    Text(thinking?.takeIf(String::isNotBlank) ?: "思考内容尚未到达。",
                        fontSize = 11.sp, lineHeight = 18.sp, color = WandColors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun ToolActivityExcerpt(text: String, color: Color) {
    Text(text, color = color, fontSize = 11.sp, lineHeight = ACTIVITY_ROW_LINE_HEIGHT,
        fontFamily = FontFamily.Monospace, minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * 活动状态标记：静态态是一枚小圆点，运行态在同**一个实例**里长成九点流动标记
 * （位置展开 + 半径变化 + 相位流动），不是 `if/else` 换两套图标。
 * reduce-motion 下不流动、不展开，只保留状态色。
 *
 * 只有摘要行传 `running = true`：全段一个动态 loading 就够了，时间线行只用
 * 颜色表达状态，否则同屏两个流动标记会把「在跑」读成两件事。
 */
@Composable
private fun ToolActivityMark(running: Boolean, color: Color, modifier: Modifier = Modifier) {
    val motion = !reduceMotionEnabled()
    val spread by animateFloatAsState(
        targetValue = if (running) 1f else 0f,
        animationSpec = WandMotion.respectMotion(motion, WandMotion.morph()),
        label = "activityMarkSpread",
    )
    // 持续流动只在真实运行态出现：历史条目停在静态点上。
    val phase = if (running && motion) {
        val transition = rememberInfiniteTransition(label = "activityMarkFlow")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = WandMotion.breath(),
            label = "activityMarkPhase",
        )
        value
    } else 0f
    val tint by animateColorAsState(
        targetValue = color,
        animationSpec = WandMotion.respectMotion(motion, WandMotion.tweenFast()),
        label = "activityMarkTint",
    )
    Canvas(modifier.size(ACTIVITY_DOT_BOX)) {
        val step = size.width / 3f
        val center = Offset(size.width / 2f, size.height / 2f)
        val spreadRadius = ACTIVITY_DOT_SIZE.toPx() / 6f
        val dotRadius = ACTIVITY_DOT_SIZE.toPx() / 3f
        val radius = dotRadius + (spreadRadius - dotRadius) * spread
        for (row in 0..2) for (column in 0..2) {
            val target = Offset(step * (column + 0.5f), step * (row + 0.5f))
            val position = center + (target - center) * spread
            val alpha = if (running && motion) {
                val wave = (1f - abs(phase - column / 2f)).coerceIn(0f, 1f)
                0.3f + 0.7f * wave
            } else 1f
            drawCircle(
                color = tint.copy(alpha = alpha),
                radius = radius,
                center = position,
            )
        }
    }
}

/** 只有正在执行的那一条算运行中；没有回执的旧调用只是「未返回」。 */
internal fun toolActivityCallRunning(item: DisplayItem.Tool, runningCallId: String?): Boolean =
    runningCallId != null && item.use.id == runningCallId

internal fun toolActivityEntryStatus(entry: ToolActivityEntry, runningCallId: String?): String = when {
    entry.calls.any { it.result?.isError == true } -> "失败"
    entry.calls.all { it.result != null || isTodoUpdateToolName(it.use.name) } -> "完成"
    entry.calls.any { toolActivityCallRunning(it, runningCallId) } -> "运行中"
    else -> "未返回"
}

/** 右侧不再画状态字样：同一份结果留在语义里，颜色与动效承担视觉表达。 */
internal fun activityEntryStateDescription(open: Boolean, status: String?, running: Boolean): String =
    listOfNotNull(
        if (open) "已展开" else "已收起",
        if (running) "运行中" else status,
    ).joinToString("，")

@Composable
private fun ToolActivitySingleDetail(item: DisplayItem.Tool, open: Boolean) {
    val use = item.use
    if (toolActivityOpensFile(use)) {
        ToolActivityFileAction(use, open)
        return
    }
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

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 重试/刷新只更新状态槽，不用加载圈替掉已经读到的参数与正文。
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 36.dp)) {
            Text(error ?: if (loading) "加载详情…" else if (detail != null) "调用详情" else "此调用暂时无法加载详情",
                fontSize = 11.sp, color = if (error != null) WandColors.danger else WandColors.textMuted,
                modifier = Modifier.weight(1f))
            if (error != null) TextButton(enabled = !loading, onClick = { retry += 1 }) { Text("重试") }
        }
        detail?.let { loaded ->
            ToolInputBody(loaded.input)
            if (loaded.result != null) ToolResultBody(loaded.result, showSectionLabel = true)
            else Text(if (result == null) "本次调用尚未返回结果" else "正在更新工具输出…",
                fontSize = 11.sp, color = WandColors.textMuted)
        }
    }
}

/** 外层面板从摘要行向下长出，详情从自己的条目向下长出；收起走同一条曲线倒放。 */
@Composable
private fun ToolActivityReveal(
    visible: Boolean,
    from: Alignment.Vertical = Alignment.Top,
    content: @Composable () -> Unit,
) {
    val motion = !reduceMotionEnabled()
    AnimatedVisibility(
        visible = visible,
        enter = if (motion) fadeIn(WandMotion.tweenFast()) +
            expandVertically(expandFrom = from, animationSpec = WandMotion.tweenNormal())
            else fadeIn(androidx.compose.animation.core.snap()),
        exit = if (motion) fadeOut(WandMotion.tweenFast()) +
            shrinkVertically(shrinkTowards = from, animationSpec = WandMotion.tweenNormal())
            else fadeOut(androidx.compose.animation.core.snap()),
    ) { content() }
}
