package com.wand.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.wand.app.SessionWatcher
import com.wand.app.data.AiTeam
import com.wand.app.data.SiliconEmployee
import com.wand.app.data.Workspace
import com.wand.app.data.employeeConversationId
import com.wand.app.ui.ConversationStore
import com.wand.app.ui.components.EmployeeAvatar
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
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
import kotlinx.coroutines.launch

private val ContactRowMinHeight = 56.dp
private val ContactAvatarSize = 40.dp
private val ContactDividerInset = 64.dp

/**
 * 通讯录：常驻搜索框 + 连续名单，是根壳 `HomeListMode.Contacts` 的视图本体。
 *
 * 顶部搜索框按「名字 / 职责 / 标签」过滤；名单、团队都按创建时间先后排列（早的在上），
 * 不做拼音分组；团队（群聊）在上、员工在下。
 * 点头像管理资料，点员工名字进入私聊，点团队名字按模板建群；发送需求后才开始工作。
 *
 * 它不是一条独立页面：顶栏复用根壳的 [HomeTopBar]，底部按 [bottomClearance] 给悬浮菜单胶囊留位，
 * 于是切到通讯录时底栏常驻、且本项按页签高亮。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactsScreen(
    conversations: ConversationStore,
    serverDisplayName: String,
    interactionEnabled: Boolean,
    onOpenSettings: () -> Unit,
    onSwitchServer: () -> Unit,
    bottomClearance: Dp,
    onOpenEmployee: (String) -> Unit,
    onOpenTeam: (String) -> Unit,
    onCreateEmployee: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenGroupChat: (String) -> Unit,
    onCreateTeam: (String) -> Unit,
    onCollapseSidebar: (() -> Unit)? = null,
) {
    val api = conversations.api
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var employees by remember { mutableStateOf<List<SiliconEmployee>>(emptyList()) }
    var teams by remember { mutableStateOf<List<AiTeam>>(emptyList()) }
    var workspaces by remember { mutableStateOf<List<Workspace>>(emptyList()) }
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
    var groupPresetId by rememberSaveable { mutableStateOf<String?>(null) }
    val rowErrors = remember { mutableStateMapOf<String, String>() }
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
    val anyBusy = dispatchFlow.busy || groupPresetId?.let { conversations.operation("group:contact:$it").phase == "sending" } == true
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

    fun conversationBusy(): Boolean = anyBusy

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

    BackHandler(enabled = templatesOpen || dispatchOpen || groupPresetId != null || query.isNotEmpty()) {
        when {
            groupPresetId != null -> groupPresetId = null
            dispatchOpen -> closeDispatchPanel()
            templatesOpen -> closeCreatePanel()
            else -> clearSearch()
        }
    }
    BackHandler(enabled = dispatchFlow.busy) { /* 临时派工仍等待确定回执；建群可退出，由共享 owner 保留提交锁。 */ }

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
                        val failures = mutableListOf<String>()
                        people.await().onSuccess { employees = it; conversations.employees.clear(); conversations.employees.addAll(it) }.onFailure { failures += "员工" }
                        groups.await().onSuccess { teams = it; conversations.presets.clear(); conversations.presets.addAll(it) }.onFailure { failures += "团队" }
                        projects.await().onSuccess { workspaces = it }.onFailure { failures += "项目" }
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
        if (assignable == null) {
            rowErrors[employee.id] = "员工已不存在或不可用，请刷新后重试。"
            return
        }
        // 与聊天列表、员工资料共用私聊身份；选择联系人本身不启动执行。
        onOpenConversation(employeeConversationId(assignable.id))
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
            val conversationId = started.detail.run?.conversationId
            if (!conversationId.isNullOrBlank()) onOpenConversation(conversationId)
            else if (runId.isNotBlank()) onOpenGroupChat(runId)
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
        closeDispatchPanel()
        groupPresetId = assignable.id
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 通讯录自带搜索框：键盘弹出时只有本页需要让出高度。根壳在 Im 模式之外不重复加。
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HomeTopBar(
            title = "通讯录",
            serverDisplayName = serverDisplayName,
            interactionEnabled = interactionEnabled,
            onOpenSettings = onOpenSettings,
            onSwitchServer = onSwitchServer,
            onCollapseSidebar = onCollapseSidebar,
            trailingActions = {
                WandMorphIconButton(
                    expanded = dispatchOpen,
                    collapsedIcon = WandIcons.thinking,
                    expandedIcon = WandIcons.close,
                    contentDescription = if (dispatchOpen) "收起临时派工" else "临时派工（决策选人）",
                    enabled = !anyBusy,
                    onClick = {
                        if (dispatchOpen) closeDispatchPanel() else { closeCreatePanel(); groupPresetId = null; dispatchOpen = true }
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
                        if (templatesOpen) closeCreatePanel() else { closeDispatchPanel(); groupPresetId = null; templatesOpen = true }
                    },
                )
            },
        )
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
        BoxWithConstraints(
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
            val presetId = groupPresetId
            if (presetId != null) {
                CompositionLocalProvider(LocalConversationPanelHeight provides (maxHeight - bottomClearance).coerceAtLeast(0.dp)) {
                    androidx.compose.runtime.key(presetId) {
                        ConversationGroupEditor(
                            state = conversations,
                            presetId = presetId,
                            draftContext = "contact:$presetId",
                            onClose = { groupPresetId = null },
                            onAccepted = { id -> groupPresetId = null; onOpenConversation(id) },
                        )
                    }
                }
            } else WandPullToRefresh(
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
                    contentPadding = PaddingValues(bottom = maxOf(28.dp, bottomClearance + 12.dp)),
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
                                ContactTeamRow(
                                    team = team,
                                    enabled = !anyBusy,
                                    error = rowErrors[team.id],
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
                                ContactPersonRow(
                                    employee = employee,
                                    enabled = !anyBusy,
                                    error = rowErrors[employee.id],
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
        enabled = enabled,
        error = error,
        showDivider = showDivider,
        openDescription = "与${employee.name}聊天",
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
        enabled = enabled,
        error = error,
        showDivider = showDivider,
        openDescription = "与${team.name}新建群聊",
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
                    .clickable(enabled = enabled, role = Role.Button, onClick = onOpen)
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
                Icon(WandIcons.chevronRight, null, Modifier.size(24.dp), tint = if (enabled) WandColors.textSecondary else WandColors.textMuted)
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
private fun contactAccentFill(index: Int): Color = when (index) {
    1 -> WandColors.info
    2 -> WandColors.success
    3 -> WandColors.thinking
    else -> WandColors.brand
}
