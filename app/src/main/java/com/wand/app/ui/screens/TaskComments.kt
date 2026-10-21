package com.wand.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.wand.app.data.BoardTask
import com.wand.app.data.TaskBoardPort
import com.wand.app.data.TaskComment
import com.wand.app.data.TaskCommentActionBreakdown
import com.wand.app.data.TaskCommentDispatch
import com.wand.app.data.TaskCommentMention
import com.wand.app.data.TaskCommentReference
import com.wand.app.data.TaskCommentResult
import com.wand.app.data.SiliconEmployee
import com.wand.app.data.boardTaskStatusLabel
import com.wand.app.data.loadTaskCommentPage
import com.wand.app.data.taskCommentActionBreakdown
import com.wand.app.data.taskCommentElapsedMillis
import com.wand.app.ui.components.*
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandShapes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray

internal data class CommentDraft(val body: String = "", val employeeId: String = "", val source: String? = null, val revision: Long = 0)
internal object TaskCommentDrafts {
    private val drafts = mutableMapOf<String, CommentDraft>()
    fun get(taskId: String) = drafts[taskId] ?: CommentDraft()
    fun set(taskId: String, value: CommentDraft): CommentDraft = value.copy(revision = get(taskId).revision + 1).also { drafts[taskId] = it }
    fun accept(taskId: String, submitted: CommentDraft) = if (get(taskId).revision == submitted.revision) set(taskId, CommentDraft()) else get(taskId)
}

/** 引用选择器的一项：编号 + 标题 + 状态。 */
private data class ReferenceCandidate(val id: String, val identifier: String, val title: String, val status: String)

/** 固定执行轮次的读取状态。 */
private data class TaskCommentResultState(
    val result: TaskCommentResult? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val offset: Int = 0,
)

/**
 * 任务详情里的「可引用任务」区：当前任务的直接子任务，点进去就能跳到那个子任务。
 * 移动端放在主描述下方（Web 放在描述卡片之后）。
 */
