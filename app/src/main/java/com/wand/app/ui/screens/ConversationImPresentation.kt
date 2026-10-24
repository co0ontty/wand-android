package com.wand.app.ui.screens

import com.wand.app.data.ContentBlock
import com.wand.app.data.ToolUseSemantic
import com.wand.app.data.ConversationTarget
import com.wand.app.data.ConversationInstance
import com.wand.app.data.ConversationTask
import com.wand.app.data.ConversationTurn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 归档任务的统一文案：不再报在跑/等你，否则和未归档任务读起来一样。 */
internal const val ConversationArchivedLabel = "已归档"

/** IM 正文不是执行摘要：普通清单和段落完整展示，仅为超长历史保留有界预览。 */
internal fun conversationNeedsCollapse(text: String): Boolean =
    text.length > 3000 || text.lineSequence().take(49).count() > 48

internal fun conversationCollapsedPreview(text: String): String {
    if (!conversationNeedsCollapse(text)) return text
    return text.lineSequence().take(24).joinToString("\n").take(1800).trimEnd() + "…"
}

/** 归档档位：全部 / 未归档 / 已归档；和侧栏「在跑」是同一套顶部筛选形态。 */
internal enum class ConversationListTier(val storageValue: String, val label: String) {
    All("all", "全部"),
    Active("active", "未归档"),
    Archived("archived", "已归档"),
    ;

    companion object {
        fun of(value: String?): ConversationListTier = entries.firstOrNull { it.storageValue == value } ?: All
    }
}

internal enum class ConversationListEmptyState(val title: String, val explanation: String, val action: String) {
    Empty("还没有对话", "从通讯录选择员工开始对话，或新建群聊。", "新建群聊"),
    Search("未找到匹配的对话", "没有匹配当前关键词的对话或任务。清空搜索即可恢复当前列表。", "清空搜索"),
    Filtered("此筛选下暂无对话", "可以切换其他分类，或查看全部对话。", "查看全部对话"),
}

internal fun conversationListEmptyState(query: String, tier: ConversationListTier): ConversationListEmptyState = when {
    query.isNotBlank() -> ConversationListEmptyState.Search
    tier != ConversationListTier.All -> ConversationListEmptyState.Filtered
    else -> ConversationListEmptyState.Empty
}

/** 归档的对话：群聊解散后归档，或它的任务已经被归档。 */
internal fun isConversationArchived(item: ConversationInstance): Boolean =
    item.dissolvedAt != null || item.tasks.any { it.task.status == "archived" }

/** 筛选与搜索共用一个入口：先按归档档筛，再按标题、预览与任务名匹配。 */
internal fun filterConversationList(
    items: List<ConversationInstance>,
    tier: ConversationListTier,
    query: String,
): List<ConversationInstance> {
    val scoped = items.filter { item -> when (tier) {
        ConversationListTier.All -> true
        ConversationListTier.Active -> !isConversationArchived(item)
        ConversationListTier.Archived -> isConversationArchived(item)
    } }
    val term = query.trim()
    if (term.isEmpty()) return scoped
    return scoped.filter { item ->
        (item.title + item.preview + item.tasks.joinToString { it.task.title }).contains(term, ignoreCase = true)
    }
}

/** Read-only header context; a historical failed run must not mask a newer completed run. */
internal fun conversationIdentityStatus(detail: ConversationInstance?): String {
    if (detail == null) return "正在加载对话"
    if (detail.deleting) return "正在删除对话"
    detail.external?.let { return it.statusText }
    if (detail.dissolvedAt != null) return "群聊已解散"
    if (!detail.unavailableReason.isNullOrBlank()) return "当前对象暂不可用"
    val runs = detail.tasks.flatMap { it.runs }
    if (runs.any { it.status == "awaiting_approval" }) return "等待你的确认"
    if (runs.any { it.status == "waiting_user" }) return "等待你的回复"
    if (runs.any { it.status == "running" || it.status == "starting" }) return "任务执行中"
    if (runs.maxByOrNull { it.updatedAt.orEmpty() }?.status == "failed") return "最近一轮失败"
    return if (detail.kind == "group") detail.team?.let { "${it.members.size} 位员工 · 可派发任务" } ?: "群聊 · 可派发任务"
        else "可以继续对话"
}

internal fun conversationRunLabel(status: String): String = when (status) {
    "running" -> "进行中"; "awaiting_approval" -> "等你批准"; "waiting_user" -> "等你回复";
    "done" -> "已完成"; "failed" -> "失败"; "stopped" -> "已停止"; else -> "待开工"
}

internal fun conversationRunActive(status: String): Boolean =
    status in listOf("running", "awaiting_approval", "waiting_user")

/** 列表任务行的短状态：归档任务不再报在跑/等你。 */
internal fun conversationTaskStatusLabel(task: ConversationTask): String =
    if (task.task.status == "archived") ConversationArchivedLabel
    else conversationRunLabel(task.runs.firstOrNull()?.status.orEmpty())

