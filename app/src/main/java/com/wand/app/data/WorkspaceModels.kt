package com.wand.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工作空间（项目）+ 任务 REST 模型，org.json 手写容错解析。
 * 镜像 src/types.ts 的 Workspace / WorkspaceTask / LayoutNode / PaneTab / TaskWindowLayout，
 * 与 src/web-ui/react/workspaces/types.ts 的前端联合类型对齐。
 *
 * 解析策略与 WandModels.kt 一致：全部可空 + 逐字段容错，服务端新增字段或个别字段形状
 * 变化时客户端不至于整体解析失败。布局里的未知 tab kind 保留为 [PaneTabKind.Unknown]，
 * 不丢弃整棵布局 —— Android 单栏一次只承载一个会话，但写回布局时必须原样保留 split /
 * editor / preview / 未来类型，不能压扁 Web 桌面布局。
 */

// MARK: - Provider / 工作窗口目标

/** 工作空间支持的 Agent provider。 */
typealias WorkspaceProvider = String

/** 任务内一个工作窗口可以运行的 Agent CLI 或空白终端。 */
enum class WorkspaceSessionTarget(val raw: String, val label: String, val description: String) {
    Claude("claude", "Claude", "Claude Code"),
    Codex("codex", "Codex", "OpenAI Codex CLI"),
    OpenCode("opencode", "OpenCode", "OpenCode CLI"),
    Grok("grok", "Grok", "Grok Build CLI"),
    Qoder("qoder", "Qoder", "Qoder CLI"),
    Pi("pi", "Pi", "Pi coding agent"),
    Shell("shell", "空白终端", "仅启动系统 Shell");

    val isShell: Boolean get() = this == Shell

    val provider: WorkspaceProvider? get() = if (this == Shell) null else raw

    companion object {
        val OPTIONS: List<WorkspaceSessionTarget> = entries.toList()

        fun fromRaw(raw: String?): WorkspaceSessionTarget? =
            OPTIONS.firstOrNull { it.raw == raw }
    }
}

/** 创建任务工作窗口时绑定到任务的上下文：保证新会话落在正确的 worktree 并归属任务。 */
data class WorkspaceBinding(
    val workspaceId: String,
    val workspaceTaskId: String,
    val cwd: String,
)

// MARK: - 布局树

/** 单个内容标签的类型。session = 终端会话；editor/preview = 非会话内容；unknown = 未来类型兜底。 */
enum class PaneTabKind { Session, Editor, Preview, Unknown }

sealed class PaneTab {
    abstract val id: String
    abstract val kind: PaneTabKind

    data class Session(override val id: String, val sessionId: String) : PaneTab() {
        override val kind: PaneTabKind get() = PaneTabKind.Session
    }
    data class Editor(override val id: String, val path: String) : PaneTab() {
        override val kind: PaneTabKind get() = PaneTabKind.Editor
    }
    data class Preview(override val id: String, val path: String) : PaneTab() {
        override val kind: PaneTabKind get() = PaneTabKind.Preview
    }
    /** 未知 kind：保留原始 JSON，写回布局时原样保留，避免压扁 Web 桌面布局。 */
    data class Unknown(override val id: String, val rawKind: String, val payload: JSONObject) : PaneTab() {
        override val kind: PaneTabKind get() = PaneTabKind.Unknown
    }

    companion object {
        fun parse(o: JSONObject): PaneTab? {
            val id = o.str("id")?.takeIf { it.isNotEmpty() } ?: return null
            return when (o.str("kind")) {
                "session" -> {
                    val sid = o.str("sessionId")?.takeIf { it.isNotEmpty() } ?: return null
                    Session(id, sid)
                }
                "editor" -> {
                    val path = o.str("path")?.takeIf { it.isNotEmpty() } ?: return null
                    Editor(id, path)
                }
                "preview" -> {
                    val path = o.str("path")?.takeIf { it.isNotEmpty() } ?: return null
                    Preview(id, path)
                }
                else -> {
                    val rawKind = o.str("kind")?.takeIf { it.isNotEmpty() } ?: "unknown"
                    Unknown(id, rawKind, o)
                }
            }
        }
    }
}

enum class LayoutSplitDir { Horizontal, Vertical }

/** 布局节点：单窗格或二叉分屏。与 Web LayoutNode 一一对应。 */
sealed class LayoutNode {
    abstract val type: String

