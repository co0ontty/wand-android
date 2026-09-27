package com.wand.app.ui.screens

import com.wand.app.data.AiTeam
import com.wand.app.data.AiTeamDirectRun
import com.wand.app.data.AiTeamMember
import com.wand.app.data.AiTeamRun
import com.wand.app.data.AiTeamRunDetail
import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 「直接开工」表单与团队只读展示的纯函数（契约 docs/ai-teams-v2-design.md §4.2/§6.2）。 */
class AiTeamPresentationTest {

    private fun workspace(id: String, cwd: String = "/repo/$id") = Workspace(
        id = id,
        name = "项目-$id",
        cwd = cwd,
        defaultProvider = null,
        layout = null,
        createdAt = null,
        lastOpenedAt = null,
    )

    private fun member(id: String, name: String, leader: Boolean, agents: List<BoardTaskAgent>) =
        AiTeamMember(id = id, name = name, duty = "做 $name", agents = agents, isLeader = leader)

    private fun agent(provider: String) = BoardTaskAgent(provider, "default", "off")

    @Test
    fun projectCandidatesDropGlobalAndCwdless() {
        val projects = listOf(
            workspace("ws_a"),
            workspace("wand-global", cwd = "/config/scratch"),
            workspace("ws_b", cwd = "  "),
        )
        // global 暂存区（服务端 400 口径）与无工作目录的项目都不给选。
        assertEquals(listOf("ws_a"), teamStartProjectCandidates(projects).map { it.id })
        assertEquals("ws_a", defaultTeamStartProjectId(projects))
    }

    @Test
    fun defaultProjectIsFirstCandidate() {
        // 服务端列表按创建时间倒序返回，第一个即「最近一个」。
        val projects = listOf(workspace("newest"), workspace("older"))
        assertEquals("newest", defaultTeamStartProjectId(projects))
        assertEquals("", defaultTeamStartProjectId(listOf(workspace("wand-global"))))
    }

    @Test
    fun submitErrorCoversNoProjectAndNoteLimits() {
        assertNotNull(teamDirectSubmitError(emptyList(), "干活"))
        assertNotNull(teamDirectSubmitError(listOf(workspace("wand-global")), "干活"))
        // trim 后为空 → 「请填写开工说明」（服务端 boundedText 同口径）。
        assertEquals("请填写开工说明。", teamDirectSubmitError(listOf(workspace("ws_a")), "  \n "))
        // 首尾空白不吃配额：4000 字 + 空白仍然合法。
        assertNull(teamDirectNoteError(" " + "字".repeat(TEAM_DIRECT_NOTE_MAX) + " "))
        assertNull(teamDirectNoteError("字".repeat(TEAM_DIRECT_NOTE_MAX)))
        val longNote = "字".repeat(TEAM_DIRECT_NOTE_MAX + 1)
        assertEquals(
            "开工说明不能超过 $TEAM_DIRECT_NOTE_MAX 个字符。",
            teamDirectSubmitError(listOf(workspace("ws_a")), longNote),
        )
        assertNull(teamDirectSubmitError(listOf(workspace("ws_a")), "按计划实现并验收"))
    }

    @Test
    fun orderedMembersPutLeaderFirst() {
        val team = AiTeam(
            id = "team_1",
            name = "开发三人组",
            description = "",
            members = listOf(
                member("m_work", "实现者", false, listOf(agent("claude"))),
                member("m_lead", "负责人", true, listOf(agent("codex"), agent("qoder"))),
                member("m_review", "审查者", false, listOf(agent("grok"))),
            ),
        )
        assertEquals(listOf("m_lead", "m_work", "m_review"), aiTeamOrderedMembers(team).map { it.id })
        assertEquals("开发三人组", team.name)
        assertEquals("3 位成员 · 负责人 负责人", aiTeamSummaryLine(team))
        val leader = aiTeamLeader(team)!!
        assertEquals("codex", aiTeamPreferredAgent(leader)?.provider)
        assertEquals("首选", aiTeamCandidateRoleLabel(0))
        assertEquals("备用 1", aiTeamCandidateRoleLabel(1))
        assertEquals("备用 3", aiTeamCandidateRoleLabel(3))
    }

    @Test
    fun directRunTaskIdFallsBackToRunSnapshot() {
        val run = AiTeamRun.parse(
            org.json.JSONObject().put("id", "run_1").put("taskId", "t_from_run"),
        )!!
        val detail = AiTeamRunDetail(run = run, steps = emptyList())
        assertEquals("t_direct", aiTeamDirectRunTaskId(AiTeamDirectRun(detail, "t_direct")))
        assertEquals("t_from_run", aiTeamDirectRunTaskId(AiTeamDirectRun(detail, "")))
    }

    @Test
    fun summaryWithoutLeaderOmitsLeaderPart() {
        val team = AiTeam(
            id = "team_2",
            name = "无人负责",
            description = "",
            members = listOf(member("m_a", "甲", false, emptyList())),
        )
        assertEquals("1 位成员", aiTeamSummaryLine(team))
        assertNull(aiTeamLeader(team))
    }
}
