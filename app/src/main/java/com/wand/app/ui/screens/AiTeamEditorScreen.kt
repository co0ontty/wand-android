package com.wand.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wand.app.data.AI_TEAM_MAX_CANDIDATES
import com.wand.app.data.AI_TEAM_MAX_MEMBERS
import com.wand.app.data.AI_TEAM_MAX_STEPS
import com.wand.app.data.AI_TEAM_MIN_MEMBERS
import com.wand.app.data.AI_TEAM_MIN_STEPS
import com.wand.app.data.AiTeamDraft
import com.wand.app.data.AiTeamMember
import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.ModelsResponse
import com.wand.app.data.TaskBoardPort
import com.wand.app.ui.SEND_SENT_DWELL_MS
import com.wand.app.ui.components.WandAgentFields
import com.wand.app.ui.components.WandBreadcrumb
import com.wand.app.ui.components.WandButton
import com.wand.app.ui.components.WandButtonVariant
import com.wand.app.ui.components.WandCard
import com.wand.app.ui.components.WandCrumb
import com.wand.app.ui.components.WandDetailBackButton
import com.wand.app.ui.components.WandDetailTopBar
import com.wand.app.ui.components.WandDialog
import com.wand.app.ui.components.WandDialogAction
import com.wand.app.ui.components.WandIconButton
import com.wand.app.ui.components.WandIconButtonVariant
import com.wand.app.ui.components.WandIcons
import com.wand.app.ui.components.WandInlinePanel
import com.wand.app.ui.components.WandTextField
import com.wand.app.ui.theme.WandColors
import com.wand.app.ui.theme.WandMotion
import com.wand.app.ui.theme.WandShapes
import com.wand.app.ui.theme.reduceMotionEnabled
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 保存按钮的三段原位反馈：加载 → 完成 → 结果，位置与尺寸不变（动效硬要求 3）。 */
private enum class TeamSavePhase { Idle, Saving, Saved, Failed }

/**
 * 团队编辑器（新建 / 编辑同一页，§5.1 团队页右栏的移动端形态）：
 * - 成员按组织图排列，点卡片**原位展开**（硬要求 7）编辑名字、职责、负责人与执行候选；
 * - 「添加成员」就地追加一张卡，不跳页；候选可加 / 删 / 上移 / 下移（顺序即降级顺序）；
 * - 保存后停在原地显示「已保存」，改动未保存时返回会先确认；
 * - 删除团队带确认，删掉后回到团队列表（调用方负责出栈）。
 *
 * 只读时（团队详情页）展示的是同一份模型，这里写回去时把 Android 不编辑的
 * `avatar` / `role` 原样带上，避免一次改名就抹掉 Web 上选好的头像与职责标注。
 */
