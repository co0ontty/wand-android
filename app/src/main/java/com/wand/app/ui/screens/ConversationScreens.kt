package com.wand.app.ui.screens

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wand.app.data.*
import com.wand.app.ui.ConversationStore
import com.wand.app.ui.ConversationApprovalButton
import com.wand.app.ui.acceptedConversationTask
import com.wand.app.ui.conversationStartupLabel
import com.wand.app.ui.SendActionVisual
import com.wand.app.ui.SendPhase
import com.wand.app.ui.thinkingEffortOptions
import com.wand.app.ui.components.*
import com.wand.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 面板开合与派发意图是两件事：附件/更多面板、键盘、正文点击、切根和返回列表都不改变这次要不要派任务。 */
internal enum class ConversationComposerEvent { PanelOpen, PanelClose, DispatchStart, DispatchCancel, DispatchSettled, LeftScreen }

/** (菜单面板是否展开, 是否派发意图) 的唯一转移表。清派发意图的只有显式取消和本次已接受。 */
internal fun conversationComposerState(
    menu: Boolean,
    taskMode: Boolean,
    event: ConversationComposerEvent,
): Pair<Boolean, Boolean> = when (event) {
    ConversationComposerEvent.PanelOpen -> true to taskMode
    ConversationComposerEvent.PanelClose -> false to taskMode
    ConversationComposerEvent.DispatchStart -> true to true
    ConversationComposerEvent.DispatchCancel -> false to false
    ConversationComposerEvent.DispatchSettled -> false to false
    ConversationComposerEvent.LeftScreen -> false to taskMode
}

/** 发送按钮的语义与实际请求路径同源：派发意图只会走 /tasks，收起面板不会把它降级成 /messages。 */
internal fun conversationSendPath(conversationId: String, dispatch: Boolean): String =
    "/api/conversations/$conversationId/" + if (dispatch) "tasks" else "messages"

@Composable
internal fun ConversationGroupAvatar(instance: ConversationInstance, size: androidx.compose.ui.unit.Dp = 48.dp) {
    Box(Modifier.size(size).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(WandColors.brandSoft), contentAlignment = Alignment.Center) {
        Icon(WandIcons.agent, "群聊 ${instance.title}", Modifier.size(size * .60f), tint = WandColors.brand)
    }
}

