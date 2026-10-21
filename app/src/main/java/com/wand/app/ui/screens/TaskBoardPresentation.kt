package com.wand.app.ui.screens

import com.wand.app.data.BOARD_TASK_STATUSES
import com.wand.app.data.AiTeam
import com.wand.app.data.BoardTask
import com.wand.app.data.BoardTaskSession
import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.boardTaskAgentLabels
import com.wand.app.data.boardTaskProviderLabel
import com.wand.app.data.boardTaskStatusLabel
import java.time.LocalDate
import java.time.ZoneId

internal data class BoardTaskStats(
    val total: Int,
    val todo: Int,
    val doing: Int,
    val done: Int,
    val accepted: Int,
    val remaining: Int,
    val high: Int,
)

internal fun boardTaskStats(tasks: List<BoardTask>): BoardTaskStats {
    var todo = 0
    var doing = 0
    var done = 0
    var high = 0
    for (task in tasks) {
        when (task.status) {
            "todo" -> todo += 1
            "doing" -> doing += 1
            "done" -> done += 1
        }
        if (task.priority == "high" || task.priority == "urgent") high += 1
    }
    return BoardTaskStats(
        total = tasks.size,
        todo = todo,
        doing = doing,
        done = done,
        accepted = tasks.count { !it.acceptance?.acceptedAt.isNullOrBlank() },
        remaining = todo + doing,
        high = high,
    )
}

internal fun boardTaskMatches(task: BoardTask, query: String, workspaceId: String): Boolean {
    val haystack = "${task.title} ${task.description} ${task.identifier} ${task.workspace?.name.orEmpty()}"
    val matchesQuery = query.isBlank() || haystack.contains(query, ignoreCase = true)
    val matchesWorkspace = workspaceId.isBlank() || task.workspaceId == workspaceId
    return matchesQuery && matchesWorkspace
}

internal fun filterBoardTasks(
    tasks: List<BoardTask>,
    query: String,
    workspaceId: String,
    status: String = "",
): List<BoardTask> = tasks.filter { task ->
    boardTaskMatches(task, query, workspaceId) && (status.isBlank() || task.status == status)
}

/** 看板里的安全临时默认组：只投影真实目录会话，不创建 wand task。 */
internal data class BoardStandaloneGroup(
    val workspaceId: String,
    val title: String,
    val workspaceCwd: String,
    val sessions: List<WorkspaceSessionSummary>,
)

/** 目录/路径命中时保留全部成员，否则只保留标题命中的成员。 */
internal fun boardStandaloneGroups(
    groups: List<TaskDirectoryGroup>,
    formalTasks: List<BoardTask>,
    query: String,
    workspaceId: String,
): List<BoardStandaloneGroup> {
    val formalIds = (formalTasks.asSequence().filter { it.status != "archived" }
        .flatMap { task -> task.sessionIds.asSequence() + task.sessions.asSequence().map { it.id } } +
        groups.asSequence().flatMap { it.tasks.asSequence() }.flatMap { it.sessions.asSequence() }.map { it.id })
        .toSet()
    val needle = query.trim()
    val seen = mutableSetOf<String>()
    return groups.mapNotNull { group ->
        if (workspaceId.isNotBlank() && group.workspaceId != workspaceId) return@mapNotNull null
        val candidates = group.standaloneSessions.filter {
            it.id !in formalIds && it.id !in seen && it.workspaceTaskId == null &&
                it.archived != true && it.teamChat == null
        }
        if (candidates.isEmpty()) return@mapNotNull null
        val directoryMatches = needle.isBlank() ||
            "${group.workspaceName} - 未分组任务 ${group.workspaceCwd}".contains(needle, ignoreCase = true)
        val visible = if (directoryMatches) candidates else candidates.filter { session ->
            listOf(session.title, session.cwd, session.employeeName, com.wand.app.data.workspaceProviderLabel(session.provider))
                .any { it?.contains(needle, ignoreCase = true) == true }
        }
        if (visible.isEmpty()) return@mapNotNull null
        seen += visible.map { it.id }
        BoardStandaloneGroup(group.workspaceId, "${group.workspaceName} - 未分组任务", group.workspaceCwd, visible)
    }
}

