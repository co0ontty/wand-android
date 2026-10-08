package com.wand.app.ui.screens

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.wand.app.ByteSizeFormatter
import com.wand.app.ByteSizeUnit
import com.wand.app.data.AiTeamMember
import com.wand.app.data.AiTeamRunDetail
import com.wand.app.data.AiTeamRun
import com.wand.app.data.AiTeamStep
import com.wand.app.data.ConversationTurn
import com.wand.app.data.TurnAuthor
import com.wand.app.data.ContentBlock
import com.wand.app.data.ModelsResponse
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.isDefiniteRequestRejection
import com.wand.app.data.aiTeamRunActive
import com.wand.app.data.boardAgentModelName
import com.wand.app.data.boardTaskAgentLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 团队群聊页的纯函数（可单测，不含 Compose 状态）。
 * 口径真源是 Web 的 `src/web-ui/react/ai-teams/team-chat-view.tsx`：
 * 同一条服务端回合，两端必须解析成同一个角色 / 同一段正文，否则两端消息长相会漂移。
 */

/** 够长才折叠，短报告直接铺开，别让展开按钮自己变成噪音（对齐 Web needsCollapse）。 */
const val CHAT_COLLAPSE_AFTER_LINES = 6
const val CHAT_COLLAPSE_AFTER_CHARS = 420

/** 仅渲染时换署名/头像；不要让改名污染消息指纹、正文与 ACK 匹配。 */
fun displayTeamTurn(turn: ConversationTurn, detail: AiTeamRunDetail): ConversationTurn {
    val author = turn.author ?: return turn
    val member = detail.presentationTeam?.members?.find { it.id == author.id } ?: return turn
    if (member.name == author.name && member.avatar == author.avatar) return turn
    return turn.copy(author = author.copy(name = member.name, avatar = member.avatar))
}

fun chatTurnText(turn: ConversationTurn): String = turn.content
    .filterIsInstance<ContentBlock.Text>()
    .map { it.text.trim() }
    .filter { it.isNotEmpty() }
    .joinToString("\n")

/** 系统事件用一行读完；已有作者前缀的服务端正文不能再重复署名。 */
fun teamNoticeLine(turn: ConversationTurn): String {
    val body = chatTurnText(turn).replace(Regex("\\s+"), " ").trim()
    if (body.isEmpty()) return ""
    val author = turn.author?.name?.trim().orEmpty()
    return if (author.isEmpty() || body.startsWith(author)) body else "$author $body"
}

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

fun teamReportFileSize(size: Long): String =
    ByteSizeFormatter.format(size, java.util.Locale.ROOT, ByteSizeUnit.Megabytes)

/** 负责人派工那几行：`1. **@实现者** T1 类型与存储迁移（依据：第 1 步「设计规格」的产物）`。 */
private val ASSIGN_LINE = Regex("^\\d+\\.\\s*\\*\\*@(.+?)\\*\\*\\s*(.+)$")
/** 新数据（S5）：`（依据：第 N 步「标题」的产物）`。 */
private val ASSIGN_BASIS = Regex("^(.+?)（(依据：.+?)）$")
/** 旧数据：`（等第 1 项完成后）` —— 保留解析，同一个槽位展示，旧运行仍可读。 */
private val ASSIGN_WAIT = Regex("^(.+?)（(等第.+?)）$")

