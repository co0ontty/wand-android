package com.wand.app.ui.screens

import com.wand.app.data.AiTeam
import com.wand.app.data.AiTeamMember
import com.wand.app.data.AiTeamRun
import com.wand.app.data.AiTeamRunDetail
import com.wand.app.data.AiTeamStep
import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import com.wand.app.data.TurnAuthor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TeamChatRenameTest {
    private val oldTeam = AiTeam("team", "旧群名", "", listOf(
        AiTeamMember("dev", "旧名字", "实现", emptyList(), false, avatar = "cat:1"),
    ))
    private val newTeam = oldTeam.copy(name = "新群名", members = listOf(
        oldTeam.members[0].copy(name = "新名字", avatar = "cat:2"),
    ))
    private val run = AiTeamRun("run", "team", oldTeam, "task", "目标", "running", "", 1, 20, "chat")
    private val step = AiTeamStep("step", 1, "work", "dev", "实现", "running", "session-1")
    private val original = ConversationTurn("assistant", listOf(ContentBlock.Text("旧名字 开始「实现」", null)),
        author = TurnAuthor("dev", "旧名字", avatar = "cat:1", sessionId = "session-1"), notice = true)

    @Test
    fun renamedIdentityAppliesOnlyToVisibleRowsAndOffice() {
        val detail = AiTeamRunDetail(run, listOf(step), chatTurns = listOf(original), displayTeam = newTeam)
        val displayed = displayTeamTurn(teamChatDisplayTurn(original), detail)
        assertEquals("新名字", displayed.author?.name)
        assertEquals("cat:2", displayed.author?.avatar)
        assertEquals("session-1", displayed.author?.sessionId)
        assertEquals("我开始处理「实现」这项工作。", chatTurnText(displayed))
        assertEquals("旧名字 开始「实现」", chatTurnText(original))
        assertEquals("旧名字", original.author?.name)
        assertEquals("新名字", teamOfficeMembers(detail).single().member.name)
        assertEquals("新名字", teamChatActivities(detail).single().memberName)
        assertEquals("旧名字", detail.run.team?.members?.single()?.name)
        val removedAuthor = original.copy(author = original.author?.copy(id = "removed"))
        assertSame(removedAuthor, displayTeamTurn(removedAuthor, detail))
    }

    @Test
    fun oldServerAndDeletedMemberFallBackToHistoricalIdentity() {
        val detail = AiTeamRunDetail(run, listOf(step))
        assertEquals("旧群名", detail.presentationTeam?.name)
        assertSame(original, displayTeamTurn(original, detail))
        val removed = detail.copy(displayTeam = newTeam.copy(members = emptyList()))
        assertSame(original, displayTeamTurn(original, removed))
    }

    @Test
    fun detailParsesDisplaySeparatelyFromRunSnapshot() {
        val raw = JSONObject("""{
            "run":{"id":"run","teamId":"team","team":{"id":"team","name":"旧群名",
                "members":[{"id":"dev","name":"旧名字","duty":"实现"}]},"taskId":"task"},
            "displayTeam":{"id":"team","name":"新群名",
                "members":[{"id":"dev","name":"新名字","duty":"实现"}]},"steps":[],"chatTurns":[]
        }""")
        val detail = AiTeamRunDetail.parse(raw)!!
        assertEquals("旧名字", detail.run.team?.members?.single()?.name)
        assertEquals("新名字", detail.presentationTeam?.members?.single()?.name)
    }
}
