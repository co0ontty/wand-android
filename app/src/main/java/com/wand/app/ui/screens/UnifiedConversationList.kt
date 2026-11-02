package com.wand.app.ui.screens

import kotlinx.coroutines.launch
import com.wand.app.ui.withLiveTitle

import androidx.compose.animation.core.*
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import com.wand.app.ui.theme.rememberGlassBackdrop
import com.wand.app.ui.theme.glassBackdropSource
import com.wand.app.ui.theme.WandColors
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wand.app.data.*
import com.wand.app.ui.ConversationStore
import com.wand.app.ui.components.*

/** 统一按实际会话展示；员工与工作区只作为元数据，不生成空人物会话。 */
@Composable
internal fun UnifiedConversationList(
    state: ConversationStore, bottomClearance: Dp = 68.dp,
    interactionEnabled: Boolean,
    onMoveSession: (WorkspaceSessionSummary) -> Unit,
    onDeleteSession: (WorkspaceSessionSummary) -> Unit,
    onOpenSettings: () -> Unit, onSwitchServer: () -> Unit,
    groups: List<TaskDirectoryGroup>, groupsLoading: Boolean, groupsError: String?,
    onOpenSession: (TaskSessionRoute) -> Unit, onCreate: (String?) -> Unit, onReload: suspend () -> Unit, showsFilters: Boolean, onMode: (HomeListMode) -> Unit, onSelect: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val filters = state.listFilters
    val listState = state.listState("unified-conversations")
    val swipeOwner = LocalHomeSwipeOwner.current
    LaunchedEffect(query, filters, listState.isScrollInProgress, showsFilters, menu) {
        swipeOwner?.value = null
    }
    DisposableEffect(Unit) { onDispose { swipeOwner?.value = null } }
    val workspaces = conversationWorkspaceOptions(groups)
    val entries = conversationFilterSessions(groups).distinctBy { it.session.id }
    val employees = conversationEmployeeOptions(entries)
    val rows = entries.filter { item ->
        conversationSessionMatches(item, filters, !groupsLoading && groupsError == null) &&
            listOfNotNull(item.session.title, item.session.toolLabel, item.session.employeeName, conversationWorkspaceLabel(item.group)).any { it.contains(query.trim(), ignoreCase = true) }
    }
    val history = emptyList<ConversationInstance>()
    val attention = rows.filter { conversationRuntime(it) in setOf(ConversationRuntime.Failed, ConversationRuntime.Confirmation) }
    val remaining = rows.filterNot { it in attention }
    val backdrop = rememberGlassBackdrop()
        Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().glassBackdropSource(backdrop)) {
        WandTextField(query, { query = it }, label = "搜索会话", singleLine = true,
            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("清除") } }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        // 手机端没有独立的导航栏，这一行就是唯一的导航入口，故新建与功能菜单保留在此。
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onCreate(filters.workspaceIds.singleOrNull()?.takeUnless { it == UnassignedConversationWorkspace }) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(WandIcons.add, null); Text("新建会话")
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(WandIcons.more, "更多") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("知识库") }, onClick = { menu = false; onMode(HomeListMode.Knowledge) })
                    DropdownMenuItem(text = { Text("任务") }, onClick = { menu = false; onMode(HomeListMode.Tasks) })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("设置") }, onClick = { menu = false; onOpenSettings() })
                    DropdownMenuItem(text = { Text("切换服务器") }, onClick = { menu = false; onSwitchServer() })
                }
            }
        }
        if (filters.count > 0) Row(Modifier.padding(horizontal = 12.dp)) {
            Text(conversationFilterSummary(filters, workspaces, employees), Modifier.weight(1f), maxLines = 2, style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { state.listFilters = ConversationListFilters() }) { Text("清除筛选") }
        }
        Box(Modifier.fillMaxWidth().height(4.dp)) {
            if (groupsLoading || state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
            (groupsError ?: state.error)?.let { error -> item { Text(error, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error); TextButton(onClick = { scope.launch { onReload() } }) { Text("重新读取") } } }
            fun androidx.compose.foundation.lazy.LazyListScope.sessions(items: List<HomeRecentConversation>) {
                items(items, key = { it.session.id }) { item ->
                    val open = {
                        swipeOwner?.value = null
                        onOpenSession(taskSessionRoute(item.session, item.group, item.task))
                    }
                    HomeSwipeRow(id = "session:" + item.session.id, enabled = interactionEnabled, actions = listOf(
                        HomeRowAction("open", "打开", WandIcons.chat, WandColors.brand, open),
                        HomeRowAction("move", "移动", WandIcons.folder, WandColors.info) { onMoveSession(item.session) },
                        HomeRowAction("delete", "删除", WandIcons.delete, WandColors.danger) { onDeleteSession(item.session) },
                    )) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .clickable(enabled = interactionEnabled, onClick = open)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.session.title ?: item.task?.name ?: "会话", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            val subhead = listOfNotNull(item.session.toolLabel, item.session.employeeName, conversationWorkspaceLabel(item.group)).joinToString(" · ")
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(BrandLogos.painterForProvider(item.session.provider), contentDescription = null,
                                    tint = BrandLogos.tintForProvider(item.session.provider, MaterialTheme.colorScheme.onSurface),
                                    modifier = Modifier.size(14.dp))
                                Text(subhead, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        SessionStatusGlyph(item.session, modifier = Modifier.padding(start = 12.dp))
                    }
                    }
                }
            }
            if (attention.isNotEmpty()) item { Text("我需要关注", Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall) }
            sessions(attention)
            if (attention.isNotEmpty() && remaining.isNotEmpty()) item { Text("其他会话", Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall) }
            sessions(remaining)
            items(history, key = { "history:" + it.id }) { item -> TextButton(onClick = { onSelect(item.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(item.title, maxLines = 2) } }
            if (rows.isEmpty() && history.isEmpty() && !groupsLoading && !state.loading) item {
                Text(if (filters.count == 0 && query.isEmpty()) "还没有会话" else "没有匹配的会话", Modifier.padding(16.dp))
                if (filters.count > 0 || query.isNotEmpty()) TextButton(onClick = { state.listFilters = ConversationListFilters(); query = "" }) { Text("清除筛选与搜索") }
            }
        }
        if (showsFilters) Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
            ConversationCapsules(filters, workspaces, employees, backdrop = backdrop, onChange = { state.listFilters = it })
        }
    }
    }

}

