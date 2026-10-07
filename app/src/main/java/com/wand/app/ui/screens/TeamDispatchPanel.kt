package com.wand.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.wand.app.data.AI_TEAM_MAX_MEMBERS
import com.wand.app.data.AiTeamDispatchRun
import com.wand.app.data.TaskBoardPort
import com.wand.app.data.TeamDispatchMember
import com.wand.app.data.TeamDispatchPlan
import com.wand.app.data.TeamDispatchSelection
import com.wand.app.data.Workspace
import com.wand.app.data.canAddDispatchMember
import com.wand.app.data.dispatchProbabilityLabel
import com.wand.app.data.dispatchSelectionPicks
import com.wand.app.data.dispatchStartBlockedReason
import com.wand.app.data.setDispatchLeader
import com.wand.app.data.toggleDispatchMember
import com.wand.app.ui.SEND_FAILED_DWELL_MS
import com.wand.app.ui.SEND_SENT_DWELL_MS
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandInPlaceSwap
import com.wand.app.ui.components.WandTextField
import com.wand.app.ui.theme.WandColors

/** 派工说明上限：与团队开工说明同口径，服务端同值校验。 */
const val TEAM_DISPATCH_NOTE_MAX = TEAM_DIRECT_NOTE_MAX

/** 面板的提交阶段；结果留在原位（不用 Toast），失败态停留更久。 */
internal enum class TeamDispatchPhase { Idle, Planning, Planned, Starting, Started, Failed }

/** 主按钮文案：三个入口（通讯录 / 首页新建任务 / 看板新建任务）共用同一套。 */
internal fun dispatchPrimaryActionLabel(phase: TeamDispatchPhase, hasPlan: Boolean): String = when (phase) {
    TeamDispatchPhase.Planning -> "正在判断…"
    TeamDispatchPhase.Starting -> "正在开工…"
    TeamDispatchPhase.Started -> "已开工"
    else -> if (hasPlan) "确认开工" else "让决策模型选人"
}

/** 临时派工需要真实项目（服务端拒 global 与无 cwd 的项目）。 */
internal fun dispatchWorkspaceBlockedReason(workspaceId: String?): String? =
    if (isTeamPickAllowed(workspaceId)) null else "临时派工需要选择一个已有项目。"

/**
 * 三个入口共用的派工流程：说明 → 建议名单 → 确认开工。
 * 失败态在原位停留更久后再恢复按钮（时间只取 motion token），错误文案也只有这一份。
 */
@Stable
internal class TeamDispatchFlowState(initialMaxMembers: Int = 3) {
    var phase by mutableStateOf(TeamDispatchPhase.Idle)
        private set
    var plan by mutableStateOf<TeamDispatchPlan?>(null)
        private set
    var selection by mutableStateOf(TeamDispatchSelection())
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var maxMembers by mutableIntStateOf(initialMaxMembers)

    val busy: Boolean
        get() = phase == TeamDispatchPhase.Planning || phase == TeamDispatchPhase.Starting || phase == TeamDispatchPhase.Started
    val hasPlan: Boolean get() = plan != null

    fun updateSelection(next: TeamDispatchSelection) {
        selection = next
    }

    fun updateMaxMembers(value: Int) {
        maxMembers = value.coerceIn(2, AI_TEAM_MAX_MEMBERS)
    }

    /** 换项目 / 改人数 / 改说明后，旧名单不再对应当前输入。 */
    fun resetResults() {
        plan = null
        selection = TeamDispatchSelection()
        message = null
    }

    /** 第一步：只拿建议名单（不建任何东西）。 */
    suspend fun loadPlan(api: TaskBoardPort, note: String) {
        val trimmed = note.trim()
        if (trimmed.isEmpty() || phase == TeamDispatchPhase.Planning || phase == TeamDispatchPhase.Starting) return
        phase = TeamDispatchPhase.Planning
        message = null
        resetResults()
        try {
            val planned = api.planTeamDispatch(trimmed, maxMembers)
            plan = planned
            selection = TeamDispatchSelection.initial(planned)
            phase = TeamDispatchPhase.Planned
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            phase = TeamDispatchPhase.Failed
            message = failure.message ?: "决策选人失败，请稍后再试。"
            delay(SEND_FAILED_DWELL_MS)
            phase = TeamDispatchPhase.Idle
        }
    }

