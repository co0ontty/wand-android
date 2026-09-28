package com.wand.app.ui.screens

import com.wand.app.data.AiTeamLiveStep
import com.wand.app.data.AiTeamStep
import com.wand.app.data.ConversationTurn
import com.wand.app.data.ContentBlock
import com.wand.app.data.ModelsResponse
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.aiTeamRunActive
import com.wand.app.data.boardAgentModelName
import com.wand.app.data.boardTaskProviderLabel
import kotlin.math.roundToInt

/**
 * 团队群聊页的纯函数（可单测，不含 Compose 状态）。
 * 口径真源是 Web 的 `src/web-ui/react/ai-teams/team-chat-view.tsx`：
 * 同一条服务端回合，两端必须解析成同一个角色 / 同一段正文，否则两端消息长相会漂移。
 */

/** 够长才折叠，短报告直接铺开，别让展开按钮自己变成噪音（对齐 Web needsCollapse）。 */
const val CHAT_COLLAPSE_AFTER_LINES = 6
const val CHAT_COLLAPSE_AFTER_CHARS = 420

/** 成员头像色板长度（变体下标取模用）。 */
const val MEMBER_AVATAR_VARIANTS = 6

fun chatTurnText(turn: ConversationTurn): String = turn.content
    .filterIsInstance<ContentBlock.Text>()
    .map { it.text.trim() }
    .filter { it.isNotEmpty() }
    .joinToString("\n")

/** 群聊里一条发言的角色：决定它排在哪一层、长什么样。 */
enum class TeamChatTurnKind { Notice, User, Leader, Step }

fun chatTurnKind(turn: ConversationTurn): TeamChatTurnKind = when {
    turn.notice -> TeamChatTurnKind.Notice
    turn.role == "user" -> TeamChatTurnKind.User
    turn.author?.leader == true -> TeamChatTurnKind.Leader
    else -> TeamChatTurnKind.Step
}

/** 服务端给成员报告加的前缀：`✅ 完成「T1 …」` / `❌ 没完成「T1 …」`。 */
private val STEP_REPORT_MARK = Regex("^(✅ 完成|❌ 没完成)「(.+?)」\\s*")

data class TeamStepReport(
    val ok: Boolean,
    val title: String,
    /** 去掉前缀之后的报告正文。 */
    val body: String,
)

fun parseStepReport(text: String): TeamStepReport? {
    val trimmed = text.trim()
    val match = STEP_REPORT_MARK.find(trimmed) ?: return null
    return TeamStepReport(
        ok = match.groupValues[1] == "✅ 完成",
        title = match.groupValues[2],
        body = trimmed.substring(match.value.length),
    )
}

/** 负责人派工那几行：`1. **@实现者** T1 类型与存储迁移（等第 1 项完成后）`。 */
private val ASSIGN_LINE = Regex("^\\d+\\.\\s*\\*\\*@(.+?)\\*\\*\\s*(.+)$")
private val ASSIGN_WAIT = Regex("^(.+?)（(等第.+?)）$")

data class TeamAssignment(
    val member: String,
    val title: String,
    /** 「等第 1、2 项完成后」这类等待说明；没有就是空串。 */
    val wait: String,
)

data class TeamLeaderMessage(
    /** 负责人自己的那段话（去掉派工清单）。 */
    val head: String,
    val assignments: List<TeamAssignment>,
)

/** 把负责人的发言拆成「说明」+「派工清单」，好把主任务渲染成公告卡而不是一坨文字。 */
fun splitLeaderMessage(text: String): TeamLeaderMessage {
    val assignments = mutableListOf<TeamAssignment>()
    val head = mutableListOf<String>()
    for (line in text.trim().split("\n")) {
        val match = ASSIGN_LINE.find(line.trim())
        if (match == null) {
            head.add(line)
            continue
        }
        val rawTitle = match.groupValues[2]
        val wait = ASSIGN_WAIT.find(rawTitle)
        assignments.add(
            if (wait == null) TeamAssignment(match.groupValues[1], rawTitle, "")
            else TeamAssignment(match.groupValues[1], wait.groupValues[1], wait.groupValues[2]),
        )
    }
    return TeamLeaderMessage(head.joinToString("\n").trim(), assignments)
}

