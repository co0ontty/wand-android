package com.wand.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import com.wand.app.data.*
import com.wand.app.ui.ConversationStore
import com.wand.app.ui.components.*
import com.wand.app.ui.theme.WandColors
import org.json.JSONObject

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConversationGroupEditor(
    state: ConversationStore,
    inviteTo: ConversationInstance? = null,
    presetId: String? = null,
    draftContext: String = inviteTo?.id ?: "create",
    onClose: () -> Unit,
    onAccepted: (String) -> Unit,
) {
    val context = draftContext
    val draft = rememberSaveable(context, saver = com.wand.app.ui.ConversationGroupDraft.Saver) { state.groupDraft(context) }
    SideEffect { state.rememberGroupDraft(context, draft) }
    var selected by draft::selected
    var templateId by draft::templateId
    var excluded by draft::excluded
    var leaderId by draft::leaderId
    var name by draft::name
    var tab by draft::tab
    var duties by draft::duties
    var settingsExpanded by rememberSaveable(context) { mutableStateOf(false) }
    var query by rememberSaveable(context) { mutableStateOf("") }
    LaunchedEffect(presetId) { if (templateId.isBlank()) templateId = presetId.orEmpty() }
    var replacement by remember { mutableStateOf<String?>(null) }
    val operation = state.operation("group:$context")
    val phase = operation.phase
    val unknown = operation.unknown
    DisposableEffect(operation) { operation.attach(); onDispose { operation.detach() } }
    ConversationLayerBackHandler(true) { if (replacement != null) replacement = null else onClose() }
    val template = state.presets.firstOrNull { it.id == templateId }
    val current = inviteTo?.team?.members.orEmpty().map { it.employeeId ?: "${it.legacyTemplateId}:${it.legacyMemberId}" }.toSet()
    val templateMembers = template?.members.orEmpty().filterNot { it.id in excluded }
    val selectedEmployees = state.employees.filter { it.id in selected && templateMembers.none { m -> m.employeeId == it.id } }
    val memberCount = templateMembers.count { (it.employeeId ?: "${template?.id}:${it.id}") !in current } + selectedEmployees.count { it.id !in current }
    val total = inviteTo?.team?.members.orEmpty().size + memberCount
    val invalid = templateMembers.any { m -> m.employeeId != null && state.employees.none { it.id == m.employeeId && !it.archived } }
    val leaderName = inviteTo?.leader?.name ?: if (leaderId.isNotBlank()) {
        templateMembers.firstOrNull { it.id == leaderId || it.employeeId == leaderId }?.name ?: selectedEmployees.firstOrNull { it.id == leaderId }?.name
    } else templateMembers.firstOrNull { it.isLeader }?.name ?: selectedEmployees.firstOrNull()?.name
    fun usePreset(id: String) { templateId = id; selected = emptyList(); excluded = emptyList(); leaderId = ""; duties = emptyMap(); replacement = null }
    val keys = (templateMembers.map { (it.employeeId ?: it.id) to it.name } + selectedEmployees.map { it.id to it.name })
    val dutyValues = templateMembers.associate { (it.employeeId ?: it.id) to (duties[it.employeeId ?: it.id] ?: it.duty) } +
        selectedEmployees.associate { it.id to (duties[it.id] ?: it.duty) }
    val invalidDuty = dutyValues.values.any { it.trim().length > 2000 }
    LaunchedEffect(invalid, invalidDuty) { if (invalid || invalidDuty) settingsExpanded = true }
    BoxWithConstraints(Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
        if (event.key == Key.Escape && event.type == KeyEventType.KeyUp) {
            if (replacement != null) replacement = null else onClose(); true
        } else false
    }) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConversationPanelHeight.current).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (inviteTo == null) "发起群聊" else "邀请成员", style = MaterialTheme.typography.titleMedium, fontSize = 18.sp)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (inviteTo == null) "已选 $memberCount 位 · 最多 8 位员工" else "已选 $memberCount 位 · 全群 $total/8 位员工", style = MaterialTheme.typography.bodyMedium, color = WandColors.textSecondary)
                if (inviteTo != null) Text("新成员将在下次派任务时加入协作，当前任务不变。", style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
                WandChoiceStrip(options = listOf(0 to "员工", 1 to "预设小组"), selected = tab, onSelect = { tab = it },
                    modifier = Modifier.fillMaxWidth(), minHeight = 48.dp, labelFontSize = 14.sp, flat = true)
                WandInlineSearchField(true, query, { query = it }, null, if (tab == 0) "搜索员工" else "搜索预设小组",
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), autoFocus = false, onClear = { query = "" })
                if (tab == 0) {
                    val employees = state.employees.filter { query.isBlank() || "${it.name} ${it.duty}".contains(query, ignoreCase = true) }
                    employees.forEach { employee ->
                        val checked = employee.id in selected || templateMembers.any { it.employeeId == employee.id }
                        val enabled = !employee.archived && employee.id !in current && (checked || total < 8)
                        fun toggle() {
                            if (!enabled) return
                            if (templateMembers.any { m -> m.employeeId == employee.id }) excluded = excluded + templateMembers.first { m -> m.employeeId == employee.id }.id
                            else selected = if (checked) selected - employee.id else selected + employee.id
                        }
                        WandListItem(
                            modifier = Modifier.heightIn(min = 64.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = { toggle() }),
                            headlineContent = { Text(employee.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(if (employee.id in current) "已在群中" else if (employee.archived) "已归档" else employee.duty, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { EmployeeAvatar(employee.id, employee.name, employee.avatar, size = 40.dp) },
                            trailingContent = { Checkbox(checked, enabled = enabled, onCheckedChange = null, modifier = Modifier.size(48.dp)) },
                        )
                    }
                    if (employees.isEmpty()) Text(if (query.isBlank()) "还没有员工，可在通讯录创建。" else "没有匹配的员工", Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium, color = WandColors.textSecondary)
                } else {
                    val presets = state.presets.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
                    presets.forEach { preset ->
                        var expanded by rememberSaveable(preset.id) { mutableStateOf(false) }
                        WandListItem(headlineContent = { Text(preset.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("${preset.members.size} 位员工 · 负责人 ${preset.members.firstOrNull { it.isLeader }?.name ?: "未设置"}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingContent = { Icon(WandIcons.expand, null, Modifier.size(20.dp).rotate(if (expanded) 180f else 0f)) },
                            modifier = Modifier.heightIn(min = 64.dp).clickable { expanded = !expanded })
                        WandInlinePanel(visible = expanded, growFrom = Alignment.Top) {
                            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                ConversationPresetDetails(preset)
                                WandButton("使用这个预设", onClick = { if ((templateId.isNotBlank() && templateId != preset.id) || selected.isNotEmpty()) replacement = preset.id else usePreset(preset.id) })
                            }
                        }
                    }
                    if (presets.isEmpty()) Text(if (query.isBlank()) "还没有预设小组，可直接选择员工。" else "没有匹配的预设小组", Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium, color = WandColors.textSecondary)
                    replacement?.let { id ->
                        Text("替换本次群名单？原模板不变。")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { WandButton("确认替换", onClick = { usePreset(id) }); WandButton("保留原名单", onClick = { replacement = null }, variant = WandButtonVariant.Text) }
                    }
                }
                val showSettings = settingsExpanded
                Row(Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 56.dp).clickable { settingsExpanded = !settingsExpanded },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (inviteTo == null) "群设置" else "成员职责", style = MaterialTheme.typography.titleSmall)
                        Text(if (inviteTo == null) "负责人 ${leaderName ?: "未选择"} · 群名与职责可选" else "沿用员工职责，可仅为本群调整", style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
                    }
                    Icon(WandIcons.expand, null, Modifier.size(20.dp).rotate(if (showSettings) 180f else 0f), tint = WandColors.textMuted)
                }
                WandInlinePanel(showSettings, growFrom = Alignment.Top) { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                templateMembers.forEach { member ->
                    Text("${member.name}${if (member.isLeader) " · 预设负责人" else ""}", style = MaterialTheme.typography.titleSmall)
                    if (member.employeeId == null) Text("使用预设中的员工资料", style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
                    if (member.employeeId != null && state.employees.none { it.id == member.employeeId && !it.archived }) Text("绑定员工不可用，请显式移除 / 替换", color = WandColors.danger)
                    val dutyKey = member.employeeId ?: member.id
                    val alreadyInGroup = (member.employeeId ?: "${template?.id}:${member.id}") in current
                    if (!alreadyInGroup) WandTextField(value = dutyValues[dutyKey].orEmpty(),
                        onValueChange = { duties = duties + (dutyKey to it) }, label = "${member.name}本群职责（仅此群）",
                        modifier = Modifier.fillMaxWidth().testTag("conversation-duty:$dutyKey"), enabled = operation.canSubmit,
                        minLines = 1, maxLines = 3, isError = dutyValues[dutyKey].orEmpty().trim().length > 2000)
                    WandButton("从本次名单移除 ${member.name}", onClick = { excluded = excluded + member.id }, variant = WandButtonVariant.Text)
                }
                selectedEmployees.filterNot { it.id in current }.forEach { employee ->
                    key(employee.id) {
                        WandTextField(value = dutyValues[employee.id].orEmpty(), onValueChange = { duties = duties + (employee.id to it) },
                            label = "${employee.name}本群职责（仅此群）", modifier = Modifier.fillMaxWidth().testTag("conversation-duty:${employee.id}"),
                            enabled = operation.canSubmit, minLines = 1, maxLines = 3,
                            isError = dutyValues[employee.id].orEmpty().trim().length > 2000)
                    }
                }
                if (inviteTo == null) {
                    Text("负责人（仅此群）", style = MaterialTheme.typography.labelMedium)
                    keys.forEach { (id, label) ->
                    val chosen = (leaderId.ifBlank { templateMembers.firstOrNull { it.isLeader }?.let { it.employeeId ?: it.id } ?: selectedEmployees.firstOrNull()?.id }) == id
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(chosen, role = Role.RadioButton, onClick = { leaderId = id }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = chosen, onClick = null); Text(label)
                    } }
                    WandTextField(value = name, onValueChange = { name = it }, label = "群名（可选）", modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
                } }
            }
            val feedback = operation.feedback.ifBlank { if (invalid) "请显式处理不可用成员。" else if (invalidDuty) "本群职责不能超过2000个字符。" else "" }
            if (feedback.isNotBlank()) Text(feedback, Modifier.heightIn(max = 96.dp).verticalScroll(rememberScrollState()).padding(vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
            if (unknown != null) WandButton("核对请求", onClick = { operation.reconcile(state.api::conversationReceipt) }, variant = WandButtonVariant.Text)
            operation.accepted?.let { receipt -> WandButton("打开已建立的群", {
                state.finishGroup(context); onAccepted(receipt.conversationId)
            }, variant = WandButtonVariant.Text) }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WandButton("取消", onClick = onClose, variant = WandButtonVariant.Text)
                WandButton(label = when (phase) { "sending" -> "处理中"; "sent" -> "已完成"; "failed" -> "失败"; "unknown" -> "未确认"; else -> if (inviteTo == null) "建群" else "邀请" },
                    modifier = Modifier.widthIn(min = 96.dp).heightIn(min = 48.dp), enabled = operation.canSubmit && memberCount > 0 && total <= 8 && !invalid && !invalidDuty,
                    onClick = {
                        val group = draft.groupInput(dutyValues, inviting = inviteTo != null)
                        val body = JSONObject().put("group", group).apply { if (inviteTo != null) put("memberVersion", inviteTo.memberVersion) }
                        operation.submit(request = {
                            state.api.conversationPost(if (inviteTo == null) "/api/conversations" else "/api/conversations/${inviteTo.id}/invitations", body).also { state.refresh() }
                        }, onAccepted = { receipt -> state.finishGroup(context); onAccepted(receipt.conversationId) })
                    })
            }
        }
    }
}

@Composable
internal fun ConversationPresetDetails(team: AiTeam) {
    var instructions by rememberSaveable(team.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        team.members.forEach { member ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${member.name}${if (member.isLeader) " · 负责人" else ""}", style = MaterialTheme.typography.titleSmall)
                Text(member.duty, style = MaterialTheme.typography.bodyMedium, color = WandColors.textSecondary)
            }
        }
        Text("${if (team.requirePlanApproval) "开工前需你确认计划" else "按预设自动开工"} · 最多 ${team.maxSteps} 步", style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
        if (team.instructions.isNotBlank()) {
            WandButton(if (instructions) "收起协作说明" else "查看协作说明", { instructions = !instructions }, variant = WandButtonVariant.Text)
            WandInlinePanel(instructions, growFrom = Alignment.Top) { Text(team.instructions, style = MaterialTheme.typography.bodyMedium, color = WandColors.textSecondary) }
        }
    }
}
