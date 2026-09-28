package com.wand.app.ui.screens

import com.wand.app.data.AI_TEAM_DESCRIPTION_MAX
import com.wand.app.data.AI_TEAM_DEFAULT_MAX_STEPS
import com.wand.app.data.AI_TEAM_INSTRUCTIONS_MAX
import com.wand.app.data.AI_TEAM_MAX_CANDIDATES
import com.wand.app.data.AI_TEAM_MAX_MEMBERS
import com.wand.app.data.AI_TEAM_MAX_STEPS
import com.wand.app.data.AI_TEAM_MEMBER_DUTY_MAX
import com.wand.app.data.AI_TEAM_MEMBER_NAME_MAX
import com.wand.app.data.AI_TEAM_MIN_MEMBERS
import com.wand.app.data.AI_TEAM_MIN_STEPS
import com.wand.app.data.AI_TEAM_NAME_MAX
import com.wand.app.data.AiTeam
import com.wand.app.data.AiTeamDraft
import com.wand.app.data.AiTeamMember
import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.boardTaskAgentKey

/**
 * 团队编辑器（新建 / 编辑）的纯函数：模板、成员增删、负责人切换、候选增删移、整份草稿校验。
 * 没有 Compose 状态，全部可单测；文案与服务端 `parseAiTeamInput` / Web `validateTeamDraft`
 * 同口径，让「保存」之前就能看到问题，而不是靠 400 回包。
 */

/** 新建团队的起步模板（镜像 Web `teams-page.tsx` 的 TEMPLATES，两端同四个）。 */
data class AiTeamTemplate(
    val id: String,
    val name: String,
    val summary: String,
    val instructions: String,
    val members: List<AiTeamTemplateMember>,
)

data class AiTeamTemplateMember(
    val name: String,
    val duty: String,
    val isLeader: Boolean = false,
)

/** 空白模板的 id：它的团队名留空由用户填，其余模板用模板名当团队名。 */
const val AI_TEAM_BLANK_TEMPLATE_ID = "blank"

val AI_TEAM_TEMPLATES: List<AiTeamTemplate> = listOf(
    AiTeamTemplate(
        id = "dev",
        name = "开发三人组",
        summary = "负责人拆解与验收，一人实现，一人审查",
        instructions = "审查者只读代码，问题写进报告，由负责人决定是否返工。",
        members = listOf(
            AiTeamTemplateMember("负责人", "拆解任务、分派步骤，审阅报告并决定下一步或完成。", isLeader = true),
            AiTeamTemplateMember("实现者", "按步骤改代码并自测，报告写清改动与验证方式。"),
            AiTeamTemplateMember("审查者", "审查改动与测试，指出问题和风险，不直接修改。"),
        ),
    ),
    AiTeamTemplate(
        id = "bugfix",
        name = "修 Bug 二人组",
        summary = "负责人定位与验证，一人修复",
        instructions = "先复现再修，修复附上验证步骤。",
        members = listOf(
            AiTeamTemplateMember("负责人", "复现并定位根因，写清修改范围，修完后验证。", isLeader = true),
            AiTeamTemplateMember("修复者", "按定位修改代码，补回归测试，报告附验证输出。"),
        ),
    ),
    AiTeamTemplate(
        id = "research",
        name = "调研加评审",
        summary = "两人并行调研不同方向，负责人汇总",
        instructions = "调研员可同时开始，只读不改文件。",
        members = listOf(
            AiTeamTemplateMember("负责人", "拆出互不重叠的调研方向，汇总结论给出建议。", isLeader = true),
            AiTeamTemplateMember("调研员甲", "按分派的方向调研，结论附出处。"),
            AiTeamTemplateMember("调研员乙", "按分派的方向调研，结论附出处。"),
        ),
    ),
    AiTeamTemplate(
        id = AI_TEAM_BLANK_TEMPLATE_ID,
        name = "空白团队",
        summary = "一位负责人加一位成员，自己写职责",
        instructions = "",
        members = listOf(
            AiTeamTemplateMember("负责人", "", isLeader = true),
            AiTeamTemplateMember("成员", ""),
        ),
    ),
)

fun aiTeamTemplateById(id: String?): AiTeamTemplate? =
    AI_TEAM_TEMPLATES.firstOrNull { it.id == id }

/** 新建成员的缺省执行配置：看板默认配置优先，取不到时用 Claude 结构化标准模式。 */
fun defaultTeamMemberAgent(defaults: BoardTaskAgent? = null): BoardTaskAgent =
    defaults ?: BoardTaskAgent.default()

/**
 * 模板 → 可编辑草稿。成员 `id` 留空由服务端生成；`avatar` 留空按成员 id 取毛色
 * （Android 不渲染头像，不凭空编造毛色序号）。
 */