/**
 * 群聊输入栏发送 / 停止的形态（对齐 Web `teamChatComposerMode`、普通会话同一位置）。
 * 运行中且没有草稿 → 这枚按钮就是停止；有草稿 → 发送，并在左侧再放一枚停止。
 */
enum class TeamChatComposerMode { Send, Stop, SendAndStop, Blocked }

fun teamChatComposerMode(
    status: String,
    hasDraft: Boolean,
    sending: Boolean = false,
): TeamChatComposerMode {
    // 发送会先清掉输入框：这一拍不能把按钮收成「停止」，否则发送中的形态会闪掉。
    if (sending) {
        return if (aiTeamRunActive(status)) TeamChatComposerMode.SendAndStop else TeamChatComposerMode.Send
    }
    val active = aiTeamRunActive(status)
    return when {
        active && hasDraft -> TeamChatComposerMode.SendAndStop
        active -> TeamChatComposerMode.Stop
        hasDraft -> TeamChatComposerMode.Send
        else -> TeamChatComposerMode.Blocked
    }
}

/**
 * 输入框引导语：状态决定这句话，用户才知道自己发的东西会被谁、什么时候看到。
 * `running` 是插话、`awaiting_approval` 是批准 / 修改意见、终态是开新一轮。
 */
fun chatInputHint(status: String): String = when (status) {
    "awaiting_approval" -> "回复『批准』即开工，其他内容会作为修改意见转给负责人"
    "waiting_user" -> "回复负责人"
    "running" -> "将作为插话，负责人下一轮看到"
    "done", "stopped", "failed" -> "发消息会接着这一轮的进度开新一轮"
    else -> ""
}

fun needsCollapse(text: String): Boolean =
    text.length > CHAT_COLLAPSE_AFTER_CHARS || text.split("\n").size > CHAT_COLLAPSE_AFTER_LINES

/**
 * 收起态实际显示的那一段。Web 用 CSS 行夹取，Compose 没有等价物，
 * 所以这里按同一套阈值（6 行 / 420 字）裁，保证两端「收起时看到多少」一致。
 */
fun collapsedPreview(text: String): String {
    val byLines = text.lineSequence().take(CHAT_COLLAPSE_AFTER_LINES).joinToString("\n")
    if (byLines.length <= CHAT_COLLAPSE_AFTER_CHARS) return byLines
    return byLines.take(CHAT_COLLAPSE_AFTER_CHARS).trimEnd() + "…"
}

/**
 * 步骤状态芯片的口径（对齐 Web StepTurn）：优先用运行里那条 work 步骤的真实状态，
 * 步骤已被截掉或与标题不完全一致时，退回报告前缀本身的成 / 败。
 */
fun teamStepStatus(steps: List<AiTeamStep>, report: TeamStepReport?): String? {
    if (report == null) return null
    val step = steps.firstOrNull { it.kind == "work" && it.title == report.title }
    return step?.status ?: if (report.ok) "done" else "failed"
}

/** 乐观临时行：发送先留在原位，服务端回包里出现同一条 user turn 才撤（对齐 Web LocalChatTurn）。 */
data class LocalChatTurn(
    val text: String,
    /** 本地发送时刻（毫秒），用来和服务端 `createdAt` 粗比。 */
    val sentAtMillis: Long,
    /** 重拉失败时为真：内容留着，但标成「未确认」。 */
    val unconfirmed: Boolean = false,
)

fun turnEpochMillis(turn: ConversationTurn): Long? {
    val raw = turn.completedAt?.takeIf { it.isNotBlank() } ?: turn.createdAt?.takeIf { it.isNotBlank() }
        ?: return null
    return runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()
}

/** 服务端回包里没有这条 user turn，就按发送时刻判定重拉是否覆盖了它。 */
fun isConfirmedBy(turn: ConversationTurn, sentAtMillis: Long): Boolean {
    if (turn.role != "user") return false
    val at = turnEpochMillis(turn) ?: return false
    return at >= sentAtMillis
}

