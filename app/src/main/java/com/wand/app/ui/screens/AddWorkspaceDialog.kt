package com.wand.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wand.app.data.Workspace
import com.wand.app.data.WorkspacePort
import com.wand.app.data.RecentPath
import com.wand.app.ui.components.*
import com.wand.app.ui.theme.WandColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal val LocalWorkspacePort = staticCompositionLocalOf<WorkspacePort?> { null }

@Composable
internal fun AddWorkspaceButton(onCreated: (Workspace) -> Unit = {}) {
    val api = LocalWorkspacePort.current ?: return
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("＋ 添加工作区") }
    if (open) AddWorkspaceDialog(api, onDismiss = { open = false }, onCreated = { open = false; onCreated(it) })
}

@Composable
internal fun WorkspaceChoice(label: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit,
                             enabled: Boolean = true, modifier: Modifier = Modifier, chip: Boolean = false) {
    val api = LocalWorkspacePort.current
    var adding by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf<Workspace?>(null) }
    WandChoice(label, options + listOfNotNull(added?.takeIf { item -> options.none { it.first == item.id } }?.let { it.id to it.name }),
        onSelect, modifier = modifier, enabled = enabled, chip = chip,
        action = if (api != null) ("＋ 添加工作区" to { adding = true }) else null)
    if (adding && api != null) AddWorkspaceDialog(api, onDismiss = { adding = false }, onCreated = {
        added = it; adding = false; onSelect(it.id)
    })
}

@Composable
internal fun AddWorkspaceDialog(api: WorkspacePort, onDismiss: () -> Unit, onCreated: (Workspace) -> Unit) {
    var cwd by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var browsing by remember { mutableStateOf(false) }
    var recent by remember { mutableStateOf<List<RecentPath>>(emptyList()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(api) {
        recent = runCatching { api.recentTaskPaths() }.getOrDefault(emptyList())
        val initial = recent.firstOrNull()?.path ?: runCatching { api.taskDefaultCwd() }.getOrNull().orEmpty()
        if (cwd.isEmpty()) cwd = initial
    }
    WandDialog(title = "添加工作区", onDismissRequest = { if (!busy) onDismiss() },
        confirm = WandDialogAction(if (busy) "正在添加…" else "添加工作区", {
            if (!busy) {
                busy = true; error = null
                scope.launch {
                    try { onCreated(api.createWorkspace(alias.trim(), cwd.trim())) }
                    catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        error = failure.message ?: "添加失败，请重试"
                    } finally { busy = false }
                }
            }
        }, enabled = !busy && cwd.isNotBlank() && alias.isNotBlank()),
        dismiss = WandDialogAction("取消", onDismiss, enabled = !busy)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WandTextField(cwd, { cwd = it }, label = "目录路径", enabled = !busy, modifier = Modifier.fillMaxWidth())
            WandButton("浏览目录", { browsing = true }, enabled = !busy, variant = WandButtonVariant.Secondary)
            recent.take(6).forEach { item -> TextButton(onClick = { cwd = item.path }, enabled = !busy) { Text(item.name + " · " + item.path) } }
            WandTextField(alias, { alias = it }, label = "路径别名", enabled = !busy, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = WandColors.danger) }
        }
    }
    if (browsing) WorkspaceDirectoryPicker(api, cwd, onSelect = { cwd = it.cwd; browsing = false }, onDismiss = { browsing = false }, allowAdd = false)
}
