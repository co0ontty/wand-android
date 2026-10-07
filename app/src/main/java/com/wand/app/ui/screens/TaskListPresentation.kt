package com.wand.app.ui.screens

import com.wand.app.data.SiliconEmployee
import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.WorkspaceTaskStatus
import com.wand.app.data.ExecutionSubject
import com.wand.app.data.workspaceProviderLabel
import com.wand.app.ui.isGenericSessionTitle
import com.wand.app.ui.sessionCwdLeaf

internal const val UNNAMED_TASK_NAME = "未命名任务"

/** 独立根模式；通讯录是导航入口，不占用模式偏好。 */
enum class HomeListMode(val storageValue: String) {
    Sessions("sessions"),
    Tasks("board"),
    Im("im");

    companion object {
        fun fromStorage(value: String?): HomeListMode = when (value) {
            "sessions" -> Sessions
            "board" -> Tasks
            else -> Im
        }
    }
}

/**
 * 目录顺序**完全**以 GET /api/tasks 返回为准（服务端已按用户拖动保存的顺序排好）。
 *
 * 这里曾经本地把「未归属」压到最后，那会覆盖用户的拖动结果——顺序现在只有一个真源，
 * 客户端不再二次排序。
 */
internal fun directoryTreeGroups(groups: List<TaskDirectoryGroup>): List<TaskDirectoryGroup> =
    groups.map { group ->
        // Presentation only: retain completed tasks in the board and session navigation.
        group.copy(
            workspaceName = if (group.isGlobal) "未归属工作区" else group.workspaceName,
            tasks = group.tasks.filter { it.status != WorkspaceTaskStatus.Done },
        )
    }.filter { !it.isGlobal || it.tasks.isNotEmpty() || it.standaloneSessions.isNotEmpty() }

internal data class HomeRecentConversation(
    val group: TaskDirectoryGroup,
    val task: com.wand.app.data.WorkspaceTaskSummary?,
    val session: WorkspaceSessionSummary,
)

/** 「最近」的唯一排序：新的在前，时间相同用 id 保证稳定。 */
internal val homeRecentConversationOrder: Comparator<HomeRecentConversation> =
    compareByDescending<HomeRecentConversation> { it.session.startedAt.orEmpty() }
        .thenBy { it.session.id }

/** The session list keeps its task tree below; this projection gives chat a direct recent entry. */
internal fun recentHomeConversations(
    groups: List<TaskDirectoryGroup>,
    limit: Int = 8,
): List<HomeRecentConversation> = groups.flatMap { group ->
    group.tasks.flatMap { task ->
        task.sessions.map { session -> HomeRecentConversation(group, task, session) }
    } + group.standaloneSessions.map { session -> HomeRecentConversation(group, null, session) }
}.distinctBy { it.session.id }
    .sortedWith(homeRecentConversationOrder)
    .take(limit.coerceAtLeast(0))

/** 首页最近列表的一级归属。 */
internal enum class HomeGroupKind { Employee, Team, Pty, BlankTerminal, Cli }

internal data class HomeGroupIdentity(val key: String, val kind: HomeGroupKind)

private val HOME_TERMINAL_GROUPS = listOf(
    HomeGroupIdentity("pty", HomeGroupKind.Pty),
    HomeGroupIdentity("blank-terminal", HomeGroupKind.BlankTerminal),
)

internal val HomeGroupKind.isTerminal: Boolean
    get() = this == HomeGroupKind.Pty || this == HomeGroupKind.BlankTerminal

/**
 * 会话的一级归属：员工 → 团队（群聊） → PTY 终端 → 空白终端。
 * key 只由会话自带的身份决定，与员工定义是否还在无关，所以分组不会因为改名/归档而漂移。
 */
internal fun homeGroupIdentityOf(session: WorkspaceSessionSummary): HomeGroupIdentity = when {
    // 员工优先：团队派发的成员会话也算这位员工的，只在员工下面标出「来自哪个团队」。
    session.employeeId != null ->
        HomeGroupIdentity("employee:${session.employeeId}", HomeGroupKind.Employee)
    // 团队一级只放群聊：同一团队的多次开工合成一行（缺 teamId 的旧数据退回 runId）。
    session.teamChat != null -> HomeGroupIdentity(
        "team:${session.teamChat.teamId ?: session.teamChat.runId}", HomeGroupKind.Team)
    // 防御：没有员工身份的派发步骤也不能丢，按一次运行归到团队。
    session.teamStep != null ->
        HomeGroupIdentity("team-run:${session.teamStep.runId}", HomeGroupKind.Team)
    // 只有手开的终端没有主人：带 provider 的归 PTY，空白 shell 单独一类。
    session.sessionKind == "pty" && !session.provider.isNullOrBlank() && session.provider != "shell" ->
        HOME_TERMINAL_GROUPS[0]
    session.sessionKind == "pty" -> HOME_TERMINAL_GROUPS[1]
    // 防御：结构化会话理论上都有员工或团队归属；真遇到没有的也不能丢，按 provider 单列一行。
    else -> HomeGroupIdentity("cli:${session.provider.orEmpty()}", HomeGroupKind.Cli)
}

