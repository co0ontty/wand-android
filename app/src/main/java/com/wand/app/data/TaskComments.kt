package com.wand.app.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** 服务端冻结的执行轮次锚点；缺失表示旧记录无法证明确切轮次，不能展示「本轮结果」。 */
data class TaskCommentResultAnchor(
    val requestId: String,
    val startIndex: Int,
    val endIndex: Int?,
    val outcome: String?,
)

data class TaskCommentDispatch(
    val employeeId: String,
    val employeeName: String,
    val state: String,
    val sessionId: String?,
    val status: String?,
    /** 派发被明确拒绝时的原因；未知送达不是这里，而是 [deliveryLabel]。 */
    val error: String? = null,
    /** 本评论引用的子任务 id，按正文出现顺序。 */
    val referencedTaskIds: List<String> = emptyList(),
    val resultAnchor: TaskCommentResultAnchor? = null,
) {
    val deliveryUncertain: Boolean get() = state in setOf("pending", "unknown") || status in setOf("pending", "unknown")

    /**
     * 状态胶囊文案；与 Web 任务流共用同一份词表（服务端 `src/task-comment-status.ts` 为唯一真源）。
     * 词表只保留这一份，改动前先对齐那份契约，避免两端漂移。
     */
    val statusLabel: String get() = when (status ?: state) {
        "paused" -> "已暂停"; "queued" -> "排队中"; "pending" -> "等待确认"; "accepted" -> "已创建执行"; "running" -> "执行中"; "blocked" -> "等待审批"
        "idle" -> "本轮已结束"; "exited" -> "会话已退出"; "stopped" -> "已停止"; "failed" -> "执行失败"
        "done" -> "已完成"; "unknown" -> "送达未知 · 不会重放"; "history" -> "历史会话"; "unavailable" -> "会话不可用"
        else -> "状态未确认"
    }

    /** 送达标记：只反映服务端承认的投递事实，不从模型正文推断。 */
    val deliveryLabel: String get() = when (state) {
        "paused" -> "已暂停"; "accepted" -> "已送达"; "queued" -> "排队中"; "pending" -> "送达待确认"; "unknown" -> "送达未知"; "failed" -> "未送达"
        else -> "送达未确认"
    }

    /** 服务端能证明确切执行轮次时，才允许展示「本轮结果」。 */
    val hasProvenRound: Boolean get() = resultAnchor != null
}

data class TaskComment(
    val id: String,
    val taskId: String,
    val authorName: String,
    val body: String,
    val createdAt: String,
    val sourceCommentId: String? = null,
    val dispatch: TaskCommentDispatch? = null,
    /** 本评论引用的子任务 id，按正文出现顺序。 */
    val referencedTaskIds: List<String> = emptyList(),
    /**
     * 引用目标的编号（`TASK-133`），对外展示用；[referencedTaskIds] 是内部稳定 ID。
     * 与 Web 端同一个 API 字段（`referencedTaskIdentifiers`）；老服务端不返回时留空，
     * 由 [TaskCommentReference.executionReferenceChips] 回到子任务列表里换编号。
     */
    val referencedTaskIdentifiers: List<String> = emptyList(),
) {
    companion object {
        fun parseList(response: JSONObject, taskId: String): List<TaskComment> {
            val comments = response.optJSONArray("comments") ?: return emptyList()
            return (0 until comments.length()).mapNotNull { index ->
                val item = comments.optJSONObject(index) ?: return@mapNotNull null
                if (item.optString("taskId") != taskId || item.optString("id").isBlank() ||
                    item.optString("body").isBlank()) return@mapNotNull null
                val referenced = item.arr("referencedTaskIds")?.stringItems(ignoreBlank = true).orEmpty()
                val referencedIdentifiers = item.arr("referencedTaskIdentifiers")?.stringItems(ignoreBlank = true).orEmpty()
                TaskComment(item.getString("id"), taskId, item.optString("authorName"),
                    item.getString("body"), item.optString("createdAt"), item.optString("sourceCommentId").takeUnless { it.isBlank() || it == "null" },
                    item.optJSONObject("dispatch")?.let { d ->
                        TaskCommentDispatch(
                            employeeId = d.optString("employeeId"),
                            employeeName = d.optString("employeeName"),
                            state = d.optString("state"),
                            sessionId = d.optString("sessionId").takeUnless { it.isBlank() || it == "null" },
                            status = d.optString("status").takeUnless { it.isBlank() || it == "null" },
                            error = d.optString("error").takeUnless { it.isBlank() || it == "null" },
                            referencedTaskIds = d.arr("referencedTaskIds")?.stringItems(ignoreBlank = true).orEmpty()
                                .ifEmpty { referenced },
                            resultAnchor = d.optJSONObject("resultAnchor")?.let { anchor ->
                                TaskCommentResultAnchor(
                                    requestId = anchor.optString("requestId"),
                                    startIndex = anchor.optInt("startIndex", -1),
                                    endIndex = if (anchor.isNull("endIndex")) null else anchor.optInt("endIndex", -1),
                                    outcome = anchor.optString("outcome").takeUnless { it.isBlank() || it == "null" },
                                )
                            },
                        )
                    },
                    referenced,
                    referencedIdentifiers)
            }
        }
    }
}

