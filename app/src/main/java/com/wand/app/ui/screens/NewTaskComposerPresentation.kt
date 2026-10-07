package com.wand.app.ui.screens

import com.wand.app.data.GLOBAL_WORKSPACE_ID
import com.wand.app.data.AiTeam
import com.wand.app.data.WorkspaceSessionTarget

/**
 * 侧栏「新建任务」的团队分支纯逻辑（对齐 Web §5.1 入口矩阵 / §6.2 A4）：
 * 可用性判定、必填校验、按钮与图标状态全部集中在这里，Composable 只做渲染。
 */

/** 与其它页面同口径：`团队名（N 人）`（看板新建、团队页同款文案）。 */
internal fun newTaskTeamOptionLabel(team: AiTeam): String =
    "${team.name}（${team.members.size} 人）"

/**
 * R2 口径：只有工作目录命中一个已存在的**非 global** 项目才允许交给团队
 * （`workspaceForPath` 已排除 synthetic 目录组，这里再兜一次 global id）。
 * 不允许时团队不落进选项，配合 newTaskTeamDisabledReason 原位说明原因。
 */
internal fun isTeamPickAllowed(workspaceId: String?): Boolean =
    !workspaceId.isNullOrBlank() && workspaceId != GLOBAL_WORKSPACE_ID

/** 团队区可见（有团队可选）时的禁用说明；允许或没有团队时返回 null。 */
internal fun newTaskTeamDisabledReason(allowed: Boolean, hasTeams: Boolean): String? =
    if (hasTeams && !allowed) "AI 团队需要先选择一个已有项目：这个目录还不是项目。" else null

/**
 * 菜单里的可点团队项（value = teamId）：**禁用态不落可点选项**由这里保证，
 * 渲染层（NewTaskComposerScreen）只遍历这个结果。allowed 由调用方用
 * isTeamPickAllowed(workspaceId) 判定。
 */
internal fun newTaskTeamOptions(
    teams: List<AiTeam>,
    allowed: Boolean,
): List<Pair<String, String>> =
    if (allowed) teams.map { it.id to newTaskTeamOptionLabel(it) } else emptyList()

/**
 * null = 可以提交。选中团队必须满足 R2（`teamAllowed` = isTeamPickAllowed(workspaceId)，
 * 由调用方判定后传入），且必须填「内容」——团队没有目标（标题+描述拼不出 run）就没意义。
 */
internal fun newTaskTeamSubmitError(
    prompt: String,
    teamSelected: Boolean,
    teamAllowed: Boolean,
): String? = when {
    !teamSelected -> null
    !teamAllowed -> "AI 团队需要先选择一个已有项目，请改回已有项目的目录后再交给团队。"
    prompt.isBlank() -> "交给团队需要先填写任务内容。"
    else -> null
}

/**
 * 重试态 = 上一轮「workspace task 已建好、但没派成团队」记下的状态非空。
 * 只有**团队分支**的重试才免建卡；CLI 分支的 id 不能跨目标复用（会往不相干的老卡上派活）。
 */
internal fun newTaskTeamRetryActive(
    teamSelected: Boolean,
    retry: NewTaskTeamRetry?,
): Boolean = teamSelected && retry != null

/**
 * 团队分支「差最后一步」的重试态，两种 id 各归各位、不许混用：
 * `workspaceTaskId` 是导航要的 workspace task id（`Screen.WorkspaceTask`），
 * `boardCardId` 是 `POST /api/wand-tasks/{id}/team-runs` 唯一认的看板卡 id。
 * 只建出 workspace task、还没解析到卡时 `boardCardId` 为 null，重试只补找卡这一步。
 */
internal data class NewTaskTeamRetry(
    val workspaceTaskId: String,
    val boardCardId: String? = null,
)

/**
 * 提交分叉总闸（纯函数）：true = 本轮要调 `createTask`。
 * 重试态（retry != null）恒 false —— 这是「团队重试绝不重复建卡」的唯一判定，
 * handler 里 `if (needCreate)` 是它唯一的分支来源。CLI 分支的父任务补关联复用走自己的式子
 * （`pendingParentLink?.first ?: …`），不经这道闸，两套语义各管各的。
 */
internal fun newTaskNeedsCardCreation(retry: NewTaskTeamRetry?): Boolean = retry == null

/** 找不到看板卡时只走对话框原有的原位错误位，不发注定 404 的请求。 */
internal fun newTaskTeamCardMissingMessage(detail: String?): String =
    "任务已创建，但暂时找不到对应的看板任务，无法交给团队：${detail ?: "可点「重试交给团队」再试一次"}"

/**
 * 「换工具即重置 CLI 参数」只对**真的换了目标**成立。派发对象菜单里再点一次当前目标
 * （团队 ⇄ 同一个 CLI 往返就是这个形状）不该清掉用户手选的模型/思考深度：
 * 团队分支根本不读这两个值，切回来没有重置的理由。
 */
