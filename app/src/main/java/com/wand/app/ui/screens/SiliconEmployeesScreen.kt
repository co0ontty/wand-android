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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.key
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.horizontalScroll
import com.wand.app.ui.components.EmployeeAvatarPicker
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.ModelsResponse
import com.wand.app.data.SiliconEmployeeDraft
import com.wand.app.data.WandApi
import com.wand.app.data.boardAgentModelOptions
import com.wand.app.data.boardTaskProviderLabel
import com.wand.app.ui.components.EmployeeAvatar
import com.wand.app.ui.components.WandAgentFields
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
import com.wand.app.ui.components.WandInlinePanel
import com.wand.app.ui.components.WandInPlaceSwap
import com.wand.app.ui.components.WandStatusIconSlot
import com.wand.app.ui.components.WandTextField
import com.wand.app.ui.theme.WandColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class EmployeeOperation { Save, Archive, Delete }

@Composable
fun SiliconEmployeeEditorScreen(
    api: WandApi,
    employeeId: String?,
    onBack: () -> Unit,
    onMessage: ((String) -> Unit)? = null,
) {
    var editingProfile by rememberSaveable(employeeId) { mutableStateOf(employeeId == null) }
    val scope = rememberCoroutineScope()
    var editingId by remember(employeeId) { mutableStateOf(employeeId) }
    var draft by remember(employeeId) { mutableStateOf(SiliconEmployeeDraft()) }
    var savedDraft by remember(employeeId) { mutableStateOf<SiliconEmployeeDraft?>(null) }
    var archived by remember(employeeId) { mutableStateOf(false) }
    var builtin by remember(employeeId) { mutableStateOf(false) }
    var models by remember(api) { mutableStateOf<ModelsResponse?>(null) }
    var loading by remember(employeeId) { mutableStateOf(true) }
    var loadError by remember(employeeId) { mutableStateOf<String?>(null) }
    var reload by remember(employeeId) { mutableStateOf(0) }
    var operation by remember(employeeId) { mutableStateOf<EmployeeOperation?>(null) }
    var error by remember(employeeId) { mutableStateOf<String?>(null) }
    var status by remember(employeeId) { mutableStateOf("") }
    var managementError by remember(employeeId) { mutableStateOf<String?>(null) }
    var managementStatus by remember(employeeId) { mutableStateOf("") }
    var confirmArchive by remember(employeeId) { mutableStateOf(false) }
    var confirmDelete by remember(employeeId) { mutableStateOf(false) }
    var confirmDiscard by remember(employeeId) { mutableStateOf(false) }
    var configurationExpanded by rememberSaveable(employeeId) { mutableStateOf(false) }
    var promptExpanded by remember(employeeId) { mutableStateOf(employeeId == null) }
    var expandedCandidate by remember(employeeId) { mutableStateOf(0) }
    var avatarProcessing by remember { mutableStateOf(false) }
    val busy = operation != null || avatarProcessing

    LaunchedEffect(api, employeeId, reload) {
        loading = true
        loadError = null
        try {
            models = try {
                api.boardModels()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { null }
            val existing = employeeId?.let { api.siliconEmployee(it) }
            if (existing != null) {
                draft = SiliconEmployeeDraft.from(existing)
                archived = existing.archived
                builtin = existing.builtin
            } else {
                builtin = false
                val default = try {
                    api.boardTaskAgentDefaults()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null }
                draft = SiliconEmployeeDraft(agents = listOf((default ?: BoardTaskAgent.default())
                    .copy(kind = "structured")))
            }
            savedDraft = draft
            error = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            loadError = failure.message ?: "无法加载员工资料。"
        } finally {
            loading = false
        }
    }

    fun edit(next: SiliconEmployeeDraft) {
        if (operation != null) return
        draft = next
        error = null
        status = ""
    }

    fun leave() {
        if (busy) return
        if (savedDraft != null && draft != savedDraft) confirmDiscard = true
        else if (editingProfile && editingId != null) editingProfile = false else onBack()
    }

    fun save() {
        if (busy || loading || savedDraft == null || loadError != null) return
        val submitted = draft
        val problem = submitted.validationError(validateTags = !builtin)
        status = ""
        error = problem
        if (problem != null) return
        operation = EmployeeOperation.Save
        scope.launch {
            try {
                val saved = if (editingId == null) api.createSiliconEmployee(submitted)
                    else api.updateSiliconEmployee(editingId!!, submitted, agentsOnly = builtin)
                editingId = saved.id
                draft = SiliconEmployeeDraft.from(saved)
                savedDraft = draft
                archived = saved.archived
                builtin = saved.builtin
                status = "员工资料已保存。"
                editingProfile = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "保存员工失败，输入已保留。"
            } finally {
                operation = null
            }
        }
    }

    BackHandler { leave() }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            WandDetailTopBar(
                title = if (editingId == null) "创建员工" else if (editingProfile) "编辑员工资料" else "员工资料",
                leading = { WandDetailBackButton(onClick = ::leave, enabled = !busy, icon = WandIcons.back) },
                actions = {
                    if (!loading && savedDraft != null && loadError == null) {
                        if (editingProfile) WandButton("保存", onClick = ::save, enabled = !busy,
                            loading = operation == EmployeeOperation.Save, variant = WandButtonVariant.Text)
                        else WandButton("编辑", onClick = { editingProfile = true }, variant = WandButtonVariant.Text)
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            when {
                loading -> WandStatusIconSlot(
                    indicatorColor = WandColors.brand, containerColor = Color.Transparent,
                    running = true, icon = WandIcons.refresh, boxSize = 44.dp, iconSize = 26.dp,
                    modifier = Modifier.align(Alignment.Center),
                )
                loadError != null || savedDraft == null -> Column(
                    modifier = Modifier.align(Alignment.Center).widthIn(max = 480.dp).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("员工资料暂时无法加载", style = MaterialTheme.typography.titleMedium,
                        color = WandColors.textPrimary)
                    Text(loadError ?: "请重试。", color = WandColors.danger,
                        style = MaterialTheme.typography.bodyMedium)
                    WandButton("重新加载", onClick = { reload += 1 }, variant = WandButtonVariant.Secondary)
                }
                !editingProfile -> LazyColumn(
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        WandCard(contentPadding = PaddingValues(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                EmployeeAvatar(editingId, draft.name, draft.avatar, size = 48.dp,
                                    modifier = Modifier.clip(CircleShape))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(draft.name, style = MaterialTheme.typography.titleMedium,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(if (archived) "已归档 · 历史对话保留" else if (builtin) "Wand 内置员工" else "员工",
                                        style = MaterialTheme.typography.bodySmall, color = WandColors.textMuted)
                                    if (draft.tagInput.isNotBlank()) Text(draft.tagInput,
                                        style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
                                }
                            }
                            if (onMessage != null && editingId != null) {
                                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                                    WandButton("发送消息", { onMessage(editingId!!) })
                                }
                            }
                            EmployeeResultText(status, error = false, success = status.isNotBlank())
                        }
                    }
                    item {
                        WandCard {
                            Column(Modifier.padding(16.dp)) {
                                Text("职责", style = MaterialTheme.typography.bodySmall, color = WandColors.textMuted)
                                Text(draft.duty.ifBlank { "暂无职责说明" }, modifier = Modifier.padding(top = 6.dp),
                                    style = MaterialTheme.typography.bodyMedium, color = WandColors.textPrimary)
                            }
                            HorizontalDivider(color = WandColors.border, modifier = Modifier.padding(start = 16.dp))
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                EmployeeDisclosureHeader("角色设定", "", promptExpanded, true) { promptExpanded = !promptExpanded }
                                WandInlinePanel(promptExpanded, growFrom = Alignment.Top) {
                                    Text(draft.prompt.ifBlank { "暂无角色设定" }, modifier = Modifier.padding(bottom = 12.dp),
                                        style = MaterialTheme.typography.bodyMedium, color = WandColors.textSecondary)
                                }
                            }
                            HorizontalDivider(color = WandColors.border, modifier = Modifier.padding(start = 16.dp))
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                EmployeeDisclosureHeader("执行配置", "${draft.agents.size} 个候选", configurationExpanded, true) {
                                    configurationExpanded = !configurationExpanded
                                }
                                WandInlinePanel(configurationExpanded, growFrom = Alignment.Top) {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                                        draft.agents.forEachIndexed { index, agent ->
                                            Text("${if (index == 0) "首选" else "备用 $index"} · ${boardTaskProviderLabel(agent.provider)} · ${agent.model.ifBlank { "默认模型" }}",
                                                style = MaterialTheme.typography.bodyMedium, color = WandColors.textSecondary)
                                        }
                                        Text("员工 ID · ${editingId.orEmpty()}", style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted)
                                    }
                                }
                            }
                        }
                    }

                }
                else -> LazyColumn(
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item(key = "employee-profile") {
                        WandCard(contentPadding = PaddingValues(16.dp)) {
                            if (builtin) Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                EmployeeAvatar(editingId, draft.name, draft.avatar, size = 44.dp,
                                    provider = draft.agents.firstOrNull()?.provider)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(draft.name.ifBlank { "新员工" }, color = WandColors.textPrimary,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis)
                                    Text(when {
                                        archived -> "已归档 · 历史对话保留"
                                        builtin -> draft.tagInput.ifBlank { "Wand 内置员工" }
                                        else -> "员工 · ${draft.agents.size} 个执行候选"
                                    }, color = if (builtin) WandColors.brand else WandColors.textMuted,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (builtin) {
                                Text(draft.duty.ifBlank { "暂无职责说明" }, color = WandColors.textSecondary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = 14.dp))
                                Text("内置资料由 Wand 维护，可在下方调整执行候选。",
                                    color = WandColors.textMuted, style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 8.dp))
                            } else {
                                EmployeeAvatarPicker(draft.avatar, draft.name, enabled = !busy, onChange = { edit(draft.copy(avatar = it)) }, onBusyChange = { avatarProcessing = it })
                                WandTextField(draft.name, { edit(draft.copy(name = it)) },
                                    label = "名字", placeholder = "例如：产品经理", singleLine = true,
                                    enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                                WandTextField(draft.tagInput, { edit(draft.copy(tagInput = it)) },
                                    label = "标签", placeholder = "例如：开发，测试，设计", singleLine = true,
                                    enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                                Text("用逗号分隔，最多 8 个，每个 20 字。",
                                    color = WandColors.textMuted, style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp))
                                WandTextField(draft.duty, { edit(draft.copy(duty = it)) },
                                    label = "职责", placeholder = "描述这位员工负责什么",
                                    minLines = 2, maxLines = 5, enabled = !busy,
                                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                            }
                        }
                    }
                    item(key = "employee-prompt") {
                        WandCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                            EmployeeDisclosureHeader(
                                title = "角色设定",
                                summary = if (builtin) "内置 · 只读" else "",
                                expanded = promptExpanded,
                                enabled = !busy,
                                onToggle = { promptExpanded = !promptExpanded },
                            )
                            WandInlinePanel(visible = promptExpanded, growFrom = Alignment.Top) {
                                if (builtin) Text(draft.prompt.ifBlank { "暂无角色设定" },
                                    color = WandColors.textSecondary, style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)
                                        .verticalScroll(rememberScrollState()).padding(top = 10.dp))
                                else WandTextField(draft.prompt, { edit(draft.copy(prompt = it)) },
                                    placeholder = "说明工作方式、边界和交付要求",
                                    minLines = 4, maxLines = 10, enabled = !busy,
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                                        .semantics { contentDescription = "角色设定" })
                            }
                        }
                    }
                    item(key = "employee-candidates-heading") {
                        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("执行工具", color = WandColors.textMuted,
                                style = MaterialTheme.typography.labelMedium)
                            Text("首选无法启动时，按顺序尝试后续候选。", color = WandColors.textMuted,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    item(key = "employee-candidates") {
                        WandCard {
                        draft.agents.forEachIndexed { index, _ ->
                        key(index) {
                        if (index > 0) HorizontalDivider(color = WandColors.border, modifier = Modifier.padding(horizontal = 16.dp))
                        val agent = draft.agents[index]
                        val expanded = expandedCandidate == index
                        val model = boardAgentModelOptions(models, agent.provider)
                            .firstOrNull { it.id == agent.model }?.label
                            ?: agent.model.ifBlank { "跟随默认模型" }
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            EmployeeDisclosureHeader(
                                title = "${if (index == 0) "首选" else "候选 ${index + 1}"} · ${boardTaskProviderLabel(agent.provider)}",
                                summary = model,
                                expanded = expanded,
                                enabled = !busy,
                                onToggle = { expandedCandidate = if (expanded) -1 else index },
                            )
                            WandInlinePanel(visible = expanded, growFrom = Alignment.Top) {
                                Column(Modifier.padding(top = 8.dp)) {
                                    WandAgentFields(
                                        models = models, agent = agent,
                                        providerLabel = "CLI 工具", allowPty = false, enabled = !busy,
                                        onChange = { next -> edit(draft.copy(agents = setTeamCandidate(
                                            draft.agents, index, next.copy(kind = "structured")))) },
                                    )
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text("候选顺序", color = WandColors.textMuted,
                                            style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                                        WandIconButton(WandIcons.arrowUp, "上移候选 ${index + 1}",
                                            onClick = {
                                                edit(draft.copy(agents = moveTeamCandidate(draft.agents, index, -1)))
                                                expandedCandidate = index - 1
                                            }, variant = WandIconButtonVariant.Quiet, enabled = !busy && index > 0)
                                        WandIconButton(WandIcons.expand, "下移候选 ${index + 1}",
                                            onClick = {
                                                edit(draft.copy(agents = moveTeamCandidate(draft.agents, index, 1)))
                                                expandedCandidate = index + 1
                                            }, variant = WandIconButtonVariant.Quiet,
                                            enabled = !busy && index < draft.agents.lastIndex)
                                        WandIconButton(WandIcons.close, "移除候选 ${index + 1}",
                                            onClick = {
                                                edit(draft.copy(agents = removeTeamCandidate(draft.agents, index)))
                                                expandedCandidate = index.coerceAtMost(draft.agents.lastIndex)
                                            }, variant = WandIconButtonVariant.Quiet,
                                            enabled = !busy && draft.agents.size > 1)
                                    }
                                }
                            }
                        }
                    }
                        }
                        }
                    }
                    item(key = "employee-add-candidate") {
                        WandButton("添加候选", onClick = {
                            if (operation == null) {
                                edit(draft.copy(agents = addTeamCandidate(draft.agents)))
                                expandedCandidate = draft.agents.lastIndex
                            }
                        }, enabled = !busy && draft.agents.size < 4,
                            modifier = Modifier.fillMaxWidth(), variant = WandButtonVariant.Text,
                            icon = WandIcons.add)
                    }
                    item(key = "employee-save") {
                        EmployeeResultText(
                            text = when {
                                operation == EmployeeOperation.Save -> "正在保存员工资料…"
                                error != null -> error.orEmpty()
                                else -> status
                            },
                            error = error != null,
                            success = status.isNotBlank(),
                        )
                    }
                    if (editingId != null && !builtin) item(key = "employee-management") {
                        WandCard(contentPadding = PaddingValues(16.dp)) {
                            Text("员工管理", color = WandColors.textSecondary,
                                style = MaterialTheme.typography.titleSmall)
                            WandButton(
                                label = if (operation == EmployeeOperation.Archive) "更新中…"
                                    else if (archived) "恢复员工" else "归档员工",
                                onClick = { confirmArchive = true },
                                variant = if (archived) WandButtonVariant.Secondary else WandButtonVariant.DangerText,
                                enabled = !busy,
                                loading = operation == EmployeeOperation.Archive,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            WandButton(
                                label = if (operation == EmployeeOperation.Delete) "删除中…" else "删除员工",
                                onClick = { confirmDelete = true },
                                variant = WandButtonVariant.DangerText,
                                enabled = !busy,
                                loading = operation == EmployeeOperation.Delete,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            EmployeeResultText(managementError ?: managementStatus,
                                error = managementError != null, success = managementStatus.isNotBlank())
                        }
                    }
                }
            }
        }
    }

    if (confirmArchive) {
        WandDialog(
            title = if (archived) "恢复员工「${savedDraft?.name}」？" else "归档员工「${savedDraft?.name}」？",
            onDismissRequest = { if (!busy) confirmArchive = false },
            confirm = WandDialogAction(
                label = if (archived) "恢复员工" else "归档员工",
                destructive = !archived,
                enabled = !busy,
                onClick = {
                    val id = editingId ?: return@WandDialogAction
                    if (operation != null || builtin) return@WandDialogAction
                    val nextArchived = !archived
                    operation = EmployeeOperation.Archive
                    managementError = null
                    managementStatus = ""
                    confirmArchive = false
                    scope.launch {
                        try {
                            api.archiveSiliconEmployee(id, nextArchived)
                            archived = nextArchived
                            managementStatus = if (archived) "已归档，历史对话仍保留。" else "员工已恢复。"
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            managementError = failure.message ?: "员工状态更新失败。"
                        } finally { operation = null }
                    }
                },
            ),
            dismiss = WandDialogAction("取消", enabled = !busy, onClick = { confirmArchive = false }),
        ) {
            Text(if (archived) "恢复后可以再次指派任务，历史对话不受影响。"
                else "历史对话保留，归档后不会出现在新任务的指派列表中。",
                color = WandColors.textSecondary)
        }
    }
    if (confirmDelete) {
        WandDialog(
            title = "删除员工「${savedDraft?.name}」？",
            onDismissRequest = { if (!busy) confirmDelete = false },
            confirm = WandDialogAction(
                label = "删除员工",
                destructive = true,
                enabled = !busy,
                onClick = {
                    val id = editingId ?: return@WandDialogAction
                    if (operation != null || builtin) return@WandDialogAction
                    operation = EmployeeOperation.Delete
                    managementError = null
                    managementStatus = ""
                    confirmDelete = false
                    scope.launch {
                        try {
                            api.deleteSiliconEmployee(id)
                            onBack()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            managementError = failure.message ?: "删除员工失败。"
                        } finally { operation = null }
                    }
                },
            ),
            dismiss = WandDialogAction("取消", enabled = !busy, onClick = { confirmDelete = false }),
        ) {
            Text("删除后不能恢复员工定义；历史对话仍保留创建时的姓名和头像。",
                color = WandColors.textSecondary)
        }
    }
    if (confirmDiscard) {
        WandDialog(
            title = "放弃未保存的员工改动？",
            onDismissRequest = { confirmDiscard = false },
            confirm = WandDialogAction("放弃改动", destructive = true, enabled = !busy, onClick = {
                confirmDiscard = false
                savedDraft?.let { draft = it }
                if (editingId != null) editingProfile = false else onBack()
            }),
            dismiss = WandDialogAction("继续编辑", onClick = { confirmDiscard = false }),
        ) { Text("名字、标签、职责、角色设定和工具顺序会恢复到上次保存的内容。") }
    }
}