data class TeamAssignment(
    val member: String,
    val title: String,
    /** 括注内容（依据 / 旧等待说明）；两者都不在就是空串。字段由 v1 的 `wait` 改名而来。 */
    val note: String,
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
        val note = ASSIGN_BASIS.find(rawTitle) ?: ASSIGN_WAIT.find(rawTitle)
        assignments.add(
            if (note == null) TeamAssignment(match.groupValues[1], rawTitle, "")
            else TeamAssignment(match.groupValues[1], note.groupValues[1], note.groupValues[2]),
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
 * 收起态实际显示的那一段 = 「上半部分」：前 6 行且不超过 420 字，被截断一定以 `…` 结尾。
 * 与 Web `collapsedPreview` 同算法，所以两端「收起时看到多少」逐字相同；
 * 正因为行数由这里定，UI 不再叠一层 maxLines 截断（两套截断会互相打架）。
 */
fun collapsedPreview(text: String): String {
    val lines = text.split("\n")
    var kept = lines.take(CHAT_COLLAPSE_AFTER_LINES).joinToString("\n")
    var truncated = lines.size > CHAT_COLLAPSE_AFTER_LINES
    if (kept.length > CHAT_COLLAPSE_AFTER_CHARS) {
        kept = kept.take(CHAT_COLLAPSE_AFTER_CHARS).trimEnd()
        truncated = true
    }
    return if (truncated) "$kept…" else kept
}

/** 「我」：用户自己的发言没有成员身份，署名固定用这个词（两端同文案）。 */
const val CHAT_SELF_NAME = "我"

/** 正文为空的发言也要占住气泡/文档卡，不能变成一个空气泡。 */
const val CHAT_EMPTY_BODY = "（这条消息没有正文）"

/** 超长正文的展开入口；它是覆盖层入口，不是原位展开，所以只有这一个状态。 */
const val CHAT_EXPAND_LABEL = "点击展开"

/**
 * 消息形态分流（设计 §2.2，与 Web `teamChatMessageShape` 同名同规则）：
 * 系统提示行居中、自己的发言走气泡、超阈值正文或负责人派工清单走全宽文档卡。
 * 「文档性质」的可判定定义就是后两条。
 */
enum class TeamChatMessageShape { Notice, Bubble, Document }

fun teamChatMessageShape(
    kind: TeamChatTurnKind,
    text: String,
    assignmentCount: Int = 0,
): TeamChatMessageShape = when {
    kind == TeamChatTurnKind.Notice -> TeamChatMessageShape.Notice
    kind == TeamChatTurnKind.User -> TeamChatMessageShape.Bubble
    needsCollapse(text) -> TeamChatMessageShape.Document
    assignmentCount > 0 -> TeamChatMessageShape.Document
    else -> TeamChatMessageShape.Bubble
}

/** 弹层副标题里的类型文案（设计 §6.2 的同一张表；只有 user / leader / step 会开弹层）。 */
fun teamChatDocTypeLabel(kind: TeamChatTurnKind, reportTitle: String? = null): String = when (kind) {
    TeamChatTurnKind.Step -> reportTitle?.takeIf { it.isNotBlank() }
        ?.let { "成员报告 · $it" } ?: "成员发言"
    TeamChatTurnKind.Leader -> "负责人派工"
    TeamChatTurnKind.User -> "我的消息"
    // 系统提示行只有一个居中弱化形态，不开全文弹层；真到这里也给一句话，不崩。
    TeamChatTurnKind.Notice -> "系统提示"
}

// ---------- @ 流转（R2：只装饰，无源文改写；与 Web 完整源文边界一致） ----------

private const val MENTION_BOUNDARY_CHARS = "（(、「【《，,。；;：:！!？?"
private const val MENTION_END_CHARS = "（(、「【《，,。；;：:！!？?）)」】》、.]}"
private val MENTION_WRAPPERS = listOf("**", "__", "~~", "*", "_")

/** 在完整源文找候选，再把合格区间投到预览；绝不把半个长名误认成短别名。 */
fun mentionRanges(
    text: String,
    names: List<String>,
    source: String = text,
    protectedRanges: List<IntRange> = emptyList(),
    pairedWrappers: Boolean = true,
): List<IntRange> {
    val candidates = names.map(String::trim).filter(String::isNotEmpty).distinct().sortedByDescending(String::length)
    if (text.isEmpty() || candidates.isEmpty()) return emptyList()
    val preview = source != text && text.endsWith("…") && source.startsWith(text.dropLast(1))
    val cutoff = if (preview) text.length - 1 else text.length
    val ranges = mutableListOf<IntRange>()
    var index = 0
    while (index < source.length) {
        val before = source.getOrNull(index - 1)
        val boundary = before == null || before.isWhitespace() || before in MENTION_BOUNDARY_CHARS
        val wrapper = if (boundary && pairedWrappers) MENTION_WRAPPERS.firstOrNull {
            source.startsWith("$it@", index)
        } else null
        val at = if (wrapper != null) index + wrapper.length else index
        if (boundary && source.getOrNull(at) == '@') {
            val hit = candidates.firstOrNull { source.startsWith(it, at + 1) }
            if (hit != null) {
                val end = at + 1 + hit.length
                val closed = wrapper != null && source.startsWith(wrapper, end)
                val tokenEnd = if (closed) end + wrapper!!.length else end
                val endBoundary = source.getOrNull(tokenEnd)?.let {
                    it.isWhitespace() || it in MENTION_END_CHARS
                } ?: true
                if ((wrapper == null || closed) && endBoundary && tokenEnd <= cutoff
                    && protectedRanges.none { it.first <= end - 1 && it.last >= at }
                ) {
                    ranges.add(at until end)
                    index = tokenEnd
                    continue
                }
            }
        }
        index++
    }
    return ranges
}

/** AnnotatedString 的文字与内部空格、原有样式/注解不变，只叠品牌底色。 */
fun mentionAnnotatedText(text: String, names: List<String>, style: SpanStyle, source: String = text): AnnotatedString =
    buildAnnotatedString {
        append(text)
        mentionRanges(text, names, source).forEach { addStyle(style, it.first, it.last + 1) }
    }

/** Markdown 输出坐标上装饰；保护代码、转义 @、链接，并保持原 Bold/SemiBold 字重。 */
fun decorateTeamMarkdown(
    value: AnnotatedString,
    names: List<String>,
    protectedRanges: List<IntRange>,
    style: SpanStyle,
): AnnotatedString {
    val ranges = mentionRanges(value.text, names, protectedRanges = protectedRanges, pairedWrappers = false)
    if (ranges.isEmpty()) return value
    return AnnotatedString.Builder(value).apply {
        for (range in ranges) {
            val strong = value.spanStyles.any { it.start < range.last + 1 && it.end > range.first &&
                (it.item.fontWeight?.weight ?: 0) >= androidx.compose.ui.text.font.FontWeight.SemiBold.weight }
            addStyle(if (strong) style.copy(fontWeight = null) else style, range.first, range.last + 1)
        }
    }.toAnnotatedString()
}

/** 完整可见载荷，而非前缀/hash；未来非 text 块身份未知，静态重绑。 */
data class TeamTurnFingerprint(
    val role: String,
    val notice: Boolean,
    val createdAt: String?,
    val completedAt: String?,
    val author: List<String?>?,
    val blocks: List<String>,
    val reportFile: com.wand.app.data.TeamReportFile? = null,
)

fun teamTurnFingerprint(turn: ConversationTurn): TeamTurnFingerprint? {
    if (turn.content.any { it !is ContentBlock.Text }) return null
    val author = turn.author
    return TeamTurnFingerprint(
        role = turn.role, notice = turn.notice, createdAt = turn.createdAt, completedAt = turn.completedAt,
        author = author?.let { listOf(it.id, it.name, it.leader.toString(), it.sessionId,
            it.provider, it.model, it.thinkingEffort, it.avatar) },
        blocks = turn.content.map { (it as ContentBlock.Text).text },
        reportFile = turn.reportFile,
    )
}

data class PresentedTeamTurn(val presentationId: String, val turn: ConversationTurn, val fingerprint: TeamTurnFingerprint?)
data class TeamChatProjection(
    val scope: String,
    val rows: List<PresentedTeamTurn>,
    val nextId: Int,
    /** 只是可证明的新尾候选；前台/贴尾/首次可见资格由页面一次消费。 */
    val candidates: List<String>,
)

private fun uniqueTailOverlap(before: List<PresentedTeamTurn>, after: List<TeamTurnFingerprint?>): Int? {
    var found: Int? = null
    for (length in 1..minOf(before.size, after.size)) {
        if (before.takeLast(length).indices.all { index ->
                before[before.size - length + index].fingerprint?.let { it == after[index] } == true
            }
        ) {
            if (found != null) return null
            found = length
        }
    }
    return found
}

/** R7-I/A：当前/上次窗口的薄账本；不可观测同文替换不冒充真实消息 ID。 */
fun projectTeamTurns(previous: TeamChatProjection?, scope: String, turns: List<ConversationTurn>): TeamChatProjection {
    val before = if (previous?.scope == scope) previous.rows else emptyList()
    val fingerprints = turns.map(::teamTurnFingerprint)
    var nextId = if (previous?.scope == scope) previous.nextId else 0
    fun allocate(): String = "turn-${++nextId}"
    val unchanged = before.size == turns.size && before.indices.all { index ->
        before[index].fingerprint != null && before[index].fingerprint == fingerprints[index]
    }
    if (unchanged) return TeamChatProjection(scope, turns.indices.map { index ->
        before[index].copy(turn = turns[index])
    }, nextId, emptyList())
    val overlap = uniqueTailOverlap(before, fingerprints)
    val oldCounts = before.mapNotNull { it.fingerprint }.groupingBy { it }.eachCount()
    val newCounts = fingerprints.filterNotNull().groupingBy { it }.eachCount()
    val lastAt = before.lastOrNull()?.turn?.createdAt?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
    val rows = mutableListOf<PresentedTeamTurn>()
    val candidates = mutableListOf<String>()
    turns.forEachIndexed { index, turn ->
        val fingerprint = fingerprints[index]
        val anchored = if (overlap != null && index < overlap) before[before.size - overlap + index] else null
        val unique = fingerprint != null && (oldCounts[fingerprint] ?: 0) <= 1 && newCounts[fingerprint] == 1
        val groupAnchored = anchored != null && fingerprint != null && oldCounts[fingerprint] == newCounts[fingerprint]
            && fingerprints.indices.all { i -> fingerprints[i] != fingerprint ||
                (overlap != null && i < overlap && before[before.size - overlap + i].fingerprint == fingerprint) }
        val retained = if (anchored != null && (unique || groupAnchored)) anchored
            else if (unique) before.firstOrNull { it.fingerprint == fingerprint } else null
        val id = retained?.presentationId ?: allocate()
        rows.add(PresentedTeamTurn(id, turn, fingerprint))
        val at = turn.createdAt?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
        if (retained == null && unique && ((before.isEmpty() && previous?.scope == scope)
                || (overlap != null && index >= overlap)) && at != null
            && (before.isEmpty() || (lastAt != null && at >= lastAt))
        ) candidates.add(id)
    }
    return TeamChatProjection(scope, rows, nextId, candidates)
}

/** R3 页面级一次性绘制资格，不随 LazyColumn 行回收/重组而重置。 */
data class TeamArrivalState(
    val scope: String,
    val foreground: Boolean = true,
    val activeIds: Set<String> = emptySet(),
)

fun teamArrivalForScope(current: TeamArrivalState, scope: String): TeamArrivalState =
    if (current.scope == scope) current else current.copy(scope = scope, activeIds = emptySet())

fun teamArrivalResumed(current: TeamArrivalState): TeamArrivalState =
    if (current.foreground) current else current.copy(foreground = true)

/** ON_PAUSE/ON_STOP 重复触发幂等；恢复时不把旧入场资格带回来。 */
fun teamArrivalPaused(current: TeamArrivalState): TeamArrivalState =
    if (!current.foreground && current.activeIds.isEmpty()) current
    else current.copy(foreground = false, activeIds = emptySet())

/** 动态关动效时消费当前批次，row 的绘制分支随即回到 alpha=1/位移=0。 */
fun teamArrivalReduced(current: TeamArrivalState): TeamArrivalState =
    if (current.activeIds.isEmpty()) current else current.copy(activeIds = emptySet())

fun teamArrivalAdmitted(
    current: TeamArrivalState,
    scope: String,
    visibleCandidates: Set<String>,
    pinned: Boolean,
    motionEnabled: Boolean,
): TeamArrivalState {
    val scoped = teamArrivalForScope(current, scope)
    return if (!scoped.foreground || !pinned || !motionEnabled || visibleCandidates.isEmpty()) scoped
    else scoped.copy(activeIds = scoped.activeIds + visibleCandidates)
}

/** 旧 scope 的退场/回收回调不能撤掉新 scope 中恰好同名的本地句柄。 */
fun teamArrivalConsumed(current: TeamArrivalState, scope: String, id: String): TeamArrivalState =
    if (current.scope != scope || id !in current.activeIds) current
    else current.copy(activeIds = current.activeIds - id)

/** Box 首帧即占最终行高；这两个数只供 graphicsLayer 绘制，不参与布局。 */
data class TeamArrivalFrame(val alpha: Float, val translationY: Float)

fun teamArrivalFrame(progress: Float, motionEnabled: Boolean, travelPx: Float): TeamArrivalFrame {
    val amount = if (motionEnabled) progress.coerceIn(0f, 1f) else 1f
    return TeamArrivalFrame(amount, if (motionEnabled) travelPx * (1f - amount) else 0f)
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
    /** 未拿到成功 ACK 时，任何同文回合都不能自动结算这条发送。 */
    val accepted: Boolean = false,
    /** 发送前已看见的回合不能充当本次 ACK。 */
    val knownFingerprints: List<TeamTurnFingerprint> = emptyList(),
    /** 成功 ACK 唯一指认的回合；拿不到时保守保留临时行。 */
    val ackFingerprint: TeamTurnFingerprint? = null,
)

fun turnEpochMillis(turn: ConversationTurn): Long? {
    val raw = turn.completedAt?.takeIf { it.isNotBlank() } ?: turn.createdAt?.takeIf { it.isNotBlank() }
        ?: return null
    return runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()
}

/** 间隔半小时或跨日时在消息流中标一次时间，短时间连续对话不反复插入。 */
fun teamChatShowsTime(previous: ConversationTurn?, current: ConversationTurn): Boolean {
    val currentAt = turnEpochMillis(current) ?: return false
    val previousAt = previous?.let(::turnEpochMillis) ?: return true
    val zone = ZoneId.systemDefault()
    val sameDay = Instant.ofEpochMilli(previousAt).atZone(zone).toLocalDate() ==
        Instant.ofEpochMilli(currentAt).atZone(zone).toLocalDate()
    return !sameDay || currentAt - previousAt >= 30 * 60 * 1_000L
}

fun teamChatTimeLabel(
    turn: ConversationTurn,
    nowMillis: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val millis = turnEpochMillis(turn) ?: return ""
    val local = Instant.ofEpochMilli(millis).atZone(zone)
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val day = when (local.toLocalDate()) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> "${local.monthValue}月${local.dayOfMonth}日"
    }
    return "$day ${local.format(DateTimeFormatter.ofPattern("HH:mm"))}"
}

/** 从成功 ACK 的消息窗里找唯一的新 user 回合；正文相同但身份不明时不能猜。 */
fun acknowledgedTeamChatFingerprint(
    messages: List<ConversationTurn>?,
    text: String,
    knownFingerprints: List<TeamTurnFingerprint>,
): TeamTurnFingerprint? {
    if (messages == null) return null
    val fingerprints = messages.map(::teamTurnFingerprint)
    val known = knownFingerprints.toSet()
    val anchor = fingerprints.indexOfLast { it != null && it in known }
    if (known.isNotEmpty() && anchor < 0) return null
    val candidates = messages.indices.drop(anchor + 1).mapNotNull { index ->
        val turn = messages[index]
        val fingerprint = fingerprints[index]
        fingerprint?.takeIf { turn.role == "user" && chatTurnText(turn) == text && it !in known }
    }
    return candidates.singleOrNull()
}

fun isConfirmedBy(turn: ConversationTurn, row: LocalChatTurn): Boolean {
    if (!row.accepted || turn.role != "user" || chatTurnText(turn) != row.text) return false
    val fingerprint = teamTurnFingerprint(turn) ?: return false
    return fingerprint == row.ackFingerprint && fingerprint !in row.knownFingerprints
}

/**
 * 发送之后调一次：`turns` 为 null 表示重拉失败，临时行留着并标「未确认」；
 * 否则把服务端已经回显的那几条撤掉，剩下的继续等下一次重拉。
 */
fun settleLocalTurns(local: List<LocalChatTurn>, turns: List<ConversationTurn>?): List<LocalChatTurn> =
    if (turns == null) local.map { if (it.accepted || it.unconfirmed) it else it.copy(unconfirmed = true) }
    else {
        val used = BooleanArray(turns.size)
        local.filter { row ->
            val matches = turns.indices.filter { index ->
                !used[index] && isConfirmedBy(turns[index], row)
            }
            if (matches.size == 1) used[matches.single()] = true
            matches.size != 1
        }
    }

/** 只有输入被接收前的明确 4xx 拒收可自动回到草稿；其余情况留未确认行。 */
fun chatSendDefinitelyRejected(error: Throwable): Boolean = isDefiniteRequestRejection(error)

enum class TeamOfficeState { Working, Attention, Queued, Done, Failed, Idle }

data class TeamOfficeMember(
    val member: AiTeamMember,
    val state: TeamOfficeState,
    val label: String,
    val task: String,
    val sessionId: String?,
)

/** 从运行步骤投影团队工位；只展示服务端真实状态，不把空闲成员伪装成工作中。 */
fun teamOfficeMembers(detail: AiTeamRunDetail): List<TeamOfficeMember> =
    detail.presentationTeam?.members.orEmpty().map { member ->
        val own = detail.steps.filter { it.memberId == member.id }
        val step = own.firstOrNull { it.status == "running" } ?: own.maxByOrNull { it.seq }
        val activity = step?.sessionId?.let { detail.memberStates[it] }
        val state = when (step?.status) {
            "running" -> if (activity == "needs_input" || activity == "needs_permission") {
                TeamOfficeState.Attention
            } else TeamOfficeState.Working
            "queued" -> TeamOfficeState.Queued
            "done" -> TeamOfficeState.Done
            "failed" -> TeamOfficeState.Failed
            else -> TeamOfficeState.Idle
        }
        val label = when (state) {
            TeamOfficeState.Attention -> if (activity == "needs_permission") "待授权" else "待回答"
            TeamOfficeState.Working -> "工作中"
            TeamOfficeState.Queued -> "排队中"
            TeamOfficeState.Done -> "已完成"
            TeamOfficeState.Failed -> "失败"
            TeamOfficeState.Idle -> "待派工"
        }
        TeamOfficeMember(member, state, label, step?.title?.takeIf { it.isNotBlank() }
            ?: member.duty.takeIf { it.isNotBlank() } ?: "等待负责人派工", step?.sessionId)
    }

/** relay 会话延续后跟随同一 chat 的最新运行；没有更新或旧服务端没标记时留在当前轮。 */
fun newestRunOnSameChat(current: AiTeamRun, runs: List<AiTeamRun>): String? {
    val chatId = current.chatSessionId ?: return null
    return runs.firstOrNull { it.chatSessionId == chatId }?.id?.takeIf { it != current.id }
}

/**
 * 会话行 → 群聊运行 id：服务端摘要没带 `teamChat`（老服务端）或缺 runId 时返回 null，
 * 调用方按普通会话打开，不猜、不崩。
 */
fun groupChatRunId(session: WorkspaceSessionSummary): String? =
    session.teamChat?.runId?.takeIf { it.isNotBlank() }

// ---------- 头像：谁的脸（设计 §5，纯函数与 Web avatarFace / chatAvatarSpec / memberCoatIndex 同名同算法） ----------

/**
 * 像素猫毛色（逐字照拄 Web `cat-coats.ts`）。`light` / `eye` 缺省时用 base / 瞳色默认值，
 * 与 Web `light ?? base` / `eye ?? #2D2D2D` 一致。
 */
data class CatCoat(
    val name: String,
    val base: Int,
    val dark: Int,
    val light: Int = base,
    val eye: Int = CAT_COAT_DEFAULT_EYE,
)

val CAT_COAT_DEFAULT_EYE: Int = 0xFF2D2D2D.toInt()
val CAT_COAT_WHITE: Int = 0xFFFFFFFF.toInt()
val CAT_COAT_NOSE: Int = 0xFFF28B9A.toInt()

val CAT_COATS: List<CatCoat> = listOf(
    CatCoat("橘猫", 0xFFF0923A.toInt(), 0xFFC46A1A.toInt()),
    CatCoat("银渐层", 0xFF9EAAB8.toInt(), 0xFF6B7B8D.toInt(), light = 0xFFC5CED8.toInt(), eye = 0xFF3F8F55.toInt()),
    CatCoat("奶牛猫", 0xFFF4F1EA.toInt(), 0xFF2F2F33.toInt()),
    CatCoat("黑猫", 0xFF3A3A40.toInt(), 0xFF1E1E22.toInt(), light = 0xFF55555C.toInt(), eye = 0xFFE9C63F.toInt()),
    CatCoat("暹罗", 0xFFE9DCC4.toInt(), 0xFF6B4A36.toInt(), light = 0xFFF4ECDD.toInt(), eye = 0xFF3F7FD8.toInt()),
    CatCoat("蓝猫", 0xFF7C8BA6.toInt(), 0xFF56627A.toInt(), light = 0xFF98A6BE.toInt(), eye = 0xFFE0A43A.toInt()),
    CatCoat("三花", 0xFFF2E6D4.toInt(), 0xFFC46A1A.toInt(), light = 0xFF3A3A40.toInt()),
    CatCoat("樱粉", 0xFFF2B8C6.toInt(), 0xFFC9788D.toInt(), light = 0xFFF8D3DC.toInt()),
)

/**
 * 10×10 像素格，**逐格照拄 Web `catCoatGrid` 的色块表**（T 透明、b 底、d 深、l 亮、w 白、
 * k 瞳、p 鼻）。放在纯 Kotlin 里是为了单测能逐格对齐两端，不用起 Compose。
 */
val CAT_COAT_GRID_ROWS: List<String> = listOf(
    "TdTTTTTTdT",
    "dbdTTTTdbd",
    "dbbbbbbbbd",
    "bbwkbbwkbb",
    "bbwwbbwwbb",
    "bbbbppbbbb",
    "bdblbblbdb",
    "TbbbbbbbbT",
    "TTbdbbdbTT",
    "TTTbTTbTTT",
)

/** 毛色下标：负数也落回 0..7（`%` 在 Kotlin 里可能为负，这里统一成非负）。 */
private fun coatIndex(coat: Int): Int = ((coat % CAT_COATS.size) + CAT_COATS.size) % CAT_COATS.size

/** 像素猫网格：`null` 是透明（两端只用同一张表，不各自画）。 */
fun catCoatGrid(coat: Int): List<List<Int?>> {
    val entry = CAT_COATS[coatIndex(coat)]
    return CAT_COAT_GRID_ROWS.map { row ->
        row.map { cell ->
            when (cell) {
                'T' -> null
                'b' -> entry.base
                'd' -> entry.dark
                'l' -> entry.light
                'w' -> CAT_COAT_WHITE
                'k' -> entry.eye
                else -> CAT_COAT_NOSE
            }
        }
    }
}

/** Web `cat-coats.ts` 的逐位复刻：`h = (h shl 5) - h + c`，Int 溢出语义与 JS `|0` 一致。 */
private fun coatHash(seed: String): Int {
    var hash = 0
    for (c in seed) hash = (hash shl 5) - hash + c.code
    return hash
}

/** 与 JS `Math.abs` 一致：`Int.MIN_VALUE` 取绝对值会溢出，先抬到 Long 再取模。 */
private fun coatHashAbs(hash: Int): Long =
    if (hash >= 0) hash.toLong()
    else if (hash == Int.MIN_VALUE) 2147483648L
    else -hash.toLong()

private val CAT_AVATAR_MARK = Regex("^cat:(\\d+)$")

/**
 * 头像毛色下标（对齐 Web `memberCoatIndex`）：`cat:<n>` 显式指定，否则按 id（新成员还没 id 时按名字）
 * 哈希。**不能**用 [memberAvatarVariant] 那套种子/取模代替，同一成员两端会算出不同的毛色。
 */
fun memberCoatIndex(authorId: String?, name: String?, avatar: String?): Int {
    val explicit = CAT_AVATAR_MARK.find(avatar.orEmpty())
    val explicitIndex = explicit?.groupValues?.get(1)?.toIntOrNull()
    if (explicitIndex != null) return explicitIndex % CAT_COATS.size
    val seed = authorId?.takeIf { it.isNotBlank() }
        ?: name?.takeIf { it.isNotBlank() }
        ?: "member"
    return (coatHashAbs(coatHash(seed)) % CAT_COATS.size).toInt()
}

/**
 * 生成头像配色（逐字照拄 Web `generated-avatar.ts` 的 `GENERATED_AVATAR_COATS`）。
 * 两端与中点对白字都不低于 4.5:1；顺序固定，改顺序会换掉所有人的底色。
 */
data class GeneratedAvatarCoat(val name: String, val from: Int, val to: Int)

val GENERATED_AVATAR_TEXT: Int = 0xFFFFFFFF.toInt()

val GENERATED_AVATAR_COATS: List<GeneratedAvatarCoat> = listOf(
    GeneratedAvatarCoat("赭橙", 0xFFA8471F.toInt(), 0xFF7E3214.toInt()),
    GeneratedAvatarCoat("湖蓝", 0xFF2F6DB5.toInt(), 0xFF1F4E8A.toInt()),
    GeneratedAvatarCoat("松绿", 0xFF2E7D5B.toInt(), 0xFF1C5B41.toInt()),
    GeneratedAvatarCoat("紫棠", 0xFF6B4EA8.toInt(), 0xFF4F3883.toInt()),
    GeneratedAvatarCoat("靛青", 0xFF2C6E7F.toInt(), 0xFF1B4F5E.toInt()),
    GeneratedAvatarCoat("绛红", 0xFFB03A48.toInt(), 0xFF872633.toInt()),
    GeneratedAvatarCoat("芥黄", 0xFF8A6A16.toInt(), 0xFF674D0D.toInt()),
    GeneratedAvatarCoat("石青", 0xFF4A5B8C.toInt(), 0xFF34426A.toInt()),
)

/** 一张生成头像的脸：渐变两端色 + 白字色 + 字形。 */
data class GeneratedAvatar(val from: Int, val to: Int, val text: Int, val glyph: String)

/** 配色种子：稳定 id 优先，最后才落到同一张 "member" 底，空身份不会随机漂。 */
fun generatedAvatarSeed(id: String?, name: String?): String =
    id?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() } ?: "member"