/** 顺序以 GET /api/wand-tasks 返回为准，客户端不再本地排序。 */
internal fun sortBoardTasks(tasks: List<BoardTask>): List<BoardTask> = tasks

internal fun groupedBoardTasks(tasks: List<BoardTask>): List<Pair<String, List<BoardTask>>> =
    BOARD_TASK_STATUSES.map { status -> status to tasks.filter { it.status == status } }

internal fun boardArchivedTasks(tasks: List<BoardTask>): List<BoardTask> =
    tasks.filter { it.status == "archived" }

/** 默认收起已完成；搜索时仍显示命中的任务，避免把搜索结果藏起来。 */
internal fun boardDoneSectionOpen(collapsed: Boolean, query: String): Boolean =
    !collapsed || query.isNotBlank()

internal fun boardTaskToggledStatus(status: String): String =
    if (status == "done" || status == "archived") "todo" else "done"

/**
 * 派发成功后应当自动打开的会话 id。
 * 服务端没给会话（只建了任务 / 会话还没落地）时返回 null，调用方据此决定要不要跳转。
 */
internal fun boardDispatchSessionId(sessionId: String?): String? =
    sessionId?.trim()?.takeIf { it.isNotEmpty() }

internal fun boardTaskCardTitle(task: BoardTask): String {
    val title = task.title.trim()
    if (title.isNotEmpty()) return title
    val fromDescription = task.description.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !isSyncedWorkspaceDescriptionLine(it) }
    return fromDescription ?: UNNAMED_TASK_NAME
}

/** 卡片一行里最多画几个会话 / 标签；多出来的折成「+N」，长任务不会把卡片撑成一面墙。 */
internal const val BOARD_TASK_CARD_SESSION_LIMIT = 3
internal const val BOARD_TASK_CARD_LABEL_LIMIT = 2

/**
 * 标签芯片的展示映射表（§2.20）：只登记**本轮已确证**的服务端内部标识，
 * 以后新增标识往这张表里加一行，不写成格式判断。
 * 表外的标签一律按用户数据原样渲染 —— 按 `snake_case` 之类规则批量隐藏会误删用户自己的标签
 * （负责人裁定 §0.6-3）。
 */
private val BOARD_TASK_LABEL_DISPLAY = mapOf(
    "team_direct" to "团队直发",
)

/** 一枚标签的展示文案：已知内部标识换成中文，其余原样。大小写完全一致才映射。 */
internal fun boardTaskLabelDisplay(label: String): String = BOARD_TASK_LABEL_DISPLAY[label] ?: label

/**
 * 分组标题行 ＋ 按钮的无障碍文案（§2.16）：分组名要加引号 —— 「进行中」本身以「中」结尾，
 * 裸拼接会读成「在进行中中新建任务」。文案写成纯函数，改动可被单测钉住。
 */
internal fun boardGroupAddTaskDescription(status: String): String =
    "在「${boardTaskStatusLabel(status)}」中新建任务"

/** 卡片上的会话行：标题优先，没标题就退回工具名。 */
internal data class BoardTaskCardSession(
    val id: String,
    val provider: String,
    val label: String,
    val running: Boolean,
    val isStructured: Boolean,
)

/** 卡片上的截止日期：已过期时交给 UI 换成警示语义色。 */
internal data class BoardTaskDue(
    val label: String,
    val overdue: Boolean,
)

internal data class BoardTaskCardModel(
    val title: String,
    /** 任务编号（WAND-12），卡片右上角等宽小字；服务端没给编号时为 null。 */
    val identifier: String?,
    /** 没有会话也没有指派时补一行描述摘要，避免卡片只剩一个标题。 */
    val body: String?,
    val workspaceName: String?,
    /** 里程碑名字（服务端 DTO 已解好）；未归属里程碑时为 null。 */
    val milestoneName: String?,
    val priority: String?,
    val agentLabel: String?,
    val labels: List<String>,
    val extraLabelCount: Int,
    val due: BoardTaskDue?,
    val processingLabel: String?,
    val running: Boolean,
    val sessions: List<BoardTaskCardSession>,
    val extraSessionCount: Int,
) {
    /** 元信息行是否有内容；全空时连 10dp 上间距都不占。 */
    val hasChips: Boolean
        get() = workspaceName != null || milestoneName != null || priority != null ||
            agentLabel != null || labels.isNotEmpty() || extraLabelCount > 0 || due != null
}