internal fun homeGroupKeyOf(session: WorkspaceSessionSummary): String =
    homeGroupIdentityOf(session).key

/**
 * 一级分组：员工 / 团队 / PTY 终端 / 空白终端。
 * 团队与终端保留一级入口；员工只有一条会话时一级行本身就是那条会话。
 */
internal data class HomeGroup(
    val key: String,
    val kind: HomeGroupKind,
    val title: String,
    /** 员工分组才有：二级行/后续操作按它归属，展示名与头像从当前定义投影。 */
    val employeeId: String? = null,
    val avatar: String? = null,
    val conversations: List<HomeRecentConversation>,
) {
    val isCollapsible: Boolean = conversations.size > 1
    val latestStartedAt: String = conversations.firstOrNull()?.session?.startedAt.orEmpty()
    val counts: HomeSessionCounts = homeSessionCounts(conversations.map { it.session })
}

/**
 * 团队与终端始终渲染一级行：终端数量与筛选档位不能改变快捷新增入口的位置。
 * 员工在只有一条会话时一级行就是那张会话卡，不套空壳。
 */
internal fun homeGroupShowsHeader(group: HomeGroup): Boolean =
    group.kind == HomeGroupKind.Team || group.kind.isTerminal || group.isCollapsible

/**
 * 员工/团队按最近窗口分组；终端不受这个窗口截断，放在末尾。
 * 只有空白终端入口常驻，PTY 分组必须有真实会话，不能凭空多画一个「0 会话」入口。
 * 调用方传全量会话，避免旧终端或正在跑的终端被「最近 N 条」提前丢掉。
 */
internal fun recentHomeGroups(
    conversations: List<HomeRecentConversation>,
    employees: List<SiliconEmployee> = emptyList(),
    limit: Int = 8,
): List<HomeGroup> {
    val ordered = conversations.distinctBy { it.session.id }.sortedWith(homeRecentConversationOrder)
    val (terminals, other) = ordered.partition { homeGroupIdentityOf(it.session).kind.isTerminal }
    val buckets = LinkedHashMap<String, MutableList<HomeRecentConversation>>()
    (other.take(limit.coerceAtLeast(0)) + terminals).forEach { conversation ->
        val key = homeGroupKeyOf(conversation.session)
        buckets.getOrPut(key) { mutableListOf() }.add(conversation)
    }
    val groups = buckets.mapNotNull { (key, items) ->
        val ordered = items.distinctBy { it.session.id }.sortedWith(homeRecentConversationOrder)
        val head = ordered.firstOrNull()?.session ?: return@mapNotNull null
        val kind = homeGroupIdentityOf(head).kind
        val employee = head.employeeId?.let { id -> employees.firstOrNull { it.id == id } }
        HomeGroup(
            key = key,
            kind = kind,
            title = homeGroupTitle(kind, ordered, employee),
            employeeId = head.employeeId,
            // 员工可能被改名/换头像：展示从当前定义投影，定义没了就退回会话快照。
            avatar = employee?.avatar?.takeIf { it.isNotBlank() } ?: head.employeeAvatar,
            conversations = ordered,
        )
    }
    val recent = groups.filterNot { it.kind.isTerminal }
        .sortedWith(compareByDescending<HomeGroup> { it.latestStartedAt }.thenBy { it.key })
    val terminalGroups = HOME_TERMINAL_GROUPS.mapNotNull { identity ->
        groups.firstOrNull { it.key == identity.key }
            ?: if (identity.kind == HomeGroupKind.BlankTerminal) HomeGroup(
                key = identity.key,
                kind = identity.kind,
                title = "空白终端",
                conversations = emptyList(),
            ) else null
    }
    return recent + terminalGroups
}