/** 与 Web 端 TaskStatusGlyph 保持一致的运行状态动效图标 */
@Composable
private fun SessionStatusGlyph(session: WorkspaceSessionSummary, modifier: Modifier = Modifier) {
    if (session.archived == true) return
    val live = session.withLiveTitle()
    val status = live.activityStatus().replace('_', '-')
    val isBlocked = com.wand.app.ui.SessionTitleStore.permissionBlockedOf(session.id) == true

    when {
        isBlocked || status in setOf("permission", "permission-blocked", "waiting-input", "reconnecting") -> {
            val infiniteTransition = rememberInfiniteTransition(label = "attention-breath")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.45f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(850, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "attention-alpha"
            )
            Icon(
                WandIcons.permission,
                contentDescription = "待处理",
                tint = WandColors.warning.copy(alpha = alpha),
                modifier = modifier.size(16.dp)
            )
        }
        status in setOf("running", "thinking") -> {
            val infiniteTransition = rememberInfiniteTransition(label = "spin")
            val angle by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing)
                ),
                label = "spin-angle"
            )
            val tint = if (status == "thinking") WandColors.info else WandColors.success
            Icon(
                WandIcons.refresh,
                contentDescription = if (status == "thinking") "思考中" else "运行中",
                tint = tint,
                modifier = modifier.size(16.dp).rotate(angle)
            )
        }
        status == "queued" -> {
            Icon(
                WandIcons.history,
                contentDescription = "排队中",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier.size(16.dp)
            )
        }
        status == "just-completed" -> {
            Icon(
                WandIcons.check,
                contentDescription = "刚完成",
                tint = WandColors.success,
                modifier = modifier.size(16.dp)
            )
        }
        status == "failed" -> {
            Icon(
                WandIcons.error,
                contentDescription = "失败",
                tint = WandColors.danger,
                modifier = modifier.size(16.dp)
            )
        }
    }
}

/** Same activity vocabulary as the Web sidebar; process liveness is not turn activity. */
@Composable
private fun SessionListStatus(session: WorkspaceSessionSummary) {
    val live = session.withLiveTitle()
    val status = live.activityStatus().replace('_', '-')
    val (label, icon, color) = when {
        session.archived == true -> Triple("已归档", WandIcons.history, MaterialTheme.colorScheme.onSurfaceVariant)
        com.wand.app.ui.SessionTitleStore.permissionBlockedOf(session.id) == true || status in setOf("permission", "permission-blocked") -> Triple("等待授权", WandIcons.permission, WandColors.warning)
        status == "waiting-input" -> Triple("等待回答", WandIcons.question, WandColors.warning)
        status == "reconnecting" -> Triple("重连中", WandIcons.refresh, WandColors.warning)
        status == "failed" -> Triple("失败", WandIcons.error, WandColors.danger)
        status == "thinking" -> Triple("思考中", WandIcons.thinking, WandColors.info)
        status == "running" -> Triple("运行中", WandIcons.refresh, WandColors.success)
        status == "queued" -> Triple("排队中", WandIcons.history, MaterialTheme.colorScheme.onSurfaceVariant)
        status == "just-completed" -> Triple("刚完成 · 未查看", WandIcons.check, WandColors.success)
        status == "exited" -> Triple("已结束", WandIcons.stop, MaterialTheme.colorScheme.onSurfaceVariant)
        status == "stopped" -> Triple("已停止", WandIcons.stop, MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Triple("空闲", WandIcons.idle, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
