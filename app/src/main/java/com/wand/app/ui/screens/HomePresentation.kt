package com.wand.app.ui.screens

import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.activityStatus
import com.wand.app.data.workspaceProviderLabel
import com.wand.app.ui.SessionTitleStore
import com.wand.app.ui.withLiveTitle
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 首页（工作台）的纯展示层。
 *
 * 首页的产品定位是「一眼看清 有多少在跑 / 有多少等我 / 最近开了什么」，而不是把目录树原样铺开。
 * 因此这里只做三件事：把会话折叠成运行脉冲、把「需要我处理」挑出来、把时间折成人话。
 * 所有函数都是纯函数，方便单测，UI 层不再自己散落 when(status) 映射。
 */

/** 会话在首页关心的三态：在跑、等你、安静。其余状态一律归 Quiet。 */
internal enum class HomeSessionPulse { Running, NeedsYou, Quiet }

internal fun homeSessionPulse(status: String?): HomeSessionPulse = when (status) {
    // reconnecting 仍在推进任务，只是链路抖动，归到「在跑」而不是「等你」。
    "running", "thinking", "reconnecting" -> HomeSessionPulse.Running
    // permission / waiting-input 是真的在等用户；failed 也需要人去看一眼。
    "permission", "waiting-input", "failed" -> HomeSessionPulse.NeedsYou
    else -> HomeSessionPulse.Quiet
}

/**
 * 会话的真实脉冲。权限态只在 WS 事件里（工作区轮询返回的摘要没有 pendingEscalation），
 * 所以先看实时 overlay，再退回由状态派生的活动态。
 */
internal fun sessionPulse(session: WorkspaceSessionSummary): HomeSessionPulse {
    if (SessionTitleStore.permissionBlockedOf(session.id) == true) return HomeSessionPulse.NeedsYou
    return homeSessionPulse(session.withLiveTitle().activityStatus())
}

internal fun sessionNeedsYou(session: WorkspaceSessionSummary): Boolean =
    sessionPulse(session) == HomeSessionPulse.NeedsYou

internal fun sessionIsRunning(session: WorkspaceSessionSummary): Boolean =
    sessionPulse(session) == HomeSessionPulse.Running

/** 目录/任务上的一行摘要：运行中、等你、其他。工作区标题靠这三个数就够了。 */
internal data class HomeSessionCounts(
    val running: Int = 0,
    val needsYou: Int = 0,
) {
    val total: Int get() = running + needsYou
}

internal fun homeSessionCounts(sessions: List<WorkspaceSessionSummary>): HomeSessionCounts {
    var running = 0
    var needsYou = 0
    for (session in sessions) {
        when (sessionPulse(session)) {
            HomeSessionPulse.Running -> running += 1
            HomeSessionPulse.NeedsYou -> needsYou += 1
            HomeSessionPulse.Quiet -> Unit
        }
    }
    return HomeSessionCounts(running = running, needsYou = needsYou)
}

/**
 * 首页状态总览：把整棵目录树压成三个数字（在跑 / 等你 / 一共多少会话）。
 * 调用方传的应该是列表真正渲染的那批分组（`directoryTreeGroups` 的结果），
 * 否则顶部的数字会和下面的卡片对不上。
 */
internal data class HomeOverview(
    val running: Int,
    val needsYou: Int,
    val sessions: Int,
) {
    val isQuiet: Boolean get() = running == 0 && needsYou == 0
}

internal fun homeOverview(groups: List<TaskDirectoryGroup>): HomeOverview {
    var running = 0
    var needsYou = 0
    var sessions = 0
    for (group in groups) {
        val groupSessions = group.sessions()
        sessions += groupSessions.size
        val counts = homeSessionCounts(groupSessions)
        running += counts.running
        needsYou += counts.needsYou
    }
    return HomeOverview(running = running, needsYou = needsYou, sessions = sessions)
}

private fun TaskDirectoryGroup.sessions(): List<WorkspaceSessionSummary> =
    tasks.flatMap { it.sessions } + standaloneSessions

