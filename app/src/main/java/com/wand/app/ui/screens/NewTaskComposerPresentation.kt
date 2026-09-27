package com.wand.app.ui.screens

import com.wand.app.data.GLOBAL_WORKSPACE_ID
import com.wand.app.data.AiTeam

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
