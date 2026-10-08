package com.wand.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wand.app.data.WorkspacePort
import com.wand.app.data.normalizeWorkspacePath
import com.wand.app.ui.components.WandBottomSheet
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandIconButton
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandInPlaceSwap
import com.wand.app.ui.components.WandListItem
import com.wand.app.ui.components.WandListItemIconSlot
import com.wand.app.ui.components.WandTextField
import com.wand.app.ui.theme.WandColors
import kotlinx.coroutines.launch

/** 三处新增共用服务器工作区/目录选择；浏览本身不落库、不创建文件夹。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceDirectoryPicker(
    api: WorkspacePort,
    currentCwd: String,
    onSelect: (WorkspaceDirectorySelection) -> Unit,
    onDismiss: () -> Unit,
    allowUnassigned: Boolean = false,
) {
    val state = remember(api) { WorkspaceDirectoryPickerState(api, currentCwd) }
    val scope = rememberCoroutineScope()
    var browsing by remember { mutableStateOf(false) }
    var pathDraft by remember { mutableStateOf(state.path) }
    var pathEdited by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        state.loadChoices()
        if (currentCwd.isBlank()) {
            val default = runCatching { api.taskDefaultCwd() }.getOrNull()
            if (!pathEdited) pathDraft = default?.trim()?.takeIf { it.isNotBlank() } ?: "/"
        }
    }
    fun browse(path: String) {
        pathEdited = true
        pathDraft = path
        browsing = true
        scope.launch { state.browse(path) }
    }
    WandBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        gesturesEnabled = false,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row {
                Text("选择工作区", style = MaterialTheme.typography.titleLarge,
                    color = WandColors.textPrimary, modifier = Modifier.weight(1f).padding(top = 12.dp))
                WandIconButton(WandIcons.close, "关闭工作区选择", onDismiss)
            }
            Text("运行目录在服务器上；选择未使用的目录也可以开始。",
                style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
            WandInPlaceSwap(contentKey = browsing, enterScale = 1f, exitScale = 1f) { shown ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (shown != true) {
                WandButton("浏览其他目录", { browse(pathDraft) }, icon = WandIcons.folder,
                    variant = WandButtonVariant.Secondary, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    if (allowUnassigned) item {
                        WorkspaceDirectoryOption("未归属工作区", "使用临时目录", currentCwd.isBlank()) {
                            onSelect(WorkspaceDirectorySelection(""))
                        }
                    }
                    items(state.workspaces, key = { it.id }) { workspace ->
                        WorkspaceDirectoryOption(workspace.name, workspace.cwd,
                            normalizeWorkspacePath(workspace.cwd) == normalizeWorkspacePath(currentCwd)) {
                            onSelect(WorkspaceDirectorySelection(workspace.cwd, workspace.id))
                        }
                    }
                    if (state.choicesLoading) item {
                        Text("正在加载工作区…", color = WandColors.textMuted, modifier = Modifier.padding(16.dp))
                    }
                    if (!state.choicesLoading && state.workspaces.isEmpty()) item {
                        Text("还没有工作区，浏览服务器目录开始。", color = WandColors.textSecondary,
                            modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                state.choicesError?.let { message ->
                    Text(message, color = WandColors.danger, style = MaterialTheme.typography.bodySmall)
                    WandButton("重试加载工作区", { scope.launch { state.loadChoices() } },
                        variant = WandButtonVariant.Text, modifier = Modifier.fillMaxWidth())
                }
            } else {
                Row {
                    WandTextField(pathDraft, { pathDraft = it; pathEdited = true }, label = "服务器目录", singleLine = true,
                        modifier = Modifier.weight(1f))
                    WandIconButton(WandIcons.chevronRight, "打开输入的目录", { browse(pathDraft) },
                        enabled = pathDraft.isNotBlank())
                }
                WandButton("返回工作区列表", { browsing = false }, variant = WandButtonVariant.Text)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    if (state.path != "/") item {
                        WorkspaceDirectoryOption("返回上一级", parentWorkspaceDirectory(state.path), false) {
                            browse(parentWorkspaceDirectory(state.path))
                        }
                    }
                    items(state.listing?.items.orEmpty().filter { it.isDirectory }, key = { it.path }) { item ->
                        WorkspaceDirectoryOption(item.name, item.path, false) { browse(item.path) }
                    }
                    if (state.loading) item {
                        Text("正在读取目录…", color = WandColors.textMuted, modifier = Modifier.padding(16.dp))
                    }
                    if (state.canSelectDirectory && state.listing?.items.orEmpty().none { it.isDirectory }) item {
                        Text("这里没有子目录，可以直接选择当前目录。", color = WandColors.textSecondary,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                    }
                    if (state.listing?.truncated == true) item {
                        Text("目录较多，仅显示部分；可输入完整路径打开。", color = WandColors.textSecondary,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                    }
                }
                state.error?.let { Text(it, color = WandColors.danger, style = MaterialTheme.typography.bodySmall) }
                WandButton("选择此目录", { onSelect(directorySelection(state.path, state.workspaces)) },
                    enabled = state.canSelectDirectory && normalizeWorkspacePath(pathDraft) == normalizeWorkspacePath(state.path),
                    loading = state.loading, modifier = Modifier.fillMaxWidth())
            }
                }
            }
        }
    }
}

@Composable
internal fun WorkspaceDirectoryField(cwd: String, enabled: Boolean, onClick: () -> Unit) {
    NewTaskSettingRow(
        icon = WandIcons.folder, label = "运行目录",
        value = cwd.ifBlank { "未归属工作区" }, enabled = enabled, onClick = onClick,
    )
}

@Composable
private fun WorkspaceDirectoryOption(title: String, path: String, selected: Boolean, onClick: () -> Unit) {
    WandListItem(
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(path, maxLines = 2, overflow = TextOverflow.MiddleEllipsis) },
        leadingContent = { WandListItemIconSlot(WandIcons.folder) },
        trailingContent = { WandListItemIconSlot(if (selected) WandIcons.check else WandIcons.chevronRight,
            tint = if (selected) WandColors.brand else WandColors.textMuted) },
    )
}
