package com.wand.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandInPlaceSwap
import com.wand.app.ui.components.WandTextField
import com.wand.app.ui.theme.GlassBackdrop
import com.wand.app.ui.theme.WandGlass
import com.wand.app.ui.theme.glassSurface

private enum class CapsuleDimension { Runtime, Workspace, Employee }
private data class CapsuleOption(val title: String, val icon: ImageVector, val monogram: String? = null)

/** 同维度取并集，维度间取交集；固定两格展示全部与当前选项，完整多选由原生弹层承载。 */
@Composable
internal fun ConversationCapsules(
    filters: ConversationListFilters,
    workspaces: List<Pair<String, String>>,
    employees: List<Pair<String, String>> = emptyList(),
    backdrop: GlassBackdrop? = null,
    onChange: (ConversationListFilters) -> Unit,
) {
    val workspaceApi = LocalWorkspacePort.current
    var addingWorkspace by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<CapsuleDimension?>(null) }
    var query by remember(open) { mutableStateOf("") }
    // Display hints live with the mounted controls; they do not change applied filters.
    var lastRuntime by remember { mutableStateOf(ConversationRuntime.Running) }
    var lastWorkspace by remember { mutableStateOf<String?>(null) }
    var lastEmployee by remember { mutableStateOf<String?>(null) }
    val selectedWorkspaces = filters.workspaceIds.ifEmpty { setOfNotNull(filters.workspaceId) }
    val runtimeChoice = ConversationRuntime.entries.firstOrNull { it in filters.runtimes } ?: lastRuntime
    val workspaceChoice = workspaces.firstOrNull { it.first in selectedWorkspaces }
        ?: selectedWorkspaces.sorted().firstOrNull()?.let { it to "工作区不可用" }
        ?: workspaces.firstOrNull { it.first == lastWorkspace }
        ?: workspaces.firstOrNull { it.first != UnassignedConversationWorkspace }
        ?: (UnassignedConversationWorkspace to "未分配工作区")
    val employeeChoice = employees.firstOrNull { it.first in filters.employeeIds }
        ?: filters.employeeIds.sorted().firstOrNull()?.let { it to employeeTitle(it, employees) }
        ?: employees.firstOrNull { it.first == lastEmployee }
        ?: employees.firstOrNull { it.first != "none" }
        ?: ("none" to "未分配员工")
    fun runtime(value: ConversationRuntime?) {
        val selected = if (value == null) emptySet() else if (value in filters.runtimes) filters.runtimes - value else filters.runtimes + value
        if (value != null && value in selected) lastRuntime = value
        onChange(filters.copy(runtimes = selected, activeOnly = false))
        if (value == null) open = null
    }
    fun workspace(value: String?) {
        val selected = if (value == null) emptySet() else if (value in selectedWorkspaces) selectedWorkspaces - value else selectedWorkspaces + value
        if (value != null && value in selected) lastWorkspace = value
        onChange(filters.copy(workspaceIds = selected, workspaceId = null))
        if (value == null) open = null
    }
    fun employee(value: String?) {
        val selected = if (value == null) emptySet() else if (value in filters.employeeIds) filters.employeeIds - value else filters.employeeIds + value
        if (value != null && value in selected) lastEmployee = value
        onChange(filters.copy(employeeIds = selected))
        if (value == null) open = null
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            ConversationCapsuleTrigger("运行状态", WandIcons.workspace,
                CapsuleOption(runtimeChoice.label, runtimeIcon(runtimeChoice)), filters.runtimes.size,
                ConversationRuntime.entries.filter { it in filters.runtimes }.joinToString("、") { it.label }
                    .ifEmpty { "全部运行状态：不含已归档的任务" },
                backdrop, onClear = { runtime(null) },
                allDescription = "全部运行状态，不含已归档的任务，清除此项筛选",
                onExpand = { open = if (open == CapsuleDimension.Runtime) null else CapsuleDimension.Runtime })
            DropdownMenu(open == CapsuleDimension.Runtime, onDismissRequest = { open = null }) {
                DropdownMenuItem(text = { Column { Text("全部运行状态"); Text("不含已归档的任务", style = MaterialTheme.typography.labelSmall) } },
                    trailingIcon = { if (filters.runtimes.isEmpty()) Icon(WandIcons.check, "已选中") }, onClick = { runtime(null) })
                ConversationRuntime.entries.forEach { value ->
                    DropdownMenuItem(text = { Column { Text(value.label); value.hint?.let { Text(it, style = MaterialTheme.typography.labelSmall) } } },
                        modifier = Modifier.semantics { selected = value in filters.runtimes },
                        leadingIcon = { Icon(runtimeIcon(value), null) },
                        trailingIcon = { if (value in filters.runtimes) Icon(WandIcons.check, "已选中") }, onClick = { runtime(value) })
                }
            }
        }
        Box {
            ConversationCapsuleTrigger("工作区", WandIcons.folder,
                CapsuleOption(workspaceChoice.second, WandIcons.folder, capsuleInitials(workspaceChoice.second).takeUnless { workspaceChoice.first == UnassignedConversationWorkspace }),
                selectedWorkspaces.size,
                selectedWorkspaces.sorted().joinToString("、") { id -> workspaces.firstOrNull { it.first == id }?.second ?: "工作区不可用" }.ifEmpty { "全部工作区" },
                backdrop, onClear = { workspace(null) }, onExpand = { open = if (open == CapsuleDimension.Workspace) null else CapsuleDimension.Workspace })
            DropdownMenu(open == CapsuleDimension.Workspace, onDismissRequest = { open = null }) {
                WandTextField(query, { query = it }, label = "搜索工作区", singleLine = true,
                    trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("清除") } }, modifier = Modifier.padding(8.dp).width(240.dp))
                DropdownMenuItem(text = { Text("全部工作区") }, trailingIcon = { if (selectedWorkspaces.isEmpty()) Icon(WandIcons.check, "已选中") }, onClick = { workspace(null) })
                val options = workspaces.filter { query.isBlank() || it.second.contains(query.trim(), ignoreCase = true) }
                if (options.isEmpty()) DropdownMenuItem(text = { Text("没有匹配的工作区") }, onClick = {}, enabled = false)
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(option.second) }, modifier = Modifier.semantics { selected = option.first in selectedWorkspaces },
                        trailingIcon = { if (option.first in selectedWorkspaces) Icon(WandIcons.check, "已选中") }, onClick = { workspace(option.first) })
                }
                if (workspaceApi != null) DropdownMenuItem(text = { Text("添加工作区") }, onClick = {
                    open = null
                    addingWorkspace = true
                })
            }
        }
        Box {
            ConversationCapsuleTrigger("员工", WandIcons.contacts,
                CapsuleOption(employeeChoice.second, WandIcons.contacts, capsuleInitials(employeeChoice.second).takeUnless { employeeChoice.first == "none" }), filters.employeeIds.size,
                filters.employeeIds.sorted().joinToString("、") { employeeTitle(it, employees) }.ifEmpty { "全部员工" },
                backdrop, onClear = { employee(null) }, onExpand = { open = if (open == CapsuleDimension.Employee) null else CapsuleDimension.Employee })
            DropdownMenu(open == CapsuleDimension.Employee, onDismissRequest = { open = null }) {
                WandTextField(query, { query = it }, label = "搜索员工", singleLine = true,
                    trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("清除") } }, modifier = Modifier.padding(8.dp).width(240.dp))
                DropdownMenuItem(text = { Text("全部员工") }, trailingIcon = { if (filters.employeeIds.isEmpty()) Icon(WandIcons.check, "已选中") }, onClick = { employee(null) })
                val options = employees.filter { query.isBlank() || it.second.contains(query.trim(), ignoreCase = true) }
                if (options.isEmpty()) DropdownMenuItem(text = { Text("没有匹配的员工") }, onClick = {}, enabled = false)
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(option.second) }, modifier = Modifier.semantics { selected = option.first in filters.employeeIds },
                        trailingIcon = { if (option.first in filters.employeeIds) Icon(WandIcons.check, "已选中") }, onClick = { employee(option.first) })
                }
            }
        }
    }
    // Dialog 生命周期归稳定的筛选控件，菜单关闭或键盘弹出不会销毁表单。
    if (addingWorkspace && workspaceApi != null) AddWorkspaceDialog(workspaceApi,
        onDismiss = { addingWorkspace = false },
        onCreated = { created ->
            lastWorkspace = created.id
            onChange(filters.copy(workspaceIds = setOf(created.id), workspaceId = null))
            addingWorkspace = false
        })
}