/**
 * 发送之后调一次：`turns` 为 null 表示重拉失败，临时行留着并标「未确认」；
 * 否则把服务端已经回显的那几条撤掉，剩下的继续等下一次重拉。
 */
fun settleLocalTurns(local: List<LocalChatTurn>, turns: List<ConversationTurn>?): List<LocalChatTurn> =
    if (turns == null) local.map { it.copy(unconfirmed = true) }
    else local.filter { row -> turns.none { isConfirmedBy(it, row.sentAtMillis) } }

/**
 * 会话行 → 群聊运行 id：服务端摘要没带 `teamChat`（老服务端）或缺 runId 时返回 null，
 * 调用方按普通会话打开，不猜、不崩。
 */
fun groupChatRunId(session: WorkspaceSessionSummary): String? =
    session.teamChat?.runId?.takeIf { it.isNotBlank() }

/** 成员头像变体：按 id / 名字 / 头像 key 散列，同一成员在任何一条消息上颜色一致。 */
fun memberAvatarVariant(authorId: String?, name: String, avatar: String?): Int {
    val seed = "${authorId.orEmpty()}#$name#${avatar.orEmpty()}"
    return (seed.hashCode() and Int.MAX_VALUE) % MEMBER_AVATAR_VARIANTS
}

// ---------- 正在输出的成员（§4.9 live 卡片，口径逐条对齐 Web team-chat-view.tsx） ----------

/**
 * live 卡片「贴尾」阈值（**dp**）：数值与 Web 的 `LIVE_TAIL_PX = 24` 相同，单位不同。
 * Web 侧 scrollTop/scrollHeight/clientHeight 本来就是同一个 CSS px，阈值直接可比；
 * Android 侧滚动几何是设备像素，density 2.625 的屏上 24px 只有 9dp，同一句话在两端口径就漂了。
 * 所以这里按 dp 记，用 [liveTailThresholdPx] 换算成像素再和几何比。
 */
const val LIVE_TAIL_DP = 24

/** dp → 设备像素（四舍五入）。density 由调用点从 `LocalDensity` 取，纯函数不碰运行时。 */
fun liveTailThresholdPx(density: Float, thresholdDp: Int = LIVE_TAIL_DP): Int =
    (thresholdDp * density).roundToInt()

/** 文本还没来时的占位，卡片不能是个空框。 */
const val LIVE_EMPTY_TEXT = "已开始，等待第一段输出…"

/** 状态芯片文案；未知状态不给芯片（步骤芯片已经说明它在哪一步）。 */
fun liveStateLabel(state: String?): String = when (state) {
    "working" -> "工作中"
    "needs_input" -> "等待回答"
    "needs_permission" -> "等待授权"
    "done" -> "已完成"
    "failed" -> "失败"
    else -> ""
}

/** 顶部省略提示；没截断就不显示。 */
fun liveOmittedText(omittedChars: Int): String = if (omittedChars > 0) "已省略前面 $omittedChars 字" else ""

/**
 * 距底够近才算贴尾：用户上滚看历史以后不许把他拽回尾部。
 * 与 Web 同参数（scrollTop / scrollHeight / clientHeight），Android 侧从
 * `verticalScroll` 的滚动位置与实测高度（设备像素）取；阈值由调用点按 density 换算好传进来。
 */
fun shouldFollowTail(scrollTop: Int, scrollHeight: Int, clientHeight: Int, thresholdPx: Int): Boolean =
    scrollHeight - (scrollTop + clientHeight) <= thresholdPx

/** 按 seq 升序、按 stepId 去重：轮询重叠或乱序都不会让同一行出现两次。 */
fun orderLiveSteps(steps: List<AiTeamLiveStep>): List<AiTeamLiveStep> =
    steps.distinctBy { it.stepId }.sortedBy { it.seq }