internal fun boardTaskCardModel(
    task: BoardTask,
    showWorkspace: Boolean,
    today: LocalDate = LocalDate.now(ZoneId.systemDefault()),
): BoardTaskCardModel {
    val workspaceName = task.workspace?.name?.trim().orEmpty()
    val labels = task.labels.filter { it.isNotBlank() }.map(::boardTaskLabelDisplay)
    val sessions = boardTaskCardSessions(task.sessions)
    val milestoneName = task.milestone?.name?.trim().orEmpty()
    return BoardTaskCardModel(
        title = boardTaskCardTitle(task),
        identifier = task.identifier.trim().takeIf { it.isNotEmpty() },
        body = boardTaskCardBody(task),
        workspaceName = workspaceName.takeIf { showWorkspace && it.isNotEmpty() },
        milestoneName = milestoneName.takeIf { it.isNotEmpty() },
        priority = task.priority.takeIf { it.isNotBlank() && it != "none" },
        agentLabel = boardTaskAgentLabels(task.sessions, task.agent),
        labels = labels.take(BOARD_TASK_CARD_LABEL_LIMIT),
        extraLabelCount = (labels.size - BOARD_TASK_CARD_LABEL_LIMIT).coerceAtLeast(0),
        due = boardTaskCardDue(task.dueDate, task.status, today),
        processingLabel = boardTaskProcessingLabel(task),
        running = task.sessions.any(::boardSessionRunning),
        sessions = sessions,
        extraSessionCount = (task.sessions.size - BOARD_TASK_CARD_SESSION_LIMIT).coerceAtLeast(0),
    )
}

/** 会话行按服务端顺序取前几条；标签缺失时用工具名占位，不出现空胶囊。 */
internal fun boardTaskCardSessions(
    sessions: List<BoardTaskSession>,
    limit: Int = BOARD_TASK_CARD_SESSION_LIMIT,
): List<BoardTaskCardSession> = sessions.take(limit).map { session ->
    BoardTaskCardSession(
        id = session.id,
        provider = session.provider,
        label = boardSessionCardLabel(session),
        running = boardSessionRunning(session),
        isStructured = session.isStructured,
    )
}

/** 会话标题；与工具名重复（或为空）时退回工具名，避免一行里出现两次「Claude」。 */
internal fun boardSessionCardLabel(session: BoardTaskSession): String {
    val provider = session.toolLabel
    val title = session.title.trim()
    if (title.isEmpty() || title.equals(provider, ignoreCase = true)) return provider
    return title
}

/**
 * 没有会话也没有指派时的描述摘要：
 * 取描述里第一行有内容的正文（跳过同步写进去的「项目 / 目录 / 分支」行），
 * 与标题重复的那行丢掉——自动标题本来就是从这行生成的。
 */
internal fun boardTaskCardBody(task: BoardTask): String? {
    if (task.sessions.isNotEmpty() || task.agent != null) return null
    val title = boardTaskCardTitle(task)
    return task.description.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !isSyncedWorkspaceDescriptionLine(it) }
        .filterNot { it.equals(title, ignoreCase = true) }
        .take(2)
        .joinToString("\n")
        .takeIf { it.isNotEmpty() }
}

/** 截止日期标签：M/D（对齐 Web 的 issueDueStamp），过期时前面加「逾期」，不只靠颜色表示。 */
internal fun boardTaskCardDue(
    dueDate: String?,
    status: String,
    today: LocalDate = LocalDate.now(ZoneId.systemDefault()),
): BoardTaskDue? {
    val value = dueDate?.trim().orEmpty()
    if (!BOARD_TASK_DUE_PATTERN.matches(value)) return null
    val overdue = boardTaskIsOverdue(value, status, today)
    val stamp = "${value.substring(5, 7).toInt()}/${value.substring(8, 10).toInt()}"
    return BoardTaskDue(label = if (overdue) "逾期 · $stamp" else stamp, overdue = overdue)
}