private fun homeGroupTitle(
    kind: HomeGroupKind,
    ordered: List<HomeRecentConversation>,
    employee: SiliconEmployee?,
): String {
    val head = ordered.first().session
    return when (kind) {
        HomeGroupKind.Employee -> employee?.name?.takeIf { it.isNotBlank() }
            ?: head.employeeName?.takeIf { it.isNotBlank() } ?: "员工"
        // 同一团队同一行：群聊与（没有员工身份的）派发步骤都可能排在前面，取组内第一个有名字的。
        HomeGroupKind.Team -> ordered.firstNotNullOfOrNull { conversation ->
            conversation.session.teamChat?.teamName?.takeIf { it.isNotBlank() }
                ?: conversation.session.teamStep?.teamName?.takeIf { it.isNotBlank() }
        } ?: "AI 团队"
        HomeGroupKind.Pty -> "PTY 终端"
        HomeGroupKind.BlankTerminal -> "空白终端"
        HomeGroupKind.Cli -> workspaceProviderLabel(head.provider)
    }
}

/** Select an existing conversation by stable contact identity, ignoring team worker sessions for CLI. */
internal fun contactConversation(
    conversations: List<HomeRecentConversation>,
    subject: ExecutionSubject,
    teamRunIds: Set<String> = emptySet(),
): HomeRecentConversation? = conversations.firstOrNull { conversation ->
    val session = conversation.session
    when (subject.type) {
        "employee" -> session.employeeId == subject.id
        "team" -> session.teamChat?.let { chat ->
            chat.teamId == subject.id || chat.runId in teamRunIds
        } == true
        "cli" -> session.employeeId == null && session.teamChat == null &&
            session.teamStep == null && session.provider == subject.id
        else -> false
    }
}

/** 任务下没有终端时不显示箭头。 */
internal fun showsTaskSessionDisclosure(sessionCount: Int): Boolean = sessionCount > 0

/**
 * 任务这一层的内容展开规则：
 * - 收起档永不展开；
 * - 在跑档只有真的露出一条在跑、失败或待处理的会话才展开，否则收成任务标题行（一级行留着的意义就在这里）；
 * - 展开档照旧：没有终端的任务默认收起来，只有当它是目录里唯一任务时才展开引导创建首个会话。
 */
internal fun isTaskSessionsExpanded(
    fold: HomeFoldMode,
    visibleSessionCount: Int,
    totalSessions: Int,
    isOnlyTask: Boolean = false,
): Boolean = when {
    fold == HomeFoldMode.Collapse -> false
    fold.filtersRunning -> visibleSessionCount > 0
    !showsTaskSessionDisclosure(totalSessions) -> isOnlyTask
    else -> true
}

/**
 * 侧栏任务行只在「当前详情就是这个任务」时高亮。
 * 已经选中该任务下某个可见会话时，只高亮会话行，避免父子两层各画一个选中矩形。
 * 选中会话不在当前可见列表里时，任务行继续作为位置提示。
 */
internal fun isTaskRowSelected(
    taskId: String,
    visibleSessionIds: Collection<String>,
    selectedTaskId: String?,
    selectedSessionId: String?,
): Boolean {
    if (taskId != selectedTaskId) return false
    val sessionId = selectedSessionId?.takeIf { it.isNotBlank() } ?: return true
    return sessionId !in visibleSessionIds
}

/**
 * 列表里的终端名：有会话标题就显示标题（和任务名重复也照显示），
 * 只有占位标题（空 / 会话 / 裸 CLI 名 / 「CLI N」）或旧版终端把 cwd 末段当标题时才回退「CLI 序号」。
 */
internal fun listSessionLabel(
    session: WorkspaceSessionSummary,
    index: Int,
): String {
    val title = session.title?.trim().orEmpty()
    if (title.isNotEmpty() && !isPlaceholderSessionTitle(title) && !isDirectoryFallbackTitle(title, session.cwd)) {
        return title
    }
    return "${workspaceProviderLabel(session.provider)} ${index + 1}"
}

private val PROVIDER_SEQUENCE_TITLE =
    Regex("^(claude|codex|opencode|grok|qoder|pi|gemini|终端)\\s+\\d+$", RegexOption.IGNORE_CASE)

/** 系统自己生成的占位标题，不是会话标题。 */
internal fun isPlaceholderSessionTitle(title: String): Boolean =
    isGenericSessionTitle(title) || PROVIDER_SEQUENCE_TITLE.matches(title.trim())

/** 旧版终端把 cwd 末段当标题：目录名不算会话标题。 */
private fun isDirectoryFallbackTitle(title: String, cwd: String?): Boolean {
    val leaf = sessionCwdLeaf(cwd).orEmpty()
    return leaf.isNotEmpty() && title.equals(leaf, ignoreCase = true)
}

internal data class SidebarManageSelection(
    val taskIds: Set<String> = emptySet(),
    val sessionIds: Set<String> = emptySet(),
) {
    val count: Int get() = taskIds.size + sessionIds.size
    val isEmpty: Boolean get() = count == 0
}