/**
 * 字形：名字首字（拉丁字母大写），没名字就用 id 首字，都没有给 `?`。
 * 按码点取首字符，与 Web 的 `Array.from(source)[0]` 一致（含代理对的 emoji 不被切半）。
 */
fun generatedAvatarGlyph(id: String?, name: String?): String {
    val source = name?.takeIf { it.isNotBlank() } ?: id?.takeIf { it.isNotBlank() } ?: ""
    if (source.isEmpty()) return "?"
    val glyph = String(Character.toChars(source.codePointAt(0)))
    return if (glyph.length == 1 && glyph[0] in 'a'..'z') glyph.uppercase(Locale.ROOT) else glyph
}

/** 生成头像：与 Web `generatedAvatarFace` 同种子、同哈希，涂一样的底、一样的字。 */
fun generatedAvatarFace(id: String?, name: String?): GeneratedAvatar {
    val seed = generatedAvatarSeed(id, name)
    val coat = GENERATED_AVATAR_COATS[(coatHashAbs(coatHash(seed)) % GENERATED_AVATAR_COATS.size).toInt()]
    return GeneratedAvatar(coat.from, coat.to, GENERATED_AVATAR_TEXT, generatedAvatarGlyph(id, name))
}

/** 一条发言的头像来源（设计 §5.2）：上传图 > 显式毛色 > 按身份生成 > 默认 APP logo。 */
sealed interface ChatAvatarSpec {
    data class Upload(val src: String) : ChatAvatarSpec
    data class Cat(val coat: Int) : ChatAvatarSpec
    data class Generated(val face: GeneratedAvatar) : ChatAvatarSpec
    data object Brand : ChatAvatarSpec
}