@Composable
private fun EmployeeDisclosureHeader(
    title: String,
    summary: String,
    expanded: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun toggle() {
        focusManager.clearFocus()
        keyboard?.hide()
        onToggle()
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
        .clickable(enabled = enabled, role = Role.Button, onClick = ::toggle),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = WandColors.textPrimary, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (summary.isNotBlank()) Text(summary, color = WandColors.textMuted,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.widthIn(max = 144.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(if (expanded) WandIcons.expand else WandIcons.chevronRight,
            contentDescription = null, tint = WandColors.textMuted,
            modifier = Modifier.size(20.dp))
    }
}

/** 仅在有真实反馈时显示；长错误保留滚动阅读。 */
@Composable
private fun EmployeeResultText(text: String, error: Boolean, success: Boolean) {
    if (text.isBlank()) return
    Box(Modifier.fillMaxWidth().heightIn(max = 96.dp).padding(top = 8.dp)
        .semantics { liveRegion = LiveRegionMode.Polite }) {
        WandInPlaceSwap(contentKey = Triple(text, error, success), enterScale = 1f, exitScale = 1f) { key ->
            @Suppress("UNCHECKED_CAST")
            val result = key as Triple<String, Boolean, Boolean>
            Text(result.first,
                color = when {
                    result.second -> WandColors.danger
                    result.third -> WandColors.success
                    else -> WandColors.textMuted
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            )
        }
    }
}
