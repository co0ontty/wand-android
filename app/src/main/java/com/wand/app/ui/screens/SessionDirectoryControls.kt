package com.wand.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.wand.app.data.WandApi
import com.wand.app.ui.components.WandDialog
import com.wand.app.ui.components.WandDialogAction
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.GitBranchIcon
import androidx.compose.ui.draw.rotate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionDirectoryHeading(name: String, detail: String?, count: String?, expanded: Boolean, onToggle: () -> Unit, onMerge: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).combinedClickable(onClick = onToggle, onLongClick = onMerge?.let { { menu = true } }).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(WandIcons.chevronRight, if (expanded) "收起" else "展开", Modifier.size(16.dp).rotate(if (expanded) 90f else 0f))
            if (onMerge == null) Icon(WandIcons.folder, null, Modifier.size(16.dp)) else GitBranchIcon(modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            count?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("合并到项目分支…") }, onClick = { menu = false; onMerge?.invoke() })
        }
    }
}

@Composable
internal fun SessionDirectoryMergeDialog(api: WandApi, workspaceId: String, path: String, onClose: () -> Unit,
    onChanged: () -> Unit, onAgent: (String, String, String) -> Unit) {
    var check by remember(workspaceId, path) { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(workspaceId, path, revision) {
        check = null; error = null; loading = true
        try { check = api.directoryMergeCheck(workspaceId, path) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "无法检查合并状态" }
        finally { loading = false }
    }
    WandDialog(onDismissRequest = { if (!busy) onClose() }, title = "合并 Worktree",
        dismiss = WandDialogAction(if (finished) "关闭" else "取消", onClose, enabled = !busy),
        confirm = WandDialogAction(if (busy) "正在合并…" else "确认合并并清理", {
            val info = check
            if (info != null && !busy && !finished) {
                busy = true
                scope.launch {
                    try { api.mergeDirectory(info); finished = true; onChanged() }
                    catch (e: Exception) { error = e.message ?: "合并结果尚未确认，请刷新核对，勿重复提交"; check = null; onChanged() }
                    finally { busy = false }
                }
            }
        }, enabled = check?.optBoolean("ok") == true && !busy && !finished),
        body = {
            if (loading) CircularProgressIndicator()
            check?.let { info ->
                Text(info.getString("sourceBranch") + " → " + info.getString("targetBranch"))
                Spacer(Modifier.height(12.dp))
                Text("${info.optInt("aheadCount")} 个待合并提交")
                Spacer(Modifier.height(12.dp))
                Text(if (finished) "已合并并清理 Worktree。" else if (info.optBoolean("ok")) "确认后合并并清理来源 Worktree 和任务分支，会话历史保留。" else info.optString("reason", "无法一键合并"))
                if (!finished && info.optBoolean("agentAllowed")) Button(onClick = {
                    onClose(); onAgent(workspaceId, info.getString("targetCwd"), info.getString("agentPrompt"))
                }, enabled = !busy) { Text("交给 Agent 合并") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { revision++ }, enabled = !busy) { Text("重新检查") } }
        })
}