internal fun newTaskTargetChangeResetsCliParams(
    previous: WorkspaceSessionTarget?,
    next: WorkspaceSessionTarget,
): Boolean = previous != next

/**
 * 新建任务表单在三种状态下显示哪些会话参数行（选择器可开性同源）：
 * 团队态 / 仅建分组 → 全收（团队不读 CLI 参数，仅建分组没有会话可配）；
 * 空白终端只配会话类型；CLI + 启动会话 → 类型 + 模型 + 思考深度。
 */
internal enum class NewTaskComposerControlChip { SessionKind, Model, ThinkingEffort }

internal fun newTaskComposerControlChips(
    teamSelected: Boolean,
    startFirstSession: Boolean,
    shellTarget: Boolean,
): List<NewTaskComposerControlChip> = when {
    teamSelected || !startFirstSession -> emptyList()
    shellTarget -> listOf(NewTaskComposerControlChip.SessionKind)
    else -> listOf(
        NewTaskComposerControlChip.SessionKind,
        NewTaskComposerControlChip.Model,
        NewTaskComposerControlChip.ThinkingEffort,
    )
}

/** 常驻设置行说明的是当前选择的结果，不再让用户从加号里的反向动作猜状态。 */
internal fun newTaskStartSessionDescription(enabled: Boolean): String =
    if (enabled) "建卡后直接进入会话" else "只创建任务，稍后再开始"

/**
 * 「没有指定任务的会话不建卡」（纯函数）：任务名留空、这一轮要直接起会话、又不挂父任务时，
 * 会话只带目录归属落进侧栏「未分组任务」；要成卡由用户在那一行「归纳为新任务」。
 * 团队与临时派工要的就是任务卡，由宿主各自的式子决定，不经这道闸。
 */
internal fun newTaskStartsUngroupedSession(
    name: String,
    startFirstSession: Boolean,
    linkedToParent: Boolean,
): Boolean = name.isBlank() && startFirstSession && !linkedToParent

/** 未分组开工下那行开关的标题与说明：不建卡，也不许再暗示「建卡后…」。 */
internal fun newTaskUngroupedStartLabel(): String = "直接开工，不建任务卡"

internal fun newTaskUngroupedStartDescription(): String =
    "会话先落在该目录的「未分组任务」里，需要时再归纳成新任务"

/** 未分组开工时工作目录只做归属，没有任务可挂独立工作树，所以这行整条收起。 */
internal fun newTaskUngroupedStatusLine(): String =
    "不建任务卡 · 会话直接使用所选工作目录，先进「未分组任务」"

internal fun newTaskUngroupedActionLabel(busy: Boolean): String =
    if (busy) "正在启动会话…" else "开始会话"

internal fun newTaskWorktreeDescription(enabled: Boolean): String =
    if (enabled) "在独立目录中工作，隔离当前修改" else "直接使用所选工作目录"

/**
 * 底部提交按钮上方的摘要：团队态不得出现「会话/启动会话」语义，
 * 且要如实反映工作树状态。
 * CLI 态沿用原文案。
 */
internal fun newTaskComposerStatusLine(
    teamSelected: Boolean,
    startFirstSession: Boolean,
    worktree: Boolean,
): String {
    val tree = if (worktree) "独立工作树" else "共用工作区"
    return if (teamSelected) "建卡后立即交给团队开工 · $tree"
    else (if (startFirstSession) "创建后启动会话" else "仅创建任务分组") + " · $tree"
}

/**
 * 提交按钮语义（纯函数）：`submitting` = 已经点下、正在跑「建卡 → 交给团队」链路。
 * retry = 上一步「建卡成功但交给团队失败」，再点只重发 POST /api/wand-tasks/{id}/team-runs。
 */
internal fun newTaskTeamActionLabel(
    submitting: Boolean,
    retry: Boolean,
    hasPrompt: Boolean,
): String = when {
    submitting -> "正在交给团队…"
    retry && hasPrompt -> "重试交给团队"
    else -> "创建并交给团队"
}

/** 收起高级设置时只投影本次真正生效的配置；不把隐藏的 CLI / worktree 值当执行事实。 */
internal fun newTaskMoreSettingsSummary(
    name: String,
    ungroupedStart: Boolean,
    worktree: Boolean,
    startFirstSession: Boolean,
    namedSubjectSelected: Boolean,
    kindLabel: String,
    modelLabel: String,
    effortLabel: String,
    shellTarget: Boolean,
    parentLabel: String?,
): String = buildList {
    add(if (ungroupedStart) "直接开始会话，不建卡" else name.trim().ifEmpty { "自动命名" })
    if (!ungroupedStart) add(if (worktree) "独立工作树" else "共用目录")
    if (!namedSubjectSelected) {
        if (!startFirstSession) add("仅建分组")
        else {
            add(kindLabel)
            if (!shellTarget) {
                add(modelLabel.ifBlank { "默认模型" })
                add("思考 · $effortLabel")
            }
        }
    }
    parentLabel?.takeIf { it.isNotBlank() }?.let { add("归属 · $it") }
}.joinToString(" · ")