@Composable
internal fun TaskReferenceableTasks(childTasks: List<BoardTask>, onOpenTask: (String) -> Unit) {
    if (childTasks.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("可引用任务", style = MaterialTheme.typography.titleSmall, color = WandColors.textPrimary)
            Text("${childTasks.size} 个子任务", style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
            Text("评论中输入 # 选择", style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
        }
        childTasks.forEach { child ->
            // 编号才是用户写进正文的句柄；服务端保证编号非空，这里不再拿内部 id 顶替（会露出 UUID）。
            val identifier = child.identifier
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(WandShapes.sm)
                    .background(WandColors.surface)
                    .border(1.dp, WandColors.border, WandShapes.sm)
                    .selectable(
                        selected = false,
                        role = Role.Button,
                        onClick = { onOpenTask(child.id) },
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .semantics {
                        contentDescription = "打开子任务 $identifier ${child.title} ${boardTaskStatusLabel(child.status)}"
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "#$identifier",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = WandColors.infoText,
                )
                Text(
                    child.title.ifBlank { "（无标题）" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = WandColors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                TaskStatusPill(boardTaskStatusLabel(child.status), boardTaskStatusTone(child.status))
            }
        }
    }
}

/** 任务状态 → 语义色调。 */
private fun boardTaskStatusTone(status: String): WandStatusTone = when (status) {
    "done" -> WandStatusTone.Success
    "doing" -> WandStatusTone.Warning
    "archived" -> WandStatusTone.Neutral
    else -> WandStatusTone.Neutral
}

/** 员工派发状态 → 语义色调。 */
private fun dispatchStatusTone(status: String): WandStatusTone = when (status) {
    "done", "idle", "accepted" -> WandStatusTone.Success
    "queued", "running", "pending" -> WandStatusTone.Warning
    "blocked" -> WandStatusTone.Permission
    "failed" -> WandStatusTone.Danger
    "stopped" -> WandStatusTone.Warning
    else -> WandStatusTone.Neutral
}

/** 小号状态胶囊。 */
@Composable
private fun TaskStatusPill(label: String, tone: WandStatusTone) {
    val background = when (tone) {
        WandStatusTone.Success -> WandColors.successSoft
        WandStatusTone.Warning -> WandColors.warningSoft
        WandStatusTone.Danger -> WandColors.dangerSoft
        WandStatusTone.Permission -> WandColors.warningSoft
        WandStatusTone.Neutral -> WandColors.surfaceSoft
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = tone.statusTextColor(),
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** 正文里的 `#TASK-133` 渲染成引用 token：等宽字体 + 主题色，点击跳到该子任务。 */
@Composable
private fun TaskReferenceText(
    body: String,
    onOpenTask: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = WandColors.textSecondary,
) {
    val tokenColor = WandColors.infoText
    val currentOnOpenTask by rememberUpdatedState(onOpenTask)
    val annotated: AnnotatedString = remember(body, tokenColor) {
        buildAnnotatedString {
            for (segment in TaskCommentReference.taskReferenceSegments(body)) {
                val identifier = segment.identifier
                if (identifier == null) {
                    append(segment.text)
                } else {
                    withStyle(SpanStyle(color = tokenColor, fontFamily = FontFamily.Monospace)) {
                        pushLink(
                            LinkAnnotation.Clickable(
                                tag = identifier,
                                styles = TextLinkStyles(
                                    style = SpanStyle(color = tokenColor, fontFamily = FontFamily.Monospace),
                                ),
                                linkInteractionListener = { link ->
                                    val tag = (link as? LinkAnnotation.Clickable)?.tag ?: identifier
                                    currentOnOpenTask(tag)
                                },
                            ),
                        )
                        append(segment.text)
                        pop()
                    }
                }
            }
        }
    }
    Text(annotated, modifier = modifier, style = style, color = color)
}

/** 员工执行卡片：头像 + 名字 + 状态胶囊 + 动作数 + 送达标记 + 耗时；正文是任务 / 回复两行。 */
@Composable
private fun TaskExecutionCard(
    comment: TaskComment,
    dispatch: TaskCommentDispatch,
    employee: SiliconEmployee?,
    resultState: TaskCommentResultState,
    expanded: Boolean,
    onToggleDetail: () -> Unit,
    onLocateComment: () -> Unit,
    onOpenSession: (String, Boolean) -> Unit,
    onLoadResult: (Int) -> Unit,
    onOpenTask: (String) -> Unit,
    referenceTargets: List<TaskCommentReference.Target>,
) {
    val result = resultState.result
    val breakdown: TaskCommentActionBreakdown? = result?.let { taskCommentActionBreakdown(it) }
    val replyText = result?.messages.orEmpty()
        .filter { it.role == "assistant" && it.text.isNotBlank() }
        .joinToString("\n") { it.text }
        .trim()
    val elapsed = result?.let { taskCommentElapsedMillis(it) }
    val status = dispatch.status ?: dispatch.state
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(WandShapes.md)
            .background(WandColors.surface)
            .border(1.dp, WandColors.border, WandShapes.md)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EmployeeAvatar(
                id = dispatch.employeeId,
                name = dispatch.employeeName,
                avatar = employee?.avatar,
                size = 22.dp,
            )
            Text(
                dispatch.employeeName.ifBlank { "员工" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = WandColors.textPrimary,
            )
            TaskStatusPill(dispatch.statusLabel, dispatchStatusTone(status))
            Text(
                taskDeliveryLabel(dispatch),
                style = MaterialTheme.typography.labelSmall,
                color = WandColors.textMuted,
            )
            Text(
                elapsed?.let { formatTaskElapsed(it) } ?: "—",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = WandColors.textMuted,
            )
        }
        // 任务行：本轮指令原文。
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            TaskRowTag("任务")
            TaskReferenceText(
                body = comment.body,
                onOpenTask = onOpenTask,
                modifier = Modifier.weight(1f),
            )
        }
        // 回复行：执行结论。
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            TaskRowTag("回复")
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (replyText.isNotBlank()) {
                    Text(
                        replyText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = WandColors.textSecondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(WandShapes.sm)
                            .background(WandColors.bgPrimary)
                            .border(1.dp, WandColors.border, WandShapes.sm)
                            .padding(10.dp),
                    )
                } else {
                    Text(
                        if (resultState.loading) "正在读取本轮结果…" else "尚未读取本轮结果。",
                        style = MaterialTheme.typography.labelSmall,
                        color = WandColors.textMuted,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            WandButton(
                label = when {
                    expanded -> "收起动作明细"
                    breakdown != null -> "${breakdown.actionCount} 个动作"
                    else -> "动作明细"
                },
                onClick = onToggleDetail,
                variant = WandButtonVariant.Text,
                compact = true,
                enabled = true,
            )
            WandButton(label = "定位原评论", onClick = onLocateComment, variant = WandButtonVariant.Text, compact = true)
            dispatch.sessionId?.let { session ->
                WandButton(label = "查看本次执行", onClick = { onOpenSession(session, true) }, variant = WandButtonVariant.Text, compact = true)
            }
            if (dispatch.hasProvenRound) {
                WandButton(
                    label = if (result == null) "查看本轮结果" else "刷新本轮结果",
                    onClick = { onLoadResult(0) },
                    variant = WandButtonVariant.Text,
                    compact = true,
                    enabled = !resultState.loading,
                )
            }
        }
        // 目标行：本轮引用了哪些子任务（用编号展示，编号才是用户写进正文的句柄）。
        val referenceChips = remember(comment.id, comment.referencedTaskIdentifiers, comment.referencedTaskIds, referenceTargets) {
            TaskCommentReference.executionReferenceChips(comment, referenceTargets)
        }
        if (referenceChips.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("目标", style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
                referenceChips.forEach { chip ->
                    TaskReferenceChip(label = "#${chip.identifier}") { onOpenTask(chip.identifier) }
                }
            }
        }
        dispatch.error?.let { error ->
            Text(error, style = MaterialTheme.typography.labelSmall, color = WandColors.dangerText)
        }
        if (resultState.failed) {
            Text("本轮结果加载失败，请重试。", style = MaterialTheme.typography.labelSmall, color = WandColors.dangerText)
        }
        result?.takeIf { !it.available }?.let {
            Text(
                if (it.reason == "still_running") "本轮执行仍在进行；完成后可读取固定轮次结果。"
                else "旧记录缺少可验证的执行轮次，无法安全展示结果。",
                style = MaterialTheme.typography.labelSmall,
                color = WandColors.textMuted,
            )
        }
        if (expanded) {
            TaskActionBreakdown(breakdown, dispatch, result)
        }
    }
}

/** 送达标记：只反映服务端承认的投递事实。 */
private fun taskDeliveryLabel(dispatch: TaskCommentDispatch): String = when (dispatch.state) {
    "accepted" -> "▷ 已送达"
    "queued" -> "▷ 排队中"
    "paused" -> "▷ 已暂停"
    "pending" -> "▷ 送达待确认"
    "unknown" -> "▷ 送达未知"
    "failed" -> "▷ 未送达"
    else -> "▷ 送达未确认"
}

/** 引用 chip：`#TASK-133`，等宽字体 + 主题色，点开对应子任务。 */
@Composable
private fun TaskReferenceChip(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = WandColors.infoText,
        modifier = Modifier
            .clip(WandShapes.xs)
            .background(WandColors.infoSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .semantics { contentDescription = "打开目标 $label" },
    )
}

/** 任务 / 回复 两行的行首标签。 */
@Composable
private fun TaskRowTag(label: String, reply: Boolean = false) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = if (reply) WandColors.successText else WandColors.textSecondary,
        modifier = Modifier
            .clip(WandShapes.xs)
            .background(if (reply) WandColors.successSoft else WandColors.surfaceSoft)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** 动作明细：工具分类计数 + 本轮元信息。 */
@Composable
private fun TaskActionBreakdown(
    breakdown: TaskCommentActionBreakdown?,
    dispatch: TaskCommentDispatch,
    result: TaskCommentResult?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(WandShapes.sm)
            .background(WandColors.bgPrimary)
            .border(1.dp, WandColors.border, WandShapes.sm),
    ) {
        Text(
            if (breakdown == null) "动作明细 · 尚未读取本轮结果"
            else "动作明细 · ${breakdown.actionCount} 个动作 / ${breakdown.thinkingCount} 段思考 / ${breakdown.replyCount} 段回复",
            style = MaterialTheme.typography.labelSmall,
            color = WandColors.textMuted,
            modifier = Modifier
                .fillMaxWidth()
                .background(WandColors.surface)
                .padding(horizontal = 10.dp, vertical = 7.dp),
        )
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (breakdown == null || breakdown.toolCounts.isEmpty()) {
                Text("没有可展示的动作记录。", style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
            }
            breakdown?.toolCounts?.forEach { (tool, count) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(WandColors.brandSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            tool.take(1).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = WandColors.brandText,
                        )
                    }
                    Text(tool, style = MaterialTheme.typography.bodySmall, color = WandColors.textPrimary, modifier = Modifier.weight(1f))
                    Text("$count 次", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = WandColors.textMuted)
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("送达 ${dispatch.deliveryLabel}", style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
                Text("执行状态 ${dispatch.statusLabel}", style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
                result?.outcome?.takeIf { it.isNotBlank() }?.let {
                    Text("本轮结果 $it", style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
                }
            }
        }
    }
}

/** 耗时文案：`2h 38m 22s`。 */
internal fun formatTaskElapsed(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

/** A chosen @ mention owns stable identity; literal @ text alone cannot dispatch. */
@Composable
internal fun TaskComments(
    api: TaskBoardPort,
    taskId: String,
    employees: List<SiliconEmployee> = emptyList(),
    childTasks: List<BoardTask> = emptyList(),
    onOpenSession: (String, Boolean) -> Unit = { _, _ -> },
    onOpenTask: (String) -> Unit = {},
) {
    var comments by remember(api, taskId) { mutableStateOf<List<TaskComment>>(emptyList()) }
    /** 已经翻出来的更早页；轮询只刷新最新一页，不能把用户翻过的历史抹掉。 */
    var olderComments by remember(taskId) { mutableStateOf<List<TaskComment>>(emptyList()) }
    var olderCursor by remember(taskId) { mutableStateOf<String?>(null) }
    var totalCount by remember(api, taskId) { mutableIntStateOf(0) }
    var loadingOlder by remember(taskId) { mutableStateOf(false) }
    var loadFailed by remember(taskId) { mutableStateOf(false) }
    var draft by remember(taskId) { mutableStateOf(TaskCommentDrafts.get(taskId)) }
    var field by remember(taskId) { mutableStateOf(TextFieldValue(draft.body, TextRange(draft.body.length))) }
    var busy by remember(taskId) { mutableStateOf(false) }
    var message by remember(taskId) { mutableStateOf("") }
    var outcome by remember(taskId) { mutableStateOf("idle") }
    var refresh by remember(taskId) { mutableIntStateOf(0) }
    var referenceHighlight by remember(taskId) { mutableIntStateOf(0) }
    var dismissedReference by remember(taskId) { mutableStateOf("") }
    var focusedCard by remember(taskId) { mutableStateOf<String?>(null) }
    var focusedComment by remember(taskId) { mutableStateOf<String?>(null) }
    val results = remember(taskId) { mutableStateMapOf<String, TaskCommentResultState>() }
    val prefs = LocalContext.current.getSharedPreferences("task-comment-requests", android.content.Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(api, taskId, lifecycle, refresh) {
        lifecycle.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                try {
                    val page = loadTaskCommentPage(api, taskId, 50)
                    comments = page.comments
                    totalCount = page.totalCount
                    // 只在还没翻过历史时用最新页的游标；翻过之后游标指向更深的一页。
                    if (olderComments.isEmpty()) olderCursor = page.nextCursor
                    loadFailed = false
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    loadFailed = true
                }
                delay(5_000)
            }
        }
    }
    val activeEmployees = employees.filter { !it.archived }
    val visibleComments = (olderComments + comments)
        .distinctBy { it.id }
        .sortedWith(compareBy({ it.createdAt }, { it.id }))
    val mention = TaskCommentMention.query(field.text, field.selection.end)
    val referenceQuery = TaskCommentReference.commentReferenceQuery(field.text, field.selection.end)
    val referenceKey = referenceQuery?.let { "${it.start}:${it.query}" }.orEmpty()
    // 引用选择器只收有编号的子任务：编号才是句柄，没有编号的子任务没法用 `#` 引用，
    // 也不能拿内部 id 顶替（会露出 UUID）。
    val candidates = childTasks.filter { it.identifier.isNotBlank() }
        .map { ReferenceCandidate(it.id, it.identifier, it.title, it.status) }
    /** 引用编号 → 子任务 id：点正文里的 `#TASK-133` 要按编号找子任务，不能把编号当任务 id 用。 */
    val referenceTargets = remember(childTasks) {
        childTasks.map { TaskCommentReference.Target(it.id, it.identifier) }
    }
    val openReferencedTask: (String) -> Unit = { identifier ->
        TaskCommentReference.taskReferenceTargetId(identifier, referenceTargets)?.let(onOpenTask)
    }
    val referenceMatches = if (referenceQuery == null || dismissedReference == referenceKey) emptyList()
    else candidates.filter {
        referenceQuery.query.isEmpty() ||
            it.identifier.contains(referenceQuery.query, ignoreCase = true) ||
            it.title.contains(referenceQuery.query, ignoreCase = true)
    }
    val matches = if (mention == null) emptyList() else activeEmployees.filter { mention.text.isEmpty() || it.name.contains(mention.text, ignoreCase = true) }
    val bound = activeEmployees.firstOrNull { it.id == draft.employeeId }
    val dispatching = bound != null && TaskCommentMention.holds(draft.body, bound.name)
    val validation = if (field.composition != null) "请先完成输入法组字，再提交评论。"
        else if (mention != null) "请先从 @ 候选列表选择员工，或删除未完成的提及。"
        else TaskCommentMention.validationMessage(draft.body, if (dispatching) bound?.name.orEmpty() else "")
    val referenceTarget = TaskCommentReference.parseTaskReferenceIds(draft.body).firstOrNull()
    // 换了引用 token 就把高亮回到第一项，避免旧的选中项落在新列表的越界位置。
    LaunchedEffect(referenceKey) { referenceHighlight = 0 }

    fun chooseReference(candidate: ReferenceCandidate) {
        val next = TaskCommentReference.insertCommentReference(field.text, field.selection.end, candidate.identifier)
        draft = TaskCommentDrafts.set(taskId, draft.copy(body = next.first))
        field = TextFieldValue(next.first, TextRange(next.second))
        referenceHighlight = 0
        dismissedReference = ""
        outcome = "idle"
    }

    fun loadResult(commentId: String, offset: Int) {
        if (results[commentId]?.loading == true) return
        results[commentId] = (results[commentId] ?: TaskCommentResultState()).copy(loading = true, failed = false)
        scope.launch {
            try {
                val page = api.taskCommentResult(taskId, commentId, offset, 40)
                if (page == null) {
                    results[commentId] = TaskCommentResultState(failed = true)
                } else if (!page.available) {
                    results[commentId] = TaskCommentResultState(result = page, loading = false)
                } else {
                    val previous = results[commentId]?.result
                    val merged = if (offset == 0 || previous == null) page else previous.copy(
                        messages = previous.messages + page.messages,
                        total = page.total,
                    )
                    results[commentId] = TaskCommentResultState(result = merged, loading = false, offset = merged.offset)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                results[commentId] = (results[commentId] ?: TaskCommentResultState()).copy(loading = false, failed = true)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("评论与员工派发", style = MaterialTheme.typography.titleSmall, color = WandColors.textPrimary)
            Text("$totalCount 条评论", style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
            WandButton(label = "刷新评论", onClick = { refresh++ }, variant = WandButtonVariant.Text, compact = true)
        }
        if (loadFailed) {
            Text("评论暂时加载失败，请刷新重试。", style = MaterialTheme.typography.labelSmall, color = WandColors.dangerText)
        }
        visibleComments.forEach { comment ->
            val dispatch = comment.dispatch
            val employee = dispatch?.let { target -> activeEmployees.firstOrNull { it.id == target.employeeId } }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(WandShapes.md)
                    .background(if (focusedComment == comment.id) WandColors.brandSoft else WandColors.surface)
                    .border(1.dp, WandColors.border, WandShapes.md)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    listOf(comment.authorName, comment.createdAt).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = WandColors.textMuted,
                )
                comment.sourceCommentId?.let { Text("回复评论 $it", style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted) }
                dispatch?.let { Text("已派发给 ${it.employeeName}", style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted) }
                SelectionContainer {
                    TaskReferenceText(
                        body = comment.body,
                        onOpenTask = openReferencedTask,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    WandButton(label = "回复", onClick = {
                        val gap = if (draft.body.isEmpty() || draft.body.last().isWhitespace()) "" else " "
                        val body = draft.body + gap
                        draft = TaskCommentDrafts.set(taskId, draft.copy(source = comment.id, body = body, employeeId = ""))
                        field = TextFieldValue(body, TextRange(body.length))
                        outcome = "idle"
                    }, variant = WandButtonVariant.Text, compact = true)
                    WandButton(label = "回复并派发", onClick = {
                        val gap = if (draft.body.isEmpty() || draft.body.last().isWhitespace()) "" else " "
                        val body = draft.body + gap + "@"
                        draft = TaskCommentDrafts.set(taskId, draft.copy(source = comment.id, body = body))
                        field = TextFieldValue(body, TextRange(body.length))
                        outcome = "idle"
                    }, variant = WandButtonVariant.Text, compact = true)
                    if (dispatch != null) {
                        WandButton(label = "查看员工执行事项", onClick = {
                            focusedCard = comment.id
                            focusedComment = comment.id
                            if (dispatch.hasProvenRound && results[comment.id]?.result == null) loadResult(comment.id, 0)
                        }, variant = WandButtonVariant.Text, compact = true)
                    }
                }
            }
            dispatch?.let {
                TaskExecutionCard(
                    comment = comment,
                    dispatch = it,
                    employee = employee,
                    resultState = results[comment.id] ?: TaskCommentResultState(),
                    expanded = focusedCard == comment.id,
                    onToggleDetail = {
                        focusedCard = if (focusedCard == comment.id) null else comment.id
                        if (focusedCard == comment.id && it.hasProvenRound && results[comment.id]?.result == null) loadResult(comment.id, 0)
                    },
                    onLocateComment = { focusedComment = comment.id },
                    onOpenSession = onOpenSession,
                    onLoadResult = { offset -> loadResult(comment.id, offset) },
                    onOpenTask = openReferencedTask,
                    referenceTargets = referenceTargets,
                )
            }
        }
        if (olderCursor != null) {
            WandButton(
                label = if (loadingOlder) "正在加载更早评论…" else "加载更早评论",
                onClick = {
                    val cursor = olderCursor ?: return@WandButton
                    if (loadingOlder) return@WandButton
                    loadingOlder = true
                    scope.launch {
                        try {
                            val page = loadTaskCommentPage(api, taskId, 50, cursor)
                            olderComments = (olderComments + page.comments).distinctBy { it.id }
                            olderCursor = page.nextCursor
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            message = "更早的评论加载失败，请重试。"
                        } finally {
                            loadingOlder = false
                        }
                    }
                },
                variant = WandButtonVariant.Text,
                compact = true,
                enabled = !loadingOlder,
            )
        }
        draft.source?.let { source ->
            val sourceComment = visibleComments.firstOrNull { it.id == source }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    sourceComment?.let { "回复 ${it.authorName}：${it.body.take(60)}" } ?: "回复 ${source.take(8)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = WandColors.textMuted,
                    modifier = Modifier.weight(1f),
                )
                WandButton(label = "取消回复", onClick = { draft = TaskCommentDrafts.set(taskId, draft.copy(source = null)) }, variant = WandButtonVariant.Text, compact = true)
            }
        }
        if (referenceMatches.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState())
                    .clip(WandShapes.sm)
                    .background(WandColors.surface)
                    .border(1.dp, WandColors.border, WandShapes.sm)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(
                    "引用任务 · 输入 # 选择 · ↑↓ 切换 Enter 插入",
                    style = MaterialTheme.typography.labelSmall,
                    color = WandColors.textMuted,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
                referenceMatches.forEachIndexed { index, candidate ->
                    val selected = index == referenceHighlight
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { chooseReference(candidate) },
                            )
                            .background(if (selected) WandColors.selectedFill else Color.Transparent)
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                            .semantics(mergeDescendants = true) {
                                contentDescription = "${candidate.identifier} ${candidate.title} ${boardTaskStatusLabel(candidate.status)}"
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "#${candidate.identifier}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = WandColors.infoText,
                        )
                        Text(
                            candidate.title.ifBlank { "（无标题）" },
                            style = MaterialTheme.typography.bodySmall,
                            color = WandColors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            boardTaskStatusLabel(candidate.status),
                            style = MaterialTheme.typography.labelSmall,
                            color = WandColors.textMuted,
                        )
                    }
                }
            }
        } else if (referenceQuery != null && candidates.isEmpty()) {
            Text("当前任务没有可引用的子任务", style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
        } else if (referenceQuery != null) {
            Text("没有匹配的子任务", style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
        }
        if (matches.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                matches.forEach { employee ->
                    WandButton(label = "@${employee.name}", onClick = {
                        val next = TaskCommentMention.insert(field.text, field.selection.end, employee.name, bound?.name.orEmpty())
                        draft = TaskCommentDrafts.set(taskId, draft.copy(body = next.first, employeeId = employee.id))
                        field = TextFieldValue(next.first, TextRange(next.second))
                        outcome = "idle"
                    }, variant = WandButtonVariant.Text)
                }
            }
        } else if (mention != null) {
            Text("没有匹配的员工", style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
        }
        WandTextField(
            value = field,
            onValueChange = { value ->
                val person = activeEmployees.firstOrNull { it.id == draft.employeeId }
                val employeeId = if (person != null && TaskCommentMention.holds(value.text, person.name)) person.id else ""
                draft = TaskCommentDrafts.set(taskId, draft.copy(body = value.text, employeeId = employeeId))
                field = value
                message = ""; outcome = "idle"
            },
            label = "评论或本轮指令",
            placeholder = "写评论。输入 @ 选择员工，选中后才会派发；输入 # 引用子任务",
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (referenceMatches.isEmpty()) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> {
                            referenceHighlight = (referenceHighlight - 1 + referenceMatches.size) % referenceMatches.size
                            true
                        }
                        Key.DirectionDown -> {
                            referenceHighlight = (referenceHighlight + 1) % referenceMatches.size
                            true
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            referenceMatches.getOrNull(referenceHighlight)?.let { chooseReference(it) }
                            true
                        }
                        Key.Escape -> {
                            dismissedReference = referenceKey
                            true
                        }
                        else -> false
                    }
                },
        )
        val submitLabel = when (outcome) {
            "saved" -> "评论已保存"; "failed" -> "提交失败"; "unknown" -> "送达待核对"
            else -> when {
                busy -> "正在提交…"
                dispatching && draft.source != null -> "回复并派发"
                dispatching -> "评论并派发"
                draft.source != null -> "回复"
                else -> "发表评论"
            }
        }
        WandButton(label = submitLabel, modifier = Modifier.fillMaxWidth(),
            enabled = !busy && validation == null, onClick = submit@{
                if (!busy) {
                    val person = activeEmployees.firstOrNull { it.id == draft.employeeId }
                    val name = if (person != null && TaskCommentMention.holds(draft.body, person.name)) person.name else ""
                    val error = if (field.composition != null) "请先完成输入法组字，再提交评论。"
                        else if (TaskCommentMention.query(field.text, field.selection.end) != null)
                        "请先从 @ 候选列表选择员工，或删除未完成的提及。"
                        else TaskCommentMention.validationMessage(draft.body, name)
                    if (error != null) {
                        message = error; outcome = "failed"
                        return@submit
                    }
                    val submitted = draft.copy(body = TaskCommentMention.submissionBody(draft.body, name),
                        employeeId = if (name.isNotEmpty()) person?.id.orEmpty() else "")
                    busy = true; message = ""
                    scope.launch {
                        val payload = JSONArray().put(submitted.body.trim()).put(submitted.employeeId).put(submitted.source ?: "").toString()
                        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
                        val key = "$taskId:$digest"
                        try {
                            val requestId = prefs.getString(key, null) ?: UUID.randomUUID().toString()
                            check(prefs.edit().putString(key, requestId).commit()) { "无法保存请求标识，未派发。" }
                            val saved = api.postTaskComment(taskId, requestId, submitted.body.trim(), submitted.employeeId.ifEmpty { null }, submitted.source)
                            if (saved.dispatch?.deliveryUncertain != true) prefs.edit().remove(key).commit()
                            val rejected = saved.dispatch?.state == "failed"
                            if (!rejected) {
                                draft = TaskCommentDrafts.accept(taskId, submitted)
                                field = TextFieldValue(draft.body, TextRange(draft.body.length))
                            }
                            outcome = if (saved.dispatch?.deliveryUncertain == true) "unknown" else if (rejected) "failed" else "saved"
                            message = if (saved.dispatch?.deliveryUncertain == true) "评论已保存；请求身份已保留，同一评论不会重新派发。"
                                else if (rejected) "执行明确未接受，原指令已保留。" else "评论已保存"
                            refresh++
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            // 明确的 4xx 拒收（含引用非法）不保留请求身份：评论没保存，重写后应能重新提交。
                            val rejected = error is com.wand.app.data.WandApiException &&
                                error.status in 400..499 && error.status !in setOf(408, 409)
                            if (rejected) prefs.edit().remove(key).commit()
                            outcome = if (rejected) "failed" else "unknown"
                            message = if (rejected) "${error.message}；原指令已保留，改完后可重新提交。"
                                else "${error.message}；请刷新核对，同一评论重试会复用请求标识。"
                        } finally { busy = false }
                    }
                }
            })
        Text(
            message.ifBlank {
                when {
                    draft.body.isNotEmpty() && validation != null -> validation
                    dispatching && referenceTarget != null -> "将派发给 @${bound?.name}，目标 #$referenceTarget。只派发这一次选中的人。"
                    dispatching -> "将派发给 @${bound?.name}。只派发这一次选中的人。"
                    else -> "正文里没有选中的 @员工时，不会启动执行。"
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = WandColors.textMuted,
        )
    }
}