    data class Pane(
        val tabs: List<PaneTab>,
        val active: Int,
    ) : LayoutNode() {
        override val type: String get() = "pane"
    }

    data class Split(
        val dir: LayoutSplitDir,
        val ratio: Double,
        val children: Pair<LayoutNode, LayoutNode>,
    ) : LayoutNode() {
        override val type: String get() = "split"
    }

    companion object {
        fun parse(value: Any?): LayoutNode? {
            val o = value as? JSONObject ?: return null
            return when (o.str("type")) {
                "pane" -> {
                    val rawTabs = o.arr("tabs") ?: JSONArray()
                    val tabs = (0 until rawTabs.length()).mapNotNull { i ->
                        rawTabs.optJSONObject(i)?.let { PaneTab.parse(it) }
                    }
                    val tabCount = maxOf(1, tabs.size)
                    val active = (o.int("active") ?: 0).coerceIn(0, tabCount - 1)
                    Pane(tabs, active)
                }
                "split" -> {
                    val dir = when (o.str("dir")) {
                        "v" -> LayoutSplitDir.Vertical
                        "h" -> LayoutSplitDir.Horizontal
                        else -> return null
                    }
                    val kids = o.arr("children")
                    if (kids == null || kids.length() != 2) return null
                    val left = parse(kids.opt(0)) ?: return null
                    val right = parse(kids.opt(1)) ?: return null
                    val ratio = (o.dbl("ratio") ?: 0.5).coerceIn(0.05, 0.95)
                    Split(dir, ratio, left to right)
                }
                else -> null
            }
        }
    }
}

/** 顶部一个工作窗口 Tab：内部是单终端或一棵分屏树。 */
data class WorkWindowLayout(
    val id: String,
    val layout: LayoutNode,
    val activeTabId: String?,
)

/** 任务级窗口集合（顶部 Tab 模型）。 */
data class TaskWindowLayout(
    val windows: List<WorkWindowLayout>,
    val activeWindowId: String?,
) {
    val isEmpty: Boolean get() = windows.isEmpty()

    companion object {
        val EMPTY = TaskWindowLayout(emptyList(), null)

        /**
         * 容错解析：兼容旧版单棵 LayoutNode（升级为一个 window）和新版 windows 集合。
         * 返回 null 表示输入既不是合法的窗口集合也不是合法的单棵布局。
         */
        fun parse(value: Any?): TaskWindowLayout? {
            // 旧版单棵布局 → 升级成一个 window。
            val legacy = LayoutNode.parse(value)
            if (legacy != null) {
                val activeTabId = firstLayoutTabId(legacy)
                return TaskWindowLayout(
                    windows = listOf(WorkWindowLayout("window-legacy", legacy, activeTabId)),
                    activeWindowId = "window-legacy",
                )
            }
            val o = value as? JSONObject ?: return null
            if (o.str("type") != "windows") return null
            val arr = o.arr("windows") ?: return null
            val used = mutableSetOf<String>()
            val windows = (0 until arr.length()).mapNotNull { i ->
                val w = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = w.str("id")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val safeId = if (id.length > 160) id.take(160) else id
                if (used.contains(safeId)) return@mapNotNull null
                used.add(safeId)
                val layout = LayoutNode.parse(w.opt("layout")) ?: return@mapNotNull null
                val requestedActive = w.str("activeTabId")?.takeIf { it.isNotEmpty() }
                val activeTabId = if (requestedActive != null && layoutHasTab(layout, requestedActive)) {
                    requestedActive
                } else {
                    firstLayoutTabId(layout)
                }
                WorkWindowLayout(safeId, layout, activeTabId)
            }
            val requestedWindow = o.str("activeWindowId")
            val activeWindowId = if (requestedWindow != null && windows.any { it.id == requestedWindow }) {
                requestedWindow
            } else {
                windows.firstOrNull()?.id
            }
            return TaskWindowLayout(windows, activeWindowId)
        }
    }
}

// MARK: - 布局纯函数辅助

/** 收集一棵布局里的所有内容标签。 */
fun layoutTabs(node: LayoutNode): List<PaneTab> = when (node) {
    is LayoutNode.Pane -> node.tabs
    is LayoutNode.Split -> layoutTabs(node.children.first) + layoutTabs(node.children.second)
}

/** 收集一棵布局里引用的会话 ID。 */
fun layoutSessionIds(node: LayoutNode): List<String> =
    layoutTabs(node).mapNotNull { (it as? PaneTab.Session)?.sessionId }

