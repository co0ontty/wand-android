package com.wand.app.data

/**
 * 任务窗口布局对齐工具（纯函数，对齐 Web window-layout.ts 的 reconcile/add）。
 *
 * 第一批 Android 单栏一次只承载一个会话，但写回布局时必须原样保留 Web 的 split /
 * editor / preview / 未知 tab，不能压扁或清空。这里只做两件事：
 *
 * 1. [reconcileTaskWindowLayout]：过滤已删除会话的引用、每个有效会话最多出现一次、
 *    缺失会话补齐成独立 window。保留 split、ratio、非会话 tab 与未知类型。
 * 2. [addSessionWindow]：新会话创建一个独立单窗格 window 并设为活动。
 *
 * 与 Web 实现的关键差异：Android 不在每次点击切换可见会话时改写 activeWindowId —— 只有
 * 新建工作窗口才写布局，从而尽量减少与 Web 桌面布局的争用。
 */

private fun sessionPaneTab(sessionId: String): PaneTab.Session =
    PaneTab.Session("tab-$sessionId", sessionId)

private fun paneWith(tab: PaneTab): LayoutNode.Pane = LayoutNode.Pane(listOf(tab), 0)

private data class NormalizedNode(
    val node: LayoutNode,
    val extras: List<PaneTab>,
)

/**
 * 一个 pane 只保留一个内容标签；历史 tabset 中的其它终端被提升成独立工作窗口。
 * 同时清理已不属于任务的 session 引用与跨窗口重复 tab。未知 kind 的标签保留。
 */
private fun normalizeNode(
    node: LayoutNode,
    validSessions: Set<String>,
    seenTabs: MutableSet<String>,
): NormalizedNode {
    if (node is LayoutNode.Pane) {
        val valid = node.tabs.filter { tab ->
            if (seenTabs.contains(tab.id)) return@filter false
            if (tab is PaneTab.Session && !validSessions.contains(tab.sessionId)) return@filter false
            seenTabs.add(tab.id)
            true
        }
        if (valid.isEmpty()) return NormalizedNode(LayoutNode.Pane(emptyList(), 0), emptyList())
        val preferred = node.tabs.getOrNull(node.active)
        val keep = if (preferred != null && valid.any { it.id == preferred.id }) preferred else valid.first()
        return NormalizedNode(
            paneWith(keep),
            valid.filter { it.id != keep.id },
        )
    }
    if (node is LayoutNode.Split) {
        val left = normalizeNode(node.children.first, validSessions, seenTabs)
        val right = normalizeNode(node.children.second, validSessions, seenTabs)
        val leftEmpty = layoutTabs(left.node).isEmpty()
        val rightEmpty = layoutTabs(right.node).isEmpty()
        val extras = left.extras + right.extras
        if (leftEmpty && rightEmpty) return NormalizedNode(left.node, extras)
        if (leftEmpty) return NormalizedNode(right.node, extras)
        if (rightEmpty) return NormalizedNode(left.node, extras)
        return NormalizedNode(
            LayoutNode.Split(node.dir, node.ratio, left.node to right.node),
            extras,
        )
    }
    return NormalizedNode(node, emptyList())
}

private fun uniqueWindowId(base: String, used: MutableSet<String>): String {
    var candidate = if (base.isNotEmpty()) base else "window"
    var suffix = 2
    while (used.contains(candidate)) {
        candidate = "${if (base.isNotEmpty()) base else "window"}-$suffix"
        suffix++
    }
    used.add(candidate)
    return candidate
}

private fun windowIdForTab(tab: PaneTab): String =
    if (tab is PaneTab.Session) "window-${tab.sessionId}" else "window-${tab.id}"

/**
 * 把 null、旧版单棵 LayoutNode 或新版窗口集合统一成窗口模型，并补齐任务会话。
 * 布局不变量：每个任务 session 恰好出现一次，每个 pane 最多一个内容标签。
 * 非会话 tab（editor/preview/unknown）与 split 结构原样保留。
 */
fun reconcileTaskWindowLayout(
    persisted: TaskWindowLayout?,
    sessionIds: List<String>,
    preferredSessionId: String? = null,
): TaskWindowLayout {
    val validSessions = sessionIds.toSet()
    val seenTabs = mutableSetOf<String>()
    val usedWindowIds = mutableSetOf<String>()
    val windows = mutableListOf<WorkWindowLayout>()

    for (source in persisted?.windows ?: emptyList()) {
        val normalized = normalizeNode(source.layout, validSessions, seenTabs)
        val tabs = layoutTabs(normalized.node)
        if (tabs.isNotEmpty()) {
            val id = uniqueWindowId(source.id, usedWindowIds)
            val active = activeLayoutTab(normalized.node, source.activeTabId)
            windows.add(WorkWindowLayout(id, normalized.node, active?.id))
        }
        for (extra in normalized.extras) {
            val id = uniqueWindowId(windowIdForTab(extra), usedWindowIds)
            windows.add(WorkWindowLayout(id, paneWith(extra), extra.id))
        }
    }

    for (sessionId in sessionIds) {
        val tab = sessionPaneTab(sessionId)
        if (seenTabs.contains(tab.id)) continue
        seenTabs.add(tab.id)
        val id = uniqueWindowId(windowIdForTab(tab), usedWindowIds)
        windows.add(WorkWindowLayout(id, paneWith(tab), tab.id))
    }

    val persistedActive = persisted?.activeWindowId
    val preferredWindow = if (preferredSessionId != null) {
        windows.firstOrNull { layoutSessionIds(it.layout).contains(preferredSessionId) }
    } else {
        null
    }
    val activeWindowId = preferredWindow?.id
        ?: windows.firstOrNull { it.id == persistedActive }?.id
        ?: windows.firstOrNull()?.id
    return TaskWindowLayout(windows, activeWindowId)
}

/**
 * 新建会话追加成一个独立单窗格 window，并设为活动。会话已存在时只切换活动窗口。
 * 对齐 Web addSessionWindow。
 */
fun addSessionWindow(
    layout: TaskWindowLayout,
    sessionId: String,
    activate: Boolean = true,
): TaskWindowLayout {
    val existing = layout.windows.firstOrNull { layoutSessionIds(it.layout).contains(sessionId) }
    if (existing != null) {
        return if (activate) activateWorkWindow(layout, existing.id) else layout
    }
    val tab = sessionPaneTab(sessionId)
    val used = layout.windows.map { it.id }.toMutableSet()
    val window = WorkWindowLayout(uniqueWindowId(windowIdForTab(tab), used), paneWith(tab), tab.id)
    return TaskWindowLayout(
        windows = layout.windows + window,
        activeWindowId = if (activate) window.id else layout.activeWindowId ?: window.id,
    )
}

/** 切换活动工作窗口（不改其它结构）。 */
fun activateWorkWindow(layout: TaskWindowLayout, windowId: String): TaskWindowLayout {
    if (!layout.windows.any { it.id == windowId } || layout.activeWindowId == windowId) return layout
    return layout.copy(activeWindowId = windowId)
}