/** 已关闭的任务不再算逾期：做完/归档之后日期只剩记录意义。 */
internal fun boardTaskIsOverdue(
    dueDate: String,
    status: String,
    today: LocalDate = LocalDate.now(ZoneId.systemDefault()),
): Boolean = status != "done" && status != "archived" && dueDate < today.toString()

private val BOARD_TASK_DUE_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}$")

private fun isSyncedWorkspaceDescriptionLine(line: String): Boolean =
    line.startsWith("项目：") || line.startsWith("目录：") || line.startsWith("分支：")

internal enum class BoardTaskSessionState {
    Running,
    WaitingInput,
    Queued,
    WaitingAuthorization,
    Archived,
    Idle,
    Finished,
    Failed,
    Unknown,
}

/**
 * 任务卡只拿到轻量会话 DTO，不能把「没有运行」推断成已暂停。
 * 优先使用结构化 inFlight / executionStatus；老服务端缺字段时才回落到 status。
 */
internal fun boardTaskSessionState(session: BoardTaskSession): BoardTaskSessionState {
    val status = session.status.trim().lowercase()
    if (status in setOf("permission", "permission-blocked", "waiting-input", "waiting_input", "needs-input", "needs_input")) {
        return BoardTaskSessionState.WaitingInput
    }
    if (session.archived) return BoardTaskSessionState.Archived
    if (session.permissionBlocked) return BoardTaskSessionState.WaitingAuthorization
    if (session.inFlight == true || session.executionStatus == "running" ||
        (session.inFlight == null && status in setOf("running", "thinking"))) {
        return BoardTaskSessionState.Running
    }
    if (status == "failed") return BoardTaskSessionState.Failed
    if (status in setOf("exited", "stopped")) return BoardTaskSessionState.Finished
    if (session.executionStatus == "queued" || status == "queued") return BoardTaskSessionState.Queued
    if (status in setOf("failed", "error", "errored")) return BoardTaskSessionState.Failed
    if (status in setOf("exited", "completed", "complete", "done", "finished")) {
        return BoardTaskSessionState.Finished
    }
    if (status == "idle" || status == "ready") return BoardTaskSessionState.Idle
    return BoardTaskSessionState.Unknown
}

internal data class BoardTaskSessionSummary(
    val running: Int = 0,
    val waitingInput: Int = 0,
    val queued: Int = 0,
    val waitingAuthorization: Int = 0,
    val archived: Int = 0,
    val idle: Int = 0,
    val finished: Int = 0,
    val failed: Int = 0,
    val unknown: Int = 0,
) {
    val total: Int get() = running + waitingInput + queued + waitingAuthorization + archived + idle + finished + failed + unknown
}

internal fun boardTaskSessionSummary(sessions: List<BoardTaskSession>): BoardTaskSessionSummary {
    var result = BoardTaskSessionSummary()
    sessions.forEach { session ->
        result = when (boardTaskSessionState(session)) {
            BoardTaskSessionState.Running -> result.copy(running = result.running + 1)
            BoardTaskSessionState.WaitingInput -> result.copy(waitingInput = result.waitingInput + 1)
            BoardTaskSessionState.Queued -> result.copy(queued = result.queued + 1)
            BoardTaskSessionState.WaitingAuthorization -> result.copy(waitingAuthorization = result.waitingAuthorization + 1)
            BoardTaskSessionState.Archived -> result.copy(archived = result.archived + 1)
            BoardTaskSessionState.Idle -> result.copy(idle = result.idle + 1)
            BoardTaskSessionState.Finished -> result.copy(finished = result.finished + 1)
            BoardTaskSessionState.Failed -> result.copy(failed = result.failed + 1)
            BoardTaskSessionState.Unknown -> result.copy(unknown = result.unknown + 1)
        }
    }
    return result
}

internal fun boardTaskSessionSummaryLabel(summary: BoardTaskSessionSummary): String? = buildList {
    if (summary.running > 0) add("运行中 · ${summary.running}")
    if (summary.waitingInput > 0) add("等待输入 · ${summary.waitingInput}")
    if (summary.queued > 0) add("排队 · ${summary.queued}")
    if (summary.waitingAuthorization > 0) add("等待授权 · ${summary.waitingAuthorization}")
    if (summary.archived > 0) add("已归档 · ${summary.archived}")
    if (summary.idle > 0) add("空闲 · ${summary.idle}")
    if (summary.finished > 0) add("已结束 · ${summary.finished}")
    if (summary.failed > 0) add("失败 · ${summary.failed}")
    // 未知状态必须显式保守展示，不能冒充「暂停」或「已完成」。
    if (summary.unknown > 0) add("状态未知 · ${summary.unknown}")
}.takeIf { it.isNotEmpty() }?.joinToString(" · ")