/** 布局中是否存在指定 tab id。 */
fun layoutHasTab(node: LayoutNode, tabId: String): Boolean {
    if (node is LayoutNode.Pane) return node.tabs.any { it.id == tabId }
    if (node is LayoutNode.Split) {
        return layoutHasTab(node.children.first, tabId) ||
            layoutHasTab(node.children.second, tabId)
    }
    return false
}

/** 找到布局里指定 id 的标签。 */
fun findLayoutTab(node: LayoutNode, tabId: String): PaneTab? {
    if (node is LayoutNode.Pane) {
        return node.tabs.firstOrNull { it.id == tabId }
    }
    if (node is LayoutNode.Split) {
        return findLayoutTab(node.children.first, tabId)
            ?: findLayoutTab(node.children.second, tabId)
    }
    return null
}

/** 取活动标签（优先按 active index / preferredTabId）。 */
fun activeLayoutTab(node: LayoutNode, preferredTabId: String? = null): PaneTab? {
    if (preferredTabId != null) {
        findLayoutTab(node, preferredTabId)?.let { return it }
    }
    if (node is LayoutNode.Pane) {
        return node.tabs.getOrNull(node.active) ?: node.tabs.firstOrNull()
    }
    if (node is LayoutNode.Split) {
        return activeLayoutTab(node.children.first)
            ?: activeLayoutTab(node.children.second)
    }
    return null
}

/** 取一棵布局里的第一个标签 id（深度优先左侧）。 */
fun firstLayoutTabId(node: LayoutNode): String? {
    if (node is LayoutNode.Pane) {
        return node.tabs.getOrNull(node.active)?.id ?: node.tabs.firstOrNull()?.id
    }
    if (node is LayoutNode.Split) {
        return firstLayoutTabId(node.children.first) ?: firstLayoutTabId(node.children.second)
    }
    return null
}

/** 任务窗口集合里当前活动 window。 */
fun activeWorkWindow(layout: TaskWindowLayout?): WorkWindowLayout? {
    if (layout == null || layout.windows.isEmpty()) return null
    return layout.windows.firstOrNull { it.id == layout.activeWindowId }
        ?: layout.windows.firstOrNull()
}

/** 活动工作窗口的活动标签。 */
fun activeWorkWindowTab(layout: TaskWindowLayout?): PaneTab? {
    val window = activeWorkWindow(layout) ?: return null
    return activeLayoutTab(window.layout, window.activeTabId)
}

// MARK: - Workspace / Task 实体

data class WorkspaceTaskWorktree(
    val branch: String,
    val path: String,
    val baseRef: String?,
    val repoRoot: String?,
) {
    companion object {
        fun parse(o: JSONObject?): WorkspaceTaskWorktree? {
            if (o == null) return null
            val branch = o.str("branch")?.takeIf { it.isNotEmpty() } ?: return null
            val path = o.str("path")?.takeIf { it.isNotEmpty() } ?: return null
            return WorkspaceTaskWorktree(branch, path, o.str("baseRef"), o.str("repoRoot"))
        }
    }
}

enum class WorkspaceTaskStatus { Active, Done }

fun WorkspaceTaskStatus.raw(): String = when (this) {
    WorkspaceTaskStatus.Active -> "active"
    WorkspaceTaskStatus.Done -> "done"
}

fun parseWorkspaceTaskStatus(raw: String?): WorkspaceTaskStatus = when (raw) {
    "done" -> WorkspaceTaskStatus.Done
    else -> WorkspaceTaskStatus.Active
}

data class Workspace(
    val id: String,
    val name: String,
    val cwd: String,
    val defaultProvider: WorkspaceProvider?,
    val layout: LayoutNode?,
    val createdAt: String?,
    val lastOpenedAt: String?,
    val worktreeCount: Int? = null,
) {
    companion object {
        fun parse(o: JSONObject): Workspace? {
            val id = o.str("id")?.takeIf { it.isNotEmpty() } ?: return null
            return Workspace(
                id = id,
                name = o.str("name") ?: "",
                cwd = o.str("cwd") ?: "",
                defaultProvider = o.str("defaultProvider")?.takeIf { it.isNotEmpty() },
                layout = LayoutNode.parse(o.opt("layout")),
                createdAt = o.str("createdAt"),
                lastOpenedAt = o.str("lastOpenedAt"),
                worktreeCount = o.int("worktreeCount"),
            )
        }

        fun parseList(arr: JSONArray): List<Workspace> =
            arr.parseEach { parse(it) }
    }
}