/** 列表只显示真实消息时间到分钟；日期与无效时间规则沿用原生聊天时钟。 */
internal fun conversationListClock(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String =
    formatChatClock(iso, zone).substringBeforeLast(":", missingDelimiterValue = "")

internal fun conversationBubbleClock(turn: ConversationTurn, zone: ZoneId = ZoneId.systemDefault()): String =
    conversationLocalTime(turn.completedAt ?: turn.createdAt, zone)?.format(DateTimeFormatter.ofPattern("HH:mm")).orEmpty()

private fun conversationLocalTime(iso: String?, zone: ZoneId) =
    runCatching { Instant.parse(iso).atZone(zone) }.getOrNull()

internal fun conversationDay(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String =
    conversationLocalTime(iso, zone)?.format(DateTimeFormatter.ofPattern("yyyy年M月d日")).orEmpty()

internal fun joinsConversationBubble(previous: ConversationTurn?, next: ConversationTurn?, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    if (previous == null || next == null || previous.notice || next.notice || previous.role != next.role) return false
    if (previous.role != "user" && (previous.author?.id.isNullOrBlank() || previous.author?.id != next.author?.id)) return false
    if (previous.conversationTarget?.runId != next.conversationTarget?.runId) return false
    val gap = runCatching { Instant.parse(next.createdAt).toEpochMilli() - Instant.parse(previous.createdAt).toEpochMilli() }.getOrNull() ?: return false
    return gap in 0 until 300_000 && conversationDay(previous.createdAt, zone) == conversationDay(next.createdAt, zone)
}

/** Polling/editing/history are not new arrivals; only an ordered overlapping tail is eligible. */
internal fun appendedConversationKeys(previous: List<String>?, next: List<String>): List<String> {
    if (previous == null) return emptyList()
    if (previous.isEmpty()) return next
    val end = next.indexOf(previous.last())
    if (end < 0) return emptyList()
    val overlap = minOf(end + 1, previous.size)
    return if (previous.takeLast(overlap) == next.subList(end + 1 - overlap, end + 1)) next.drop(end + 1) else emptyList()
}

internal fun conversationInitials(title: String): String {
    val words = title.trim().split(Regex("\\s+"))
    fun first(value: String, count: Long): String = value.codePoints().limit(count).toArray().let { String(it, 0, it.size) }
    return (if (words.size > 1) words.take(2).joinToString("") { first(it, 1) } else first(words.first(), 2)).uppercase(Locale.ROOT).ifBlank { "群" }
}

/** 只用服务端来源身份绑定工具操作；任务 relay 不能冒充执行成员。 */
internal fun conversationTurnSessionId(turn: ConversationTurn, detail: ConversationInstance?): String? =
    turn.sessionLink?.sessionId?.takeIf { it.isNotBlank() } ?: turn.author?.sessionId?.takeIf { it.isNotBlank() }
        ?: detail?.communicationSessionId?.takeIf { turn.conversationTarget == null && it.isNotBlank() }

internal fun conversationSessionToolResults(detail: ConversationInstance): Map<String, Map<String, ContentBlock.ToolResult>> =
    detail.messages.groupBy { conversationTurnSessionId(it, detail).orEmpty() }
        .mapValues { (_, turns) -> conversationToolResults(turns) }

/** 当前通信、运行中的成员及未回答的提问才接实时协议，不为完整历史逐条建连接。 */
internal fun conversationInteractiveSessions(detail: ConversationInstance?): Set<String> = buildSet {
    if (detail == null || detail.dissolvedAt != null || detail.deleting) return@buildSet
    detail.communicationSessionId?.takeIf { it.isNotBlank() }?.let(::add)
    detail.messages.filter { it.sessionPreview?.status in setOf("starting", "running", "waiting_user") }
        .mapNotNull { it.sessionLink?.sessionId }.forEach(::add)
    detail.runDetails.filter { conversationRunActive(it.run.status) }.forEach { run ->
        run.steps.filter { it.status == "running" || run.memberStates[it.sessionId] in setOf("needs_permission", "needs_input") }
            .mapNotNull { it.sessionId?.takeIf(String::isNotBlank) }.forEach(::add)
    }
    val results = conversationSessionToolResults(detail)
    detail.messages.forEach { turn ->
        val sessionId = conversationTurnSessionId(turn, detail) ?: return@forEach
        if (turn.content.filterIsInstance<ContentBlock.ToolUse>().any {
            (it.semantic is ToolUseSemantic.QuestionRequest || it.name == "AskUserQuestion") && results[sessionId]?.containsKey(it.id) != true
        }) add(sessionId)
    }
}

internal fun conversationTaskSummary(detail: ConversationInstance?): String {
    val tasks = detail?.tasks.orEmpty()
    val active = tasks.filter { it.task.status != "archived" }.flatMap { it.runs }.filter { conversationRunActive(it.status) }
    val waiting = active.count { it.status in setOf("awaiting_approval", "waiting_user") }
    return when {
        waiting > 0 -> "$waiting 项等你处理 · ${tasks.size} 项任务"
        active.isNotEmpty() -> "${active.size} 项进行中 · ${tasks.size} 项任务"
        tasks.isNotEmpty() -> "${tasks.size} 项任务"
        else -> "群任务"
    }
}

/** 阅读筛选不改变接收人；只有明确回复操作才给出有效的任务目标。 */
internal fun conversationReplyTarget(task: ConversationTask): ConversationTarget? =
    task.runs.firstOrNull { conversationRunActive(it.status) }?.let { ConversationTarget(task.task.id, it.id) }

internal fun mentionConversationLeader(draft: String, name: String): String {
    val mention = "@${name.trim()}"
    if (name.isBlank() || draft.lineSequence().any { it.trimStart().startsWith("$mention ") || it.trim() == mention }) return draft
    return if (draft.isBlank()) "$mention " else "$mention $draft"
}