/** 评论分页页；老服务端不返回游标时 [nextCursor] 为空，只能看最新一页。 */
data class TaskCommentPage(
    val comments: List<TaskComment>,
    val totalCount: Int,
    val nextCursor: String?,
)

/** 固定执行轮次里的一条发言（只保留展示与统计需要的字段）。 */
data class TaskCommentTurn(
    val role: String,
    val text: String,
    /** 工具调用名，按出现顺序。 */
    val tools: List<String>,
    val thinkingBlocks: Int,
    val createdAt: String?,
    val completedAt: String?,
)

/** 固定执行轮次结果。 */
data class TaskCommentResult(
    val available: Boolean,
    /** 服务端给出的不可读原因（still_running / unconfirmed_legacy）。 */
    val reason: String?,
    val outcome: String,
    val messages: List<TaskCommentTurn>,
    val offset: Int,
    val total: Int,
) {
    companion object {
        fun parse(response: JSONObject): TaskCommentResult {
            val messages = response.optJSONArray("messages")
            val turns = (0 until (messages?.length() ?: 0)).mapNotNull { index ->
                val turn = messages?.optJSONObject(index) ?: return@mapNotNull null
                val blocks = turn.optJSONArray("content")
                val text = StringBuilder()
                val tools = mutableListOf<String>()
                var thinking = 0
                for (blockIndex in 0 until (blocks?.length() ?: 0)) {
                    val block = blocks?.optJSONObject(blockIndex) ?: continue
                    when (block.optString("type")) {
                        "text" -> text.append(block.optString("text"))
                        "tool_use" -> tools.add(block.optString("name"))
                        "thinking" -> thinking += 1
                    }
                }
                TaskCommentTurn(
                    role = turn.optString("role"),
                    text = text.toString(),
                    tools = tools,
                    thinkingBlocks = thinking,
                    createdAt = turn.optString("createdAt").takeUnless { it.isBlank() || it == "null" },
                    completedAt = turn.optString("completedAt").takeUnless { it.isBlank() || it == "null" },
                )
            }
            return TaskCommentResult(
                available = response.optBoolean("available", false),
                reason = response.optString("reason").takeUnless { it.isBlank() || it == "null" },
                outcome = response.optString("outcome"),
                messages = turns,
                offset = response.optInt("offset", 0),
                total = response.optInt("total", 0),
            )
        }
    }
}

/** 动作明细：按工具分类计数，供执行卡片展开区展示。 */
data class TaskCommentActionBreakdown(
    val toolCounts: List<Pair<String, Int>>,
    val actionCount: Int,
    val thinkingCount: Int,
    val replyCount: Int,
)

/** 统计固定轮次里的动作 / 思考 / 回复段数。 */
fun taskCommentActionBreakdown(result: TaskCommentResult): TaskCommentActionBreakdown {
    val counts = LinkedHashMap<String, Int>()
    var thinking = 0
    var replies = 0
    for (turn in result.messages) {
        for (tool in turn.tools) counts[tool] = (counts[tool] ?: 0) + 1
        thinking += turn.thinkingBlocks
        if (turn.role == "assistant" && turn.text.isNotBlank()) replies += 1
    }
    return TaskCommentActionBreakdown(
        toolCounts = counts.entries.sortedByDescending { it.value }.map { it.key to it.value },
        actionCount = counts.values.sum(),
        thinkingCount = thinking,
        replyCount = replies,
    )
}

/** 本轮耗时：从首轮开始到最后一段结束。缺时间戳时返回 null，不猜。 */
fun taskCommentElapsedMillis(result: TaskCommentResult): Long? {
    val start = result.messages.firstOrNull { !it.createdAt.isNullOrBlank() }?.createdAt ?: return null
    val end = result.messages.lastOrNull { !it.completedAt.isNullOrBlank() }?.completedAt
        ?: result.messages.lastOrNull { !it.createdAt.isNullOrBlank() }?.createdAt
        ?: return null
    val startMillis = parseIsoMillis(start) ?: return null
    val endMillis = parseIsoMillis(end) ?: return null
    return (endMillis - startMillis).coerceAtLeast(0)
}

/** ISO-8601 → 毫秒；解析不了返回 null（不猜时间）。 */
internal fun parseIsoMillis(value: String): Long? = try {
    java.time.Instant.parse(value).toEpochMilli()
} catch (_: Exception) {
    try {
        java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    }
}

/** Recheck cancellation after even a non-cancellable transport; never apply another task's rows. */
internal suspend fun loadTaskComments(api: TaskBoardPort, taskId: String): List<TaskComment> {
    val comments = api.listTaskComments(taskId)
    currentCoroutineContext().ensureActive()
    return comments.filter { it.taskId == taskId && it.body.isNotBlank() }
}

/**
 * 同上，但带分页：读回来先确认协程还活着，否则换任务后迟到的响应会落到新任务上。
 */
internal suspend fun loadTaskCommentPage(
    api: TaskBoardPort,
    taskId: String,
    limit: Int = 50,
    before: String? = null,
): TaskCommentPage {
    val page = api.listTaskCommentPage(taskId, limit, before)
    currentCoroutineContext().ensureActive()
    return page
}
