package com.wand.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wand.app.data.KnowledgeScope
import com.wand.app.ui.KnowledgeController
import com.wand.app.ui.components.*
import com.wand.app.ui.theme.WandColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The same document surface serves phone drill-in and tablet list/detail. */
@Composable
fun KnowledgeScreen(
    controller: KnowledgeController,
    bottomClearance: Dp,
    serverDisplayName: String,
    onOpenSettings: () -> Unit,
    onSwitchServer: () -> Unit,
) {
    LaunchedEffect(controller) { controller.open() }
    val hasDocument = controller.selected != null || controller.editing
    val directoryScroll = rememberLazyListState()
    LaunchedEffect(controller.scope, controller.ownerId, controller.query) { directoryScroll.scrollToItem(0) }
    BackHandler(enabled = hasDocument || controller.busy) { if (!controller.busy) controller.backToDirectory() }
    Column(Modifier.fillMaxSize()) {
        if (!hasDocument) HomeTopBar(
            title = "知识库", serverDisplayName = serverDisplayName, interactionEnabled = !controller.busy,
            onNewTask = null, newTaskEnabled = false,
            onOpenSettings = { controller.guard(onOpenSettings) },
            onSwitchServer = { controller.guard(onSwitchServer) },
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val split = maxWidth >= 720.dp
            Row(Modifier.fillMaxSize()) {
                if (split || !hasDocument) {
                    KnowledgeDirectory(controller, bottomClearance, directoryScroll,
                        if (split) Modifier.width(280.dp).fillMaxHeight() else Modifier.fillMaxSize())
                }
                if (split) VerticalDivider(color = WandColors.border)
                if (split || hasDocument) {
                    KnowledgeDocumentPane(controller, bottomClearance, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
    if (controller.confirmingDiscard) WandDialog(
        title = "放弃未保存的修改？", onDismissRequest = controller::keepEditing,
        confirm = WandDialogAction("放弃修改", controller::discardAndContinue, destructive = true),
        dismiss = WandDialogAction("继续编辑", controller::keepEditing),
    ) { Text("正文还未保存。继续编辑可保留当前输入。") }
    if (controller.confirmingDelete) WandDialog(
        title = "删除文档？", onDismissRequest = controller::cancelDelete,
        confirm = WandDialogAction("删除", controller::deleteConfirmed, destructive = true),
        dismiss = WandDialogAction("取消", controller::cancelDelete),
    ) { Text("将从${controller.owner?.name ?: "当前"}的知识库中删除“${controller.selected?.title.orEmpty()}”，无法撤销。") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KnowledgeDirectory(controller: KnowledgeController, clearance: Dp, scroll: LazyListState, modifier: Modifier) {
    var ownerMenu by remember { mutableStateOf(false) }
    val documents = controller.visibleDocuments
    Column(modifier) {
        SecondaryTabRow(selectedTabIndex = controller.scope.ordinal, containerColor = Color.Transparent) {
            KnowledgeScope.entries.forEach { scope ->
                Tab(selected = controller.scope == scope, enabled = !controller.busy,
                    onClick = { controller.selectScope(scope) }, text = { Text(scope.label) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (controller.scope == KnowledgeScope.Global) {
                Text("所有员工、项目与 CLI", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            } else Box(Modifier.weight(1f)) {
                TextButton(onClick = { ownerMenu = true }, enabled = !controller.busy && controller.owners != null) {
                    Text(controller.owner?.name ?: if (controller.scope == KnowledgeScope.Personal) "选择员工" else "选择项目",
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                    Icon(WandIcons.expand, contentDescription = "选择知识库归属", Modifier.size(20.dp))
                }
                DropdownMenu(expanded = ownerMenu, onDismissRequest = { ownerMenu = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                    if (controller.scope == KnowledgeScope.Project) AddWorkspaceButton { workspace ->
                        ownerMenu = false; controller.refreshOwners(preferredOwnerId = workspace.id)
                    }
                    controller.owners?.forScope(controller.scope).orEmpty().forEach { owner ->
                        DropdownMenuItem(text = { Text(owner.name + if (owner.readOnly) " · 只读" else "") },
                            onClick = { ownerMenu = false; controller.selectOwner(owner.id) },
                            trailingIcon = if (controller.ownerId == owner.id) ({ Icon(WandIcons.statusDone, null) }) else null)
                    }
                }
            }
            IconButton(onClick = controller::reload, enabled = !controller.loading && !controller.busy) {
                Icon(WandIcons.refresh, "刷新知识库")
            }
            IconButton(onClick = controller::startNew, enabled = controller.canCreate) {
                Icon(WandIcons.add, "新建文档")
            }
        }
        WandInlineSearchField(expanded = true, query = controller.query, onQueryChange = controller::updateQuery,
            onCollapse = null, placeholder = "搜索标题或正文", autoFocus = false,
            onClear = { controller.updateQuery("") }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        if (controller.scope == KnowledgeScope.Global) Text("全局约定自动加载，合计最多 24 KB。默认安装同步各 CLI；现有终端会话可能需要重开。",
            style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary, modifier = Modifier.padding(horizontal = 16.dp))
        controller.library?.cliSyncErrors?.takeIf { it.isNotEmpty() }?.let { errors ->
            Text("知识已保存，部分 CLI 尚未同步：${errors.joinToString("；")}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }
        if (controller.readOnly) Text("项目已删除，已存文档仍可阅读和删除。", style = MaterialTheme.typography.bodySmall,
            color = WandColors.textSecondary, modifier = Modifier.padding(16.dp))
        if (controller.library?.let { it.total >= it.limit } == true) Text("当前知识库已达 ${controller.library?.limit} 篇上限。",
            style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary, modifier = Modifier.padding(horizontal = 16.dp))
        if (controller.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!controller.editing && controller.selected == null) KnowledgeFeedback(controller)
        when {
            controller.loading && controller.library == null -> KnowledgeEmpty("正在读取知识库…", "", Modifier.weight(1f))
            controller.owners == null -> KnowledgeEmpty("暂时无法读取知识库", "点击刷新重试。", Modifier.weight(1f))
            controller.ownerId == null -> KnowledgeEmpty(
                if (controller.scope == KnowledgeScope.Global) "全局知识库不可用" else if (controller.scope == KnowledgeScope.Personal) "还没有员工" else "还没有项目",
                if (controller.scope == KnowledgeScope.Global) "请更新服务端后重试。" else if (controller.scope == KnowledgeScope.Personal) "创建员工后，就会有独立的个人知识库。" else "创建工作区后，可以在这里保存项目知识。",  Modifier.weight(1f))
            documents.isEmpty() -> Column(Modifier.weight(1f).padding(bottom = clearance), horizontalAlignment = Alignment.CenterHorizontally) {
                KnowledgeEmpty(if (controller.query.isNotBlank()) "没有找到相关文档" else "这里还没有文档",
                    if (controller.query.isNotBlank()) "试试其他关键词，或清除搜索。" else "在对话中说“记住”，或新建一篇文档。", Modifier.weight(1f))
                if (controller.query.isBlank() && controller.canCreate) TextButton(onClick = controller::startNew) { Text("新建文档") }
            }
            else -> LazyColumn(Modifier.weight(1f), state = scroll, contentPadding = PaddingValues(top = 8.dp, bottom = clearance)) {
                documents.groupBy { it.folder.ifBlank { "未分类" } }.forEach { (folder, entries) ->
                    item(key = "folder:$folder") {
                        Text(folder, style = MaterialTheme.typography.labelMedium, color = WandColors.textSecondary,
                            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp))
                    }
                    items(entries, key = { it.id }) { document ->
                        Row(Modifier.fillMaxWidth()
                            .background(if (controller.selected?.id == document.id) WandColors.brandSoft else Color.Transparent)
                            .clickable(enabled = !controller.busy && !controller.loading) { controller.selectDocument(document) }
                            .padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(WandIcons.read, null, Modifier.size(20.dp), tint = WandColors.textSecondary)
                            Text(document.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp).weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KnowledgeDocumentPane(controller: KnowledgeController, clearance: Dp, modifier: Modifier) {
    val document = controller.selected
    if (document == null && !controller.editing) {
        KnowledgeEmpty("选择一篇文档", "从目录打开文档，或新建一篇知识。", modifier)
        return
    }
    Column(modifier.background(WandColors.bgElevated)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = controller::backToDirectory, enabled = !controller.busy) {
                Icon(WandIcons.back, null, Modifier.size(20.dp)); Text("目录")
            }
            Text(controller.owner?.name.orEmpty(), style = MaterialTheme.typography.labelMedium, color = WandColors.textSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
            if (controller.editing) {
                TextButton(onClick = controller::cancelEditing, enabled = !controller.busy) { Text("取消") }
                TextButton(onClick = controller::save, enabled = !controller.busy && !controller.conflict) { Text(if (controller.busy) "保存中" else "保存") }
            } else {
                IconButton(onClick = controller::requestDelete, enabled = !controller.busy && !controller.loading) { Icon(WandIcons.delete, "删除文档") }
                if (!controller.readOnly) TextButton(onClick = controller::edit, enabled = !controller.busy && !controller.loading) { Text("编辑") }
            }
        }
        HorizontalDivider(color = WandColors.border)
        if (controller.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        KnowledgeFeedback(controller)
        key(document?.id ?: "new", controller.editing) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = clearance + 24.dp)) {
                    if (controller.editing) {
                        val draft = controller.draft
                        val colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent)
                        TextField(value = draft.title, onValueChange = { controller.updateDraft(draft.copy(title = it)) },
                            enabled = !controller.busy, label = { Text("标题") }, textStyle = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.fillMaxWidth(), colors = colors)
                        TextField(value = draft.folder, onValueChange = { controller.updateDraft(draft.copy(folder = it)) },
                            enabled = !controller.busy, label = { Text("目录（可选）") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), colors = colors)
                        TextField(value = draft.content, onValueChange = { controller.updateDraft(draft.copy(content = it)) },
                            enabled = !controller.busy, label = { Text("正文 · 支持 Markdown") }, minLines = 14,
                            modifier = Modifier.fillMaxWidth(), colors = colors)
                        controller.latestDocument?.let { latest ->
                            var expanded by remember(latest.id, latest.revision) { mutableStateOf(false) }
                            HorizontalDivider(Modifier.padding(top = 16.dp), color = WandColors.border)
                            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起最新版本（只读）" else "查看最新版本（只读）") }
                            if (expanded) SelectionContainer {
                                Column {
                                    Text(latest.title, style = MaterialTheme.typography.titleLarge)
                                    Text("你的修改保留在上方。取消本次编辑后，可编辑最新版本。", style = MaterialTheme.typography.bodySmall,
                                        color = WandColors.textSecondary, modifier = Modifier.padding(vertical = 12.dp))
                                    MarkdownText(latest.content)
                                }
                            }
                        }
                    } else if (document != null) SelectionContainer {
                        Column {
                            Text("更新于 ${knowledgeDate(document.updatedAt)}", style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
                            Text(document.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 18.dp, bottom = 28.dp))
                            MarkdownText(document.content)
                            HorizontalDivider(Modifier.padding(top = 36.dp, bottom = 16.dp), color = WandColors.border)
                            Text(if (controller.scope == KnowledgeScope.Global) "全局约定 · 跨员工、项目与 CLI 自动加载"
                                else if (controller.scope == KnowledgeScope.Personal) "${controller.owner?.name.orEmpty()}的个人知识 · 其他员工不会读取"
                                else "${controller.owner?.name.orEmpty()}的项目知识 · 用于此项目的对话",
                                style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KnowledgeFeedback(controller: KnowledgeController) {
    val message = controller.error ?: controller.notice ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(message, style = MaterialTheme.typography.bodySmall,
            color = if (controller.error == null) WandColors.textSecondary else WandColors.dangerText)
        if (controller.conflict) {
            TextButton(onClick = if (controller.editing) controller::readLatest else controller::reload,
                enabled = !controller.busy && !controller.loading) { Text(if (controller.loading) "读取中" else "读取最新版本") }
            if (controller.latestRead && controller.latestDocument == null) Text("文档已被删除，你的草稿仍保留。",
                style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
        }
    }
}

@Composable
private fun KnowledgeEmpty(title: String, hint: String, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (hint.isNotBlank()) Text(hint, style = MaterialTheme.typography.bodyMedium, color = WandColors.textSecondary,
            modifier = Modifier.padding(top = 8.dp))
    }
}

internal fun knowledgeDate(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)