/** 一行 live 输出；`leaving` 是这一步已经收工、正在原位收回。 */
data class LiveChatRow(
    val step: AiTeamLiveStep,
    val leaving: Boolean,
)

/**
 * 新一批 live 来了：本次还在输出的进来，上一批里消失的（含**已经在退场的**）标成退场。
 * **排序只看 seq，与这一行是否在退场无关**（同 Web `mergeLiveRows`）：把没播完的退场行搬到
 * 它「该在的新位置」会让行在列表里跳一下、动画重播，所以退场行一律留在原位。
 * 退场行不再被下一次合并摘掉——那样会把没播完的收工动画腰斩，
 * 摘除只由该行自己的退场计时结束（`TeamLiveStepRow` 的 `onRetire`，时长取 `WandMotion.fast`
 * 那一档，等价于 Web 的 `animationend` + 等长兜底定时器）负责。
 * 同一 `stepId` 重新开工时 `kept` 命中，自然回到 `leaving = false`，不会留下幽灵卡。
 */
fun mergeLiveRows(current: List<LiveChatRow>, steps: List<AiTeamLiveStep>): List<LiveChatRow> {
    val next = orderLiveSteps(steps)
    val kept = next.map { it.stepId }.toSet()
    // 已经在退场的行继续留着：下一次轮询只是「又来了一批文本」，不该顺手撤掉没收完的卡。
    val leaving = current.filter { it.step.stepId !in kept }.map { it.copy(leaving = true) }
    return (next.map { LiveChatRow(it, false) } + leaving).sortedBy { it.step.seq }
}

/** 步骤芯片：`#seq 标题`，标题查的是本次运行的那条步骤。 */
fun liveStepChip(step: AiTeamLiveStep, steps: List<AiTeamStep>): String {
    val title = steps.firstOrNull { it.id == step.stepId }?.title.orEmpty()
    return if (title.isBlank()) "#${step.seq}" else "#${step.seq} $title"
}

// ---------- 署名「CLI · 模型 · 思考深度」（口径逐条对齐 Web agentSignatureLabel） ----------

/** 旧四档思考深度；与 Web `ISSUE_AGENT_EFFORTS` 逐字一致。 */
private val TEAM_EFFORT_LABELS = mapOf(
    "off" to "关闭",
    "standard" to "标准",
    "deep" to "深入",
    "max" to "最大",
)

/** CLI 自己报出来的原生档位（`provider:level` 里的 level）；与 Web `COMPACT_LABELS` 逐字一致。 */
private val TEAM_COMPACT_THINKING_LABELS = mapOf(
    "auto" to "自动",
    "none" to "关闭",
    "minimal" to "最低",
    "low" to "低",
    "medium" to "中",
    "high" to "高",
    "xhigh" to "超高",
    "max" to "极高",
    "ultra" to "极限",
    "ultracode" to "极限代码",
)

/** 思考深度中文标签：先查旧四档，查不到就剥掉 `provider:` 前缀按原生档位读。 */
fun teamAgentEffortLabel(effort: String): String {
    TEAM_EFFORT_LABELS[effort]?.let { return it }
    if (effort.isBlank()) return "关闭"
    val native = if (effort.contains(':')) effort.substringAfter(':') else effort
    return TEAM_COMPACT_THINKING_LABELS[native] ?: native
}

/**
 * 「CLI · 模型 · 思考深度」，群聊三处署名（live 卡头部、成员步骤行、负责人行）共用。
 * provider 走 `boardTaskProviderLabel`（与 Web `ISSUE_AGENT_PROVIDERS` 同表），
 * 思考深度走 [teamAgentEffortLabel]（与 Web `ISSUE_AGENT_EFFORTS` + `compactThinkingLabel` 同表），
 * `model` 等于 `BOARD_AGENT_DEFAULT_MODEL`（"default"）时是「跟随服务端默认」的哨兵值、不是模型名：
 * 传了模型目录就换成服务端默认模型的具体名字，拿不到名字才整段不显示。
 * 哪一段缺就少一段，所以不会出现 `null`、空串或多余的「 · 」。
 */
