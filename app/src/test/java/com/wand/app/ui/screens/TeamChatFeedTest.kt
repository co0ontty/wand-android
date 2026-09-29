package com.wand.app.ui.screens

import com.wand.app.data.AiTeam
import com.wand.app.data.AiTeamMember
import com.wand.app.data.AiTeamRun
import com.wand.app.data.AiTeamRunDetail
import com.wand.app.data.AiTeamStep
import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import com.wand.app.data.SubagentMeta
import com.wand.app.data.TurnAuthor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamChatFeedTest {
    private val member = TurnAuthor(
        id = "developer", name = "实现者", avatar = "cat:2", provider = "codex",
        model = "default", thinkingEffort = "high", sessionId = "session-1",
    )

    private fun turn(text: String, notice: Boolean = true) = ConversationTurn(
        role = "assistant", content = listOf(ContentBlock.Text(text, null)), notice = notice,
        author = member, createdAt = "2026-09-29T03:00:00Z", completedAt = "2026-09-29T03:00:01Z",
    )

    private fun text(turn: ConversationTurn): String = (turn.content.single() as ContentBlock.Text).text

    @Test
    fun legacyStartBecomesMemberSpeechWithoutChangingItsIdentityOrSource() {
        val original = turn("实现者 开始「修复上传」")
        val shown = teamChatDisplayTurn(original)
        assertEquals("我开始处理「修复上传」这项工作。", text(shown))
        assertFalse(shown.notice)
        assertEquals(original.copy(notice = false, content = shown.content), shown)
        assertSame(original.author, shown.author)
        assertTrue(original.notice)
        assertEquals("实现者 开始「修复上传」", text(original))
        assertSame(shown, teamChatDisplayTurn(shown))
    }

    @Test
    fun legacyStartPreservesQuotedAndEmojiTitlesLiterally() {
        assertEquals("我开始处理「检查「上传」与 😀」这项工作。",
            text(teamChatDisplayTurn(turn("实现者 开始「检查「上传」与 😀」"))))
        val regexName = TurnAuthor(id = "qa", name = "QA [A]+")
        assertEquals("我开始处理「测试」这项工作。",
            text(teamChatDisplayTurn(turn("QA [A]+ 开始「测试」").copy(author = regexName))))
    }

    @Test
    fun incompatibleNoticeAndUnattributedMessagesRemainTheSameObject() {
        val cases = listOf(
            turn("其他人 开始「修复上传」"),
            turn("实现者 开始「修复上传」").copy(role = "user"),
            turn("实现者 开始「修复上传」").copy(role = "system"),
            turn("实现者 开始「修复上传」").copy(author = null),
            turn(" 开始「修复上传」").copy(author = member.copy(name = "")),
            turn("实现者 开始「修复上传」", notice = false),
            turn("引用：实现者 开始「修复上传」"),
            turn("实现者 开始「修复上传」后失败"),
            turn("实现者 开始「」"),
            turn("实现者 开始「  」"),
            turn("实现者 开始「修复\n上传」"),
            turn("实现者 开始「修复上传」\n补充内容"),
            turn("团队已停止。在群里发消息可以让团队接着处理。"),
            turn("负责人的模型出错：开始处理失败"),
            turn("✅ 完成「修复上传」\n\n已验证。", notice = false),
        )
        cases.forEach { assertSame(text(it), it, teamChatDisplayTurn(it)) }
    }

    @Test
    fun sourceWithAdditionalBlocksOrSubagentContextIsNeverRewritten() {
        val original = turn("实现者 开始「修复上传」")
        val cases = listOf(
            original.copy(content = emptyList()),
            original.copy(content = original.content + ContentBlock.Text("补充", null)),
            original.copy(content = original.content + ContentBlock.ToolResult("tool-1", "输出", false, false, null)),
            original.copy(content = listOf(ContentBlock.Text(text(original), SubagentMeta("tool-1", "worker", null)))),
        )
        cases.forEach { assertSame(it, teamChatDisplayTurn(it)) }
    }

    @Test
    fun serverStartTemplateKeepsItsDependencyLineAndMemberMetadata() {
        val basis = "依据 @设计师 第 1 步「设计规格」的产物 .wand-team/report-1.md"
        val original = turn("我正在开始工作：第 2 步「实现接口」\n$basis", notice = false)
        val shown = teamChatDisplayTurn(original)
        assertEquals("我开始处理「实现接口」这项工作。\n$basis", text(shown))
        assertEquals(original.copy(content = shown.content), shown)
        assertEquals("我开始处理第 2 步的工作。", text(teamChatDisplayTurn(
            turn("我正在开始工作：第 2 步", notice = false))))
        assertEquals("我开始处理「检查」这项工作。\n依据上游步骤的产物继续", text(teamChatDisplayTurn(
            turn("我正在开始工作：第 3 步「检查」\n依据上游步骤的产物继续", notice = false))))
    }

    @Test
    fun serverTemplateDoesNotRewriteFreeSpeechOrMultipleParagraphs() {
        val first = "我正在开始工作：第 2 步「实现接口」"
        val cases = listOf(
            turn(first),
            turn(first, notice = false).copy(role = "user"),
            turn(first, notice = false).copy(author = null),
            turn("我正在开始工作：实现接口", notice = false),
            turn("$first 然后执行测试", notice = false),
            turn("$first\n顺便说明一下", notice = false),
            turn("$first\n依据", notice = false),
            turn("$first\n依据报告\n下面是我的详细计划", notice = false),
            turn("$first\n\n依据报告", notice = false),
            turn("我正在开始工作：第 0 步「实现接口」", notice = false),
            turn("我正在开始工作：第 2 步「  」", notice = false),
            turn("我正在开始工作：第 2 步「实现\n接口」", notice = false),
        )
        cases.forEach { assertSame(text(it), it, teamChatDisplayTurn(it)) }
    }

    private val roster = listOf(
        AiTeamMember("lead", "负责人", "派工", emptyList(), true),
        AiTeamMember("dev", "开发者", "实现", emptyList(), false),
        AiTeamMember("qa", "审查者", "检查", emptyList(), false),
    )
    private val run = AiTeamRun("run", "team", AiTeam("team", "开发组", "", roster),
        "task", "目标", "running", "", 1, 20, "chat")

    private fun step(id: String, seq: Int, memberId: String = "dev", status: String = "running",
        sessionId: String? = "session-$id") = AiTeamStep(id, seq,
        if (memberId == "lead") "leader" else "work", memberId, "任务 $id", status, sessionId)

    @Test
    fun activityUsesOnlyRunningStepsSortedBySequenceAndDeduplicatedById() {
        val repeated = step("dev", 2)
        val detail = AiTeamRunDetail(run, listOf(
            step("qa", 4, "qa", "queued"), repeated, step("done", 3, status = "done"),
            step("lead", 1, "lead"), repeated, step("", 0), step("failed", 5, status = "failed"),
        ))
        val rows = teamChatActivities(detail)
        assertEquals(listOf("lead", "dev"), rows.map { it.stepId })
        assertEquals(listOf("负责人", "开发者"), rows.map { it.memberName })
        assertEquals(listOf("任务 lead", "任务 dev"), rows.map { it.title })
        assertEquals(listOf("working", "working"), rows.map { it.state })
        assertEquals("session-dev", rows[1].sessionId)
    }

    @Test
    fun attentionAndCompletionStatesFollowOnlyTheirCurrentSession() {
        val detail = AiTeamRunDetail(run, listOf(step("a", 1), step("b", 2), step("c", 3), step("d", 4)),
            memberStates = mapOf("session-a" to "needs_permission", "session-b" to "needs_input",
                "session-c" to "failed", "session-d" to "done", "unrelated-session" to "failed"))
        assertEquals(listOf("needs_permission", "needs_input", "failed", "done"),
            teamChatActivities(detail).map { it.state })
        assertEquals(listOf("working"), teamChatActivities(detail.copy(
            steps = listOf(step("new", 5)), memberStates = detail.memberStates + ("dev" to "needs_permission"),
        )).map { it.state })
    }

    @Test
    fun activityNeverCreatesSessionsOrBorrowsAnotherMembersStatus() {
        val detail = AiTeamRunDetail(run, listOf(step("a", 1, sessionId = null),
            step("b", 2, sessionId = "  "), step("c", 3, memberId = "missing")),
            memberStates = mapOf("" to "needs_permission", "  " to "needs_input", "session-c" to "future-state"))
        val rows = teamChatActivities(detail)
        assertEquals(listOf("working", "working", "working"), rows.map { it.state })
        assertNull(rows[0].sessionId)
        assertNull(rows[1].sessionId)
        assertEquals("成员", rows[2].memberName)
        assertEquals("成员", teamChatActivities(detail.copy(run = run.copy(team = null))).first().memberName)
    }

    @Test
    fun terminalOrUnknownRunNeverRevivesStaleRunningSteps() {
        val detail = AiTeamRunDetail(run, listOf(step("dev", 1)),
            memberStates = mapOf("session-dev" to "needs_permission"))
        listOf("done", "failed", "stopped", "future-state", "").forEach { status ->
            assertTrue(status, teamChatActivities(detail.copy(run = run.copy(status = status))).isEmpty())
        }
        listOf("running", "awaiting_approval", "waiting_user").forEach { status ->
            assertEquals(status, 1, teamChatActivities(detail.copy(run = run.copy(status = status))).size)
        }
    }

    @Test
    fun sameMemberAndTitleCanRepresentIndependentAttempts() {
        val first = step("attempt-1", 1).copy(title = "修复上传")
        val retry = step("attempt-2", 2).copy(title = first.title)
        assertEquals(listOf("attempt-1", "attempt-2"),
            teamChatActivities(AiTeamRunDetail(run, listOf(first, retry))).map { it.stepId })
    }
}
