package com.wand.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.ModelsResponse
import com.wand.app.data.SiliconEmployee
import com.wand.app.data.SiliconEmployeeDraft
import com.wand.app.data.WandApi
import com.wand.app.ui.components.WandAgentFields
import com.wand.app.ui.components.EmployeeAvatar
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandDetailBackButton
import com.wand.app.ui.components.WandDetailTopBar
import com.wand.app.ui.components.WandDialog
import com.wand.app.ui.components.WandDialogAction
import com.wand.app.ui.components.WandIconButton
import com.wand.app.ui.components.WandIconButtonVariant
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandTextField
import com.wand.app.ui.theme.WandColors
import kotlinx.coroutines.launch

@Composable
fun SiliconEmployeesScreen(
    api: WandApi,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onCreate: () -> Unit,
) {
    var employees by remember { mutableStateOf<List<SiliconEmployee>>(emptyList()) }
    var showArchived by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(api, refresh, showArchived) {
        loading = true
        try {
            employees = api.listSiliconEmployees(includeArchived = showArchived)
            error = null
        } catch (failure: Exception) {
            error = failure.message ?: "无法加载硅基员工。"
        } finally {
            loading = false
        }
    }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            WandDetailTopBar(
                title = "硅基员工",
                subtitle = "像联系人一样选择一起工作的角色",
                leading = { WandDetailBackButton(onClick = onBack) },
                actions = {
                    WandIconButton(WandIcons.refresh, "刷新员工", onClick = { refresh++ })
                    WandIconButton(WandIcons.add, "创建员工", onClick = onCreate)
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                WandButton(
                    label = if (showArchived) "只看在职员工" else "查看已归档员工",
                    onClick = { showArchived = !showArchived },
                    variant = WandButtonVariant.Text,
                    compact = true,
                )
            }
            if (loading && employees.isEmpty()) item {
                Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = WandColors.brand, modifier = Modifier.size(24.dp))
                }
            }
            if (!loading && employees.isEmpty() && error == null) item {
                Text(
                    if (showArchived) "没有已归档员工。" else "还没有员工。点右上角 ＋ 创建一位。",
                    color = WandColors.textMuted,
                    modifier = Modifier.padding(22.dp),
                )
            }
            items(employees, key = { it.id }) { employee ->
                WandCard(
                    modifier = Modifier.fillMaxWidth().clickable { onEdit(employee.id) },
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EmployeeAvatar(employee.id, employee.name, employee.avatar,
                            size = 28.dp, provider = employee.agents.firstOrNull()?.provider)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(employee.name, color = WandColors.textPrimary,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold)
                            Text(employee.duty.ifBlank { "还没有填写职责" },
                                color = WandColors.textSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                            Text(
                                employee.agents.joinToString(" → ") { it.provider } +
                                    if (employee.archived) " · 已归档" else "",
                                color = WandColors.textMuted,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Icon(WandIcons.chevronRight, contentDescription = null,
                            tint = WandColors.textMuted, modifier = Modifier.size(16.dp))
                    }
                }
            }
            error?.let { message -> item {
                Text(message, color = WandColors.danger, modifier = Modifier.padding(12.dp))
                WandButton("重试", onClick = { refresh++ }, variant = WandButtonVariant.Secondary)
            } }
        }
    }
}