/** GET /api/workspaces/:id 返回的会话摘要。 */
data class WorkspaceSessionSummary(
    val id: String,
    val provider: WorkspaceProvider?,
    val sessionKind: String?,
    val runner: String?,
    val title: String?,
    val status: String?,
    val cwd: String?,
    val startedAt: String?,
) {
    companion object {
        fun parse(o: JSONObject): WorkspaceSessionSummary? {
            val id = o.str("id")?.takeIf { it.isNotEmpty() } ?: return null
            return WorkspaceSessionSummary(
                id = id,
                provider = o.str("provider")?.takeIf { it.isNotEmpty() },
                sessionKind = o.str("sessionKind"),
                runner = o.str("runner"),
                title = o.str("title"),
                status = o.str("status"),
                cwd = o.str("cwd"),
                startedAt = o.str("startedAt"),
            )
        }

        fun parseList(arr: JSONArray): List<WorkspaceSessionSummary> =
            arr.parseEach { parse(it) }
    }
}

data class WorkspaceTask(
    val id: String,
    val workspaceId: String,
    val name: String,
    val worktree: WorkspaceTaskWorktree?,
    val layout: TaskWindowLayout?,
    val status: WorkspaceTaskStatus,
    val createdAt: String?,
    val lastOpenedAt: String?,
) {
    companion object {
        fun parse(o: JSONObject): WorkspaceTask? {
            val id = o.str("id")?.takeIf { it.isNotEmpty() } ?: return null
            val workspaceId = o.str("workspaceId")?.takeIf { it.isNotEmpty() } ?: return null
            return WorkspaceTask(
                id = id,
                workspaceId = workspaceId,
                name = o.str("name") ?: "",
                worktree = WorkspaceTaskWorktree.parse(o.obj("worktree")),
                layout = TaskWindowLayout.parse(o.opt("layout")),
                status = parseWorkspaceTaskStatus(o.str("status")),
                createdAt = o.str("createdAt"),
                lastOpenedAt = o.str("lastOpenedAt"),
            )
        }

        fun parseList(arr: JSONArray): List<WorkspaceTask> =
            arr.parseEach { parse(it) }
    }
}

/** POST /api/workspaces/:id/tasks 的返回：创建出的任务（含服务端生成的 worktree 信息）。 */
data class WorkspaceTaskCreation(
    val id: String,
    val workspaceId: String,
    val name: String,
    val worktree: WorkspaceTaskWorktree?,
    val status: WorkspaceTaskStatus,
) {
    companion object {
        fun parse(o: JSONObject): WorkspaceTaskCreation? {
            val id = o.str("id")?.takeIf { it.isNotEmpty() } ?: return null
            return WorkspaceTaskCreation(
                id = id,
                workspaceId = o.str("workspaceId") ?: "",
                name = o.str("name") ?: "",
                worktree = WorkspaceTaskWorktree.parse(o.obj("worktree")),
                status = parseWorkspaceTaskStatus(o.str("status")),
            )
        }
    }
}

/**
 * GET /api/tasks 聚合行：任务 + 运行期派生字段；目录信息在 TaskDirectoryGroup 上。
 * 对齐 web/iOS 同名模型。
 */
data class WorkspaceTaskSummary(
    val task: WorkspaceTask,
    /** 任务实际运行目录。 */
    val cwd: String,
    /** 是否隔离（有独立 worktree）。 */
    val isolated: Boolean,
    val worktreeError: String?,
    val sessions: List<WorkspaceSessionSummary>,
) {
    val id: String get() = task.id
    val isIsolated: Boolean get() = isolated || task.worktree != null

    companion object {
        fun parse(o: JSONObject): WorkspaceTaskSummary? {
            val task = WorkspaceTask.parse(o) ?: return null
            return WorkspaceTaskSummary(
                task = task,
                cwd = o.str("cwd") ?: "",
                isolated = o.bool("isolated") ?: false,
                worktreeError = o.str("worktreeError"),
                sessions = o.arr("sessions")?.let(WorkspaceSessionSummary::parseList) ?: emptyList(),
            )
        }
    }
}

/**
 * 目录组一级容器：任务归属目录；未绑定任务的会话归入 standaloneSessions。
 * synthetic 表示该目录没有项目实体，仅用于展示。
 */