@Composable
internal fun ConversationList(state: ConversationStore, bottomClearance: androidx.compose.ui.unit.Dp = 68.dp, onOpenEmployee: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {}, onSwitchServer: () -> Unit = {}, onCollapseSidebar: (() -> Unit)? = null, onSelect: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var create by rememberSaveable { mutableStateOf(false) }
    var listTier by rememberSaveable { mutableStateOf(ConversationListTier.All.storageValue) }
    var swipedId by rememberSaveable { mutableStateOf<String?>(null) }
    var dissolveId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    var batchBusy by remember { mutableStateOf(false) }
    var pendingId by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var navigationMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun isBusy(): Boolean = pendingId != null || batchBusy
    fun update(itemId: String, patch: JSONObject) {
        if (isBusy()) return
        pendingId = itemId; actionError = null; swipedId = null
        scope.launch {
            try { state.updateListState(itemId, patch); dissolveId = null }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { actionError = failure.message ?: "更新失败，请重试" }
            finally { pendingId = null }
        }
    }
    fun exitSelection() { selecting = false; selectedIds = emptySet(); confirmBatchDelete = false }
    fun toggleSelected(itemId: String) {
        selectedIds = if (itemId in selectedIds) selectedIds - itemId else selectedIds + itemId
    }
    /** 批量置顶整批走同一条 patch 通道，失败项留在选中集里可重试，不静默吞掉。 */
    fun runBatchPin(action: ConversationBatchAction) {
        val ids = selectedIds.toList()
        if (ids.isEmpty() || isBusy()) return
        batchBusy = true; actionError = null
        scope.launch {
            var failure: String? = null
            ids.forEach { id ->
                try { state.updateListState(id, JSONObject().put("pinned", action == ConversationBatchAction.Pin)) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (err: Exception) { failure = err.message ?: "更新失败，请重试" }
            }
            batchBusy = false; actionError = failure
            if (failure == null) exitSelection()
        }
    }
    fun runBatchDelete() {
        val ids = selectedIds.toList()
        if (ids.isEmpty() || isBusy()) return
        batchBusy = true; actionError = null; confirmBatchDelete = false
        scope.launch {
            var failure: String? = null
            val failed = linkedSetOf<String>()
            ids.forEach { id ->
                try { state.remove(id) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (err: Exception) { failure = err.message ?: "删除失败，请重试"; failed += id }
            }
            batchBusy = false; actionError = failure
            if (failed.isEmpty()) exitSelection() else selectedIds = failed
        }
    }
    val forms = rememberSaveableStateHolder()
    val layer = remember { ConversationOutsideLayer() }
    val conversationListState = state.listState("conversations")
    // 滚动、切档、开建档面板都要收起唯一那一条划开的行；切档同时清掉多选态，避免残留跨档选中。
    LaunchedEffect(conversationListState.isScrollInProgress) { if (conversationListState.isScrollInProgress) swipedId = null }
    LaunchedEffect(listTier) { swipedId = null; exitSelection() }
    LaunchedEffect(create) { if (create) swipedId = null }
    val searchTrigger = remember { androidx.compose.ui.focus.FocusRequester() }
    val createTrigger = remember { androidx.compose.ui.focus.FocusRequester() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun close(restoreFocus: Boolean = false) {
        val trigger = if (create) createTrigger else searchTrigger
        if (create) create = false else query = ""
        keyboard?.hide()
        if (restoreFocus) runCatching { trigger.requestFocus() }
    }
    LaunchedEffect(state.layerRevision) { create = false }
    // 建档面板优先于多选态消耗返回；多选态退出后选中集一并清掉。
    ConversationLayerBackHandler(create || selecting) { if (create) close(true) else exitSelection() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    CompositionLocalProvider(LocalConversationPanelHeight provides maxHeight) {
    Column(Modifier.fillMaxSize().imePadding().then(layer.host(create, setOf("actions", "form", "search", "list")) { close() }).onKeyEvent { event ->
        if (event.key == Key.Escape && event.type == KeyEventType.KeyUp && (create || query.isNotEmpty())) { close(true); true } else false
    }) {
        Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(if (listTier == ConversationListTier.All.storageValue) "消息" else ConversationListTier.of(listTier).label,
                Modifier.padding(horizontal = 104.dp), fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.align(Alignment.CenterEnd).size(width = 96.dp, height = 48.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(44.dp).clip(CircleShape).background(WandColors.surface).border(0.6.dp, WandColors.border, CircleShape))
            Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                WandIconButton(WandIcons.more, "消息菜单", { navigationMenu = true }, enabled = !create, iconSize = 20.dp)
                DropdownMenu(navigationMenu, onDismissRequest = { navigationMenu = false }) {
                    ConversationListTier.entries.forEach { tier -> DropdownMenuItem(
                        text = { Text(if (tier == ConversationListTier.All) "全部消息" else tier.label) },
                        trailingIcon = { if (tier.storageValue == listTier) Icon(WandIcons.check, null, tint = WandColors.brand) },
                        onClick = { listTier = tier.storageValue; navigationMenu = false }) }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("设置") }, leadingIcon = { Icon(WandIcons.settings, null) }, onClick = { navigationMenu = false; onOpenSettings() })
                    DropdownMenuItem(text = { Text("切换服务器") }, leadingIcon = { Icon(WandIcons.swapServer, null) }, onClick = { navigationMenu = false; onSwitchServer() })
                    onCollapseSidebar?.let { collapse -> DropdownMenuItem(text = { Text("收起侧栏") }, onClick = { navigationMenu = false; collapse() }) }
                }
            }
            WandIconButton(if (create) WandIcons.close else WandIcons.add, if (create) "关闭建群" else "发起群聊",
                onClick = { create = !create }, iconSize = 20.dp, modifier = Modifier.focusRequester(createTrigger).then(layer.region("actions")))
            }
            }
        }
        if (!create) WandInlineSearchField(true, query, { query = it }, null, "搜索聊天或任务",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).heightIn(min = 40.dp).then(layer.region("search")),
            autoFocus = false, focusRequester = searchTrigger, onClear = { query = "" })
        WandInlinePanel(visible = create, growFrom = Alignment.Top,
            modifier = (if (create) Modifier.weight(1f) else Modifier).then(layer.region("form"))) {
            forms.SaveableStateProvider("create-group") {
                ConversationGroupEditor(state, onClose = { close(true) }, onAccepted = { create = false; onSelect(it) })
            }
        }
        val rows = filterConversationList(state.items, ConversationListTier.of(listTier), query)
        val selectedRows = rows.filter { it.id in selectedIds }
        val batchAction = conversationBatchAction(selectedRows.map { it.pinnedAt != null })
        if (selecting && !create) ConversationManageBar(
            count = selectedIds.size,
            allSelected = selectedIds.isNotEmpty() && selectedIds.size == rows.size,
            busy = isBusy(),
            batchAction = batchAction,
            onSelectAll = { selectedIds = if (selectedIds.isNotEmpty() && selectedIds.size == rows.size) emptySet() else rows.map { it.id }.toSet() },
            onBatchPin = { batchAction?.let(::runBatchPin) },
            onDelete = { if (selectedIds.isNotEmpty()) confirmBatchDelete = true },
            onDone = { exitSelection() },
        )
        actionError?.let { Text(it, Modifier.padding(12.dp), color = WandColors.danger) }
        LazyColumn(state = conversationListState, userScrollEnabled = !create,
            modifier = (if (create) Modifier.height(0.dp).clearAndSetSemantics {} else Modifier.weight(1f))
                .padding(horizontal = 12.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(WandColors.surface).then(layer.region("list")),
            contentPadding = PaddingValues(top = 4.dp, bottom = bottomClearance)) {
            if (state.error != null) item { ErrorState(state.error.orEmpty(), onRetry = { state.retry() }) }
            if (state.loading && state.items.isEmpty()) items(3) { Row(Modifier.fillMaxWidth().height(76.dp).padding(12.dp)) {
                Box(Modifier.size(44.dp).clip(WandShapes.md).background(WandColors.surface))
                Column(Modifier.padding(start = 12.dp)) { repeat(2) { Box(Modifier.padding(vertical = 4.dp).width(120.dp).height(12.dp).background(WandColors.surface)) } }
            } }
            items(rows, key = { it.id }) { item ->
                val employee = state.employees.firstOrNull { it.id == item.peerEmployeeId }
                val matchingTasks = if (query.isBlank()) item.tasks else item.tasks.filter { it.task.title.contains(query, ignoreCase = true) }
                val expanded = if (query.isNotBlank()) matchingTasks.isNotEmpty() else item.id in state.expandedGroups
                val selected = state.selectedId == item.id
                val managedSelected = item.id in selectedIds
                val archived = isConversationArchived(item)
                val summary = if (item.dissolvedAt != null) "群聊已解散 · 点击查看或恢复" else item.preview.ifBlank { if (item.kind == "group") "尚未有消息" else "还没有消息" }
                // 归档的对话不再是「等你回复/在跑」，只留给归档标记；否则和未归档的排成一样。
                val status = if (archived) null else item.tasks.flatMap { it.runs }.firstOrNull { it.status in listOf("waiting_user", "awaiting_approval", "failed", "running") }?.status
                Column {
                  // 操作从长按菜单改成从右往左划出的抽屉；多选态下没有滑动手势，整行只做选中切换。
                  ConversationSwipeRowCard(
                    actions = if (selecting) emptyList() else conversationSwipeActions(item.kind, item.pinnedAt != null, item.dissolvedAt != null),
                    revealed = swipedId == item.id,
                    onRevealedChange = { open -> swipedId = if (open) item.id else swipedId?.takeIf { it != item.id } },
                    onAction = { action ->
                        when (action) {
                            ConversationSwipeAction.Pin -> update(item.id, JSONObject().put("pinned", true))
                            ConversationSwipeAction.Unpin -> update(item.id, JSONObject().put("pinned", false))
                            ConversationSwipeAction.Dissolve -> dissolveId = item.id
                            ConversationSwipeAction.Restore -> update(item.id, JSONObject().put("dissolved", false))
                            ConversationSwipeAction.Delete -> deleteId = item.id
                        }
                    },
                    cardBackground = WandColors.surface,
                  ) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp)
                        .background(if (selected || managedSelected) WandColors.selectedFill else Color.Transparent)
                        .semantics { this.selected = if (selecting) managedSelected else selected }
                        .combinedClickable(
                            onClick = { if (selecting) toggleSelected(item.id) else { create = false; keyboard?.hide(); onSelect(item.id) } },
                            onLongClickLabel = if (selecting) "取消选择对话" else "多选对话",
                            onLongClick = { keyboard?.hide(); if (selecting) toggleSelected(item.id) else { swipedId = null; selecting = true; selectedIds = setOf(item.id) } },
                        )
                        .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (selecting) ManageCheck(managedSelected)
                        Box(Modifier.size(48.dp).clickable(enabled = employee != null && !selecting, role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "查看${employee?.name.orEmpty()}的资料") { employee?.let { onOpenEmployee(it.id) } }, contentAlignment = Alignment.Center) {
                            if (employee != null) EmployeeAvatar(employee.id, employee.name, employee.avatar, size = 44.dp) else ConversationGroupAvatar(item, size = 44.dp)
                        }
                        Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(item.title, Modifier.weight(1f), fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal,
                                    color = if (archived) WandColors.textSecondary else WandColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (item.pinnedAt != null) Icon(WandIcons.leader, "已置顶", Modifier.size(12.dp), tint = WandColors.textMuted)
                                val clock = conversationListClock(item.messageAt)
                                if (clock.isNotBlank()) Text(clock, style = MaterialTheme.typography.labelSmall, color = WandColors.textMuted, maxLines = 1)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (status != null) Text(conversationRunLabel(status), fontSize = 12.sp, color = if (status == "failed") WandColors.danger else WandColors.textSecondary, maxLines = 1)
                                Text(if (archived) "$ConversationArchivedLabel · $summary" else summary,
                                    Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp, color = WandColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (!selecting && listTier != ConversationListTier.Archived.storageValue && item.tasks.isNotEmpty()) WandMorphIconButton(expanded, WandIcons.expand, WandIcons.expand,
                            if (expanded) "收起群任务" else "展开群任务", { if (item.id in state.expandedGroups) state.expandedGroups.remove(item.id) else state.expandedGroups.add(item.id) }, touchSize = 48.dp, rotationDegrees = 180f)
                        else if (!selecting) Icon(WandIcons.chevronRight, null, Modifier.padding(horizontal = 10.dp).size(16.dp), tint = WandColors.textMuted)
                    }
                  }
                    WandInlinePanel(expanded && listTier != ConversationListTier.Archived.storageValue, growFrom = Alignment.Top) {
                    Column {
                    matchingTasks.forEach { task ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                            state.filter(item.id, task.task.id)
                            onSelect(item.id)
                        }.padding(start = 76.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(task.task.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(conversationTaskStatusLabel(task), Modifier.widthIn(min = 64.dp), style = MaterialTheme.typography.labelSmall, color = WandColors.textSecondary)
                        }
                    }
                    }
                    }
                    ConversationInsetDivider(start = 68.dp)
                }
            }
            if (rows.isEmpty() && !state.loading && state.error == null) item {
                val empty = conversationListEmptyState(query, ConversationListTier.of(listTier))
                EmptyState(icon = WandIcons.toolResult, title = empty.title, subtitle = empty.explanation,
                    actionText = empty.action, onAction = {
                        when (empty) {
                            ConversationListEmptyState.Empty -> { create = true; keyboard?.hide() }
                            ConversationListEmptyState.Search -> { query = ""; runCatching { searchTrigger.requestFocus() } }
                            ConversationListEmptyState.Filtered -> { query = ""; listTier = ConversationListTier.All.storageValue; keyboard?.hide() }
                        }
                    })
            }
        }
    }
    }
    }
    dissolveId?.let { dissolving ->
        WandDialog(title = "解散群聊？", onDismissRequest = { if (!isBusy()) dissolveId = null },
            confirm = WandDialogAction("解散群聊", destructive = true, enabled = !isBusy(), onClick = { update(dissolving, JSONObject().put("dissolved", true)) }),
            dismiss = WandDialogAction("取消", enabled = !isBusy(), onClick = { dissolveId = null })) {
            Text("群聊将归档并保留在会话列表。历史记录和关联任务保留，恢复后可继续聊天。")
            actionError?.let { Text(it, color = WandColors.danger) }
        }
    }
    deleteId?.let { deleting ->
        WandDialog(title = "删除对话「${state.items.firstOrNull { it.id == deleting }?.title.orEmpty()}」？",
            onDismissRequest = { if (!isBusy()) deleteId = null },
            confirm = WandDialogAction("删除对话", destructive = true, enabled = !isBusy(), onClick = {
                if (pendingId == null && !batchBusy) { pendingId = deleting; actionError = null; scope.launch {
                    try { state.remove(deleting); deleteId = null }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (failure: Exception) { actionError = failure.message ?: "删除失败，请重试" }
                    finally { pendingId = null }
                } }
            }),
            dismiss = WandDialogAction("取消", enabled = !isBusy(), onClick = { deleteId = null })) {
            Text(if (state.items.firstOrNull { it.id == deleting }?.kind == "group") "将删除这个群聊、所有关联任务，以及它们的消息、执行记录和资源文件。此操作无法撤销。" else "将永久删除此私聊的消息记录和会话文件。员工资料及已经派出的群聊任务保留。此操作无法撤销。")
            actionError?.let { Text(it, color = WandColors.danger) }
        }
    }
    if (confirmBatchDelete) {
        WandDialog(title = "删除选中的 ${selectedIds.size} 个对话？",
            onDismissRequest = { if (!isBusy()) confirmBatchDelete = false },
            icon = WandIcons.delete,
            confirm = WandDialogAction(if (batchBusy) "正在删除…" else "删除对话", destructive = true, enabled = !isBusy(), onClick = { runBatchDelete() }),
            dismiss = WandDialogAction("取消", enabled = !isBusy(), onClick = { confirmBatchDelete = false })) {
            Text("将逐个删除这些对话，以及其中关联的任务、消息记录和资源文件。此操作无法撤销。")
            actionError?.let { Text(it, color = WandColors.danger) }
        }
    }
}

