package com.wand.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.wand.app.data.TaskBoardPort
import com.wand.app.data.TaskComment
import com.wand.app.data.TaskCommentMention
import com.wand.app.data.SiliconEmployee
import com.wand.app.data.loadTaskComments
import com.wand.app.ui.components.*
import com.wand.app.ui.theme.WandColors
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

/** A chosen @ mention owns stable identity; literal @ text alone cannot dispatch. */
@Composable
internal fun TaskComments(api: TaskBoardPort, taskId: String, employees: List<SiliconEmployee> = emptyList(), onOpenSession: (String, Boolean) -> Unit = { _, _ -> }) {
    var comments by remember(api, taskId) { mutableStateOf<List<TaskComment>>(emptyList()) }
    var draft by remember(taskId) { mutableStateOf(TaskCommentDrafts.get(taskId)) }
    var field by remember(taskId) { mutableStateOf(TextFieldValue(draft.body, TextRange(draft.body.length))) }
    var busy by remember(taskId) { mutableStateOf(false) }
    var message by remember(taskId) { mutableStateOf("") }
    var outcome by remember(taskId) { mutableStateOf("idle") }
    var refresh by remember(taskId) { mutableIntStateOf(0) }
    val prefs = LocalContext.current.getSharedPreferences("task-comment-requests", android.content.Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(api, taskId, lifecycle, refresh) {
        lifecycle.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                try { comments = loadTaskComments(api, taskId) }
                catch (error: Exception) { if (error is CancellationException) throw error; message = "评论暂时加载失败，请刷新重试。" }
                delay(5_000)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("评论与员工派发", style = MaterialTheme.typography.titleSmall, color = WandColors.textPrimary)
        WandButton(label = "刷新评论", onClick = { refresh++ }, variant = WandButtonVariant.Text)
        comments.forEach { comment ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(listOf(comment.authorName, comment.createdAt).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
                comment.sourceCommentId?.let { Text("回复评论 $it", style = MaterialTheme.typography.labelSmall) }
                SelectionContainer { Text(comment.body, style = MaterialTheme.typography.bodyMedium, color = WandColors.textSecondary) }
                comment.dispatch?.let { dispatch ->
                    Text("@${dispatch.employeeName} · ${dispatch.label}", style = MaterialTheme.typography.labelSmall)
                    dispatch.sessionId?.let { session -> WandButton(label = "查看执行", onClick = { onOpenSession(session, true) }, variant = WandButtonVariant.Text) }
                }
                WandButton(label = "回复并 @", onClick = {
                    val gap = if (draft.body.isEmpty() || draft.body.last().isWhitespace()) "" else " "
                    val body = draft.body + gap + "@"
                    draft = TaskCommentDrafts.set(taskId, draft.copy(source = comment.id, body = body))
                    field = TextFieldValue(body, TextRange(body.length))
                    outcome = "idle"
                }, variant = WandButtonVariant.Text)
            }
        }
        val activeEmployees = employees.filter { !it.archived }
        val mention = TaskCommentMention.query(field.text, field.selection.end)
        val matches = if (mention == null) emptyList() else activeEmployees.filter { mention.text.isEmpty() || it.name.contains(mention.text, ignoreCase = true) }
        val bound = activeEmployees.firstOrNull { it.id == draft.employeeId }
        val dispatching = bound != null && TaskCommentMention.holds(draft.body, bound.name)
        draft.source?.let { source -> WandButton(label = "取消回复 · ${source.take(8)}", onClick = { draft = TaskCommentDrafts.set(taskId, draft.copy(source = null)) }, variant = WandButtonVariant.Text) }
        if (matches.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                matches.forEach { employee ->
                    WandButton(label = "@${employee.name}", onClick = {
                        val next = TaskCommentMention.insert(field.text, field.selection.end, employee.name)
                        draft = TaskCommentDrafts.set(taskId, draft.copy(body = next.first, employeeId = employee.id))
                        field = TextFieldValue(next.first, TextRange(next.second))
                        outcome = "idle"
                    }, variant = WandButtonVariant.Text)
                }
            }
        } else if (mention != null) {
            Text("没有匹配的员工", style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
        }
        WandTextField(value = field, onValueChange = { value ->
            val person = activeEmployees.firstOrNull { it.id == draft.employeeId }
            val employeeId = if (person != null && TaskCommentMention.holds(value.text, person.name)) person.id else ""
            draft = TaskCommentDrafts.set(taskId, draft.copy(body = value.text, employeeId = employeeId))
            field = value
            message = ""; outcome = "idle"
        }, label = "评论或本轮指令", placeholder = "写评论。输入 @ 选择员工，选中后才会派发", modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp))
        WandButton(label = if (busy) "正在提交…" else when (outcome) {
            "saved" -> "评论已保存"; "failed" -> "提交失败"; "unknown" -> "送达待核对"
            else -> if (dispatching) "评论并派发" else "发表评论"
        }, modifier = Modifier.fillMaxWidth(),
            enabled = !busy && draft.body.isNotBlank(), onClick = {
                if (!busy) {
                    val person = activeEmployees.firstOrNull { it.id == draft.employeeId }
                    val employeeId = if (person != null && TaskCommentMention.holds(draft.body, person.name)) person.id else ""
                    val submitted = draft.copy(employeeId = employeeId)
                    busy = true; message = ""
                    scope.launch {
                        try {
                            val payload = JSONArray().put(submitted.body.trim()).put(submitted.employeeId).put(submitted.source ?: "").toString()
                            val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
                            val key = "$taskId:$digest"
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
                            outcome = if (error is com.wand.app.data.WandApiException && error.status in 400..499 && error.status !in setOf(408, 409)) "failed" else "unknown"
                            message = "${error.message}；请刷新核对，同一评论重试会复用请求标识。"
                        } finally { busy = false }
                    }
                }
            })
        Text(message.ifBlank { if (dispatching) "将派发给 @${bound?.name}。只派发这一次选中的人。" else "正文里没有选中的 @员工时，不会启动执行。" }, style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
    }
}