data class TaskDirectoryGroup(
    val workspaceId: String,
    val workspaceName: String,
    val workspaceCwd: String,
    val synthetic: Boolean,
    val tasks: List<WorkspaceTaskSummary>,
    val standaloneSessions: List<WorkspaceSessionSummary>,
) {
    val id: String get() = workspaceId

    companion object {
        fun parse(o: JSONObject): TaskDirectoryGroup? {
            val workspaceId = o.str("workspaceId")?.takeIf { it.isNotEmpty() } ?: return null
            return TaskDirectoryGroup(
                workspaceId = workspaceId,
                workspaceName = o.str("workspaceName") ?: "",
                workspaceCwd = o.str("workspaceCwd") ?: "",
                synthetic = o.bool("synthetic") ?: false,
                tasks = o.arr("tasks")?.parseEach(WorkspaceTaskSummary::parse) ?: emptyList(),
                standaloneSessions = o.arr("standaloneSessions")
                    ?.let(WorkspaceSessionSummary::parseList) ?: emptyList(),
            )
        }

        fun parseList(arr: JSONArray): List<TaskDirectoryGroup> =
            arr.parseEach { parse(it) }
    }
}

/** GET /api/workspace-tasks/:taskId 的返回：在 WorkspaceTask 基础上带回运行期派生字段。 */
data class WorkspaceTaskDetail(
    val task: WorkspaceTask,
    /** 任务实际运行目录（worktree 路径，或非 git 时回退到项目目录）。 */
    val cwd: String,
    /** 是否隔离（有独立 worktree）。 */
    val isolated: Boolean,
    /** 非 git / 基线缺失时的降级提示。 */
    val worktreeError: String?,
    /** 该任务下已绑定的会话。 */
    val sessions: List<WorkspaceSessionSummary>,
) {
    val id: String get() = task.id
    val workspaceId: String get() = task.workspaceId
    val name: String get() = task.name
    val status: WorkspaceTaskStatus get() = task.status
    val worktree: WorkspaceTaskWorktree? get() = task.worktree

    companion object {
        fun parse(o: JSONObject): WorkspaceTaskDetail? {
            val task = WorkspaceTask.parse(o) ?: return null
            return WorkspaceTaskDetail(
                task = task,
                cwd = o.str("cwd") ?: "",
                isolated = o.bool("isolated") ?: false,
                worktreeError = o.str("worktreeError"),
                sessions = o.arr("sessions")?.let(WorkspaceSessionSummary::parseList) ?: emptyList(),
            )
        }
    }
}

/**
 * 服务端会话列表按最近更新时间返回，但编辑器式标签必须保持创建顺序，避免每次新增后
 * 已有标签被重新编号、位置整体跳动。时间缺失或相同时保留服务端原始相对顺序。
 * 对齐 Web orderWorkspaceSessions。
 */
fun orderWorkspaceSessions(sessions: List<WorkspaceSessionSummary>): List<WorkspaceSessionSummary> {
    // 对齐 Web orderWorkspaceSessions：按 startedAt 升序（更早创建在前），缺失时间排后，
    // 时间相同或缺失时保留服务端原始相对顺序（稳定排序）。
    val indexed = sessions.mapIndexed { index, session -> IndexedSession(session, index, session.startedAt) }
    return indexed.sortedWith { a, b ->
        val aHas = a.startedAt != null && a.startedAt.isNotEmpty()
        val bHas = b.startedAt != null && b.startedAt.isNotEmpty()
        when {
            aHas && bHas && a.startedAt != b.startedAt -> a.startedAt!!.compareTo(b.startedAt!!)
            aHas != bHas -> if (aHas) -1 else 1
            else -> a.index - b.index
        }
    }.map { it.session }
}

private data class IndexedSession(
    val session: WorkspaceSessionSummary,
    val index: Int,
    val startedAt: String?,
)

data class WorkspaceWorktreeCommit(
    val hash: String,
    val shortHash: String?,
    val subject: String,
) {
    companion object {
        fun parse(o: JSONObject): WorkspaceWorktreeCommit? {
            val hash = o.str("hash")?.takeIf { it.isNotEmpty() } ?: return null
            val subject = o.str("subject") ?: return null
            return WorkspaceWorktreeCommit(hash, o.str("shortHash"), subject)
        }

        fun parseList(arr: JSONArray): List<WorkspaceWorktreeCommit> =
            arr.parseEach { parse(it) }
    }
}

