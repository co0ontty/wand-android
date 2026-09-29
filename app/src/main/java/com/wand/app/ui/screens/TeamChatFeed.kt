package com.wand.app.ui.screens

import com.wand.app.data.AiTeamRunDetail
import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import com.wand.app.data.aiTeamRunActive

private val TEAM_STEP_START = Regex("^我正在开始工作：第 ([1-9][0-9]*) 步(?:「([^\\r\\n]+)」)?$")
private val TEAM_ACTIVITY_STATES = setOf("working", "needs_input", "needs_permission", "done", "failed")

/**
 * 仅兼容服务端两种已知开工模板；调用方仍用原 turn 对齐消息身份、保存和确认送达。
 * 不合并正文块、不猜作者，也不改写用户或成员的自由发言。
 */
fun teamChatDisplayTurn(turn: ConversationTurn): ConversationTurn {
    if (turn.role != "assistant") return turn
    val name = turn.author?.name?.takeIf { it.isNotBlank() } ?: return turn
    val block = turn.content.singleOrNull() as? ContentBlock.Text ?: return turn
    if (block.subagent != null) return turn
    val text = block.text
    val replacement = if (turn.notice) {
        val prefix = "$name 开始「"
        if (!text.startsWith(prefix) || !text.endsWith("」") || '\n' in text || '\r' in text) return turn
        val title = text.substring(prefix.length, text.length - 1)
        if (title.isBlank()) return turn
        "我开始处理「$title」这项工作。"
    } else {
        val lines = text.split('\n')
        if (lines.size !in 1..2 || lines.any { '\r' in it }) return turn
        if (lines.size == 2 && (!lines[1].startsWith("依据") || lines[1].removePrefix("依据").isBlank())) return turn
        val match = TEAM_STEP_START.matchEntire(lines[0]) ?: return turn
        val title = match.groupValues[2]
        if (title.isNotEmpty() && title.isBlank()) return turn
        val start = if (title.isEmpty()) "我开始处理第 ${match.groupValues[1]} 步的工作。"
            else "我开始处理「$title」这项工作。"
        if (lines.size == 2) "$start\n${lines[1]}" else start
    }
    return turn.copy(notice = false, content = listOf(block.copy(text = replacement)))
}

/** 主群聊只显示步骤事实与待处理入口，不承载成员的工具输出或终端文本。 */
data class TeamChatActivity(
    val stepId: String,
    val memberName: String,
    val title: String,
    val sessionId: String?,
    val state: String,
)

fun teamChatActivities(detail: AiTeamRunDetail): List<TeamChatActivity> {
    // 终态快照可能仍带着尚未清理的 running 步骤或旧会话状态，不能让它复活状态行。
    if (!aiTeamRunActive(detail.run.status)) return emptyList()
    val members = detail.run.team?.members.orEmpty().associateBy { it.id }
    return detail.steps.asSequence()
        .filter { it.status == "running" && it.id.isNotBlank() }
        .sortedBy { it.seq }
        .distinctBy { it.id }
        .map { step ->
            val sessionId = step.sessionId?.takeIf { it.isNotBlank() }
            val state = sessionId?.let { detail.memberStates[it] }
                ?.takeIf { it in TEAM_ACTIVITY_STATES } ?: "working"
            TeamChatActivity(
                stepId = step.id,
                memberName = members[step.memberId]?.name?.takeIf { it.isNotBlank() } ?: "成员",
                title = step.title,
                sessionId = sessionId,
                state = state,
            )
        }.toList()
}