@Composable
private fun ConversationCapsuleTrigger(
    title: String, allIcon: ImageVector, option: CapsuleOption, selectedCount: Int, summary: String,
    backdrop: GlassBackdrop?, onClear: () -> Unit, onExpand: () -> Unit,
    allDescription: String = "全部$title，清除此项筛选",
) {
    val selectedColor = MaterialTheme.colorScheme.primaryContainer
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.width(100.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Row(
            Modifier.glassSurface(backdrop, RoundedCornerShape(percent = 50), WandGlass.regular, drawRim = true)
                .clickable(onClick = onExpand)
                .semantics {
                    this.selected = selectedCount > 0
                    this.stateDescription = if (selectedCount == 0) "$summary；常用选项：${option.title}" else summary
                }
                .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)
                .background(if (selectedCount == 0) selectedColor else androidx.compose.ui.graphics.Color.Transparent, CircleShape)) {
                Icon(allIcon, allDescription, tint = MaterialTheme.colorScheme.primary)
            }
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)
                .background(if (selectedCount > 0) selectedColor else androidx.compose.ui.graphics.Color.Transparent, CircleShape)) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    WandInPlaceSwap(option, Modifier.size(32.dp), enterScale = 1f, exitScale = 1f) { displayed ->
                        val current = displayed as CapsuleOption
                        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                            Icon(current.icon, "$title 筛选，可多选", tint = MaterialTheme.colorScheme.primary)
                            current.monogram?.let {
                                Text(it, modifier = Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(2.5.dp))
                                    .padding(horizontal = 2.dp, vertical = 0.5.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Clip)
                            }
                        }
                    }
                    if (selectedCount > 1) Text(selectedCount.toString(), modifier = Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape).padding(horizontal = 3.5.dp, vertical = 0.5.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

private fun runtimeIcon(value: ConversationRuntime): ImageVector = when (value) {
    ConversationRuntime.Confirmation -> WandIcons.permission
    ConversationRuntime.Running -> WandIcons.usage
    ConversationRuntime.Queued -> WandIcons.history
    ConversationRuntime.Idle -> WandIcons.idle
    ConversationRuntime.Finished -> WandIcons.stop
    ConversationRuntime.Failed -> WandIcons.error
}
private fun employeeTitle(id: String, employees: List<Pair<String, String>>): String =
    if (id == "none") "未分配员工" else employees.firstOrNull { it.first == id }?.second ?: "员工不可用"

private fun capsuleInitials(label: String): String {
    val words = label.trim().split(' ', '_', '-', '/').filter { it.isNotEmpty() }
    return (if (words.size > 1) words.take(2).joinToString("") { it.take(1) } else label.take(2)).uppercase()
}