fun aiTeamTemplateDraft(
    template: AiTeamTemplate,
    agent: BoardTaskAgent? = null,
): AiTeamDraft {
    val memberAgent = defaultTeamMemberAgent(agent)
    return AiTeamDraft(
        name = if (template.id == AI_TEAM_BLANK_TEMPLATE_ID) "" else template.name,
        description = template.summary,
        instructions = template.instructions,
        requirePlanApproval = true,
        maxSteps = AI_TEAM_DEFAULT_MAX_STEPS,
        members = template.members.map { member ->
            AiTeamMember(
                id = "",
                name = member.name,
                duty = member.duty,
                agents = listOf(memberAgent),
                isLeader = member.isLeader,
            )
        },
    )
}

/** 既有团队 → 可编辑草稿（原样带上头像等 Android 不编辑的字段）。 */
fun aiTeamEditableDraft(team: AiTeam): AiTeamDraft = AiTeamDraft.from(team)

// MARK: - 成员增删与负责人

fun newTeamMember(agent: BoardTaskAgent? = null): AiTeamMember = AiTeamMember(
    id = "",
    name = "",
    duty = "",
    agents = listOf(defaultTeamMemberAgent(agent)),
    isLeader = false,
)

/** 加一个成员；到上限原样返回（按钮在界面上同步禁用）。 */
fun addTeamMember(
    members: List<AiTeamMember>,
    agent: BoardTaskAgent? = null,
): List<AiTeamMember> =
    if (members.size >= AI_TEAM_MAX_MEMBERS) members else members + newTeamMember(agent)

/** 删一个成员；下限是 2（服务端同口径）。删掉的若是负责人，由第一位成员顶上，保证恰好一位。 */
fun removeTeamMember(members: List<AiTeamMember>, index: Int): List<AiTeamMember> {
    if (members.size <= AI_TEAM_MIN_MEMBERS || index !in members.indices) return members
    val next = members.filterIndexed { at, _ -> at != index }.toMutableList()
    if (next.none { it.isLeader }) next[0] = next[0].copy(isLeader = true)
    return next
}

/** 设为负责人：团队里恰好一位，其余全部取消。 */
fun setTeamLeader(members: List<AiTeamMember>, index: Int): List<AiTeamMember> {
    if (index !in members.indices) return members
    return members.mapIndexed { at, member -> member.copy(isLeader = at == index) }
}

fun replaceTeamMember(
    members: List<AiTeamMember>,
    index: Int,
    member: AiTeamMember,
): List<AiTeamMember> {
    if (index !in members.indices) return members
    return members.mapIndexed { at, current -> if (at == index) member else current }
}

// MARK: - 执行候选

/** 候选行标题：第一行首选，其余按降级顺序编号（与团队详情的只读行同一套文案）。 */
fun aiTeamCandidateLabel(index: Int): String = aiTeamCandidateRoleLabel(index)

/** 加一个候选：复制末位配置作起点（重复由校验逼着改），到上限原样返回。 */
fun addTeamCandidate(agents: List<BoardTaskAgent>): List<BoardTaskAgent> =
    if (agents.size >= AI_TEAM_MAX_CANDIDATES) {
        agents
    } else {
        agents + (agents.lastOrNull() ?: defaultTeamMemberAgent())
    }

fun setTeamCandidate(
    agents: List<BoardTaskAgent>,
    index: Int,
    agent: BoardTaskAgent,
): List<BoardTaskAgent> =
    if (index in agents.indices) agents.mapIndexed { at, current -> if (at == index) agent else current } else agents

/** 删一个候选：最后一个不许删（每成员至少 1 个候选）。 */
fun removeTeamCandidate(agents: List<BoardTaskAgent>, index: Int): List<BoardTaskAgent> =
    if (agents.size <= 1 || index !in agents.indices) agents else agents.filterIndexed { at, _ -> at != index }

/** 上移 / 下移一位；顺序即降级顺序，越界原样返回。 */
fun moveTeamCandidate(agents: List<BoardTaskAgent>, index: Int, delta: Int): List<BoardTaskAgent> {
    val target = index + delta
    if (index !in agents.indices || target !in agents.indices) return agents
    val next = agents.toMutableList()
    val moved = next[index]
    next[index] = next[target]
    next[target] = moved
    return next
}

/** 与更靠前候选五元组相同的行下标（同一身份口径，服务端 400 的前置检查）。 */
fun duplicateTeamCandidates(agents: List<BoardTaskAgent>): List<Int> {
    val seen = mutableSetOf<String>()
    val duplicates = mutableListOf<Int>()
    agents.forEachIndexed { index, agent ->
        if (!seen.add(boardTaskAgentKey(agent))) duplicates += index
    }
    return duplicates
}

