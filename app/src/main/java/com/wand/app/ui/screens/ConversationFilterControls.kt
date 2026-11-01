package com.wand.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandTextField

private enum class ConversationFilterField { Stage, Workspace, Archive }

/** Draft exists only while the anchored popup is open; dismiss never applies it. */
@Composable
internal fun ConversationFilterControls(
    applied: ConversationListFilters,
    workspaces: List<Pair<String, String>>,
    onApply: (ConversationListFilters) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(applied) }
    var field by remember { mutableStateOf<ConversationFilterField?>(null) }
    var workspaceQuery by remember { mutableStateOf("") }
    val configuration = LocalConfiguration.current
    val panelWidth = (configuration.screenWidthDp - 24).coerceAtMost(384).dp
    val workspaceLabel = draft.workspaceId?.let { id ->
        workspaces.firstOrNull { it.first == id }?.second ?: "工作区不可用（$id）"
    } ?: "全部工作区"
    Box {
        TextButton(onClick = { draft = applied; workspaceQuery = ""; field = null; open = true },
            contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp)
                .semantics { contentDescription = "筛选，${applied.count} 个条件" }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(WandIcons.tune, null, Modifier.size(18.dp))
                Text(if (applied.count == 0) "筛选" else applied.count.toString(), style = MaterialTheme.typography.labelSmall)
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { if (field == null) open = false },
            // Child menus own outside/back dismissal while open, including taps in their search field.
            properties = PopupProperties(focusable = true,
                dismissOnBackPress = field == null, dismissOnClickOutside = field == null),
        ) {
            Column(Modifier.width(panelWidth).padding(horizontal = 8.dp)
                .onPreviewKeyEvent {
                    if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) {
                        if (field != null) field = null else open = false
                        true
                    } else false
                }) {
                // All three peer fields are in the first row; only their option menus need scrolling.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ConversationFilterDropdown(
                        label = "状态", fullLabel = "任务状态", value = draft.stage.label,
                        expanded = field == ConversationFilterField.Stage,
                        onOpen = { field = ConversationFilterField.Stage }, onDismiss = { field = null },
                        modifier = Modifier.weight(1f),
                    ) {
                        ConversationTaskStage.entries.filter { it != ConversationTaskStage.Unknown }.forEach { stage ->
                            ConversationFilterOption(stage.label, draft.stage == stage) {
                                draft = draft.copy(stage = stage); field = null
                            }
                        }
                    }
                    ConversationFilterDropdown(
                        label = "工作区", fullLabel = "工作区", value = workspaceLabel,
                        expanded = field == ConversationFilterField.Workspace,
                        onOpen = { workspaceQuery = ""; field = ConversationFilterField.Workspace }, onDismiss = { field = null },
                        modifier = Modifier.weight(1f),
                    ) {
                        WandTextField(workspaceQuery, { workspaceQuery = it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            label = "搜索工作区", singleLine = true)
                        ConversationFilterOption("全部工作区", draft.workspaceId == null) {
                            draft = draft.copy(workspaceId = null); field = null
                        }
                        val options = if (draft.workspaceId != null && workspaces.none { it.first == draft.workspaceId })
                            workspaces + (draft.workspaceId!! to workspaceLabel) else workspaces
                        val matches = options.filter { it.second.contains(workspaceQuery.trim(), ignoreCase = true) }
                        matches.forEach { (id, name) ->
                            ConversationFilterOption(name, draft.workspaceId == id) {
                                draft = draft.copy(workspaceId = id); field = null
                            }
                        }
                        if (matches.isEmpty()) Text("未找到工作区", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                        AddWorkspaceButton { workspace -> draft = draft.copy(workspaceId = workspace.id); field = null }
                    }
                    ConversationFilterDropdown(
                        label = "归档", fullLabel = "对话归档范围",
                        value = draft.tier.filterLabel,
                        expanded = field == ConversationFilterField.Archive,
                        onOpen = { field = ConversationFilterField.Archive }, onDismiss = { field = null },
                        modifier = Modifier.weight(1f),
                    ) {
                        ConversationListTier.entries.forEach { tier ->
                            ConversationFilterOption(tier.filterLabel,
                                draft.tier == tier) { draft = draft.copy(tier = tier); field = null }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(draft.activeOnly, role = Role.Switch, onValueChange = { draft = draft.copy(activeOnly = it) })
                    .semantics { contentDescription = "只看活动会话" },
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("仅活动", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(draft.activeOnly, onCheckedChange = null)
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { draft = ConversationListFilters(); workspaceQuery = "" },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("重置") }
                    TextButton(onClick = { open = false },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("取消") }
                    TextButton(onClick = { onApply(draft); open = false },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("应用") }
                }
            }
        }
    }
}

@Composable
private fun ConversationFilterDropdown(
    label: String,
    fullLabel: String,
    value: String,
    expanded: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val configuration = LocalConfiguration.current
    Box(modifier) {
        OutlinedButton(onClick = onOpen,
            modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).semantics {
                contentDescription = "$fullLabel：$value"
                stateDescription = if (expanded) "已展开" else "已收起"
            }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
            shape = MaterialTheme.shapes.small) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Icon(WandIcons.expand, null, Modifier.size(16.dp).align(Alignment.End))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss,
            modifier = Modifier.width((configuration.screenWidthDp - 32).coerceAtMost(352).dp)
                .heightIn(max = (configuration.screenHeightDp * .55f).dp)
                .onPreviewKeyEvent {
                    if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) { onDismiss(); true } else false
                },
            properties = PopupProperties(focusable = true),
            content = content)
    }
}

@Composable
private fun ConversationFilterOption(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp).semantics { stateDescription = if (selected) "已选中" else "未选中" },
        trailingIcon = { if (selected) Icon(WandIcons.check, null) })
}

internal fun conversationFilterSummary(filters: ConversationListFilters, workspaces: List<Pair<String, String>>, employees: List<Pair<String, String>> = emptyList()): String =
    buildList {
        if (filters.runtimes.isNotEmpty()) add(ConversationRuntime.entries.filter { it in filters.runtimes }.joinToString("、") { it.label })
        if (filters.workspaceIds.isNotEmpty()) add(filters.workspaceIds.map { id -> workspaces.firstOrNull { it.first == id }?.second ?: "工作区不可用" }.joinToString("、"))
        if (filters.employeeIds.isNotEmpty()) add("员工：" + filters.employeeIds.map { id -> employees.firstOrNull { it.first == id }?.second ?: "员工不可用" }.joinToString("、"))
        if (filters.stage != ConversationTaskStage.All) add(filters.stage.label)
        filters.workspaceId?.let { id -> add(workspaces.firstOrNull { it.first == id }?.second ?: "工作区不可用（$id）") }
        if (filters.tier != ConversationListTier.Active) add(if (filters.tier == ConversationListTier.All) filters.tier.filterLabel else "对话${filters.tier.label}")
        if (filters.activeOnly) add("只看活动会话")
    }.joinToString(" · ")
