package com.wand.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import com.wand.app.ui.SEND_FAILED_DWELL_MS
import com.wand.app.ui.SEND_SENT_DWELL_MS
import com.wand.app.ui.components.EmployeeAvatar
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandDetailBackButton
import com.wand.app.ui.components.WandDetailTopBar
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandInlinePanel
import com.wand.app.ui.components.WandInlinePanelAction
import com.wand.app.ui.components.WandInlineSearchField
import com.wand.app.ui.components.WandMorphIconButton
import com.wand.app.ui.components.WandPullToRefresh
import com.wand.app.ui.components.WandStatusIconSlot
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.reduceMotionEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ContactRowMinHeight = 56.dp
private val ContactAvatarSize = 40.dp
private val ContactDividerInset = 64.dp

/**
 * 通讯录：常驻搜索框 + 连续名单。
 *
 * 顶部搜索框按「名字 / 职责 / 标签」过滤；名单、团队都按创建时间先后排列（早的在上），
 * 不做拼音分组；团队（群聊）在上、员工在下。
 * 点头像管理资料，点名字开新对话，右上角 ＋ 仍从原位展开创建面板。
 */
@OptIn(ExperimentalFoundationApi::class)
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
    conversationState: com.wand.app.ui.ConversationStore? = null,
    onOpenConversation: (String) -> Unit = {},
) {
    if (conversationState != null) {
        ConversationContacts(conversationState, onBack, onOpenConversation, onOpenEmployee, onCreateEmployee, onOpenTeam)
        return
    }
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
    // 无指派派工：页头 morph 按钮原位展开面板；草稿与建议名单留在本页状态里
    // （收起再展开接着改，和 Web 一致），离开本页整体丢弃。
    var dispatchOpen by remember { mutableStateOf(false) }
    var dispatchNote by remember { mutableStateOf("") }
    var dispatchWorkspaceId by remember { mutableStateOf("") }
    var dispatchProjectMenuOpen by remember { mutableStateOf(false) }
    val dispatchFlow = remember { TeamDispatchFlowState() }
    var query by rememberSaveable { mutableStateOf("") }
    val employeeConversations = remember { mutableStateMapOf<String, RecentEmployeeConversation>() }
    val teamConversations = remember { mutableStateMapOf<String, RecentTeamConversation>() }
    val rowErrors = remember { mutableStateMapOf<String, String>() }
    var openingId by remember { mutableLongStateOf(0L) }
    val visibleEmployees = remember(employees, query) {
        contactOrderedEmployees(contactDirectoryEmployees(employees, query))
    }
    val visibleTeams = remember(teams, query) {
        contactOrderedTeams(contactDirectoryTeams(teams, query))
    }
    val motionEnabled = !reduceMotionEnabled()
    val listState = rememberLazyListState()
    val searchFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val binding = contactConversationBinding(defaultCwd, workspaces)
    val teamWorkspaceId = contactTeamStartWorkspaceId(workspaces)
    val anyBusy = employeeConversations.values.any { it.busy } || teamConversations.values.any { it.busy }
    val loadingEmpty = loading && employees.isEmpty() && teams.isEmpty()
    val layout = remember(visibleEmployees, visibleTeams, query.isBlank(), error != null, loadingEmpty) {
        contactDirectoryLayout(
            employeeIds = visibleEmployees.map { it.id },
            teamIds = visibleTeams.map { it.id },
            queryBlank = query.isBlank(),
            hasError = error != null,
            loadingEmpty = loadingEmpty,
        )
    }
    val employeeById = remember(visibleEmployees) { visibleEmployees.associateBy { it.id } }
    val teamById = remember(visibleTeams) { visibleTeams.associateBy { it.id } }

    fun conversationBusy(): Boolean = employeeConversations.values.any { it.busy } ||
        teamConversations.values.any { it.busy }

    fun closeCreatePanel() {
        templatesOpen = false
    }

    /** 收起派工面板：提交中不收（等确定回执），内容留着，再展开还能接着改。 */
    fun closeDispatchPanel() {
        if (dispatchFlow.busy) return
        dispatchOpen = false
        dispatchProjectMenuOpen = false
    }

    fun clearSearch() {
        query = ""
        focusManager.clearFocus()
        keyboard?.hide()
    }

    BackHandler(enabled = templatesOpen || dispatchOpen || query.isNotEmpty()) {
        when {
            dispatchOpen -> closeDispatchPanel()
            templatesOpen -> closeCreatePanel()
            else -> clearSearch()
        }
    }
    BackHandler(enabled = anyBusy) { /* 等待新对话回执，防止离开后重复创建。 */ }

    LaunchedEffect(templatesOpen, query) {
        if (templatesOpen || query.isNotEmpty()) listState.scrollToItem(0)
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

    /** 派工两步都走共享流程：建议名单 → 确认开工；导航由本页负责。 */
    fun planDispatch() {
        scope.launch { dispatchFlow.loadPlan(api, dispatchNote) }
    }

    fun startDispatch() {
        scope.launch {
            val started = dispatchFlow.submit(api, dispatchWorkspaceId, dispatchNote) ?: return@launch
            val runId = started.detail.run?.id.orEmpty()
            dispatchOpen = false
            dispatchNote = ""
            if (runId.isNotBlank()) onOpenGroupChat(runId)
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
        containerColor = WandColors.bgPrimary,
        topBar = {
            WandDetailTopBar(
                title = "通讯录",
                leading = { WandDetailBackButton(onClick = { if (!conversationBusy()) onBack() }, enabled = !anyBusy) },
                actions = {
                    WandMorphIconButton(
                        expanded = dispatchOpen,
                        collapsedIcon = WandIcons.thinking,
                        expandedIcon = WandIcons.close,
                        contentDescription = if (dispatchOpen) "收起临时派工" else "临时派工（决策选人）",
                        enabled = !anyBusy,
                        onClick = {
                            if (dispatchOpen) closeDispatchPanel() else { closeCreatePanel(); dispatchOpen = true }
                        },
                    )
                    WandMorphIconButton(
                        expanded = templatesOpen,
                        collapsedIcon = WandIcons.add,
                        expandedIcon = WandIcons.close,
                        contentDescription = if (templatesOpen) "收起创建" else "创建员工或团队",
                        enabled = !anyBusy,
                        onClick = {
                            // 两个面板互斥：它们是同位展开的，同时开会让原位语义变得摸不到。
                            if (templatesOpen) closeCreatePanel() else { closeDispatchPanel(); templatesOpen = true }
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
            Box(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                WandInlineSearchField(
                    expanded = true,
                    query = query,
                    onQueryChange = { query = it },
                    onCollapse = null,
                    placeholder = "搜索名字、职责、标签",
                    autoFocus = false,
                    focusRequester = searchFocus,
                    onClear = {
                        query = ""
                        searchFocus.requestFocus()
                        keyboard?.show()
                    },
                )
            }
            HorizontalDivider(thickness = 0.5.dp, color = WandColors.border)
            // 派工面板在搜索框下方原位展开：列表顺势下移，触发按钮留在页头不动。
            WandInlinePanel(visible = dispatchOpen, growFrom = Alignment.Top) {
                TeamDispatchPanel(
                    projects = teamStartProjectCandidates(workspaces),
                    projectsLoading = loading,
                    projectError = error,
                    projectMenuOpen = dispatchProjectMenuOpen,
                    onToggleProjectMenu = { dispatchProjectMenuOpen = !dispatchProjectMenuOpen },
                    onPickProject = {
                        dispatchWorkspaceId = it
                        dispatchProjectMenuOpen = false
                        dispatchFlow.resetResults()
                    },
                    selectedProjectName = teamStartProjectCandidates(workspaces)
                        .firstOrNull { it.id == dispatchWorkspaceId }?.name,
                    selectedProjectId = dispatchWorkspaceId,
                    note = dispatchNote,
                    onNoteChange = { dispatchNote = it.take(TEAM_DISPATCH_NOTE_MAX) },
                    flow = dispatchFlow,
                    onPlan = { planDispatch() },
                    onStart = { startDispatch() },
                )
            }
            Box(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .weight(1f)
                    .semantics {
                        if (loading) {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = "正在刷新通讯录…"
                        }
                    },
            ) {
                WandPullToRefresh(
                    isRefreshing = loading && !loadingEmpty,
                    onRefresh = {
                        if (!loading) {
                            closeCreatePanel()
                            refreshNonce += 1
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = "点名字开新对话，点头像改资料" },
                        state = listState,
                        contentPadding = PaddingValues(bottom = 28.dp),
                    ) {
                        layout.forEachIndexed { index, slot ->
                            val next = layout.getOrNull(index + 1)
                            when (slot) {
                                ContactSlot.CreatePanel -> item(key = "create-panel") {
                                    WandInlinePanel(visible = templatesOpen, growFrom = Alignment.Top) {
                                        Column(Modifier.padding(bottom = 8.dp)) {
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
                                ContactSlot.Error -> item(key = "contacts-error") {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            error.orEmpty(),
                                            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                                            color = WandColors.danger,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        WandButton(
                                            "重试",
                                            onClick = { refreshNonce += 1 },
                                            enabled = !loading,
                                            variant = WandButtonVariant.Text,
                                            compact = true,
                                        )
                                    }
                                }
                                ContactSlot.Loading -> item(key = "loading") {
                                    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                                        WandStatusIconSlot(
                                            indicatorColor = WandColors.brand,
                                            containerColor = Color.Transparent,
                                            running = true,
                                            icon = WandIcons.refresh,
                                            boxSize = 40.dp,
                                            iconSize = 24.dp,
                                            modifier = Modifier.semantics { contentDescription = "正在加载通讯录" },
                                        )
                                    }
                                }
                                ContactSlot.NoResults -> item(key = "no-results") {
                                    Column(
                                        Modifier.fillMaxWidth().padding(vertical = 28.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text("没有匹配的员工或团队", color = WandColors.textSecondary)
                                        WandButton("清空搜索", onClick = ::clearSearch, variant = WandButtonVariant.Text)
                                    }
                                }
                                ContactSlot.ResultCount -> item(key = "result-count") {
                                    Text(
                                        "找到 ${visibleEmployees.size + visibleTeams.size} 个结果",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 8.dp)
                                            .semantics { liveRegion = LiveRegionMode.Polite },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = WandColors.textMuted,
                                    )
                                }
                                ContactSlot.TeamHeader -> item(key = "teams-header") {
                                    ContactSectionHeader("团队", visibleTeams.size)
                                }
                                ContactSlot.TeamEmpty -> item(key = "teams-empty") {
                                    Text(
                                        "还没有团队。点右上角 ＋ 选一个模板就能开始。",
                                        color = WandColors.textMuted,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                    )
                                }
                                is ContactSlot.Team -> item(key = "team:${slot.id}") {
                                    val team = teamById[slot.id] ?: return@item
                                    val conversation = teamConversations[team.id]
                                    ContactTeamRow(
                                        team = team,
                                        creating = conversation?.busy == true,
                                        created = conversation?.runId != null,
                                        enabled = !anyBusy && conversation?.creationUnconfirmed != true,
                                        error = conversation?.error ?: rowErrors[team.id],
                                        showDivider = next is ContactSlot.Team,
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
                                ContactSlot.GroupGap -> item(key = "group-gap") {
                                    Spacer(Modifier.fillMaxWidth().height(8.dp))
                                }
                                ContactSlot.EmployeeHeader -> item(key = "employees-header") {
                                    ContactSectionHeader("员工", visibleEmployees.size)
                                }
                                ContactSlot.EmployeeEmpty -> item(key = "employees-empty") {
                                    Text(
                                        "还没有员工。点右上角 ＋ 创建一位。",
                                        color = WandColors.textMuted,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                    )
                                }
                                is ContactSlot.Employee -> item(key = "employee:${slot.id}") {
                                    val employee = employeeById[slot.id] ?: return@item
                                    val conversation = employeeConversations[employee.id]
                                    ContactPersonRow(
                                        employee = employee,
                                        creating = conversation?.busy == true,
                                        created = conversation?.snapshot != null,
                                        enabled = !anyBusy && conversation?.creationUnconfirmed != true,
                                        error = conversation?.error ?: rowErrors[employee.id],
                                        showDivider = next is ContactSlot.Employee,
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
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactSectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(WandColors.surfaceSoft)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.labelMedium,
            color = WandColors.textSecondary,
            fontWeight = FontWeight.SemiBold,
        )
        if (count > 0) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = WandColors.textMuted,
            )
        }
    }
}

@Composable
private fun ContactPersonRow(
    employee: SiliconEmployee,
    creating: Boolean,
    created: Boolean,
    enabled: Boolean,
    error: String?,
    showDivider: Boolean,
    onAvatar: () -> Unit,
    onOpen: () -> Unit,
    managementEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val subtitle = when {
        employee.displayTags.isNotEmpty() -> employee.displayTags.joinToString(" · ")
        employee.duty.isNotBlank() -> employee.duty
        else -> null
    }
    val subtitleColor = if (employee.displayTags.isNotEmpty() && employee.builtin) {
        WandColors.brand
    } else {
        WandColors.textMuted
    }
    ContactDirectoryRow(
        title = employee.name,
        subtitle = subtitle,
        subtitleColor = subtitleColor,
        creating = creating,
        created = created,
        enabled = enabled,
        error = error,
        showDivider = showDivider,
        openDescription = if (creating) "正在创建${employee.name}的对话" else "与${employee.name}新建对话",
        onOpen = onOpen,
        modifier = modifier,
        avatar = {
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
                EmployeeAvatar(employee.id, employee.name, employee.avatar, size = ContactAvatarSize)
            }
        },
    )
}

@Composable
private fun ContactTeamRow(
    team: AiTeam,
    creating: Boolean,
    created: Boolean,
    enabled: Boolean,
    error: String?,
    showDivider: Boolean,
    onOpen: () -> Unit,
    onAvatar: () -> Unit,
    managementEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val fill = contactAccentFill(contactAccentIndex(team.id))
    ContactDirectoryRow(
        title = team.name,
        subtitle = "${team.members.size} 位成员" + team.description.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
        subtitleColor = WandColors.textMuted,
        creating = creating,
        created = created,
        enabled = enabled,
        error = error,
        showDivider = showDivider,
        openDescription = if (creating) "正在创建${team.name}的群聊" else "与${team.name}新建群聊",
        onOpen = onOpen,
        modifier = modifier,
        avatar = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(enabled = managementEnabled, role = Role.Button, onClick = onAvatar)
                    .semantics { contentDescription = "管理${team.name}的资料" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(ContactAvatarSize).clip(CircleShape).background(fill),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        WandIcons.agent,
                        contentDescription = null,
                        tint = WandColors.bgPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun ContactDirectoryRow(
    title: String,
    subtitle: String?,
    subtitleColor: Color,
    creating: Boolean,
    created: Boolean,
    enabled: Boolean,
    error: String?,
    showDivider: Boolean,
    openDescription: String,
    onOpen: () -> Unit,
    avatar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ContactRowMinHeight)
                .padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            avatar()
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = ContactRowMinHeight)
                    .clickable(enabled = enabled && !creating, role = Role.Button, onClick = onOpen)
                    .semantics { contentDescription = openDescription }
                    .padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = WandColors.textPrimary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = subtitleColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                ContactConversationStatus(creating = creating, created = created, enabled = enabled)
            }
        }
        WandInlinePanel(visible = error != null, growFrom = Alignment.Top) {
            Text(
                error.orEmpty(),
                color = WandColors.danger,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WandColors.dangerSoft)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (showDivider && error == null) {
            HorizontalDivider(
                modifier = Modifier.padding(start = ContactDividerInset),
                thickness = 0.5.dp,
                color = WandColors.border,
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
    Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        WandStatusIconSlot(
            indicatorColor = when {
                creating || created -> WandColors.brand
                enabled -> WandColors.textSecondary
                else -> WandColors.textMuted.copy(alpha = 0.48f)
            },
            containerColor = Color.Transparent,
            running = creating,
            icon = if (created) WandIcons.check else WandIcons.send,
            boxSize = 28.dp,
            iconSize = 18.dp,
        )
    }
}

@Composable
private fun contactAccentFill(index: Int): Color = when (index) {
    1 -> WandColors.info
    2 -> WandColors.success
    3 -> WandColors.thinking
    else -> WandColors.brand
}