/** `GET /api/workspaces/:id/worktrees` 的任务 worktree 审查项。 */
data class WorkspaceWorktreeReview(
    val taskId: String,
    val taskName: String,
    val taskStatus: String,
    val branch: String,
    val path: String,
    val baseRef: String?,
    val state: String,
    val actionable: Boolean,
    val reason: String?,
    val aheadCount: Int,
    val hasUncommittedChanges: Boolean,
    val hasConflicts: Boolean,
    val commits: List<WorkspaceWorktreeCommit>,
) {
    val stateLabel: String
        get() = when (state) {
            "ready" -> "待合并"
            "dirty" -> "有未提交改动"
            "conflict" -> "可能冲突"
            "empty" -> "已同步"
            else -> "不可用"
        }

    val summary: String
        get() {
            val commit = commits.firstOrNull()?.subject?.trim().orEmpty()
            val name = taskName.trim()
            return when {
                commit.isNotEmpty() && commit != name -> "$name · $commit"
                name.isNotEmpty() -> name
                commit.isNotEmpty() -> commit
                else -> branch
            }
        }

    val details: String
        get() {
            val parts = buildList {
                if (aheadCount > 0) add("$aheadCount commits")
                if (hasUncommittedChanges) add("工作区有改动")
                if (hasConflicts) add("需处理冲突")
            }
            if (parts.isNotEmpty()) return parts.joinToString(" · ")
            if (!reason.isNullOrEmpty()) return reason
            return "没有新的待合并改动"
        }

    companion object {
        fun parse(o: JSONObject): WorkspaceWorktreeReview? {
            val taskId = o.str("taskId")?.takeIf { it.isNotEmpty() } ?: return null
            return WorkspaceWorktreeReview(
                taskId = taskId,
                taskName = o.str("taskName") ?: "",
                taskStatus = o.str("taskStatus") ?: "",
                branch = o.str("branch") ?: "",
                path = o.str("path") ?: "",
                baseRef = o.str("baseRef"),
                state = o.str("state") ?: "",
                actionable = o.bool("actionable") ?: false,
                reason = o.str("reason"),
                aheadCount = o.int("aheadCount") ?: 0,
                hasUncommittedChanges = o.bool("hasUncommittedChanges") ?: false,
                hasConflicts = o.bool("hasConflicts") ?: false,
                commits = o.arr("commits")?.let(WorkspaceWorktreeCommit::parseList) ?: emptyList(),
            )
        }

        fun parseList(arr: JSONArray): List<WorkspaceWorktreeReview> =
            arr.parseEach { parse(it) }
    }
}

data class WorkspaceWorktreeOverview(
    val workspaceId: String,
    val repoRoot: String,
    val targetBranch: String,
    val worktrees: List<WorkspaceWorktreeReview>,
) {
    val actionableWorktrees: List<WorkspaceWorktreeReview>
        get() = worktrees.filter { it.actionable }

    companion object {
        fun parse(o: JSONObject): WorkspaceWorktreeOverview? {
            val workspaceId = o.str("workspaceId")?.takeIf { it.isNotEmpty() } ?: return null
            return WorkspaceWorktreeOverview(
                workspaceId = workspaceId,
                repoRoot = o.str("repoRoot") ?: "",
                targetBranch = o.str("targetBranch") ?: "",
                worktrees = o.arr("worktrees")?.let(WorkspaceWorktreeReview::parseList) ?: emptyList(),
            )
        }
    }
}

/**
 * 与 Web `buildWorkspaceMergeAgentPrompt` / iOS 同名函数一致：
 * Agent 只拿服务端审查结果里的规范路径与 ref。
 */