/**
 * 「只看要处理的」过滤：空目录、空任务一起消失，否则筛完只剩一排空壳标题
 * 比不筛还难读。任务保留，但只留需要处理的会话。
 */
internal fun attentionOnlyGroups(groups: List<TaskDirectoryGroup>): List<TaskDirectoryGroup> =
    groups.mapNotNull { group ->
        val tasks = group.tasks.mapNotNull { task ->
            val sessions = task.sessions.filter(::sessionNeedsYou)
            if (sessions.isEmpty()) null else task.copy(sessions = sessions, totalSessions = sessions.size)
        }
        val standalone = group.standaloneSessions.filter(::sessionNeedsYou)
        if (tasks.isEmpty() && standalone.isEmpty()) null
        else group.copy(tasks = tasks, standaloneSessions = standalone)
    }

/**
 * 拖动排序：把 [from] 位置的元素搬到 [to] 位置，其余元素保持相对顺序。
 *
 * 单独抽出来是因为「拖到哪一格」和「列表怎么变」必须分开：手势只报目标索引，
 * 顺序变化在这里一次算清，UI 与保存走的都是同一份结果。
 */
internal fun <T> movedItem(items: List<T>, from: Int, to: Int): List<T> {
    if (from == to) return items
    if (from !in items.indices || to !in items.indices) return items
    return items.toMutableList().apply { add(to, removeAt(from)) }
}

/** 组 id 顺序：拖动落点 → 持久化用的一串 id。 */
internal fun groupIdsInOrder(groups: List<TaskDirectoryGroup>): List<String> =
    groups.map { it.id }

/**
 * 服务端返回的顺序可能与本地待保存的顺序不一致（保存请求还在路上时轮询到了旧顺序）。
 * 有本地待保存顺序时以本地为准，避免卡片「弹回原位再跳回来」。
 */
internal fun applyPendingGroupOrder(
    groups: List<TaskDirectoryGroup>,
    pending: List<String>?,
): List<TaskDirectoryGroup> {
    if (pending.isNullOrEmpty()) return groups
    val position = pending.withIndex().associate { (index, id) -> id to index }
    // 稳定排序：不在待保存顺序里的组保持服务端给的相对顺序，接在后面。
    return groups.sortedBy { position[it.id] ?: Int.MAX_VALUE }
}

/**
 * 首页搜索：命中工作区名 / 任务名 / 会话标题 / provider / 目录。三种命中粒度不同：
 * - 工作区名命中 → 整个工作区原样保留（“我要看这个项目”）；
 * - 任务名命中 → 任务保留、会话全留（“我要看这个任务下的东西”）；
 * - 只命中会话 → 任务保留，但只留命中的会话。
 * 命中不到的工作区、任务、会话一律从列表里消失，否则搜完还是一片没关的东西。
 */
internal fun homeSearchGroups(groups: List<TaskDirectoryGroup>, query: String): List<TaskDirectoryGroup> {
    val q = query.trim()
    if (q.isEmpty()) return groups
    return groups.mapNotNull { group ->
        if (group.workspaceName.contains(q, ignoreCase = true)) return@mapNotNull group
        val tasks = group.tasks.mapNotNull { task ->
            val taskHit = task.name.contains(q, ignoreCase = true)
            val sessions = if (taskHit) task.sessions else task.sessions.filter { it.matchesHomeQuery(q) }
            if (!taskHit && sessions.isEmpty()) return@mapNotNull null
            task.copy(sessions = sessions, totalSessions = sessions.size)
        }
        val standalone = group.standaloneSessions.filter { it.matchesHomeQuery(q) }
        if (tasks.isEmpty() && standalone.isEmpty()) {
            null
        } else {
            group.copy(tasks = tasks, standaloneSessions = standalone)
        }
    }
}

private fun WorkspaceSessionSummary.matchesHomeQuery(query: String): Boolean =
    listOfNotNull(title, provider, providerSearchLabel(provider), cwd).any {
        it.contains(query, ignoreCase = true)
    }

/** provider 既按原始 id（claude）命中，也按界面上的名字（Claude）命中。 */
private fun providerSearchLabel(provider: String?): String? =
    provider?.takeIf { it.isNotBlank() }?.let(::workspaceProviderLabel)