/** 窄栏一项一个目录，顺序与展开后的目录树一致。 */
internal data class CollapsedRailDirectory(
    val group: TaskDirectoryGroup,
    val activity: String?,
)

private val RAIL_ATTENTION_STATUSES = setOf(
    "failed",
    "waiting-input",
    "waiting_input",
    "permission-blocked",
    "reconnecting",
)

internal fun sessionRailActivity(session: WorkspaceSessionSummary): String? = when {
    session.status in RAIL_ATTENTION_STATUSES -> "attention"
    session.inFlight == true || session.ptyBusy == true || session.status == "thinking" -> "running"
    else -> null
}

internal fun directoryRailActivity(group: TaskDirectoryGroup): String? {
    val sessions = group.tasks.flatMap { it.sessions } + group.standaloneSessions
    if (sessions.any { sessionRailActivity(it) == "attention" }) return "attention"
    if (sessions.any { sessionRailActivity(it) == "running" }) return "running"
    return null
}

/** 当前任务或终端落在这个目录里时，窄栏文件夹保持选中。 */
internal fun directoryContainsSelection(
    group: TaskDirectoryGroup,
    selectedTaskId: String?,
    selectedSessionId: String?,
): Boolean {
    if (!selectedTaskId.isNullOrBlank() && group.tasks.any { it.id == selectedTaskId }) return true
    val sessionId = selectedSessionId?.takeIf { it.isNotBlank() } ?: return false
    if (group.standaloneSessions.any { it.id == sessionId }) return true
    return group.tasks.any { task -> task.sessions.any { it.id == sessionId } }
}

/** 平板窄栏：每个可见目录一个文件夹，不再把任务摊平成图标。 */
internal fun collapsedRailDirectories(groups: List<TaskDirectoryGroup>): List<CollapsedRailDirectory> =
    directoryTreeGroups(groups).map { group ->
        CollapsedRailDirectory(group = group, activity = directoryRailActivity(group))
    }

internal fun collectManagedIds(groups: List<TaskDirectoryGroup>): SidebarManageSelection {
    val taskIds = linkedSetOf<String>()
    val sessionIds = linkedSetOf<String>()
    groups.forEach { group ->
        group.tasks.forEach { task ->
            taskIds += task.id
            task.sessions.forEach { sessionIds += it.id }
        }
        group.standaloneSessions.forEach { sessionIds += it.id }
    }
    return SidebarManageSelection(taskIds, sessionIds)
}

/**
 * 归档会连同任务里的终端一起隐藏，所以被选中任务名下的终端不再进删除集合，
 * 只保留真正独立选中的终端（对齐 web 端「两者互不覆盖」）。
 */
internal fun resolveManagedAction(
    selection: SidebarManageSelection,
    groups: List<TaskDirectoryGroup>,
): SidebarManageSelection {
    val owned = groups.asSequence()
        .flatMap { it.tasks }
        .filter { it.id in selection.taskIds }
        .flatMap { it.sessions }
        .map { it.id }
        .toSet()
    return SidebarManageSelection(
        taskIds = selection.taskIds,
        sessionIds = selection.sessionIds.filterNot { it in owned }.toSet(),
    )
}

/**
 * 批量操作里任务是归档（终端继续跑、worktree 保留），只有显式选中的终端才真删除。
 * 对齐 web 端 `describeManagedAction` 与 iOS 同类实现，三端用同一句动作名。
 */
internal fun describeManagedAction(selection: SidebarManageSelection): String = when {
    selection.taskIds.isNotEmpty() && selection.sessionIds.isNotEmpty() -> "归档任务并删除终端"
    selection.taskIds.isNotEmpty() -> "归档任务"
    else -> "删除终端"
}

/** 只有真的会杀终端时才用危险样式；纯归档不该渲染成红色破坏性操作。 */
internal fun managedSelectionIsDestructive(selection: SidebarManageSelection): Boolean =
    selection.sessionIds.isNotEmpty()

/** 批量确认弹窗正文：说清归档与删除各自会发生什么（归档是软删除）。 */
internal fun describeManagedConfirmMessage(selection: SidebarManageSelection): String = when {
    selection.taskIds.isEmpty() -> "将结束所选终端，此操作无法撤销。"
    selection.sessionIds.isEmpty() ->
        "所选任务会从侧栏隐藏并移入看板归档，终端与 Worktree 都保留。"
    else ->
        "所选任务会移入看板归档（终端与 Worktree 保留），同时结束所选终端。"
}