fun buildWorkspaceMergeAgentPrompt(
    workspace: Workspace,
    overview: WorkspaceWorktreeOverview,
    selectedTaskIds: Set<String>,
): String {
    val selected = overview.worktrees.filter { it.taskId in selectedTaskIds && it.actionable }
    if (overview.targetBranch.isEmpty()) {
        throw IllegalStateException("无法识别项目默认分支。")
    }
    if (selected.isEmpty()) {
        throw IllegalStateException("请至少选择一个有待合并改动的 Worktree。")
    }
    val manifest = JSONArray()
    selected.forEachIndexed { index, worktree ->
        val subjects = JSONArray()
        worktree.commits.map { it.subject }.filter { it.isNotEmpty() }.forEach(subjects::put)
        manifest.put(
            JSONObject()
                .put("order", index + 1)
                .put("task", worktree.taskName)
                .put("branch", worktree.branch)
                .put("worktreePath", worktree.path)
                .put("baseRef", worktree.baseRef ?: JSONObject.NULL)
                .put("uncommittedChanges", worktree.hasUncommittedChanges)
                .put("potentialConflict", worktree.hasConflicts)
                .put("commitsAhead", worktree.aheadCount)
                .put("commitSubjects", subjects),
        )
    }
    return listOf(
        "你是 Wand 为项目「${workspace.name}」启动的 Worktree 合并 Agent。",
        "项目主工作区：${overview.repoRoot.ifEmpty { workspace.cwd }}",
        "唯一目标分支：${overview.targetBranch}",
        "",
        "请按清单顺序审查并合并所选 Worktree：",
        manifest.toString(2),
        "",
        "执行要求：",
        "1. 先读取项目内适用的 AGENTS.md/agent.md 与仓库约定，再检查主工作区和每个 Worktree 的真实 Git 状态。",
        "2. 对有未提交改动的 Worktree，先理解改动、完成必要验证，并创建清晰的提交；不得丢弃或覆盖现有用户改动。",
        "3. 只把清单中的分支合并到 ${overview.targetBranch}，按清单顺序逐个处理；不要改为其他目标分支。",
        "4. 遇到冲突时理解双方意图后解决并验证；若无法安全判断，停止在可恢复状态并清楚报告，不要强行覆盖。",
        "5. 合并完成后运行与改动相称的测试。不要 push，也不要删除 Worktree、任务分支或 Wand 的项目任务记录。",
        "6. 最后汇报每个 Worktree 的提交/合并结果、测试结果，以及任何仍需人工处理的问题。",
    ).joinToString("\n")
}

/** 工作区内统一使用的 provider 展示名（对齐 Web workspaceProviderLabel）。 */
fun workspaceProviderLabel(provider: String?): String = when (provider) {
    "claude" -> "Claude"
    "codex" -> "Codex"
    "opencode" -> "OpenCode"
    "grok" -> "Grok"
    "qoder" -> "Qoder"
    "pi" -> "Pi"
    else -> "终端"
}

/** 单个会话标签的展示名（对齐 Web workspaceSessionLabel）。 */
fun workspaceSessionLabel(session: WorkspaceSessionSummary, index: Int): String {
    val title = session.title?.trim()
    return if (!title.isNullOrEmpty()) title else "${workspaceProviderLabel(session.provider)} ${index + 1}"
}

// MARK: - 布局序列化（PUT 回写服务端）

/** 把单个内容标签序列化回 JSON。Unknown 原样保留原始 payload。 */
fun PaneTab.toJsonObject(): JSONObject = when (this) {
    is PaneTab.Session -> JSONObject().put("id", id).put("kind", "session").put("sessionId", sessionId)
    is PaneTab.Editor -> JSONObject().put("id", id).put("kind", "editor").put("path", path)
    is PaneTab.Preview -> JSONObject().put("id", id).put("kind", "preview").put("path", path)
    is PaneTab.Unknown -> payload
}

/** 把布局节点序列化回 JSON。 */
fun LayoutNode.toJsonObject(): JSONObject = when (this) {
    is LayoutNode.Pane -> JSONObject().apply {
        put("type", "pane")
        put("tabs", JSONArray().also { arr -> tabs.forEach { arr.put(it.toJsonObject()) } })
        put("active", active)
    }
    is LayoutNode.Split -> JSONObject().apply {
        put("type", "split")
        put("dir", if (dir == LayoutSplitDir.Vertical) "v" else "h")
        put("ratio", ratio)
        put("children", JSONArray().put(children.first.toJsonObject()).put(children.second.toJsonObject()))
    }
}

/** 把工作窗口序列化回 JSON。 */
fun WorkWindowLayout.toJsonObject(): JSONObject = JSONObject().apply {
    put("id", id)
    put("layout", layout.toJsonObject())
    if (activeTabId != null) put("activeTabId", activeTabId)
}

/** 把任务窗口集合序列化回 JSON（type=windows）。 */
fun TaskWindowLayout.toJsonObject(): JSONObject = JSONObject().apply {
    put("type", "windows")
    put("windows", JSONArray().also { arr -> windows.forEach { arr.put(it.toJsonObject()) } })
    put("activeWindowId", activeWindowId ?: JSONObject.NULL)
}