/**
 * 会话卡第二行的形态描述。provider 由卡片左侧的品牌标承担，这里只说形态，
 * 避免同一个信息在一行里说两遍。
 */
internal fun sessionFormLabel(session: WorkspaceSessionSummary): String = when {
    session.sessionKind == "pty" && !session.provider.isNullOrBlank() && session.provider != "shell" ->
        "PTY 终端"
    session.sessionKind == "pty" -> "空白终端"
    else -> "结构化"
}

/**
 * 会话时间折成人话。首页是「最近发生了什么」的视图，精确到秒的绝对时间没有意义。
 * 解析失败返回 null，调用方直接不渲染这一项，而不是显示一个假时间。
 */
internal fun sessionRelativeTime(
    iso: String?,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String? {
    val raw = iso?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val start = runCatching { Instant.parse(raw) }.getOrNull() ?: return null
    val deltaMillis = nowMillis - start.toEpochMilli()
    // 时钟漂移下（设备比服务端慢）可能算出负数，按「刚刚」处理，不显示「-3 分钟前」。
    if (deltaMillis < 0L) return "刚刚"
    val delta = Duration.ofMillis(deltaMillis)
    val minutes = delta.toMinutes()
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 60 * 24 -> "${delta.toHours()} 小时前"
        minutes < 60 * 24 * 2 -> "昨天"
        minutes < 60 * 24 * 7 -> "${delta.toDays()} 天前"
        else -> LocalDate.ofInstant(start, zone).let { "${it.monthValue} 月 ${it.dayOfMonth} 日" }
    }
}

/**
 * 会话元信息行：`结构化 · 12 分钟前`。
 * 时间拿不到时只留类型，不留空的分隔符。
 */
internal fun sessionMetaLine(session: WorkspaceSessionSummary, nowMillis: Long): String {
    val kind = sessionFormLabel(session)
    val time = sessionRelativeTime(session.startedAt, nowMillis) ?: return kind
    return "$kind · $time"
}

/** 摘要片段的语义色：让数字自己带颜色，避免再挂一个重复的胶囊。 */
internal enum class HomeSummaryTone { Muted, Running, NeedsYou }

internal data class HomeSummarySegment(val text: String, val tone: HomeSummaryTone)

/**
 * 工作区 / 任务标题下的一行摘要：`3 个会话 · 1 运行中 · 1 待处理`。
 * 分段而不是整串字符串，是为了让「运行中 / 待处理」带语义色，
 * 不必再在标题右侧重复挂一个活动胶囊。
 */
internal fun workspaceSummarySegments(
    sessionCount: Int,
    counts: HomeSessionCounts,
): List<HomeSummarySegment> = buildList {
    add(HomeSummarySegment("$sessionCount 个会话", HomeSummaryTone.Muted))
    if (counts.running > 0) add(HomeSummarySegment("${counts.running} 运行中", HomeSummaryTone.Running))
    if (counts.needsYou > 0) add(HomeSummarySegment("${counts.needsYou} 待处理", HomeSummaryTone.NeedsYou))
}

internal fun workspaceSummaryLine(sessionCount: Int, counts: HomeSessionCounts): String =
    workspaceSummarySegments(sessionCount, counts).joinToString(" · ") { it.text }

/** 任务块的摘要：先报会话数，已完成 / 活动量按需追加。 */
internal fun taskSummarySegments(
    sessionCount: Int,
    done: Boolean,
    counts: HomeSessionCounts,
): List<HomeSummarySegment> = buildList {
    add(HomeSummarySegment("$sessionCount 个会话", HomeSummaryTone.Muted))
    if (done) add(HomeSummarySegment("已完成", HomeSummaryTone.Muted))
    if (counts.running > 0) add(HomeSummarySegment("${counts.running} 运行中", HomeSummaryTone.Running))
    if (counts.needsYou > 0) add(HomeSummarySegment("${counts.needsYou} 待处理", HomeSummaryTone.NeedsYou))
}