fun chatAvatarSpec(author: TurnAuthor?): ChatAvatarSpec =
    author?.let { chatAvatarSpec(it.id, it.name, it.avatar) } ?: ChatAvatarSpec.Brand

/** 能定位到成员身份才给脸（`cat:<n>` 是用户挑过的毛色，仍然是猫）；「我」才回落成默认 APP logo。 */
fun chatAvatarSpec(id: String?, name: String?, avatar: String?): ChatAvatarSpec {
    val value = avatar.orEmpty()
    if (value.startsWith("data:image/")) return ChatAvatarSpec.Upload(value)
    if (id.isNullOrBlank() && name.isNullOrBlank()) return ChatAvatarSpec.Brand
    if (CAT_AVATAR_MARK.matches(value)) {
        return ChatAvatarSpec.Cat(memberCoatIndex(id, name, value))
    }
    return ChatAvatarSpec.Generated(generatedAvatarFace(id, name))
}

/** 全文快照：列表 key/owner 共用 scoped 本地句柄；关闭期间正文/名单保持原值。 */
data class TeamChatDoc(
    val presentationId: String,
    val scope: String,
    val text: String,
    val name: String,
    val clock: String,
    val typeLabel: String,
    val chip: String = "",
    val avatar: ChatAvatarSpec = ChatAvatarSpec.Brand,
    /** 明确来源，自己的正式/临时消息传空名单；打开后 roster 更新不改层内内容。 */
    val mentionNames: List<String> = emptyList(),
)

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
 * provider + engine 走 `boardTaskAgentLabel`（与 Web `ISSUE_AGENT_PROVIDERS` 同表），
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
    engine: String? = null,
): String {
    val parts = mutableListOf<String>()
    provider?.takeIf { it.isNotBlank() }?.let { parts.add(boardTaskAgentLabel(it, engine)) }
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
