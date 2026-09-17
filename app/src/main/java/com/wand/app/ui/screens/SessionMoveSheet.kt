package com.wand.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.WorkspacePort
import com.wand.app.ui.components.*
import com.wand.app.ui.theme.WandColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class SessionMoveTarget(val id: String, val workspace: String, val name: String, val current: Boolean)

internal fun sessionMoveTargets(groups: List<TaskDirectoryGroup>, sessionId: String, query: String): List<SessionMoveTarget> =
    groups.flatMap { group -> group.tasks.map { task ->
        SessionMoveTarget(task.id, group.workspaceName, task.name, task.sessions.any { it.id == sessionId })
    } }.distinctBy { it.id }.filter {
        query.isBlank() || "${it.workspace} ${it.name}".contains(query.trim(), ignoreCase = true)
    }

/** Touch/keyboard equivalent of desktop drag-and-drop, with an explicit destination confirmation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionMoveSheet(
    api: WorkspacePort,
    sessionId: String,
    sessionTitle: String,
    onDismiss: () -> Unit,
    onMoved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var groups by remember(api, sessionId) { mutableStateOf<List<TaskDirectoryGroup>>(emptyList()) }
    var query by remember(sessionId) { mutableStateOf("") }
    var selectedId by remember(sessionId) { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(api, sessionId, retry) {
        loading = true
        error = null
        try { groups = api.listTaskGroups() }
        catch (cause: Exception) {
            if (cause is CancellationException) throw cause
            error = cause.message ?: "无法加载任务，请重试"
        } finally { loading = false }
    }
    val allTargets = sessionMoveTargets(groups, sessionId, "")
    val duplicateNames = allTargets.groupingBy { it.workspace to it.name }.eachCount()
    // Keep selectable destinations above the disabled source, especially when the IME is open.
    val targets = sessionMoveTargets(groups, sessionId, query).sortedBy { it.current }
    val selected = allTargets.firstOrNull { it.id == selectedId && !it.current }
    WandBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        gesturesEnabled = !busy,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !busy }),
        modifier = Modifier.imePadding(),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("移动会话到任务", style = MaterialTheme.typography.titleLarge, color = WandColors.textPrimary)
            Text(sessionTitle, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            Text("只改变任务归属，保留运行目录、历史和正在执行的 CLI。",
                style = MaterialTheme.typography.bodySmall, color = WandColors.textMuted,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
            WandTextField(value = query, onValueChange = { query = it; selectedId = null }, label = "搜索任务或工作区",
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
            error?.let { message ->
                Text(message, color = WandColors.danger, modifier = Modifier.padding(top = 8.dp))
                TextButton(onClick = { retry++ }, enabled = !busy) { Text("重新加载任务") }
            }
            LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
                if (!loading && error == null && targets.none { !it.current }) item {
                    Text(if (query.isBlank()) "还没有其他任务，请先创建一个任务分组。" else "没有匹配的目标任务。",
                        color = WandColors.textMuted, modifier = Modifier.padding(vertical = 16.dp))
                }
                items(targets, key = { it.id }) { target ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .clickable(enabled = !busy && !target.current) { selectedId = target.id }
                        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selectedId == target.id, enabled = !busy && !target.current,
                            onClick = { selectedId = target.id })
                        Column(Modifier.weight(1f)) {
                            Text(target.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium, color = WandColors.textPrimary)
                            Text(if ((duplicateNames[target.workspace to target.name] ?: 0) > 1)
                                "${target.workspace} · ${target.id.take(8)}" else target.workspace,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
                        }
                        if (target.current) Text("当前任务", style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WandButton(label = "取消", onClick = onDismiss, enabled = !busy,
                    variant = WandButtonVariant.Secondary, modifier = Modifier.weight(1f))
                WandButton(label = if (busy) "移动中…" else "确认移动", enabled = selected != null && !busy && !loading,
                    modifier = Modifier.weight(1f), onClick = {
                        val destination = selected ?: return@WandButton
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                api.moveWorkspaceSession(destination.id, sessionId)
                                Toast.makeText(context, "已移至「${destination.name}」，运行目录不变", Toast.LENGTH_SHORT).show()
                                onMoved()
                                onDismiss()
                            } catch (cause: Exception) {
                                if (cause is CancellationException) throw cause
                                error = if (cause is com.wand.app.data.WandApiException && cause.status == 404)
                                    "任务或会话已不存在；若服务端尚未更新，请先升级服务端。"
                                else cause.message ?: "移动失败，原会话不受影响"
                            } finally { busy = false }
                        }
                    })
            }
        }
    }
}
