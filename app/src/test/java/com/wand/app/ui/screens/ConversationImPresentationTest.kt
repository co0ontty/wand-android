package com.wand.app.ui.screens

import com.wand.app.data.AiTeamRun
import com.wand.app.data.BoardTask
import com.wand.app.data.ConversationInstance
import com.wand.app.data.ConversationTask
import com.wand.app.data.ConversationTurn
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class ConversationImPresentationTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun ordinaryImChecklistWithBlankLinesIsShownInFull() {
        val reply = "已按优先级整理：\n\n1. 对齐聊天列表与输入区\n2. 检查通讯录和设置页面\n3. 复核任务进入与返回路径\n\n现在先处理聊天页面。"
        assertFalse(conversationNeedsCollapse(reply))
        assertEquals(reply, conversationCollapsedPreview(reply))
    }

    @Test fun longImHistoryStillHasABoundedPreview() {
        val prose = "历史消息正文".repeat(1000)
        val lines = (1..100).joinToString("\n") { "第 $it 条执行说明" }
        listOf(prose, lines).forEach { text ->
            assertTrue(conversationNeedsCollapse(text))
            val preview = conversationCollapsedPreview(text)
            assertTrue(preview.length <= 1801)
            assertTrue(preview.lineSequence().count() <= 24)
            assertTrue(preview.endsWith("…"))
        }
    }

    @Test fun listClockUsesRealLocalMinutesAndKeepsCrossDayDate() {
        val today = ZonedDateTime.now(zone).withHour(9).withMinute(5).withSecond(37).withNano(0)
        assertEquals("09:05", conversationListClock(today.toInstant().toString(), zone))
        val yesterday = today.minusDays(1)
        assertEquals("${yesterday.monthValue}/${yesterday.dayOfMonth} 09:05", conversationListClock(yesterday.toInstant().toString(), zone))
    }

    @Test fun absentOrInvalidListTimeDoesNotInventAClock() {
        listOf(null, "", "  ", "invalid", "2026-10-07").forEach {
            assertEquals("", conversationListClock(it, zone))
        }
    }

    @Test fun messageClockUsesExistingCompletionThenCreationRule() {
        val turn = ConversationTurn("assistant", emptyList(), createdAt = "2026-10-06T00:00:00Z", completedAt = "2026-10-06T00:01:00Z")
        assertEquals(formatChatClock(turn.completedAt), conversationTurnClock(turn))
        assertEquals(formatChatClock(turn.createdAt), conversationTurnClock(turn.copy(completedAt = null)))
        assertEquals("", conversationTurnClock(turn.copy(createdAt = null, completedAt = null)))
        assertEquals("", conversationTurnClock(turn.copy(completedAt = "invalid")))
    }

    @Test fun groupingRespectsIdentityTimeDayAndNotice() {
        val first = ConversationTurn("assistant", emptyList(), createdAt = "2026-10-07T08:00:00Z", author = com.wand.app.data.TurnAuthor(id = "a", name = "Alice"))
        assertTrue(joinsConversationBubble(first, first.copy(createdAt = "2026-10-07T08:04:59Z"), zone))
        assertFalse(joinsConversationBubble(first, first.copy(createdAt = "2026-10-07T08:05:00Z"), zone))
        assertFalse(joinsConversationBubble(first, first.copy(notice = true), zone))
        assertFalse(joinsConversationBubble(first, first.copy(author = first.author!!.copy(id = "b")), zone))
        assertFalse(joinsConversationBubble(first, first.copy(createdAt = "invalid"), zone))
        assertFalse(joinsConversationBubble(first.copy(createdAt = "2026-10-07T15:59:00Z"), first.copy(createdAt = "2026-10-07T16:00:00Z"), zone))
        assertFalse(joinsConversationBubble(first.copy(author = null), first.copy(author = null), zone))
        assertFalse(joinsConversationBubble(first, first.copy(conversationTarget = com.wand.app.data.ConversationTarget("t", "r")), zone))
        assertEquals("16:00", conversationBubbleClock(first, zone))
    }

    @Test fun arrivalsAreOnlyNewOverlappingTails() {
        assertEquals(emptyList<String>(), appendedConversationKeys(null, listOf("a", "b")))
        assertEquals(listOf("a"), appendedConversationKeys(emptyList(), listOf("a")))
        assertEquals(listOf("c"), appendedConversationKeys(listOf("a", "b"), listOf("b", "c")))
        assertEquals(emptyList<String>(), appendedConversationKeys(listOf("a", "b"), listOf("older", "a", "b")))
        assertEquals(emptyList<String>(), appendedConversationKeys(listOf("a", "b"), listOf("a", "x", "b", "c")))
        assertEquals(emptyList<String>(), appendedConversationKeys(listOf("a", "b"), listOf("x", "y")))
    }

    @Test fun groupLogoUsesNameRatherThanRosterAndPreservesUnicode() {
        assertEquals("设计", conversationInitials("设计协作群"))
        assertEquals("PD", conversationInitials(" Product Design "))
        assertEquals("🐯协", conversationInitials("🐯协作"))
        assertEquals("群", conversationInitials(" "))
    }

    private fun boardTask(id: String, status: String, title: String = "任务 $id") = BoardTask(
        id = id, workspaceId = null, identifier = "TASK-1", title = title, titleSource = "auto",
        description = "", status = status, priority = "none", labels = emptyList(), dueDate = null,
        sortOrder = 0, agent = null, createdAt = "", updatedAt = "", sessionIds = emptyList(),
        sessions = emptyList(), workspace = null, milestone = null,
    )

    private fun run(status: String) = AiTeamRun(
        id = "run-$status", teamId = "team", team = null, taskId = "t1", objective = "", status = status,
        statusDetail = "", stepsUsed = 0, stepLimit = 0, chatSessionId = null, roundNumber = 1,
    )

    private fun conversation(
        id: String,
        title: String = id,
        dissolvedAt: String? = null,
        taskStatuses: List<String> = emptyList(),
    ) = ConversationInstance(
        id = id, kind = "group", title = title, peerEmployeeId = null, team = null, memberVersion = 1,
        sessionId = null, communicationSessionId = null, sourceTemplateId = null, nameSource = "auto",
        preview = "", messageAt = "", unavailableReason = null, memberUnavailableReasons = emptyMap(),
        joinedVersions = emptyMap(),
        tasks = taskStatuses.mapIndexed { index, status -> ConversationTask(boardTask("t$index", status, "$title 的任务"), emptyList()) },
        messages = emptyList(), runDetails = emptyList(), pinnedAt = null, dissolvedAt = dissolvedAt,
        dissolvedBy = null, deleting = false,
    )

    @Test fun archiveTiersSplitArchivedTasksFromUnarchivedOnes() {
        val dissolved = conversation("a", "已解散的群", dissolvedAt = "2026-10-06T02:00:00Z", taskStatuses = listOf("archived"))
        val archivedTaskOnly = conversation("b", "任务被归档的群", taskStatuses = listOf("archived"))
        val live = conversation("c", "还在聊的群", taskStatuses = listOf("doing"))
        val items = listOf(dissolved, archivedTaskOnly, live)
        assertTrue(isConversationArchived(dissolved))
        assertTrue(isConversationArchived(archivedTaskOnly))
        assertFalse(isConversationArchived(live))
        assertEquals(items, filterConversationList(items, ConversationListTier.All, ""))
        assertEquals(listOf(live), filterConversationList(items, ConversationListTier.Active, ""))
        assertEquals(listOf(dissolved, archivedTaskOnly), filterConversationList(items, ConversationListTier.Archived, ""))
        // 档位与查询是同一入口：搜索词仍能命中归档任务名，但不会把它们算进未归档档。
        assertEquals(listOf(dissolved), filterConversationList(items, ConversationListTier.Archived, "已解散的群 的任务"))
        assertEquals(emptyList<ConversationInstance>(), filterConversationList(items, ConversationListTier.Active, "已解散的群 的任务"))
        assertEquals(listOf("全部", "未归档", "已归档"), ConversationListTier.entries.map { it.label })
        assertEquals(ConversationListTier.Archived, ConversationListTier.of("archived"))
        assertEquals(ConversationListTier.All, ConversationListTier.of("unknown"))
    }

    @Test fun archivedTaskRowsNeverReportALiveRunStatus() {
        assertEquals("已归档", conversationTaskStatusLabel(ConversationTask(boardTask("t1", "archived"), listOf(run("waiting_user")))))
        assertEquals("等你回复", conversationTaskStatusLabel(ConversationTask(boardTask("t1", "doing"), listOf(run("waiting_user")))))
        assertEquals("待开工", conversationTaskStatusLabel(ConversationTask(boardTask("t1", "doing"), emptyList())))
        assertEquals("已归档", ConversationArchivedLabel)
    }
    @Test fun sourceSessionsNeverBorrowAnotherMemberResultOrGuessRelayOwnership() {
        fun turn(session: String?, content: List<com.wand.app.data.ContentBlock>) = ConversationTurn("assistant", content,
            author = session?.let { com.wand.app.data.TurnAuthor(id = "employee-$it", name = "Member", sessionId = it) })
        val tool = com.wand.app.data.ContentBlock.ToolUse("same-tool", "AskUserQuestion", null, org.json.JSONObject(), null)
        val result = com.wand.app.data.ContentBlock.ToolResult("same-tool", "answer", false, false, null)
        val detail = conversation("group").copy(communicationSessionId = "channel", messages = listOf(
            turn("alice-session", listOf(tool)), turn("bob-session", listOf(result)),
        ))
        assertEquals("channel", conversationTurnSessionId(turn(null, emptyList()), detail))
        assertNull(conversationTurnSessionId(turn(null, emptyList()).copy(conversationTarget = com.wand.app.data.ConversationTarget("t", "r")), detail))
        assertEquals(emptyMap<String, com.wand.app.data.ContentBlock.ToolResult>(), conversationSessionToolResults(detail)["alice-session"])
        assertEquals(setOf("channel", "alice-session"), conversationInteractiveSessions(detail))
        val answered = detail.copy(messages = detail.messages + turn("alice-session", listOf(result)))
        assertEquals(setOf("channel"), conversationInteractiveSessions(answered))
        assertEquals(emptySet<String>(), conversationInteractiveSessions(detail.copy(dissolvedAt = "now")))
    }

    @Test fun directMessageActivityUsesItsOwnSessionAndNeverThePreviousConversationChannel() {
        val turn = ConversationTurn("assistant", emptyList(),
            sessionLink = com.wand.app.data.ConversationSessionLink("message-session", "消息"),
            sessionPreview = com.wand.app.data.ConversationSessionPreview("running", "回复"))
        val detail = conversation("dm").copy(kind = "dm", messages = listOf(turn))
        assertEquals("message-session", conversationTurnSessionId(turn, detail.copy(communicationSessionId = "old-channel")))
        assertEquals(setOf("message-session"), conversationInteractiveSessions(detail))
        assertTrue(conversationInteractiveSessions(detail.copy(messages = listOf(turn.copy(
            sessionPreview = com.wand.app.data.ConversationSessionPreview("done", "结束"))))).isEmpty())
    }

    @Test fun taskReplyIsExplicitAndMentionsPreserveExistingDraft() {
        assertNull(conversationReplyTarget(ConversationTask(boardTask("t1", "done"), listOf(run("done")))))
        assertEquals(com.wand.app.data.ConversationTarget("t1", "run-waiting_user"),
            conversationReplyTarget(ConversationTask(boardTask("t1", "doing"), listOf(run("waiting_user")))))
        assertEquals("@虎妞 原有草稿", mentionConversationLeader("原有草稿", "虎妞"))
        assertEquals("@虎妞 原有草稿", mentionConversationLeader("@虎妞 原有草稿", "虎妞"))
        assertEquals("@虎妞 ", mentionConversationLeader("", "虎妞"))
        assertEquals("草稿", mentionConversationLeader("草稿", ""))
        assertEquals("1 项等你处理 · 1 项任务", conversationTaskSummary(conversation("group").copy(
            tasks = listOf(ConversationTask(boardTask("t1", "doing"), listOf(run("waiting_user")))))))
    }

}