@Composable
fun SiliconEmployeeEditorScreen(
    api: WandApi,
    employeeId: String?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var editingId by remember(employeeId) { mutableStateOf(employeeId) }
    var draft by remember(employeeId) { mutableStateOf(SiliconEmployeeDraft()) }
    var savedDraft by remember(employeeId) { mutableStateOf<SiliconEmployeeDraft?>(null) }
    var archived by remember(employeeId) { mutableStateOf(false) }
    var models by remember { mutableStateOf<ModelsResponse?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var confirmArchive by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(api, employeeId) {
        loading = true
        models = runCatching { api.boardModels() }.getOrNull()
        try {
            val existing = employeeId?.let { api.siliconEmployee(it) }
            if (existing != null) {
                draft = SiliconEmployeeDraft.from(existing)
                archived = existing.archived
            } else {
                val default = runCatching { api.boardTaskAgentDefaults() }.getOrNull()
                draft = SiliconEmployeeDraft(agents = listOf((default ?: BoardTaskAgent.default())
                    .copy(kind = "structured")))
            }
            savedDraft = draft
            error = null
        } catch (failure: Exception) {
            error = failure.message ?: "无法加载员工资料。"
        } finally {
            loading = false
        }
    }

    fun edit(next: SiliconEmployeeDraft) {
        draft = next
        error = null
        status = ""
    }

    fun leave() {
        if (!busy && savedDraft != null && draft != savedDraft) confirmDiscard = true
        else if (!busy) onBack()
    }

    BackHandler { leave() }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            WandDetailTopBar(
                title = if (editingId == null) "创建硅基员工" else "编辑硅基员工",
                subtitle = "设定角色与候选工具的尝试顺序",
                leading = { WandDetailBackButton(onClick = ::leave) },
            )
        },
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = WandColors.brand)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    WandCard(contentPadding = PaddingValues(14.dp)) {
                        WandTextField(draft.name, { edit(draft.copy(name = it)) },
                            label = "名字", placeholder = "例如：产品经理", singleLine = true,
                            enabled = !busy, modifier = Modifier.fillMaxWidth())
                        WandTextField(draft.duty, { edit(draft.copy(duty = it)) },
                            label = "职责", placeholder = "描述这位员工负责什么",
                            minLines = 2, maxLines = 5, enabled = !busy,
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                        WandTextField(draft.prompt, { edit(draft.copy(prompt = it)) },
                            label = "角色设定 Prompt", placeholder = "说明工作方式、边界和交付要求",
                            minLines = 4, maxLines = 10, enabled = !busy,
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                    }
                }
                item {
                    Text("工具链 · 首选无法启动时按顺序尝试",
                        color = WandColors.textSecondary,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 4.dp))
                }
                items(draft.agents.size, key = { it }) { index ->
                    val agent = draft.agents[index]
                    WandCard(contentPadding = PaddingValues(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (index == 0) "首选工具" else "候选 ${index + 1}",
                                color = WandColors.brand,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f))
                            WandIconButton(WandIcons.arrowUp, "上移候选 ${index + 1}",
                                onClick = { edit(draft.copy(agents = moveTeamCandidate(draft.agents, index, -1))) },
                                variant = WandIconButtonVariant.Compact, enabled = !busy && index > 0)
                            WandIconButton(WandIcons.expand, "下移候选 ${index + 1}",
                                onClick = { edit(draft.copy(agents = moveTeamCandidate(draft.agents, index, 1))) },
                                variant = WandIconButtonVariant.Compact,
                                enabled = !busy && index < draft.agents.lastIndex)
                            WandIconButton(WandIcons.close, "移除候选 ${index + 1}",
                                onClick = { edit(draft.copy(agents = removeTeamCandidate(draft.agents, index))) },
                                variant = WandIconButtonVariant.Compact,
                                enabled = !busy && draft.agents.size > 1)
                        }
                        WandAgentFields(
                            models = models, agent = agent,
                            providerLabel = "CLI 工具", allowPty = false, enabled = !busy,
                            onChange = { next ->
                                edit(draft.copy(agents = setTeamCandidate(draft.agents, index,
                                    next.copy(kind = "structured"))))
                            },
                        )
                    }
                }
                item {
                    WandButton("添加候选", onClick = {
                        edit(draft.copy(agents = addTeamCandidate(draft.agents)))
                    }, enabled = !busy && draft.agents.size < 4,
                        variant = WandButtonVariant.Secondary, icon = WandIcons.add)
                }
                item {
                    error?.let { Text(it, color = WandColors.danger) }
                    if (status.isNotBlank()) Text(status, color = WandColors.success)
                    WandButton(
                        label = if (busy) "保存中…" else "保存员工",
                        onClick = {
                            val problem = draft.validationError()
                            if (problem != null) {
                                error = problem
                            } else {
                                busy = true
                                error = null
                                scope.launch {
                                    try {
                                        val saved = if (editingId == null) api.createSiliconEmployee(draft)
                                            else api.updateSiliconEmployee(editingId!!, draft)
                                        editingId = saved.id
                                        draft = SiliconEmployeeDraft.from(saved)
                                        savedDraft = draft
                                        status = "已保存。"
                                    } catch (failure: Exception) {
                                        error = failure.message ?: "保存员工失败。"
                                    } finally { busy = false }
                                }
                            }
                        },
                        enabled = !busy,
                        loading = busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (editingId != null) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WandButton(
                            label = if (archived) "恢复员工" else "归档员工",
                            onClick = { confirmArchive = true },
                            variant = if (archived) WandButtonVariant.Secondary else WandButtonVariant.DangerText,
                            enabled = !busy,
                        )
                        WandButton(
                            label = "删除员工",
                            onClick = { confirmDelete = true },
                            variant = WandButtonVariant.DangerText,
                            enabled = !busy,
                        )
                    }
                }
            }
        }
    }

    if (confirmArchive) {
        WandDialog(
            title = if (archived) "恢复员工「${draft.name}」？" else "归档员工「${draft.name}」？",
            onDismissRequest = { confirmArchive = false },
            confirm = WandDialogAction(
                label = if (archived) "恢复员工" else "归档员工",
                destructive = !archived,
                enabled = !busy,
                onClick = {
                    val id = editingId ?: return@WandDialogAction
                    busy = true
                    scope.launch {
                        try {
                            api.archiveSiliconEmployee(id, !archived)
                            archived = !archived
                            status = if (archived) "已归档。历史对话仍会保留。" else "已恢复。"
                        } catch (failure: Exception) {
                            error = failure.message ?: "员工状态更新失败。"
                        } finally {
                            busy = false
                            confirmArchive = false
                        }
                    }
                },
            ),
            dismiss = WandDialogAction("取消", onClick = { confirmArchive = false }),
        ) {
            Text("历史对话保留，归档后不会出现在新任务的指派列表中。",
                color = WandColors.textSecondary)
        }
    }
    if (confirmDelete) {
        WandDialog(
            title = "删除员工「${draft.name}」？",
            onDismissRequest = { if (!busy) confirmDelete = false },
            confirm = WandDialogAction(
                label = if (busy) "删除中…" else "删除员工",
                destructive = true,
                enabled = !busy,
                onClick = {
                    val id = editingId ?: return@WandDialogAction
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            api.deleteSiliconEmployee(id)
                            confirmDelete = false
                            onBack()
                        } catch (failure: Exception) {
                            error = failure.message ?: "删除员工失败。"
                        } finally {
                            busy = false
                            confirmDelete = false
                        }
                    }
                },
            ),
            dismiss = WandDialogAction("取消", onClick = { confirmDelete = false }),
        ) {
            Text("删除后不能恢复员工定义；历史对话仍保留创建时的姓名和头像。",
                color = WandColors.textSecondary)
        }
    }
    if (confirmDiscard) {
        WandDialog(
            title = "放弃未保存的员工改动？",
            onDismissRequest = { confirmDiscard = false },
            confirm = WandDialogAction("放弃改动", destructive = true, onClick = onBack),
            dismiss = WandDialogAction("继续编辑", onClick = { confirmDiscard = false }),
        ) { Text("名字、职责、角色设定和工具顺序会恢复到上次保存的内容。") }
    }
}