internal fun boardSessionRunning(status: String): Boolean = status == "running"

internal fun boardSessionRunning(session: BoardTaskSession): Boolean =
    boardTaskSessionState(session) == BoardTaskSessionState.Running

internal fun boardSessionFinished(status: String): Boolean =
    status in setOf("exited", "completed", "complete", "done", "finished", "idle")

internal fun boardTaskProcessingLabel(task: BoardTask): String? {
    if (task.status != "doing") return null
    val summary = boardTaskSessionSummary(task.sessions)
    val queued = task.queuedExecutionCount ?: task.queuedCommentCount
    if (summary.total == 0) {
        return when {
            task.executionStatus == "running" -> "正在处理..."
            queued > 0 -> "排队中 · $queued 条等待执行"
            else -> "等待派发"
        }
    }
    if (summary.running == summary.total) return "正在处理..."
    if (summary.queued > 0 && summary.total == 1) return "排队中 · ${summary.queued} 条等待执行"
    if (!task.acceptance?.acceptedAt.isNullOrBlank()) return "验收通过 · 已完成"
    if (!task.acceptance?.blocker.isNullOrBlank()) return "待处理：${task.acceptance?.blocker}"
    return boardTaskSessionSummaryLabel(summary)?.plus(" · 尚待验收")
}

// MARK: - 新建对话框的「指派对象」（对齐 Web 看板新建的第一次指派，§5.1）

/**
 * 团队组是否出现在「指派对象」里：只有「进行中」列新建（创建即决定要跑）且有团队时。
 * 「待办」列只建卡，不给选团队（选了也没有派发时机）。
 */
internal fun boardCreateTeamChoiceVisible(teams: List<AiTeam>, dispatches: Boolean): Boolean =
    dispatches && teams.isNotEmpty()

/** 「指派对象」选项："" = CLI 工具；团队标签 `名字（N 人）`，与 TaskBoardDetailPane 派发表单同口径。 */
internal fun boardCreateTargetOptions(
    teams: List<AiTeam>,
    dispatches: Boolean,
): List<Pair<String, String>> = buildList {
    add("" to "CLI 工具")
    if (boardCreateTeamChoiceVisible(teams, dispatches)) {
        teams.forEach { add(it.id to "${it.name}（${it.members.size} 人）") }
    }
}

/**
 * 「本次提交要不要走交给团队链路」的唯一判定：handler 的跳过建卡取 id、重发调用、
 * 按钮文案三处都读它，不再各写一份条件（上轮 dispatches 口径不一致的根因）。
 */
internal fun boardDispatchesToTeam(
    teamSelected: Boolean,
    dispatches: Boolean,
    hasDescription: Boolean,
): Boolean = teamSelected && dispatches && hasDescription

/**
 * 确认按钮文案：创建中 / 交给团队 / 重试交给团队 / 创建并指派 / 纯创建。
 * teamRunRetry = 上一次「建卡成功但交给团队失败」——再点只重发第二步，绝不重复建卡。
 */
internal fun boardCreateActionLabel(
    teamSelected: Boolean,
    dispatches: Boolean,
    hasDescription: Boolean,
    busy: Boolean,
    teamRunRetry: Boolean = false,
    employeeSelected: Boolean = false,
): String = when {
    busy && employeeSelected && dispatches && hasDescription -> "正在交给员工…"
    busy && teamSelected -> "正在交给团队…"
    busy -> "创建中…"
    employeeSelected && dispatches && hasDescription -> "创建并交给员工"
    boardDispatchesToTeam(teamSelected, dispatches, hasDescription) && teamRunRetry -> "重试交给团队"
    teamSelected && dispatches && hasDescription -> "创建并交给团队"
    !teamSelected && dispatches && hasDescription -> "创建并指派"
    else -> "创建任务"
}