    /** 第二步：确认名单后才开工；成功返回 run，导航交给调用方。 */
    suspend fun submit(api: TaskBoardPort, workspaceId: String, note: String): AiTeamDispatchRun? {
        val trimmed = note.trim()
        if (dispatchStartBlockedReason(selection, workspaceId, trimmed, false).isNotEmpty()) return null
        if (phase == TeamDispatchPhase.Starting) return null
        phase = TeamDispatchPhase.Starting
        message = null
        return try {
            val started = api.startTeamDispatch(workspaceId, trimmed, dispatchSelectionPicks(selection))
            phase = TeamDispatchPhase.Started
            message = "已开工，正在打开群聊…"
            delay(SEND_SENT_DWELL_MS)
            phase = TeamDispatchPhase.Idle
            resetResults()
            started
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            phase = TeamDispatchPhase.Failed
            message = failure.message ?: "开工失败，请换个项目或稍后再试。"
            delay(SEND_FAILED_DWELL_MS)
            phase = TeamDispatchPhase.Idle
            null
        }
    }
}

/**
 * 建议名单区：人数上限（还没出名单时才显示）→ 说明 → 成员行 → 备选 → 禁用原因。
 * 通讯录面板与两个「新建任务」入口共用，避免三份拷贝各自漂移。
 */
@Composable
internal fun TeamDispatchRoster(
    flow: TeamDispatchFlowState,
    modifier: Modifier = Modifier,
    maxRosterHeight: androidx.compose.ui.unit.Dp = 260.dp,
) {
    val plan = flow.plan
    if (!flow.hasPlan) {
        Row(
            modifier = modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("人数上限", color = WandColors.textSecondary, style = MaterialTheme.typography.labelMedium)
            (2..AI_TEAM_MAX_MEMBERS).forEach { value ->
                WandButton(
                    label = "$value",
                    onClick = {
                        flow.updateMaxMembers(value)
                        flow.resetResults()
                    },
                    variant = if (value == flow.maxMembers) WandButtonVariant.Primary else WandButtonVariant.Secondary,
                    compact = true,
                    enabled = !flow.busy,
                )
            }
        }
        return
    }
    Text(
        plan?.note.orEmpty(),
        color = WandColors.textSecondary,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxRosterHeight)
            .verticalScroll(rememberScrollState())
            .padding(top = 4.dp),
    ) {
        plan?.members.orEmpty().forEach { member ->
            TeamDispatchMemberRow(
                member = member,
                selected = flow.selection.members.any { it.employeeId == member.employeeId },
                isLeader = flow.selection.leaderId == member.employeeId,
                busy = flow.busy,
                onToggle = { flow.updateSelection(toggleDispatchMember(flow.selection, member)) },
                onLeader = { flow.updateSelection(setDispatchLeader(flow.selection, member.employeeId)) },
            )
        }
        val bench = plan?.bench.orEmpty()
        if (bench.isNotEmpty()) {
            Text(
                if (canAddDispatchMember(flow.selection)) "备选（点一下加入）" else "备选（名单已满）",
                color = WandColors.textMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )
            bench.forEach { member ->
                TeamDispatchMemberRow(
                    member = member,
                    selected = flow.selection.members.any { it.employeeId == member.employeeId },
                    isLeader = flow.selection.leaderId == member.employeeId,
                    busy = flow.busy,
                    onToggle = { flow.updateSelection(toggleDispatchMember(flow.selection, member)) },
                    onLeader = { flow.updateSelection(setDispatchLeader(flow.selection, member.employeeId)) },
                )
            }
        }
    }
}

/**
 * 通讯录页头的派工面板（原位展开由宿主用 [com.wand.app.ui.components.WandInlinePanel] 负责）：
 * 项目 + 说明 + 建议名单 + 确认开工；结果留在原位。
 */
