package com.wand.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.wand.app.SessionWatcher
import com.wand.app.data.AiTeam
import com.wand.app.data.SiliconEmployee
import com.wand.app.data.WandApi
import com.wand.app.data.Workspace
import com.wand.app.ui.components.EmployeeAvatar
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandDetailBackButton
import com.wand.app.ui.components.WandDetailTopBar
import com.wand.app.ui.components.WandIconButton
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandInlinePanel
import com.wand.app.ui.components.WandInlinePanelAction
import com.wand.app.ui.components.WandInlineSearchField
import com.wand.app.ui.components.WandInPlaceSwap
import com.wand.app.ui.components.WandMorphIconButton
import com.wand.app.ui.components.WandStatusIconSlot
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.reduceMotionEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 通讯录：直接列出员工和团队名称。
 *
 * - 点头像管理该员工资料；点名字/整行则与首页最近会话右侧「＋」一样开新对话；
 * - 点团队名称一键开新群聊，点团队头像管理资料；
 * - 顶栏 ＋ 只负责创建员工 / 新建团队，从原位展开，不把落地页做成管理界面。
 */
@Composable
fun ContactsScreen(
    api: WandApi,
    onBack: () -> Unit,
    onOpenEmployee: (String) -> Unit,
    onOpenTeam: (String) -> Unit,
    onCreateEmployee: () -> Unit,
    onOpenSession: (TaskSessionRoute) -> Unit,
    onOpenGroupChat: (String) -> Unit,
    onCreateTeam: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var employees by remember { mutableStateOf<List<SiliconEmployee>>(emptyList()) }
    var teams by remember { mutableStateOf<List<AiTeam>>(emptyList()) }
    var workspaces by remember { mutableStateOf<List<Workspace>>(emptyList()) }
    var defaultCwd by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshNonce by remember { mutableIntStateOf(0) }
    var templatesOpen by remember { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val employeeConversations = remember { mutableStateMapOf<String, RecentEmployeeConversation>() }
    val teamConversations = remember { mutableStateMapOf<String, RecentTeamConversation>() }
    val rowErrors = remember { mutableStateMapOf<String, String>() }
    var openingId by remember { mutableLongStateOf(0L) }
    val visibleEmployees = contactDirectoryEmployees(employees, query)
    val visibleTeams = contactDirectoryTeams(teams, query)
    val motionEnabled = !reduceMotionEnabled()
    val listState = rememberLazyListState()
    val searchFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val binding = contactConversationBinding(defaultCwd, workspaces)
    val teamWorkspaceId = contactTeamStartWorkspaceId(workspaces)
    val anyBusy = employeeConversations.values.any { it.busy } || teamConversations.values.any { it.busy }

    fun conversationBusy(): Boolean = employeeConversations.values.any { it.busy } ||
        teamConversations.values.any { it.busy }

    fun closeCreatePanel() {
        templatesOpen = false
    }

    fun closeSearch() {
        focusManager.clearFocus()
        keyboard?.hide()
        query = ""
        searchOpen = false
    }

    fun clearSearch() {
        query = ""
        searchFocus.requestFocus()
        keyboard?.show()
    }

    BackHandler(enabled = templatesOpen || searchOpen) {
        if (templatesOpen) closeCreatePanel() else closeSearch()
    }
    BackHandler(enabled = anyBusy) { /* 等待新对话回执，防止离开后重复创建。 */ }

    LaunchedEffect(templatesOpen, searchOpen, query) {
        if (templatesOpen || searchOpen) listState.scrollToItem(0)
    }

    LaunchedEffect(api, lifecycleOwner, refreshNonce) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            suspend fun refresh() {
                loading = true
                try {
                    coroutineScope {
                        // 各部分独立刷新；一个请求失败不会清空已经显示的资料。
                        suspend fun <T> fetch(block: suspend () -> T): Result<T> = try {
                            Result.success(block())
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            Result.failure(failure)
                        }
                        val people = async { fetch { api.listSiliconEmployees() } }
                        val groups = async { fetch { api.listAiTeams() } }
                        val projects = async { fetch { api.listWorkspaces() } }
                        val directory = async { fetch { api.taskDefaultCwd() } }
                        val failures = mutableListOf<String>()
                        people.await().onSuccess { employees = it }.onFailure { failures += "员工" }
                        groups.await().onSuccess { teams = it }.onFailure { failures += "团队" }
                        projects.await().onSuccess { workspaces = it }.onFailure { failures += "项目" }
                        directory.await().onSuccess { defaultCwd = it }.onFailure { failures += "默认目录" }
                        error = failures.takeIf { it.isNotEmpty() }
                            ?.joinToString("、")?.let { "${it}未能刷新，已有内容已保留。请重试。" }
                    }
                } finally {
                    loading = false
                }
            }
            refresh()
            SessionWatcher.employeeDefinitionChanges.collect { refresh() }
        }
    }

    fun openEmployeeConversation(employee: SiliconEmployee) {
        if (conversationBusy()) return
        closeCreatePanel()
        rowErrors.remove(employee.id)
        val assignable = contactAssignableEmployee(employee.id, employees)
        val target = binding
        if (assignable == null) {
            rowErrors[employee.id] = "员工已不存在或不可用，请刷新后重试。"
            return
        }
        if (target == null) {
            rowErrors[employee.id] = "还没有可用的工作目录，请先选择或创建一个项目。"
            return
        }
        val request = employeeConversations[employee.id]
            ?.takeIf { it.snapshot == null && it.binding == target }
            ?: RecentEmployeeConversation(employee.id, target).also {
                employeeConversations[employee.id] = it
            }
        if (request.creationUnconfirmed) return
        openingId += 1
        val currentOpening = openingId
        scope.launch {
            val snapshot = request.create(api, contactAssignableEmployee(employee.id, employees))
            if (snapshot != null && currentOpening == openingId) {
                onOpenSession(
                    TaskSessionRoute(
                        sessionId = snapshot.id,
                        structured = snapshot.isStructured,
                        workspaceId = snapshot.workspaceId,
                    ),
                )
            }
        }
    }

    fun openTeamConversation(team: AiTeam) {
        if (conversationBusy()) return
        closeCreatePanel()
        rowErrors.remove(team.id)
        val assignable = contactAssignableTeam(team.id, teams)
        if (assignable == null) {
            rowErrors[team.id] = "团队已不存在，请刷新后重试。"
            return
        }
        if (teamWorkspaceId.isBlank()) {
            rowErrors[team.id] = "AI 团队需要先选择一个已有项目"
            return
        }
        val request = teamConversations[team.id]
            ?.takeIf { it.runId == null && it.workspaceId == teamWorkspaceId }
            ?: RecentTeamConversation(team.id, teamWorkspaceId).also {
                teamConversations[team.id] = it
            }
        if (request.creationUnconfirmed) return
        openingId += 1
        val currentOpening = openingId
        scope.launch {
            val created = request.create(api, contactAssignableTeam(team.id, teams))
            val runId = created?.detail?.run?.id
            if (!runId.isNullOrBlank() && currentOpening == openingId) {
                onOpenGroupChat(runId)
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            WandDetailTopBar(
                title = "通讯录",
                subtitle = "点名字开新对话，点头像改资料",
                leading = { WandDetailBackButton(onClick = { if (!conversationBusy()) onBack() }, enabled = !anyBusy) },
                titleContent = {
                    WandInPlaceSwap(
                        contentKey = searchOpen,
                        modifier = Modifier.weight(1f),
                        enterScale = 1f,
                        exitScale = 1f,
                    ) { searching ->
                        if (searching == true) {
                            WandInlineSearchField(
                                expanded = searchOpen,
                                query = query,
                                onQueryChange = { query = it },
                                onCollapse = null,
                                placeholder = "搜索名字、标签",
                                focusRequester = searchFocus,
                            )
                        } else Column {
                            Text("通讯录", style = MaterialTheme.typography.titleMedium, color = WandColors.textPrimary)
                            Text("点名字开新对话，点头像改资料", style = MaterialTheme.typography.labelSmall,
                                color = WandColors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                actions = {
                    WandMorphIconButton(
                        expanded = searchOpen,
                        collapsedIcon = WandIcons.search,
                        expandedIcon = WandIcons.close,
                        contentDescription = if (!searchOpen) "搜索通讯录" else if (query.isNotEmpty()) "清空搜索" else "关闭搜索",
                        onClick = {
                            when {
                                !searchOpen -> { templatesOpen = false; searchOpen = true }
                                query.isNotEmpty() -> clearSearch()
                                else -> closeSearch()
                            }
                        },
                    )
                    WandMorphIconButton(
                        expanded = templatesOpen,
                        collapsedIcon = WandIcons.add,
                        expandedIcon = WandIcons.close,
                        contentDescription = if (templatesOpen) "收起创建" else "创建员工或团队",
                        enabled = !anyBusy,
                        onClick = {
                            templatesOpen = !templatesOpen
                            if (templatesOpen) closeSearch()
                        },
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 创建面板属于同一个滚动容器，小屏或大字体下列表仍可到达。
            LazyColumn(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(14.dp, 8.dp, 14.dp, 30.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "create-panel") {
                    WandInlinePanel(visible = templatesOpen, growFrom = Alignment.Top) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                WandInlinePanelAction(
                                    icon = WandIcons.agent,
                                    label = "创建员工",
                                    enabled = !anyBusy,
                                    onClick = {
                                        if (!conversationBusy()) {
                                            closeCreatePanel()
                                            onCreateEmployee()
                                        }
                                    },
                                )
                            }
                            AiTeamTemplatePanel(onPick = { templateId ->
                                if (!conversationBusy()) {
                                    closeCreatePanel()
                                    onCreateTeam(templateId)
                                }
                            })
                        }
                    }
                }
                item(key = "directory-status") {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (loading) "正在刷新通讯录…" else if (query.isNotBlank()) "找到 ${visibleEmployees.size + visibleTeams.size} 个结果"
                            else "${visibleEmployees.size} 位员工 · ${visibleTeams.size} 个团队",
                            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.labelMedium,
                            color = WandColors.textMuted,
                        )
                        WandIconButton(WandIcons.refresh, "刷新通讯录", enabled = !loading, onClick = { refreshNonce += 1 })
                    }
                }
                if (error != null) {
                    item(key = "contacts-error") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(error.orEmpty(), modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                                color = WandColors.danger, style = MaterialTheme.typography.bodySmall)
                            WandButton("重试", onClick = { refreshNonce += 1 }, enabled = !loading,
                                variant = WandButtonVariant.Text, compact = true)
                        }
                    }
                }
                when {
                    loading && employees.isEmpty() && teams.isEmpty() -> item(key = "loading") { Box(
                        Modifier.fillMaxWidth().height(160.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        WandStatusIconSlot(
                            indicatorColor = WandColors.brand,
                            containerColor = Color.Transparent,
                            running = true,
                            icon = WandIcons.refresh,
                            boxSize = 40.dp,
                            iconSize = 24.dp,
                            modifier = Modifier.semantics { contentDescription = "正在加载通讯录" },
                        )
                    } }
                    query.isNotBlank() && visibleEmployees.isEmpty() && visibleTeams.isEmpty() -> item(key = "no-results") {
                        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("没有匹配的员工或团队", color = WandColors.textSecondary)
                            WandButton("清空搜索", onClick = ::clearSearch, variant = WandButtonVariant.Text)
                        }
                    }
                    else -> {
                        if (visibleEmployees.isNotEmpty() || query.isBlank()) item(key = "employees-header") { ContactSectionHeader("员工", visibleEmployees.size) }
                        if (visibleEmployees.isEmpty() && query.isBlank() && error == null) {
                            item(key = "employees-empty") {
                                Text(
                                    "还没有员工。点右上角 ＋ 创建一位。",
                                    color = WandColors.textMuted,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                                )
                            }
                        }
                        items(visibleEmployees, key = { "employee:${it.id}" }) { employee ->
                            val conversation = employeeConversations[employee.id]
                            ContactPersonRow(
                                employee = employee,
                                creating = conversation?.busy == true,
                                created = conversation?.snapshot != null,
                                enabled = !anyBusy && conversation?.creationUnconfirmed != true,
                                error = conversation?.error ?: rowErrors[employee.id],
                                onAvatar = { if (!conversationBusy()) onOpenEmployee(employee.id) },
                                managementEnabled = !anyBusy,
                                onOpen = { openEmployeeConversation(employee) },
                                modifier = Modifier.animateItem(
                                    fadeInSpec = if (motionEnabled) WandMotion.tweenFast() else null,
                                    placementSpec = if (motionEnabled) WandMotion.tweenNormal() else null,
                                    fadeOutSpec = if (motionEnabled) WandMotion.tweenFast() else null,
                                ),
                            )
                        }
                        if (visibleTeams.isNotEmpty() || query.isBlank()) item(key = "teams-header") { ContactSectionHeader("AI 团队", visibleTeams.size) }
                        if (visibleTeams.isEmpty() && query.isBlank() && error == null) {
                            item(key = "teams-empty") {
                                Text(
                                    "还没有团队。点右上角 ＋ 选一个模板就能开始。",
                                    color = WandColors.textMuted,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                                )
                            }
                        }
                        items(visibleTeams, key = { "team:${it.id}" }) { team ->
                            val conversation = teamConversations[team.id]
                            ContactTeamRow(
                                team = team,
                                creating = conversation?.busy == true,
                                created = conversation?.runId != null,
                                enabled = !anyBusy && conversation?.creationUnconfirmed != true,
                                error = conversation?.error ?: rowErrors[team.id],
                                onOpen = { openTeamConversation(team) },
                                onAvatar = { if (!conversationBusy()) onOpenTeam(team.id) },
                                managementEnabled = !anyBusy,
                                modifier = Modifier.animateItem(
                                    fadeInSpec = if (motionEnabled) WandMotion.tweenFast() else null,
                                    placementSpec = if (motionEnabled) WandMotion.tweenNormal() else null,
                                    fadeOutSpec = if (motionEnabled) WandMotion.tweenFast() else null,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactSectionHeader(title: String, count: Int) {
    Text(
        "$title · $count",
        color = WandColors.textPrimary,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp).semantics { heading() },
    )
}

@Composable
private fun ContactPersonRow(
    employee: SiliconEmployee,
    creating: Boolean,
    created: Boolean,
    enabled: Boolean,
    error: String?,
    onAvatar: () -> Unit,
    onOpen: () -> Unit,
    managementEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    WandCard(
        modifier = modifier.fillMaxWidth(),
        shape = WandShapes.lg,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(WandShapes.sm)
                    .semantics {
                        contentDescription = "管理${employee.name}的信息"
                        role = Role.Button
                    }
                    .clickable(enabled = managementEnabled, role = Role.Button, onClick = onAvatar),
                contentAlignment = Alignment.Center,
            ) {
                EmployeeAvatar(employee.id, employee.name, employee.avatar, size = 36.dp)
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 72.dp)
                    .clip(WandShapes.sm)
                    .clickable(
                        enabled = enabled && !creating,
                        role = Role.Button,
                        onClick = onOpen,
                    )
                    .semantics {
                        contentDescription = if (creating) "正在创建${employee.name}的对话"
                            else "与${employee.name}新建对话"
                    }
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        employee.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = WandColors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (employee.displayTags.isNotEmpty()) Text(
                        employee.displayTags.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (employee.builtin) WandColors.brand else WandColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    else if (employee.duty.isNotBlank()) Text(
                        employee.duty, style = MaterialTheme.typography.labelSmall,
                        color = WandColors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                ContactConversationStatus(
                    creating = creating,
                    created = created,
                    enabled = enabled,
                )
            }
        }
        WandInlinePanel(visible = error != null, growFrom = Alignment.Top) {
            Text(
                error.orEmpty(),
                color = WandColors.danger,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ContactTeamRow(
    team: AiTeam,
    creating: Boolean,
    created: Boolean,
    enabled: Boolean,
    error: String?,
    onOpen: () -> Unit,
    onAvatar: () -> Unit,
    managementEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    WandCard(
        modifier = modifier.fillMaxWidth(),
        shape = WandShapes.lg,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(WandShapes.sm)
                    .clickable(enabled = managementEnabled, role = Role.Button, onClick = onAvatar)
                    .semantics { contentDescription = "管理${team.name}的资料" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(36.dp).clip(WandShapes.sm)
                        .background(WandColors.brandSoft.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        WandIcons.agent,
                        contentDescription = null,
                        tint = WandColors.brand,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.weight(1f).heightIn(min = 72.dp).clip(WandShapes.sm)
                    .clickable(enabled = enabled && !creating, role = Role.Button, onClick = onOpen)
                    .semantics {
                        contentDescription = if (creating) "正在创建${team.name}的群聊"
                        else "与${team.name}新建群聊"
                    }.padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        team.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = WandColors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${team.members.size} 位成员" + team.description.takeIf { it.isNotBlank() }
                            ?.let { " · $it" }.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = WandColors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ContactConversationStatus(
                    creating = creating,
                    created = created,
                    enabled = enabled,
                )
            }
        }
        WandInlinePanel(visible = error != null, growFrom = Alignment.Top) {
            Text(
                error.orEmpty(),
                color = WandColors.danger,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ContactConversationStatus(
    creating: Boolean,
    created: Boolean,
    enabled: Boolean,
) {
    Box(
        modifier = Modifier.size(44.dp),
        contentAlignment = Alignment.Center,
    ) {
        WandStatusIconSlot(
            indicatorColor = if (enabled || creating) WandColors.brand
                else WandColors.textMuted.copy(alpha = 0.48f),
            containerColor = Color.Transparent,
            running = creating,
            icon = if (created) WandIcons.check else WandIcons.add,
            boxSize = 28.dp,
            iconSize = 20.dp,
        )
    }
}