/** 多选操作条：沿用侧栏管理条的卡片形态，动作换成会话自己的置顶与删除。 */
@Composable
private fun ConversationManageBar(
    count: Int,
    allSelected: Boolean,
    busy: Boolean,
    batchAction: ConversationBatchAction?,
    onSelectAll: () -> Unit,
    onBatchPin: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    WandCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        containerColor = WandColors.surface.copy(alpha = .92f),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (count > 0) "已选择 $count 个对话" else "点选对话可批量置顶或删除",
                style = MaterialTheme.typography.labelLarge, color = WandColors.textPrimary, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = onSelectAll, enabled = !busy) { Text(if (allSelected) "取消全选" else "全选") }
            TextButton(onClick = onBatchPin, enabled = !busy && batchAction != null) {
                Text(if (batchAction == ConversationBatchAction.Unpin) "取消置顶" else "置顶",
                    color = if (!busy && batchAction != null) WandColors.brand else WandColors.textMuted)
            }
            TextButton(onClick = onDelete, enabled = !busy && count > 0) {
                Text("删除", color = if (!busy && count > 0) WandColors.danger else WandColors.textMuted)
            }
            TextButton(onClick = onDone) { Text("完成") }
        }
    }
}

/**
 * 聊天详情：手机首屏是 ConversationList，本页是它上面的一条导航记录，返回即回来源列表。
 * 记忆的 selectedId 只投影列表高亮，不能替用户打开详情。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConversationChatScreen(state: ConversationStore, id: String, isHapticEnabled: () -> Boolean,
    onContacts: () -> Unit, onOpenEmployee: (String) -> Unit = {}, onOpenSession: (String) -> Unit, showBack: Boolean = false, onBack: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val detail = state.details[id]
    val displayTitle = detail?.title ?: state.items.firstOrNull { it.id == id }?.title
        ?: state.selectedTitle.takeIf { state.selectedId == id && it.isNotBlank() } ?: id.takeIf { it.isNotBlank() }
    val target = state.targets[id]
    val filter = state.filters[id].orEmpty()
    val key = state.draftKey(id, target)
    val composer = state.composer(id, target)
    val inputPresentation = remember(key) { ChatInputPresentation() }
    var defaultsOpen by remember(key) { mutableStateOf(false) }
    val executionChoice = remember { com.wand.app.ui.EmployeeExecutionPreferenceState() }
    var executionModels by remember(id) { mutableStateOf<com.wand.app.data.ModelsResponse?>(null) }
    var executionModelsLoading by remember(id) { mutableStateOf(false) }
    var executionModelsError by remember(id) { mutableStateOf<String?>(null) }
    var executionToolOpen by remember(id) { mutableStateOf(false) }
    var executionModelOpen by remember(id) { mutableStateOf(false) }
    var executionEffortOpen by remember(id) { mutableStateOf(false) }
    val privateChat = detail?.kind == "dm"
    val currentId by rememberUpdatedState(id)
    val currentKey by rememberUpdatedState(key)
    val employee = state.employees.firstOrNull { it.id == detail?.peerEmployeeId || id == employeeConversationId(it.id) }
    val defaultExecutionAgent = employee?.agents?.firstOrNull()
    LaunchedEffect(id, employee?.id) { executionChoice.selectEmployee(employee) }
    val selectedExecutionAgent = executionChoice.preference ?: defaultExecutionAgent
    suspend fun loadExecutionModels() {
        executionModelsLoading = true
        executionModelsError = null
        try {
            executionModels = state.api.models()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            executionModelsError = failure.message ?: "无法加载模型目录，请重试。"
        } finally {
            executionModelsLoading = false
        }
    }
    LaunchedEffect(id, privateChat, defaultExecutionAgent) {
        executionChoice.selectEmployee(employee)
        executionModels = null
        executionModelsError = null
        if (privateChat) loadExecutionModels()
    }
    var menu by rememberSaveable(id) { mutableStateOf(false) }
    var taskMode by rememberSaveable(id) { mutableStateOf(false) }
    val metadata = rememberSaveable(id, saver = com.wand.app.ui.ConversationComposeDraft.Saver) { state.composeDraft(id) }
    SideEffect { state.rememberComposeDraft(id, metadata) }
    var title by metadata::title
    var projectId by metadata::projectId
    var continueTask by metadata::continueTask
    var memberVersion by metadata::memberVersion
    var members by rememberSaveable(id) { mutableStateOf(false) }
    var headerMenu by remember(id) { mutableStateOf(false) }
    var invite by rememberSaveable(id) { mutableStateOf(false) }
    var titleOpen by rememberSaveable(id) { mutableStateOf(false) }
    var taskDetails by rememberSaveable(id) { mutableStateOf(false) }
    var chatCwd by metadata::chatCwd
    val actionOperation = state.operation("action:$key")
    DisposableEffect(actionOperation) { actionOperation.attach(); onDispose { actionOperation.detach() } }
    val actionPhase = if (actionOperation.purpose == "approve") null else when (actionOperation.phase) {
        "sending" -> SendActionVisual.Sending; "sent" -> SendActionVisual.Sent; "failed", "unknown" -> SendActionVisual.Failed; else -> null
    }
    val unaddressedComposer = state.composer("", null)
    val adoption = composer.pendingDraftAdoption(unaddressedComposer)
    val alive = remember(id) { mutableStateOf(true) }
    DisposableEffect(id) { alive.value = true; onDispose { alive.value = false } }
    val forms = rememberSaveableStateHolder()
    val layer = remember { ConversationOutsideLayer() }
    val memberTrigger = remember { androidx.compose.ui.focus.FocusRequester() }
    val menuTrigger = remember { androidx.compose.ui.focus.FocusRequester() }
    val inviteTrigger = remember { androidx.compose.ui.focus.FocusRequester() }
    val titleTrigger = remember { androidx.compose.ui.focus.FocusRequester() }
    val taskTrigger = memberTrigger
    val messageTriggers = remember { mutableMapOf<String, androidx.compose.ui.focus.FocusRequester>() }
    LaunchedEffect(id) { withFrameNanos { }; runCatching { titleTrigger.requestFocus() } }
    val run = detail?.runDetails?.firstOrNull { it.run.id == target?.runId }
    val interactiveIds = conversationInteractiveSessions(detail)
    val protocols = interactiveIds.associateWith(state::sessionStore)
    interactiveIds.forEach { sessionId -> androidx.compose.runtime.key(sessionId) {
        DisposableEffect(state, sessionId) {
            state.attachSession(sessionId)
            onDispose { state.detachSession(sessionId) }
        }
    } }
    val communication = detail?.communicationSessionId?.let(protocols::get)
    val running = if (target != null) run?.run?.status?.let(::conversationRunActive) == true else communication?.isResponding == true
    protocols.forEach { (sessionId, protocol) ->
        LaunchedEffect(sessionId, protocol.toast) {
            protocol.toast?.let { state.feedback[key] = it; protocol.toast = null }
        }
    }
    val unknown = state.unknownRequests[key]
    val currentTaskMode by rememberUpdatedState(taskMode)
    LaunchedEffect(id) { while (true) { state.load(id); delay(3000) } }
    DisposableEffect(id, detail?.kind) {
        val close = if (detail?.kind == "dm") state.watchTaskPreviews(id) else null
        onDispose { close?.invoke() }
    }
    DisposableEffect(id, filter) { onDispose { state.persist() } }
    LaunchedEffect(run?.run?.status, actionOperation.phase) {
        if (target != null && run != null && !conversationRunActive(run.run.status) && !actionOperation.approvalFeedback) {
            state.feedback[key] = "本轮已结束，保留草稿；选择群内沟通，不自动继续。"
            if (composer.draft.isBlank() && composer.attachments.isEmpty()) state.target(id, null)
        }
    }
    fun applyComposer(event: ConversationComposerEvent) {
        val (nextMenu, nextTaskMode) = conversationComposerState(menu, taskMode, event)
        menu = nextMenu; taskMode = nextTaskMode
    }
    fun closeLayer(restoreFocus: Boolean = true): Boolean {
        val trigger = when { menu -> menuTrigger; invite -> inviteTrigger; members -> memberTrigger; taskDetails -> taskTrigger; titleOpen -> titleTrigger; else -> return false }
        // 系统返回只结束临时面板/输入法和页面栈；普通消息卡的展开不吞返回。
        if (menu) applyComposer(ConversationComposerEvent.PanelClose) else if (invite) invite = false
        else if (members) members = false else if (taskDetails) taskDetails = false else titleOpen = false
        if (restoreFocus) runCatching { trigger.requestFocus() }
        return true
    }
    LaunchedEffect(state.layerRevision) { applyComposer(ConversationComposerEvent.LeftScreen); invite = false; members = false; taskDetails = false; titleOpen = false; headerMenu = false }
    ConversationLayerBackHandler(menu || invite || members || taskDetails || titleOpen) { closeLayer() }
    val pickers = rememberAttachmentPickerActions { uris ->
        val captured = composer; val capturedId = id; val cwd = if (taskMode) state.projects.firstOrNull { it.id == projectId }?.cwd else chatCwd.ifBlank { null }
        captured.upload { remaining ->
            val receipt = state.post(captured.sessionId, "/api/conversations/$capturedId/channel", JSONObject().apply { if (cwd != null) put("cwd", cwd) })
            val sessionId = receipt.sessionId ?: throw ConversationUnconfirmedException(receipt.requestId)
            uploadComposerAttachments(context, state.api, sessionId, uris, remaining)
        }
    }
    fun composeTask(task: ConversationTask? = null) {
        memberVersion = detail?.memberVersion ?: 1
        continueTask = task?.task?.id
        title = task?.task?.title ?: composer.draft.trim().lineSequence().firstOrNull().orEmpty().take(200)
        if (task != null) { projectId = task.task.workspaceId.orEmpty(); if (composer.draft.isBlank()) composer.editDraft(task.task.description.ifBlank { task.task.title }) }
        applyComposer(ConversationComposerEvent.DispatchStart)
    }
    fun action(name: String) {
        val capturedTarget = target ?: return
        val capturedKey = key
        if (unknown != null) return
        val capturedId = id
        actionOperation.submit(purpose = name, request = {
            state.api.conversationPost("/api/conversations/$capturedId/actions", JSONObject().put("target", capturedTarget.toJson()).put("action", name)).also {
                state.feedback[capturedKey] = it.error ?: "操作已接受"; state.load(capturedId)
            }
        })
    }
    fun stop() {
        applyComposer(ConversationComposerEvent.PanelClose)
        if (target != null) action("stop") else communication?.stopResponding()
    }
    fun replyTo(task: ConversationTask) {
        val replyTarget = conversationReplyTarget(task) ?: return
        applyComposer(ConversationComposerEvent.DispatchCancel)
        state.target(id, replyTarget)
        state.filter(id, task.task.id)
        taskDetails = false
    }
    fun send() {
        if (detail == null || detail.unavailableReason != null || unknown != null || !actionOperation.canSubmit) return
        if (taskMode && (projectId.isBlank() || detail.memberVersion != memberVersion)) { state.feedback[key] = "请选择工作项目并核对当前成员版本"; return }
        val capturedKey = key; val capturedId = id; val dispatch = taskMode
        val capturedPreference = executionChoice.forMessage(
            employee = employee,
            isDirectMessage = privateChat,
            hasRunTarget = target != null,
            isDispatch = dispatch,
            unknown = unknown != null,
            sendPhase = composer.sendPhase,
            uploading = composer.uploading,
        )
        val body = JSONObject().put("title", title).put("workspaceId", projectId)
        if (detail.kind == "group") body.put("memberVersion", memberVersion)
        if (continueTask != null) body.put("continueTaskId", continueTask)
        var accepted: ConversationReceipt? = null
        composer.submit(deliver = { text ->
            accepted = if (dispatch) state.post(capturedKey, conversationSendPath(capturedId, true), body.put("description", text))
            else state.post(capturedKey, conversationSendPath(capturedId, false), JSONObject().put("input", text).put("target", target?.toJson() ?: JSONObject.NULL).apply {
                if (chatCwd.isNotBlank()) put("cwd", chatCwd)
                capturedPreference?.let { agent ->
                    put("executionPreference", JSONObject().put("provider", agent.provider)
                        .put("model", agent.model).put("thinkingEffort", agent.thinkingEffort)
                        .apply { agent.engine?.let { put("engine", it) } })
                }
            })
            state.feedback[capturedKey] = accepted?.error ?: if (dispatch) "任务已接受" else "已发送"
            state.refresh(); state.load(capturedId)
        }, afterAccepted = {
            if (alive.value && state.selectedId == capturedId && currentId == capturedId && currentKey == capturedKey && (!dispatch || currentTaskMode)) {
                applyComposer(ConversationComposerEvent.DispatchSettled)
                accepted?.takeIf { detail.kind == "group" && dispatch && acceptedConversationTask(it, composer.draft.isNotEmpty() || composer.attachments.isNotEmpty()) }?.let {
                    val nextTarget = if (it.taskId != null && it.runId != null) ConversationTarget(it.taskId, it.runId) else null
                    state.select(it.conversationId)
                    state.target(it.conversationId, nextTarget)
                    state.feedback[state.draftKey(it.conversationId, nextTarget)] = it.error?.let { error -> "原群/原任务已接受，启动失败：$error" } ?: "任务已接受"
                    scope.launch { state.load(it.conversationId) }
                }
            }
        })
    }
    if (executionToolOpen && selectedExecutionAgent != null) {
        ComposerChoiceSheet(
            title = "本页执行工具",
            options = com.wand.app.data.AGENT_TOOL_OPTIONS.map { it.id to it.label },
            selected = selectedExecutionAgent.toolId,
            onSelect = { toolId ->
                val option = com.wand.app.data.agentToolOption(toolId)
                if (option != null) executionChoice.chooseTool(
                    toolId, employee,
                    enabled = unknown == null && composer.sendPhase != SendPhase.Sending && !composer.uploading,
                )
                executionToolOpen = false
            },
            onDismiss = { executionToolOpen = false },
        )
    }
    if (executionModelOpen && selectedExecutionAgent != null) {
        val agent = selectedExecutionAgent
        val options = com.wand.app.data.boardAgentModelOptions(executionModels, agent.provider)
            .map { it.id to it.label }.toMutableList()
        if (options.none { it.first == agent.model }) options.add(agent.model to agent.model)
        ComposerChoiceSheet(
            title = "本页模型", options = options, selected = agent.model, searchable = true,
            searchPlaceholder = "搜索模型",
            onSelect = { model ->
                val efforts = thinkingEffortOptions(agent.provider, model,
                    executionModels?.defaultModelFor(agent.provider),
                    executionModels?.modelsFor(agent.provider).orEmpty(),
                    executionModels?.thinkingEffortsFor(agent.provider).orEmpty())
                executionChoice.chooseModel(
                    model, efforts.map { it.id }.toSet(), employee,
                    enabled = unknown == null && composer.sendPhase != SendPhase.Sending && !composer.uploading,
                )
                executionModelOpen = false
            },
            onDismiss = { executionModelOpen = false },
        )
    }
    if (executionEffortOpen && selectedExecutionAgent != null) {
        val agent = selectedExecutionAgent
        val efforts = thinkingEffortOptions(agent.provider, agent.model,
            executionModels?.defaultModelFor(agent.provider),
            executionModels?.modelsFor(agent.provider).orEmpty(),
            executionModels?.thinkingEffortsFor(agent.provider).orEmpty())
        ComposerChoiceSheet(
            title = "本页思考深度", options = efforts.map { it.id to it.menuLabel }, selected = agent.thinkingEffort,
            onSelect = { effort ->
                executionChoice.chooseThinkingEffort(
                    effort, efforts.map { it.id }.toSet(), employee,
                    enabled = unknown == null && composer.sendPhase != SendPhase.Sending && !composer.uploading,
                )
                executionEffortOpen = false
            },
            onDismiss = { executionEffortOpen = false },
        )
    }
    if (defaultsOpen) WandDialog(title = "员工默认配置", onDismissRequest = { defaultsOpen = false },
        confirm = WandDialogAction("查看配置", { defaultsOpen = false; employee?.id?.let(onOpenEmployee) }),
        dismiss = WandDialogAction("取消", { defaultsOpen = false })) {
        val defaults = employeeComposerDefaults(employee?.agents.orEmpty())
        Text("首选模型：${defaults.model}\n权限：${defaults.permission}\n思考：${defaults.thinking}\n\n私聊每条消息开启独立工作。修改员工默认配置将影响此员工后续新工作，不修改历史会话或正在运行的任务；此处不会直接保存更改。", color = WandColors.textSecondary)
    }
    val receiver = if (taskMode) "${if (continueTask != null) "继续此任务" else "派新任务"} · 我 + ${detail?.team?.members?.joinToString { it.name } ?: employee?.name.orEmpty()}"
        else if (target != null && run != null) "${if (run.run.status == "awaiting_approval") "计划意见" else if (run.run.status == "waiting_user") "回复任务" else "补充任务"} · ${detail.tasks.firstOrNull { it.task.id == target.taskId }?.task?.title} · 第 ${run.run.roundNumber ?: 1} 轮"
        else if (detail?.kind == "group") "群内沟通 · 发给负责人 ${detail.leader?.name}，群内可见" else "每条消息开启独立会话"
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val safeHeight = maxHeight - with(density) { (WindowInsets.statusBars.getTop(this) + WindowInsets.navigationBars.getBottom(this)).toDp() }
    val panelHeight = minOf(480.dp, (safeHeight * .60f).coerceAtLeast(0.dp))
    val menuPanelHeight = minOf(360.dp, panelHeight)
    val minimumChrome = with(density) { MaterialTheme.typography.bodyLarge.lineHeight.toDp() * 3 + MaterialTheme.typography.labelSmall.lineHeight.toDp() } + 64.dp + 44.dp + 32.dp
    val shortPane = safeHeight < minimumChrome && !privateChat
    CompositionLocalProvider(LocalConversationPanelHeight provides panelHeight) {
    Column(Modifier.fillMaxSize().then(if (shortPane) Modifier.verticalScroll(rememberScrollState()) else Modifier)
        .background(if (privateChat) WandColors.bgPrimary else WandColors.surface).statusBarsPadding().then(layer.host((menu && !privateChat) || invite, if (menu) setOf("menu", "menu-trigger") else setOf("invite", "member-trigger", "invite-trigger")) {
        if (menu) applyComposer(ConversationComposerEvent.PanelClose) else invite = false
    }).onKeyEvent { event ->
        event.key == Key.Escape && event.type == KeyEventType.KeyUp && closeLayer()
    }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().heightIn(min = if (privateChat) topCompanionClearance() else 56.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            if (showBack) WandIconButton(WandIcons.back, "返回", onBack, modifier = Modifier.align(if (privateChat) Alignment.TopStart else Alignment.CenterStart), variant = WandIconButtonVariant.Chrome)
            if (privateChat) {
                val active = protocols.values.firstOrNull { it.isResponding }
                val avatarActivity = avatarFileActivity(active?.messages.orEmpty(), active != null && active.pendingEscalation == null && active.legacyPermissionPrompt == null)
                TopChatCompanion(employee?.id, employee?.name ?: displayTitle, employee?.avatar,
                    when {
                        active?.pendingEscalation != null || active?.legacyPermissionPrompt != null -> "等待确认"
                        active != null -> companionWorkStatus(true, avatarActivity, false)
                        else -> conversationIdentityStatus(detail) },
                    avatarActivity, companionThinking(active?.messages.orEmpty(), active != null, avatarActivity),
                    onEdit = inputPresentation::edit)
            }
            if (!privateChat) Box(Modifier.fillMaxWidth().padding(horizontal = 56.dp).heightIn(min = 48.dp).focusRequester(titleTrigger).clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = if (detail?.kind == "group") "查看群成员" else "查看员工资料") {
                if (employee != null) onOpenEmployee(employee.id) else { members = !members; taskDetails = false; invite = false }
            }, contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (employee != null) EmployeeAvatar(employee.id, employee.name, employee.avatar, size = 40.dp, floating = true)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(displayTitle ?: employee?.name ?: "消息", maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall, color = WandColors.textPrimary)
                    Text(conversationIdentityStatus(detail), style = MaterialTheme.typography.labelSmall,
                        color = WandColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                }
            }
            Box(Modifier.align(if (privateChat) Alignment.TopEnd else Alignment.CenterEnd)) {
                WandIconButton(WandIcons.more, "聊天菜单", { headerMenu = true }, variant = WandIconButtonVariant.Chrome,
                    modifier = Modifier.focusRequester(memberTrigger).then(layer.region("member-trigger")))
                DropdownMenu(headerMenu, onDismissRequest = { headerMenu = false }) {
                    if (detail?.kind == "group") DropdownMenuItem(text = { Text("群成员 · ${detail.team?.members.orEmpty().size + 1}") },
                        onClick = { headerMenu = false; members = !members; taskDetails = false; invite = false })
                    else employee?.let { person -> DropdownMenuItem(text = { Text("员工资料") }, onClick = { headerMenu = false; onOpenEmployee(person.id) }) }
                    DropdownMenuItem(text = { Text(conversationTaskSummary(detail)) }, leadingIcon = { Icon(WandIcons.todo, null) },
                        onClick = { headerMenu = false; taskDetails = !taskDetails; members = false; invite = false })
                    if (detail?.kind == "group") DropdownMenuItem(text = { Text("派新任务") }, enabled = detail.dissolvedAt == null && !detail.deleting,
                        onClick = { headerMenu = false; composeTask() })
                    detail?.communicationSessionId?.let { sid -> DropdownMenuItem(text = { Text("执行过程") }, onClick = { headerMenu = false; onOpenSession(sid) }) }
                    DropdownMenuItem(text = { Text("通讯录") }, onClick = { headerMenu = false; onContacts() })
                }
            }
        }
        WandInlinePanel(titleOpen, growFrom = Alignment.Top) { Text("${displayTitle ?: employee?.name.orEmpty()} · ${if (detail?.kind == "group") "${detail.team?.members.orEmpty().size + 1} 人 · ${detail.team?.members.orEmpty().size} 位员工" else "私聊"}", Modifier.padding(8.dp)) }
        WandInlinePanel(members, growFrom = Alignment.Top) { Column(Modifier.fillMaxWidth().heightIn(max = panelHeight).background(WandColors.surface).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("群成员 · ${detail?.team?.members.orEmpty().size + 1}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                WandButton(if (invite) "收起邀请" else "邀请成员", { invite = !invite }, variant = WandButtonVariant.Text,
                    enabled = detail?.dissolvedAt == null && detail?.deleting != true, modifier = Modifier.focusRequester(inviteTrigger).then(layer.region("invite-trigger")))
            }
            detail?.team?.members.orEmpty().forEach { m ->
                WandListItem(
                    headlineContent = { Text("${m.name}${if (m.isLeader) " · 负责人" else ""}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(m.duty, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        detail?.memberUnavailableReasons?.get(m.id)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = WandColors.danger) }
                    } },
                    leadingContent = { EmployeeAvatar(m.employeeId ?: m.id, m.name, m.avatar, size = 44.dp) },
                    trailingContent = if (m.employeeId != null) { { ToolbarIconButton(WandIcons.toolResult, "和${m.name}私聊", { members = false; state.select(employeeConversationId(m.employeeId)) }, modifier = Modifier.size(48.dp)) } } else null,
                    modifier = Modifier.heightIn(min = 76.dp).clickable(enabled = m.employeeId != null, role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "查看${m.name}的资料") { m.employeeId?.let(onOpenEmployee) },
                )
                ConversationInsetDivider()
            }
            WandInlinePanel(invite, growFrom = Alignment.Top, modifier = layer.region("invite")) { forms.SaveableStateProvider("invite:$id") {
                ConversationGroupEditor(state, inviteTo = detail, onClose = { invite = false }, onAccepted = { invite = false; scope.launch { state.load(id) } })
            } }
        } }
        if (filter.isNotBlank()) Row(Modifier.fillMaxWidth().background(WandColors.surfaceSoft).padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(detail?.tasks?.firstOrNull { it.task.id == filter }?.task?.title ?: "任务消息", Modifier.weight(1f), fontSize = 12.sp, color = WandColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ToolbarIconButton(WandIcons.close, "查看全部消息", { state.filter(id, "") }, iconSize = 16.dp)
        }
        WandInlinePanel(taskDetails, growFrom = Alignment.Top) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = panelHeight).background(WandColors.surface), contentPadding = PaddingValues(bottom = 8.dp)) {
                if (detail?.tasks.isNullOrEmpty()) item { Text("还没有任务，在聊天中沟通，或从 ＋ 派新任务。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
                items(detail?.tasks.orEmpty(), key = { it.task.id }) { t ->
                var executionDetails by rememberSaveable(id, t.task.id) { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = if (executionDetails) "收起任务详情" else "查看任务详情") { executionDetails = !executionDetails },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(t.task.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (t.task.status == "archived") ConversationArchivedLabel else t.runs.firstOrNull()?.let { conversationRunLabel(it.status) } ?: conversationStartupLabel(t), style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
                        }
                        Icon(WandIcons.expand, null, Modifier.size(20.dp).rotate(if (executionDetails) 180f else 0f), tint = WandColors.textMuted)
                    }
                    t.startup?.error?.let { Text("$it · 请核对原群/原任务，不要重新派发。", style = MaterialTheme.typography.bodySmall, color = WandColors.danger) }
                    WandInlinePanel(executionDetails, growFrom = Alignment.Top) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (t.task.description.isNotBlank()) Text(t.task.description, style = MaterialTheme.typography.bodyMedium, color = WandColors.textSecondary)
                            detail?.runDetails.orEmpty().filter { it.run.taskId == t.task.id }.forEach { d ->
                                Text("第 ${d.run.roundNumber ?: 1} 轮 · ${conversationRunLabel(d.run.status)}", style = MaterialTheme.typography.labelMedium, color = WandColors.textSecondary)
                                if (d.run.statusDetail.isNotBlank()) Text(d.run.statusDetail, style = MaterialTheme.typography.bodyMedium)
                                d.steps.forEach { step ->
                                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = step.sessionId != null, role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "查看${step.title}的执行过程") { step.sessionId?.let(onOpenSession) },
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text("${step.seq}", style = MaterialTheme.typography.labelMedium, color = WandColors.textMuted)
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(step.title, style = MaterialTheme.typography.bodyMedium)
                                            Text(conversationRunLabel(step.status), style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
                                        }
                                        if (step.sessionId != null) Icon(WandIcons.chevronRight, null, Modifier.size(20.dp), tint = WandColors.textMuted)
                                    }
                                }
                            }
                        }
                    }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WandButton("查看消息", { state.filter(id, t.task.id); taskDetails = false }, variant = WandButtonVariant.Text)
                        if (conversationReplyTarget(t) != null) WandButton("回复此任务", { replyTo(t) }, variant = WandButtonVariant.Secondary)
                        else if (t.task.status != "archived") WandButton("继续此任务", { taskDetails = false; composeTask(t) }, variant = WandButtonVariant.Secondary)
                    }
                } }
            }
        }
        WandInPlaceSwap(contentKey = "$id\u0001$filter", modifier = (if (shortPane) Modifier.height(120.dp) else Modifier.weight(1f)).widthIn(max = 760.dp).fillMaxWidth(), durationMillis = WandMotion.normal, enterScale = 1f, exitScale = 1f) { displayed ->
            val parts = (displayed as? String).orEmpty().split('\u0001', limit = 2)
            val displayedId = parts.firstOrNull().orEmpty()
            val displayedFilter = parts.getOrNull(1).orEmpty()
            val displayedDetail = state.details[displayedId]
            val turns = displayedDetail?.messages.orEmpty().filter { displayedFilter.isBlank() || it.conversationTarget?.taskId == displayedFilter || it.conversationLink?.taskId == displayedFilter }
            val messageList = state.listState("messages:$displayedId:$displayedFilter")
            val messageKeys = turns.map(::conversationMessageKey)
            val resultsBySession = displayedDetail?.let(::conversationSessionToolResults).orEmpty()
            var previousKeys by remember(displayedId, displayedFilter) { mutableStateOf<List<String>?>(null) }
            val arrivals = remember(displayedId, displayedFilter) { mutableStateMapOf<String, Boolean>() }
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            var foreground by remember(lifecycleOwner) { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
            DisposableEffect(lifecycleOwner, displayedId, displayedFilter) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    foreground = lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) arrivals.clear()
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer); arrivals.clear() }
            }
            val reducedMotion = com.wand.app.ui.theme.reduceMotionEnabled()
            LaunchedEffect(reducedMotion) { if (reducedMotion) arrivals.clear() }
            var unseen by remember(displayedId, displayedFilter) { mutableIntStateOf(0) }
            var following by remember(displayedId, displayedFilter) { mutableStateOf(true) }
            suspend fun scrollToConversationTail() {
                val last = messageList.layoutInfo.totalItemsCount - 1
                if (last < 0) return
                messageList.scrollToItem(last)
                // A long final reply is taller than the viewport: align its end, not its start.
                withFrameNanos { }
                val layout = messageList.layoutInfo
                val tail = layout.visibleItemsInfo.lastOrNull { it.index == last } ?: return
                val overflow = (tail.offset + tail.size - layout.viewportEndOffset + layout.afterContentPadding).coerceAtLeast(0)
                if (overflow > 0) messageList.scrollBy(overflow.toFloat())
            }
            val draggingMessages by messageList.interactionSource.collectIsDraggedAsState()
            LaunchedEffect(messageList) {
                var userScroll = false
                snapshotFlow { Triple(messageList.isScrollInProgress, draggingMessages, messageList.canScrollForward) }.collect { (scrolling, dragging, forward) ->
                    if (dragging) userScroll = true
                    // Layout/programmatic changes are not evidence that the reader
                    // chose to rejoin the tail. Only an actual drag (and its fling) is.
                    if (userScroll) { following = !forward; if (!forward) unseen = 0 }
                    if (!scrolling && !dragging) userScroll = false
                }
            }
            // IME/composer height changes do not change message keys. Keep the tail
            // visible only when following; an older-message reader retains their anchor.
            LaunchedEffect(messageList, displayedId, displayedFilter) {
                snapshotFlow { messageList.layoutInfo.let { it.viewportEndOffset - it.viewportStartOffset } }.collect { height ->
                    if (height > 0 && following && !messageList.isScrollInProgress) scrollToConversationTail()
                }
            }
            LaunchedEffect(messageList, turns.lastOrNull()?.sessionPreview?.text, turns.lastOrNull()?.taskPreview?.text) {
                if (following && !messageList.isScrollInProgress) scrollToConversationTail()
            }
            LaunchedEffect(messageKeys, displayedDetail != null) {
                if (displayedDetail == null) return@LaunchedEffect
                val initial = previousKeys == null
                val added = appendedConversationKeys(previousKeys, messageKeys)
                previousKeys = messageKeys
                arrivals.keys.retainAll(messageKeys.toSet())
                val own = turns.any { it.role == "user" && conversationMessageKey(it) in added }
                if (initial && messageKeys.isNotEmpty() && messageList.firstVisibleItemIndex == 0 && messageList.firstVisibleItemScrollOffset == 0) {
                    scrollToConversationTail()
                } else if (added.isNotEmpty()) {
                    if (following || own) {
                        scrollToConversationTail()
                        withFrameNanos { }
                        val visibleKeys = messageList.layoutInfo.visibleItemsInfo.map { it.key }.toSet()
                        arrivals.clear()
                        if (foreground && !reducedMotion) added.filter { it in visibleKeys }.forEach { arrivals[it] = true }
                        unseen = 0
                    } else unseen += added.size
                }
            }
            Box(Modifier.fillMaxSize().clipToBounds()) {
            LazyColumn(state = messageList, modifier = Modifier.fillMaxSize().pointerInput(inputPresentation) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); inputPresentation.dismiss() } }, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
                if (state.error != null) item { ErrorState(state.error.orEmpty(), onRetry = state::retry) }
                if (turns.isEmpty() && state.error == null) item {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (displayedId.isNotBlank() && displayedDetail == null) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            Text("正在加载对话…", style = MaterialTheme.typography.bodyLarge)
                        } else {
                            Icon(WandIcons.toolResult, null, Modifier.size(32.dp), tint = WandColors.textMuted)
                            Text(if (displayedDetail?.dissolvedAt != null) "群聊已解散" else if (displayedId.isBlank()) "选择员工，开始聊天" else if (displayedFilter.isNotBlank()) "此任务暂无消息" else if (displayedDetail?.kind == "group") "群已建立" else "还没有消息", style = MaterialTheme.typography.bodyLarge)
                            Text(if (displayedDetail?.dissolvedAt != null) "恢复后可继续聊天。" else if (displayedId.isBlank()) "可先写草稿，从通讯录选择接收对象后发送。" else if (displayedDetail?.kind == "group") "可以先沟通，也可以派任务。" else "直接说出你想做的事；执行过程和需要确认的内容会显示在这里。", style = MaterialTheme.typography.bodySmall, color = WandColors.textMuted)
                        }
                    }
                }
                itemsIndexed(turns, key = { _, turn -> conversationMessageKey(turn) }) { index, turn ->
                    val joined = joinsConversationBubble(turns.getOrNull(index - 1), turn)
                    val tail = !joinsConversationBubble(turn, turns.getOrNull(index + 1))
                    val day = conversationDay(turn.createdAt)
                    if (day.isNotBlank() && day != conversationDay(turns.getOrNull(index - 1)?.createdAt)) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            Text(day, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal, color = WandColors.textMuted)
                        }
                    }
                    Spacer(Modifier.height(if (joined) 3.dp else 8.dp))
                    turn.conversationTarget?.let { t -> Text(displayedDetail?.tasks?.firstOrNull { it.task.id == t.taskId }?.task?.title ?: t.taskId, style = MaterialTheme.typography.labelSmall) }
                    val sourceSession = conversationTurnSessionId(turn, displayedDetail)
                    val protocol = protocols[sourceSession]
                    val toolResults = resultsBySession[sourceSession].orEmpty() + conversationToolResults(protocol?.messages.orEmpty())
                    CompositionLocalProvider(com.wand.app.ui.LocalServerBaseUrl provides state.api.baseUrl,
                        com.wand.app.ui.LocalImageGallery provides { com.wand.app.ui.conversationImagePages(turns) },
                        LocalChatApi provides state.api, LocalChatSessionId provides sourceSession.orEmpty(),
                        LocalChatWorkingDirectory provides protocol?.snapshot?.cwd) {
                        val messageKey = "$displayedId:${conversationMessageKey(turn)}"
                        TeamTurnArrival(playing = arrivals[conversationMessageKey(turn)] == true, own = turn.role == "user",
                            onConsumed = { arrivals.remove(conversationMessageKey(turn)) }) {
                        val authorEmployeeId = (displayedDetail?.team?.members.orEmpty() + displayedDetail?.runDetails.orEmpty().flatMap { it.run.team?.members.orEmpty() }).firstOrNull { it.id == turn.author?.id }?.employeeId
                            ?: state.employees.firstOrNull { it.id == turn.author?.id }?.id
                        val privatePeer = if (turn.author == null && turn.conversationTarget == null && displayedDetail?.kind != "group") state.employees.firstOrNull { it.id == displayedDetail?.peerEmployeeId } else null
                        if (turn.sessionLink != null) ConversationSessionReplyCard(turn, protocol) { onOpenSession(turn.sessionLink.sessionId) }
                        else if (turn.conversationLink != null) ConversationTaskPreviewCard(turn) { state.openTask(turn.conversationLink, turn.taskPreview) }
                        else ConversationInstanceTurn(turn, state.api.baseUrl, onOpenSession, protocol = protocol, toolResults = toolResults, onAvatarClick = authorEmployeeId?.let { { onOpenEmployee(it) } }, group = displayedDetail?.kind == "group", joined = joined, tail = tail,
                            fallbackAuthor = privatePeer?.let { TurnAuthor(id = it.id, name = it.name, avatar = it.avatar) },
                            mentionNames = (displayedDetail?.team?.members.orEmpty() + displayedDetail?.runDetails.orEmpty().flatMap { it.run.team?.members.orEmpty() }).map { it.name }.distinct(),
                            expanded = messageKey in state.expandedMessages, onExpandedChange = { expanded ->
                                if (expanded) state.expandedMessages.add(messageKey) else state.expandedMessages.remove(messageKey)
                            }, expandRequester = messageTriggers.getOrPut(messageKey) { androidx.compose.ui.focus.FocusRequester() })
                        }
                    }
                }
            }
            if (unseen > 0) WandButton("↓ $unseen 条新消息", {
                scope.launch { scrollToConversationTail(); following = true; unseen = 0 }
            }, modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp), variant = WandButtonVariant.Secondary)
            }
        }
        if (detail?.dissolvedAt != null) Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${conversationDay(detail.dissolvedAt)} ${conversationListClock(detail.dissolvedAt)} · ${detail.dissolvedBy ?: "我"}解散了群聊", style = MaterialTheme.typography.bodySmall, color = WandColors.textSecondary)
            var restoring by remember(id) { mutableStateOf(false) }
            var restoreError by remember(id) { mutableStateOf<String?>(null) }
            TextButton(enabled = !restoring, onClick = { restoring = true; restoreError = null; scope.launch {
                try { state.updateListState(id, JSONObject().put("dissolved", false)); state.load(id) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { restoreError = failure.message ?: "恢复失败，请重试" }
                finally { restoring = false }
            } }) { Text(if (restoring) "正在恢复…" else "恢复群聊", textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline) }
            restoreError?.let { Text(it, color = WandColors.danger) }
        } else Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = if (privateChat) 0.dp else 16.dp, vertical = if (privateChat) 0.dp else 4.dp)) {
            val feedback = actionOperation.feedback.ifBlank { state.feedback[key] ?: detail?.unavailableReason.orEmpty() }.takeUnless { it == "已发送" }.orEmpty()
            if (feedback.isNotBlank()) Text(feedback, Modifier.fillMaxWidth().heightIn(max = 96.dp).verticalScroll(rememberScrollState())
                .background(WandColors.surface).padding(horizontal = 14.dp, vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall, color = WandColors.textMuted)
            if (actionOperation.unknown != null) WandButton("核对操作", { actionOperation.reconcile(state.api::conversationReceipt) }, variant = WandButtonVariant.Text)
            val permissions = protocols.values.filter { it.pendingEscalation != null || it.legacyPermissionPrompt != null }
            if (permissions.isNotEmpty()) Column(Modifier.fillMaxWidth().heightIn(max = panelHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                permissions.forEach { protocol -> androidx.compose.runtime.key(protocol.sessionId) {
                    val escalation = protocol.pendingEscalation
                    val legacy = protocol.legacyPermissionPrompt
                    Text("${protocol.snapshot?.employeeName ?: displayTitle.orEmpty()} · 等待确认", style = MaterialTheme.typography.labelMedium)
                    PermissionCard(escalation, legacy, { resolution -> protocol.resolvePermission(resolution, escalation?.requestId) })
                } }
            }
            if (taskMode || target != null || id.isBlank()) Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(receiver, Modifier.weight(1f).heightIn(min = 20.dp).padding(horizontal = 6.dp), style = MaterialTheme.typography.labelSmall, color = WandColors.textSecondary)
                if (target != null && !taskMode) ToolbarIconButton(WandIcons.close, if (detail?.kind == "group") "回到群聊" else "回到私聊", {
                    state.target(id, null)
                }, iconSize = 16.dp)
            }
            if (unknown != null) WandButton("核对请求", { scope.launch { runCatching { state.reconcile(key) }.onFailure { state.feedback[key] = it.message.orEmpty() } } }, variant = WandButtonVariant.Text)
            if (id.isNotBlank() && adoption != null) WandButton("采用尚未选择的草稿", {
                if (state.selectedId == id && currentId == id && currentKey == key) {
                    state.feedback[key] = if (composer.adoptDraftFrom(unaddressedComposer, adoption)) "已采用待接收的正文与附件" else "草稿状态已变化，请重新核对；未覆盖当前输入"
                }
            }, variant = WandButtonVariant.Text)
            if (run?.run?.status == "awaiting_approval" || actionOperation.approvalFeedback) ConversationApprovalButton(
                phase = if (actionOperation.purpose == "approve") actionOperation.phase else "idle",
                enabled = actionOperation.canSubmit && composer.sendPhase != SendPhase.Sending && unknown == null,
                onClick = { action("approve") },
            )
            if (run?.run?.status == "waiting_user" && run.run.statusDetail.contains("步数上限")) WandButton("明确增加本轮步数", { action("continue") }, enabled = actionOperation.canSubmit)
            val canSubmit = composer.canSubmit && id.isNotBlank() && detail != null && detail.unavailableReason == null && unknown == null && actionOperation.canSubmit
            val actions = composerActionPresentation(composer.sendPhase, running && !taskMode,
                composer.draft.isNotBlank() || composer.attachments.isNotEmpty(), canSubmit,
                feedback = actionPhase ?: if (unknown != null) SendActionVisual.Failed else null)
            SharedMessageComposer(backdrop = null, sessionKey = composer.sessionId, draft = composer.draft, onDraftChange = composer::editDraft,
                attachments = composer.attachments, baseUrl = state.api.baseUrl, onRemoveAttachment = composer::removeAttachment,
                uploading = composer.uploading, attachOpen = menu, onAttachOpenChange = { open ->
                    applyComposer(if (open) ConversationComposerEvent.PanelOpen else ConversationComposerEvent.PanelClose)
                    if (open) { members = false; invite = false; taskDetails = false; titleOpen = false }
                },
                onMenuDismissFocus = { runCatching { menuTrigger.requestFocus() } },
                onPickPhoto = pickers.pickPhoto, onPickFile = pickers.pickFile, canSubmit = canSubmit, onSend = ::send,
                allowRefocus = true, onExpandedChange = {},
                editorOverlayOpen = pickers.open || defaultsOpen || headerMenu || taskDetails || members || invite,
                presentation = inputPresentation,
                actionCount = actions.slotCount,
                exteriorAction = { send -> ComposerSendStopActions(
                    presentation = actions,
                    stopDescription = if (target != null) "停止选中的本轮" else "停止当前回复",
                    sendDescription = if (taskMode) "确认派发任务" else if (running) "继续发送" else "发送消息",
                    onSend = send, onStop = ::stop,
                    busy = !actionOperation.canSubmit,
                    canSubmit = canSubmit, failureDescription = if (unknown != null) "送达未确认，请先核对" else "操作失败")
                },
                deliveryPhase = if (unknown != null) SendPhase.Failed else composer.sendPhase,
                inlineControls = true,
                menuActionModifier = Modifier.focusRequester(menuTrigger).then(layer.region("menu-trigger")),
                menuPanelModifier = layer.region("menu").heightIn(max = menuPanelHeight).verticalScroll(rememberScrollState()),
                menuContent = {
                    if (!taskMode) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (detail?.kind == "group") WandButton("派新任务", { composeTask() }, enabled = id.isNotBlank(), variant = WandButtonVariant.Text)
                            if (target == null) detail?.leader?.let { leader -> WandButton("@${leader.name}", {
                                composer.editDraft(mentionConversationLeader(composer.draft, leader.name)); applyComposer(ConversationComposerEvent.PanelClose)
                            }, variant = WandButtonVariant.Text) }
                        }
                        WandTextField(chatCwd, { chatCwd = it }, label = "工作目录", singleLine = true)
                        if (running) WandButton("停止本轮", ::stop, variant = WandButtonVariant.DangerText)
                        if (detail?.kind == "dm") employee?.let { peer -> WandButton("员工执行配置", { applyComposer(ConversationComposerEvent.PanelClose); onOpenEmployee(peer.id) }, variant = WandButtonVariant.Text) }
                        detail?.communicationSessionId?.let { sid -> WandButton(if (detail.kind == "dm") "历史私聊执行窗口" else "工具与资源", { applyComposer(ConversationComposerEvent.PanelClose); onOpenSession(sid) }, variant = WandButtonVariant.Text) }
                    } else Column {
                        Text(if (continueTask != null) "继续任务" else "派新任务", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        WandTextField(title, { title = it }, label = "任务名称", singleLine = true, enabled = continueTask == null)
                        Text("工作项目", fontSize = 14.sp)
                        state.projects.forEach { project -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(projectId == project.id, { projectId = project.id }); Text(project.name) } }
                        Text("${detail?.team?.members?.joinToString { it.name } ?: employee?.name.orEmpty()} · ${if (detail?.team?.requirePlanApproval == false) "自动开工" else "需批准计划"}", fontSize = 12.sp, color = WandColors.textSecondary)
                        WandButton("取消派发", {
                            if (unknown == null && composer.discardUnsentDraft()) {
                                applyComposer(ConversationComposerEvent.DispatchCancel)
                                title = ""; continueTask = null
                            }
                        }, enabled = composer.sendPhase != SendPhase.Sending && unknown == null, variant = WandButtonVariant.Text)
                    }
                }, controls = {
                    if (privateChat && target == null && !taskMode && defaultExecutionAgent != null) {
                        if (executionModelsError != null) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(executionModelsError.orEmpty(), Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall, color = WandColors.danger,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                TextButton(enabled = !executionModelsLoading, onClick = { scope.launch { loadExecutionModels() } }) {
                                    Text(if (executionModelsLoading) "重试中…" else "重试")
                                }
                            }
                        }
                        val agent = selectedExecutionAgent ?: defaultExecutionAgent
                        val models = com.wand.app.data.boardAgentModelOptions(executionModels, agent.provider)
                        val modelLabel = models.firstOrNull { it.id == agent.model }?.label
                            ?: agent.model.takeUnless { it == "default" } ?: "默认模型"
                        val efforts = thinkingEffortOptions(
                            agent.provider, agent.model, executionModels?.defaultModelFor(agent.provider),
                            executionModels?.modelsFor(agent.provider).orEmpty(),
                            executionModels?.thinkingEffortsFor(agent.provider).orEmpty(),
                        )
                        val effortLabel = efforts.firstOrNull { it.id == agent.thinkingEffort }?.label ?: "自动"
                        ComposerSettingsRow(
                            modelLabel,
                            com.wand.app.data.agentToolOption(agent.toolId)?.label ?: agent.provider,
                            effortLabel,
                            enabled = unknown == null && composer.sendPhase != SendPhase.Sending && !composer.uploading,
                            modeLabel = "工具",
                            onModel = { executionModelOpen = true },
                            onMode = { executionToolOpen = true },
                            onThinking = { executionEffortOpen = true },
                        )
                        if (executionChoice.preference != null) {
                            WandButton("恢复员工默认", { executionChoice.reset() },
                                enabled = unknown == null && composer.sendPhase != SendPhase.Sending && !composer.uploading,
                                variant = WandButtonVariant.Text)
                        }
                    }
                }, trailingActions = { _, _ -> })
        }
    }
    }
    }
}