@Composable
internal fun TeamDispatchPanel(
    projects: List<Workspace>,
    projectsLoading: Boolean,
    projectError: String?,
    projectMenuOpen: Boolean,
    onToggleProjectMenu: () -> Unit,
    onPickProject: (String) -> Unit,
    selectedProjectName: String?,
    selectedProjectId: String,
    note: String,
    onNoteChange: (String) -> Unit,
    flow: TeamDispatchFlowState,
    onPlan: () -> Unit,
    onStart: () -> Unit,
) {
    val busy = flow.busy
    val blocked = dispatchStartBlockedReason(flow.selection, selectedProjectId, note, busy)

    WandCard(
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        contentPadding = PaddingValues(14.dp),
    ) {
        Text(
            "临时派工",
            color = WandColors.textPrimary,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "不指派员工：写清要做什么，让本机决策模型按职责与标签给出建议名单，确认后才开工。",
            color = WandColors.textMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (projects.isEmpty()) {
            Text(
                when {
                    projectsLoading -> "正在加载项目…"
                    projectError != null -> projectError
                    else -> "还没有可开工的项目，先在工作区创建一个项目。"
                },
                color = if (projectError != null) WandColors.danger else WandColors.textMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            return@WandCard
        }
        Box(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            WandButton(
                label = "项目：" + (selectedProjectName ?: "选择项目"),
                onClick = onToggleProjectMenu,
                variant = WandButtonVariant.Secondary,
                compact = true,
                trailingIcon = WandIcons.expand,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(
                expanded = projectMenuOpen,
                onDismissRequest = { onToggleProjectMenu() },
                containerColor = WandColors.bgElevated,
            ) {
                projects.forEach { project ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(project.name.ifBlank { project.id }, color = WandColors.textPrimary)
                                Text(
                                    project.cwd,
                                    color = WandColors.textMuted,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        onClick = { onPickProject(project.id) },
                    )
                }
            }
        }
        WandTextField(
            value = note,
            onValueChange = onNoteChange,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            label = "开工说明",
            placeholder = "要做什么？这段话是决策判断与团队执行的唯一目标",
            minLines = 3,
            maxLines = 6,
            enabled = !busy,
        )
        WandButton(
            label = dispatchPrimaryActionLabel(flow.phase, flow.hasPlan),
            onClick = if (flow.hasPlan) onStart else onPlan,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            enabled = if (flow.hasPlan) blocked.isEmpty() else !busy && note.isNotBlank(),
            loading = flow.phase == TeamDispatchPhase.Planning || flow.phase == TeamDispatchPhase.Starting,
            icon = if (flow.hasPlan) WandIcons.send else WandIcons.thinking,
        )
        TeamDispatchRoster(flow)
        if (flow.hasPlan && blocked.isNotEmpty()) {
            Text(
                blocked,
                color = WandColors.textMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .padding(top = 6.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            WandInPlaceSwap(
                contentKey = Pair(flow.message.orEmpty(), flow.phase == TeamDispatchPhase.Failed),
                enterScale = 1f,
                exitScale = 1f,
            ) { key ->
                @Suppress("UNCHECKED_CAST")
                val result = key as Pair<String, Boolean>
                Text(
                    result.first,
                    color = if (result.second) WandColors.danger else WandColors.textMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 一行候选：勾选参与 + 名字/职责 + 参与概率 + 负责人入口（只有选中的行才有）。 */
@Composable
private fun TeamDispatchMemberRow(
    member: TeamDispatchMember,
    selected: Boolean,
    isLeader: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onLeader: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WandButton(
            label = if (selected) "已选" else "加入",
            onClick = onToggle,
            variant = if (selected) WandButtonVariant.Primary else WandButtonVariant.Secondary,
            compact = true,
            enabled = !busy,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                member.name,
                color = if (selected) WandColors.brand else WandColors.textPrimary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                member.duty.ifBlank { "未填写职责" },
                color = WandColors.textMuted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            dispatchProbabilityLabel(member.probability),
            color = WandColors.textSecondary,
            style = MaterialTheme.typography.labelMedium,
        )
        if (selected) {
            WandButton(
                label = if (isLeader) "负责人" else "设为负责人",
                onClick = onLeader,
                variant = if (isLeader) WandButtonVariant.Primary else WandButtonVariant.Secondary,
                compact = true,
                enabled = !busy,
            )
        }
    }
}