fun agentSignatureLabel(
    provider: String?,
    model: String?,
    thinkingEffort: String?,
    models: ModelsResponse? = null,
): String {
    val parts = mutableListOf<String>()
    provider?.takeIf { it.isNotBlank() }?.let { parts.add(boardTaskProviderLabel(it)) }
    val modelName = boardAgentModelName(models, provider.orEmpty(), model)
    if (modelName.isNotBlank()) parts.add(modelName)
    val trimmedEffort = thinkingEffort?.trim().orEmpty()
    if (trimmedEffort.isNotEmpty()) parts.add(teamAgentEffortLabel(trimmedEffort))
    return parts.joinToString(" · ")
}

// ---------- 外层群聊列表的贴底口径（同 Web isFollowingTail） ----------

/**
 * 外层列表此刻是否贴底：只有最后一个条目已经滚到视口末尾、且距底不超过 [thresholdPx] 才算。
 * 与卡片内滚共用 [LIVE_TAIL_DP] 一个阈值（都经 [liveTailThresholdPx] 换算），两处不会漂到不同值。
 * `distancePx` 传 null（还没测量到任何条目）时按贴底处理，首屏要停在最新一条上。
 */
fun listFollowsTail(distancePx: Int?, thresholdPx: Int): Boolean =
    distancePx == null || distancePx <= thresholdPx

/**
 * 列表距底还有多少设备像素：最后一个条目没进视口就是「远不止一个条目」（返回一个大数），
 * 进了就按「视口底 - 该条目底 - contentPadding.bottom」算，可为负（最后一条已经滚过视口底）。
 * 不扣 [bottomPaddingPx] 的话，**手动滚到底那一次会被判成不跟随**：滚到底时条目底部离视口底部
 * 正好差一个 contentPadding（14dp 在 density 2.625 上是 37px），比阈值还大。
 *
 * [viewportEndOffset] 直接用 `LazyListLayoutInfo.viewportEndOffset`，**不要**再拿
 * `viewportEndOffset - viewportStartOffset` 自算视口高度：Compose 里条目 offset 与两个 viewport
 * 边界同在「content 顶边」这个原点（`viewportStartOffset = -paddingTop`、
 * `viewportEndOffset = 视口高 - paddingTop`），自算高度将多出一整个 paddingTop，条目底却是按
 * content 顶边量的 —— 于是 12dp 顶部 padding 混进距离里（density 2.625 上 31px），
 * 24dp 的跟随窗口实际只剩 12dp。
 */
fun listTailDistancePx(
    lastVisibleIndex: Int,
    totalItemsCount: Int,
    lastVisibleOffset: Int,
    lastVisibleHeight: Int,
    viewportEndOffset: Int,
    bottomPaddingPx: Int,
): Int {
    if (totalItemsCount <= 0) return 0
    if (lastVisibleIndex < totalItemsCount - 1) return Int.MAX_VALUE / 2
    return viewportEndOffset - (lastVisibleOffset + lastVisibleHeight) - bottomPaddingPx
}

/**
 * 外层群聊列表的贴底判定：几何（设备像素）+ contentPadding（dp）+ density 进，布尔出，
 * 单位在这一处对齐。调用点与单测走同一个函数，免得「单测测纯函数、调用点自己拼」两头漂。
 */
fun listPinnedFromLayout(
    lastVisibleIndex: Int,
    totalItemsCount: Int,
    lastVisibleOffset: Int,
    lastVisibleHeight: Int,
    viewportEndOffset: Int,
    contentPaddingBottomDp: Float,
    density: Float,
): Boolean = listFollowsTail(
    listTailDistancePx(
        lastVisibleIndex = lastVisibleIndex,
        totalItemsCount = totalItemsCount,
        lastVisibleOffset = lastVisibleOffset,
        lastVisibleHeight = lastVisibleHeight,
        viewportEndOffset = viewportEndOffset,
        bottomPaddingPx = (contentPaddingBottomDp * density).roundToInt(),
    ),
    thresholdPx = liveTailThresholdPx(density),
)