/** 单个成员候选列表自身的问题，空串表示没问题。 */
fun teamCandidateListError(agents: List<BoardTaskAgent>): String = when {
    agents.isEmpty() -> "至少保留 1 个执行候选。"
    agents.size > AI_TEAM_MAX_CANDIDATES -> "执行候选最多 $AI_TEAM_MAX_CANDIDATES 个。"
    duplicateTeamCandidates(agents).isNotEmpty() ->
        "候选「${aiTeamCandidateLabel(duplicateTeamCandidates(agents).first())}」与更靠前的配置相同，改一项即可。"
    else -> ""
}

// MARK: - 步数上限

/** 步数上限夹到 5–200；非法输入回落默认 30（服务端同值域）。 */
fun clampTeamMaxSteps(value: Int?): Int =
    when {
        value == null -> AI_TEAM_DEFAULT_MAX_STEPS
        value < AI_TEAM_MIN_STEPS -> AI_TEAM_MIN_STEPS
        value > AI_TEAM_MAX_STEPS -> AI_TEAM_MAX_STEPS
        else -> value
    }

/** ±步进：一次 5 步，撞到上下限就停住（按钮同步禁用）。 */
fun stepTeamMaxSteps(value: Int, delta: Int): Int = clampTeamMaxSteps(value + delta)

fun canDecreaseTeamMaxSteps(value: Int): Boolean = value > AI_TEAM_MIN_STEPS

fun canIncreaseTeamMaxSteps(value: Int): Boolean = value < AI_TEAM_MAX_STEPS

// MARK: - 草稿校验

fun teamMemberDisplayName(member: AiTeamMember, index: Int): String =
    member.name.trim().ifBlank { "成员 ${index + 1}" }

/**
 * 整份草稿的问题列表（空 = 可以保存），顺序稳定，界面逐行原位显示。
 * 与服务端 `parseAiTeamInput` 同口径：名字 trim 后判长度、名字去重忽略大小写、恰好一位负责人。
 */
fun aiTeamDraftErrors(draft: AiTeamDraft): List<String> {
    val errors = mutableListOf<String>()
    val name = draft.name.trim()
    when {
        name.isEmpty() -> errors += "请填写团队名。"
        name.length > AI_TEAM_NAME_MAX -> errors += "团队名不能超过 $AI_TEAM_NAME_MAX 个字符。"
    }
    if (draft.description.trim().length > AI_TEAM_DESCRIPTION_MAX) {
        errors += "说明不能超过 $AI_TEAM_DESCRIPTION_MAX 个字符。"
    }
    if (draft.instructions.trim().length > AI_TEAM_INSTRUCTIONS_MAX) {
        errors += "协作指令不能超过 $AI_TEAM_INSTRUCTIONS_MAX 个字符。"
    }
    if (draft.maxSteps !in AI_TEAM_MIN_STEPS..AI_TEAM_MAX_STEPS) {
        errors += "步数上限需要是 $AI_TEAM_MIN_STEPS–$AI_TEAM_MAX_STEPS 之间的整数。"
    }
    val members = draft.members
    if (members.size < AI_TEAM_MIN_MEMBERS) errors += "至少要有 $AI_TEAM_MIN_MEMBERS 位成员。"
    if (members.size > AI_TEAM_MAX_MEMBERS) errors += "最多 $AI_TEAM_MAX_MEMBERS 位成员。"
    val leaders = members.count { it.isLeader }
    if (leaders != 1) errors += "负责人要恰好 1 位，当前 $leaders 位。"
    val seenNames = mutableSetOf<String>()
    members.forEachIndexed { index, member ->
        val memberName = member.name.trim()
        when {
            memberName.isEmpty() -> errors += "${teamMemberDisplayName(member, index)}的名字不能为空。"
            memberName.length > AI_TEAM_MEMBER_NAME_MAX ->
                errors += "成员名字不能超过 $AI_TEAM_MEMBER_NAME_MAX 个字符。"
            !seenNames.add(memberName.lowercase()) -> errors += "成员名字「$memberName」重复。"
        }
        if (member.duty.trim().length > AI_TEAM_MEMBER_DUTY_MAX) {
            errors += "成员「${teamMemberDisplayName(member, index)}」的职责不能超过 $AI_TEAM_MEMBER_DUTY_MAX 个字符。"
        }
        val candidateError = teamCandidateListError(member.agents)
        if (candidateError.isNotEmpty()) {
            errors += "${teamMemberDisplayName(member, index)}：$candidateError"
        }
    }
    return errors
}

fun aiTeamDraftCanSave(draft: AiTeamDraft): Boolean = aiTeamDraftErrors(draft).isEmpty()

/** 编辑器标题与提交按钮文案（新建 / 编辑两态）。 */
fun teamEditorTitle(isNew: Boolean): String = if (isNew) "新建团队" else "编辑团队"

fun teamEditorSubmitLabel(isNew: Boolean, saving: Boolean): String = when {
    saving -> "保存中…"
    isNew -> "创建团队"
    else -> "保存"
}
