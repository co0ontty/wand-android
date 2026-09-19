package com.wand.app.ui.screens

import com.wand.app.data.BOARD_TASK_STATUSES
import com.wand.app.data.BoardTask
import com.wand.app.data.BoardTaskSession
import com.wand.app.data.boardTaskAgentLabels
import com.wand.app.data.boardTaskProviderLabel
import java.time.LocalDate
import java.time.ZoneId

internal data class BoardTaskStats(
    val total: Int,
    val todo: Int,
    val doing: Int,
    val done: Int,
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

/** 顺序以 GET /api/wand-tasks 返回为准，客户端不再本地排序。 */
internal fun sortBoardTasks(tasks: List<BoardTask>): List<BoardTask> = tasks

internal fun groupedBoardTasks(tasks: List<BoardTask>): List<Pair<String, List<BoardTask>>> =
    BOARD_TASK_STATUSES.map { status -> status to tasks.filter { it.status == status } }

internal fun boardArchivedTasks(tasks: List<BoardTask>): List<BoardTask> =
    tasks.filter { it.status == "archived" }

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
    val labels = task.labels.filter { it.isNotBlank() }
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
        running = task.sessions.any { boardSessionRunning(it.status) },
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
        running = boardSessionRunning(session.status),
        isStructured = session.isStructured,
    )
}

/** 会话标题；与工具名重复（或为空）时退回工具名，避免一行里出现两次「Claude」。 */
internal fun boardSessionCardLabel(session: BoardTaskSession): String {
    val provider = boardTaskProviderLabel(session.provider)
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

internal fun boardSessionRunning(status: String): Boolean = status == "running"

internal fun boardSessionFinished(status: String): Boolean =
    status == "exited" || status == "idle"

internal fun boardTaskProcessingLabel(task: BoardTask): String? {
    if (task.status != "doing") return null
    val running = task.sessions.any { boardSessionRunning(it.status) }
    val finished = task.sessions.isNotEmpty() && task.sessions.all { boardSessionFinished(it.status) }
    return when {
        running -> "正在处理..."
        finished -> "等待验收"
        task.sessions.isNotEmpty() -> "暂停处理"
        else -> "等待派发"
    }
}