@Composable
fun AiTeamEditorScreen(
    api: TaskBoardPort,
    /** null = 新建团队，[templateId] 决定起步模板。 */
    teamId: String?,
    templateId: String?,
    onBack: () -> Unit,
    /** 删除成功后回到团队列表（调用方负责把编辑器与已删除的详情页一起出栈）。 */
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // 新建保存成功后原地转成「编辑这个新团队」：后续保存走 PUT，标题也跟着变。
    var editingId by remember(teamId) { mutableStateOf(teamId) }
    var draft by remember { mutableStateOf<AiTeamDraft?>(null) }
    var initialDraft by remember { mutableStateOf<AiTeamDraft?>(null) }
    var models by remember { mutableStateOf<ModelsResponse?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var expandedMember by remember { mutableStateOf(-1) }
    /** 保存过一次（或已经报错）之后才展开校验文案，避免新页面一进来就红一片。 */
    var showErrors by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(TeamSavePhase.Idle) }
    var statusText by remember { mutableStateOf("") }
    var saveError by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(api, teamId, templateId, reload) {
        loading = true
        loadError = null
        // 模型目录只影响下拉选项；拿不到就用「跟随服务端默认」一项兜底，不挡编辑。
        models = runCatching { api.boardModels() }.getOrNull()
        val defaultAgent = runCatching { api.boardTaskAgentDefaults() }.getOrNull()
        try {
            if (teamId == null) {
                val template = aiTeamTemplateById(templateId) ?: aiTeamTemplateById(AI_TEAM_BLANK_TEMPLATE_ID)!!
                val created = aiTeamTemplateDraft(template, defaultAgent)
                draft = created
                initialDraft = created
                showErrors = false
                // 新建就把负责人那张卡摊开：用户可以立刻改名字与执行配置。
                expandedMember = 0
            } else {
                val team = api.listAiTeams().firstOrNull { it.id == teamId }
                if (team == null) {
                    loadError = "团队不存在或已被删除。"
                } else {
                    val editable = aiTeamEditableDraft(team)
                    draft = editable
                    initialDraft = editable
                    showErrors = false
                    expandedMember = -1
                }
            }
        } catch (e: Exception) {
            loadError = e.message ?: "无法加载团队"
        } finally {
            loading = false
        }
    }

    val current = draft
    val dirty = current != null && initialDraft != null && current != initialDraft
    val errors = current?.let(::aiTeamDraftErrors) ?: emptyList()

    /** 任何一次编辑都清掉上一轮的保存结果（结果文案不越过下一次改动）。 */
    fun editDraft(next: AiTeamDraft) {
        draft = next
        saveError = null
        statusText = ""
        phase = TeamSavePhase.Idle
    }

    fun save() {
        val editing = draft ?: return
        val problems = aiTeamDraftErrors(editing)
        showErrors = true
        if (problems.isNotEmpty()) {
            statusText = ""
            saveError = problems.first()
            phase = TeamSavePhase.Failed
            return
        }
        phase = TeamSavePhase.Saving
        saveError = null
        statusText = ""
        scope.launch {
            try {
                val saved = if (editingId == null) {
                    api.createAiTeam(editing)
                } else {
                    api.updateAiTeam(editingId!!, editing)
                }
                editingId = saved.id
                val next = aiTeamEditableDraft(saved)
                draft = next
                initialDraft = next
                statusText = "已保存。"
                phase = TeamSavePhase.Saved
                // 完成态停留一拍再回到「保存」，结果文案留在原地（失败态比完成态久）。
                delay(SEND_SENT_DWELL_MS)
                if (phase == TeamSavePhase.Saved) phase = TeamSavePhase.Idle
            } catch (e: Exception) {
                // 失败原因原位留着，直到用户改下一处（不自动消失，也不用 Toast）。
                saveError = e.message ?: "保存团队失败。"
                phase = TeamSavePhase.Failed
            }
        }
    }

    fun leave() {
        if (dirty) confirmDiscard = true else onBack()
    }

    fun patchMember(index: Int, patch: (AiTeamMember) -> AiTeamMember) {
        val editing = draft ?: return
        // 设为负责人时同步卸任其他成员（服务端要求恰好一位，界面先保证一致）。
        val members = if (patch(editing.members[index]).isLeader) {
            setTeamLeader(editing.members, index).let { set ->
                set.mapIndexed { at, member -> if (at == index) patch(member) else member }
            }
        } else {
            replaceTeamMember(editing.members, index, patch(editing.members[index]))
        }
        editDraft(editing.copy(members = members))
    }

    BackHandler { leave() }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            WandDetailTopBar(
                title = "",
                leading = { WandDetailBackButton(onClick = { leave() }) },
                titleContent = {
                    Column(modifier = Modifier.weight(1f)) {
                        WandBreadcrumb(
                            crumbs = listOf(
                                WandCrumb("AI 团队", onClick = { leave() }),
                                WandCrumb(teamEditorTitle(editingId == null)),
                            ),
                        )
                        Text(
                            if (editingId == null) "选好模板就能改，保存前都算草稿" else "改完点保存，已开始的运行按启动时的快照继续",
                            style = MaterialTheme.typography.labelSmall,
                            color = WandColors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    if (editingId != null && !loading) {
                        WandIconButton(
                            icon = WandIcons.delete,
                            contentDescription = "删除团队",
                            onClick = { confirmDelete = true },
                            variant = WandIconButtonVariant.Toolbar,
                            tint = WandColors.danger,
                            enabled = !deleting && phase != TeamSavePhase.Saving,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                loading -> CircularProgressIndicator(
                    color = WandColors.brand,
                    modifier = Modifier.align(Alignment.Center).size(26.dp),
                )
                current == null -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        loadError ?: "团队不存在或已被删除。",
                        color = WandColors.danger,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    WandButton(
                        label = "重试",
                        onClick = { reload += 1 },
                        variant = WandButtonVariant.Secondary,
                        compact = true,
                    )
                }
                else -> LazyColumn(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = 720.dp)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(14.dp, 14.dp, 14.dp, 30.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "team-basics") {
                        TeamBasicsCard(
                            draft = current,
                            enabled = phase != TeamSavePhase.Saving && !deleting,
                            onNameChange = { editDraft(current.copy(name = it)) },
                            onDescriptionChange = { editDraft(current.copy(description = it)) },
                            onInstructionsChange = { editDraft(current.copy(instructions = it)) },
                            onMaxStepsChange = { editDraft(current.copy(maxSteps = it)) },
                            onApprovalChange = { editDraft(current.copy(requirePlanApproval = it)) },
                        )
                    }
                    item(key = "team-members-head") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "成员",
                                color = WandColors.textSecondary,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${current.members.size}/$AI_TEAM_MAX_MEMBERS · 点卡片原位展开编辑",
                                color = WandColors.textMuted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    // 编辑期保持定义顺序（不因切换负责人重排）：点了「设为负责人」就跳位置，
                    // 等于让触发那一行在动画前后换坐标，按动效总则 2 不做。负责人靠徽标标识，
                    // 只读的详情页才把负责人置顶展示。
                    items(current.members.size, key = { index ->
                        current.members[index].id.ifBlank { "team-member-draft-$index" }
                    }) { index ->
                        TeamMemberCard(
                            member = current.members[index],
                            index = index,
                            models = models,
                            expanded = expandedMember == index,
                            canRemove = current.members.size > AI_TEAM_MIN_MEMBERS,
                            enabled = phase != TeamSavePhase.Saving && !deleting,
                            modifier = Modifier.animateItem(),
                            onToggle = {
                                expandedMember = if (expandedMember == index) -1 else index
                            },
                            onPatch = { patch -> patchMember(index, patch) },
                            onRemove = {
                                editDraft(current.copy(members = removeTeamMember(current.members, index)))
                                expandedMember = -1
                            },
                            onSetLeader = { editDraft(current.copy(members = setTeamLeader(current.members, index))) },
                        )
                    }
                    item(key = "team-add-member") {
                        WandButton(
                            label = "添加成员",
                            onClick = {
                                val agent = current.members.lastOrNull()?.agents?.firstOrNull()
                                val next = addTeamMember(current.members, agent)
                                if (next.size != current.members.size) {
                                    editDraft(current.copy(members = next))
                                    // 新卡直接摊开，省一次点击（与 Web 的 setOpenMember(length) 同口径）。
                                    expandedMember = next.lastIndex
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            variant = WandButtonVariant.Secondary,
                            icon = WandIcons.add,
                            enabled = current.members.size < AI_TEAM_MAX_MEMBERS &&
                                phase != TeamSavePhase.Saving && !deleting,
                        )
                    }
                    if (showErrors && errors.isNotEmpty()) {
                        item(key = "team-errors") {
                            WandCard(containerColor = WandColors.dangerSoft, contentPadding = PaddingValues(12.dp)) {
                                errors.forEach { message ->
                                    Text(
                                        message,
                                        color = WandColors.danger,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                    item(key = "team-save") {
                        TeamSaveCard(
                            isNew = editingId == null,
                            phase = phase,
                            statusText = statusText,
                            error = saveError,
                            canSave = errors.isEmpty(),
                            onSave = { save() },
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        WandDialog(
            title = "删除团队「${initialDraft?.name ?: current?.name.orEmpty()}」？",
            onDismissRequest = { confirmDelete = false },
            icon = WandIcons.delete,
            confirm = WandDialogAction(
                label = if (deleting) "删除中…" else "删除团队",
                destructive = true,
                enabled = !deleting,
                onClick = {
                    val id = editingId ?: return@WandDialogAction
                    deleting = true
                    scope.launch {
                        try {
                            api.deleteAiTeam(id)
                            confirmDelete = false
                            onDeleted()
                        } catch (e: Exception) {
                            saveError = e.message ?: "删除团队失败。"
                            phase = TeamSavePhase.Failed
                            showErrors = true
                            confirmDelete = false
                        } finally {
                            deleting = false
                        }
                    }
                },
            ),
            dismiss = WandDialogAction(label = "取消", onClick = { confirmDelete = false }),
        ) {
            Text(
                "已经开始的运行不受影响，会按启动时的团队快照继续。",
                color = WandColors.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    if (confirmDiscard) {
        WandDialog(
            title = "放弃未保存的团队改动？",
            onDismissRequest = { confirmDiscard = false },
            confirm = WandDialogAction(
                label = "放弃改动",
                destructive = true,
                onClick = {
                    confirmDiscard = false
                    onBack()
                },
            ),
            dismiss = WandDialogAction(label = "继续编辑", onClick = { confirmDiscard = false }),
        ) {
            Text(
                "名称、成员和各自的 CLI / 模型 / 执行模式改动还没保存，离开后回到上次保存的内容。",
                color = WandColors.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun TeamBasicsCard(
    draft: AiTeamDraft,
    enabled: Boolean,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onInstructionsChange: (String) -> Unit,
    onMaxStepsChange: (Int) -> Unit,
    onApprovalChange: (Boolean) -> Unit,
) {
    WandCard(contentPadding = PaddingValues(14.dp)) {
        Text(
            "协作设置",
            color = WandColors.textPrimary,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        WandTextField(
            value = draft.name,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            label = "团队名",
            placeholder = "例如：全栈小组",
            singleLine = true,
            enabled = enabled,
        )
        WandTextField(
            value = draft.description,
            onValueChange = onDescriptionChange,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            label = "简介",
            placeholder = "可选：这个团队擅长什么",
            minLines = 2,
            maxLines = 3,
            enabled = enabled,
        )
        WandTextField(
            value = draft.instructions,
            onValueChange = onInstructionsChange,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            label = "协作指令",
            placeholder = "写进负责人和每位成员的提示词：分工约定、工作要求、注意事项。",
            minLines = 3,
            maxLines = 8,
            enabled = enabled,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("步数上限", color = WandColors.textPrimary, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "负责人轮次与成员步骤合计，$AI_TEAM_MIN_STEPS–$AI_TEAM_MAX_STEPS",
                    color = WandColors.textMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TeamStepStepper(
                value = draft.maxSteps,
                enabled = enabled,
                onDecrease = { onMaxStepsChange(stepTeamMaxSteps(draft.maxSteps, -5)) },
                onIncrease = { onMaxStepsChange(stepTeamMaxSteps(draft.maxSteps, 5)) },
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(WandShapes.sm)
                .clickable(enabled = enabled) { onApprovalChange(!draft.requirePlanApproval) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("计划需要我批准", color = WandColors.textPrimary, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "负责人给出第一份计划后先停下，等你批准或退回再开始执行。",
                    color = WandColors.textMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Switch(
                checked = draft.requirePlanApproval,
                onCheckedChange = if (enabled) onApprovalChange else null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = WandColors.brand,
                    uncheckedThumbColor = WandColors.textMuted,
                    uncheckedTrackColor = WandColors.surfaceSoft,
                ),
            )
        }
    }
}

/** 步数上限的计数步进器：− / N / ＋，到边界按钮同时禁用，值本身不变形。 */
@Composable
private fun TeamStepStepper(
    value: Int,
    enabled: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        WandIconButton(
            icon = WandIcons.minus,
            contentDescription = "减少 5 步",
            onClick = onDecrease,
            variant = WandIconButtonVariant.Compact,
            tint = WandColors.textSecondary,
            enabled = enabled && canDecreaseTeamMaxSteps(value),
        )
        Box(
            modifier = Modifier
                .widthIn(min = 48.dp)
                .clip(WandShapes.sm)
                .background(WandColors.surfaceSoft.copy(alpha = 0.6f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("$value", color = WandColors.textPrimary, style = MaterialTheme.typography.bodyMedium)
        }
        WandIconButton(
            icon = WandIcons.add,
            contentDescription = "增加 5 步",
            onClick = onIncrease,
            variant = WandIconButtonVariant.Compact,
            tint = WandColors.textSecondary,
            enabled = enabled && canIncreaseTeamMaxSteps(value),
        )
    }
}

@Composable
private fun TeamMemberCard(
    member: AiTeamMember,
    index: Int,
    models: ModelsResponse?,
    expanded: Boolean,
    canRemove: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onPatch: ((AiTeamMember) -> AiTeamMember) -> Unit,
    onRemove: () -> Unit,
    onSetLeader: () -> Unit,
) {
    val label = teamMemberDisplayName(member, index)
    val candidates = member.agents
    val duplicates = duplicateTeamCandidates(candidates)
    // 展开箭头：同一个图标旋转到底，不是两个分支硬切（动效硬要求 4）。
    val arrowAngle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = WandMotion.respectMotion(!reduceMotionEnabled(), WandMotion.tweenFast()),
        label = "teamMemberArrow",
    )
    WandCard(modifier = modifier, contentPadding = PaddingValues(0.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onToggle)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        color = WandColors.textPrimary,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (member.isLeader) {
                        // §2.3 F1：展开/收起的标题行只留一份名字 + 一枚矢量星，
                        // 文字角色在名字恰为「负责人」时会重复，也不让同一角色有两种 UI。
                        Icon(
                            WandIcons.leader,
                            contentDescription = "团队角色：负责人",
                            tint = WandColors.brand,
                            modifier = Modifier.padding(start = 8.dp).size(13.dp),
                        )
                    }
                }
                Text(
                    member.duty.ifBlank { "未填职责" },
                    color = WandColors.textMuted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Icon(
                WandIcons.expand,
                contentDescription = if (expanded) "收起" else "展开编辑",
                tint = WandColors.textMuted,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(arrowAngle),
            )
        }
        // 原地展开编辑区（硬要求 7）：展开态给的是更多信息与操作，收起是同一段动画倒放。
        WandInlinePanel(visible = expanded, growFrom = Alignment.Top) {
            Column(
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WandTextField(
                    value = member.name,
                    onValueChange = { name -> onPatch { it.copy(name = name) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = "成员名字",
                    placeholder = "例如：实现者",
                    singleLine = true,
                    enabled = enabled,
                )
                WandTextField(
                    value = member.duty,
                    onValueChange = { duty -> onPatch { it.copy(duty = duty) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = "职责说明",
                    placeholder = "写清这位成员负责什么、做到什么程度",
                    minLines = 2,
                    maxLines = 5,
                    enabled = enabled,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(WandShapes.sm)
                        .clickable(enabled = enabled && !member.isLeader, onClick = onSetLeader)
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (member.isLeader) "负责人（团队里最多一位）" else "设为负责人",
                        color = if (member.isLeader) WandColors.brand else WandColors.textPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (member.isLeader) {
                        Icon(WandIcons.check, contentDescription = null, tint = WandColors.brand, modifier = Modifier.size(18.dp))
                    }
                }
                Text(
                    "执行候选 · 首选在前，启动失败按顺序降级",
                    color = WandColors.textSecondary,
                    style = MaterialTheme.typography.labelMedium,
                )
                candidates.forEachIndexed { candidateIndex, agent ->
                    TeamCandidateRow(
                        agent = agent,
                        label = aiTeamCandidateLabel(candidateIndex),
                        models = models,
                        duplicate = candidateIndex in duplicates,
                        index = candidateIndex,
                        total = candidates.size,
                        enabled = enabled,
                        onChange = { next -> onPatch { it.copy(agents = setTeamCandidate(it.agents, candidateIndex, next)) } },
                        onMove = { delta -> onPatch { it.copy(agents = moveTeamCandidate(it.agents, candidateIndex, delta)) } },
                        onRemove = { onPatch { it.copy(agents = removeTeamCandidate(it.agents, candidateIndex)) } },
                    )
                }
                val listError = teamCandidateListError(candidates)
                if (listError.isNotEmpty()) {
                    Text(listError, color = WandColors.danger, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WandButton(
                        label = "添加候选",
                        onClick = { onPatch { it.copy(agents = addTeamCandidate(it.agents)) } },
                        variant = WandButtonVariant.Secondary,
                        compact = true,
                        icon = WandIcons.add,
                        enabled = enabled && candidates.size < AI_TEAM_MAX_CANDIDATES,
                    )
                    WandButton(
                        label = "移除成员",
                        onClick = onRemove,
                        variant = WandButtonVariant.DangerText,
                        compact = true,
                        icon = WandIcons.delete,
                        enabled = enabled && canRemove,
                    )
                }
            }
        }
    }
}

@Composable
private fun TeamCandidateRow(
    agent: BoardTaskAgent,
    label: String,
    models: ModelsResponse?,
    duplicate: Boolean,
    index: Int,
    total: Int,
    enabled: Boolean,
    onChange: (BoardTaskAgent) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(WandShapes.sm)
            .background(if (duplicate) WandColors.dangerSoft else WandColors.surfaceSoft.copy(alpha = 0.45f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = if (index == 0) WandColors.brand else WandColors.textMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
            WandIconButton(
                icon = WandIcons.arrowUp,
                contentDescription = "把$label 上移",
                onClick = { onMove(-1) },
                variant = WandIconButtonVariant.Compact,
                enabled = enabled && index > 0,
            )
            WandIconButton(
                icon = WandIcons.expand,
                contentDescription = "把$label 下移",
                onClick = { onMove(1) },
                variant = WandIconButtonVariant.Compact,
                enabled = enabled && index < total - 1,
            )
            WandIconButton(
                icon = WandIcons.close,
                contentDescription = if (total <= 1) "至少要保留 1 个候选" else "删除$label",
                onClick = onRemove,
                variant = WandIconButtonVariant.Compact,
                tint = WandColors.danger,
                enabled = enabled && total > 1,
            )
        }
        WandAgentFields(
            models = models,
            agent = agent,
            providerLabel = label,
            onChange = onChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun TeamSaveCard(
    isNew: Boolean,
    phase: TeamSavePhase,
    statusText: String,
    error: String?,
    canSave: Boolean,
    onSave: () -> Unit,
) {
    WandCard(contentPadding = PaddingValues(14.dp)) {
        // 结果行固定占位：加载 → 完成 → 失败都在同一位置显示，尺寸不跳（硬要求 3）。
        // 完成态停留一拍后回到「保存」，结果文案留着直到下一次编辑。
        val result = when (phase) {
            TeamSavePhase.Saved -> statusText.ifBlank { "已保存。" }
            TeamSavePhase.Failed -> error ?: "保存团队失败。"
            TeamSavePhase.Saving -> "保存中…"
            TeamSavePhase.Idle -> if (statusText.isNotBlank()) statusText else if (canSave) "" else "上面还有要改的地方。"
        }
        Text(
            result,
            color = when {
                phase == TeamSavePhase.Failed -> WandColors.danger
                statusText.isNotBlank() -> WandColors.success
                else -> WandColors.textMuted
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        WandButton(
            label = when (phase) {
                TeamSavePhase.Saving -> "保存中…"
                TeamSavePhase.Saved -> "已保存"
                else -> teamEditorSubmitLabel(isNew = isNew, saving = false)
            },
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
            loading = phase == TeamSavePhase.Saving,
            enabled = phase != TeamSavePhase.Saving,
            icon = if (phase == TeamSavePhase.Saved) WandIcons.check else null,
        )
    }
}
