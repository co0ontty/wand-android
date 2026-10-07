package com.wand.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无指派派工的契约与选择规则（真源 src/team-dispatch.ts）。
 * 这些规则决定“确认开工”能提交什么，所以解析与边界都在这里守住。
 */
class TeamDispatchModelsTest {

    private fun member(
        id: String,
        name: String = id,
        probability: Double = 0.5,
        isLeader: Boolean = false,
    ) = TeamDispatchMember(
        employeeId = id, name = name, duty = "$name 的职责", tags = listOf("标签"),
        avatar = "", probability = probability, isLeader = isLeader,
    )

    @Test
    fun planParsingKeepsRosterBenchAndDecisionUsage() {
        val payload = JSONObject()
            .put("members", JSONArray().put(memberJson("e_a", "前端小美", 0.95, leader = true)).put(memberJson("e_b", "测试小周", 0.61)))
            .put("bench", JSONArray().put(memberJson("e_c", "后端小强", 0.51)))
            .put("considered", 4).put("omitted", 0)
            .put("threshold", 0.5).put("maxMembers", 3)
            .put("note", "从 4 名候选里建议 2 人（1 次本地判断）。")
            .put("decision", JSONObject().put("calls", 1).put("model", "aac6fef/laya-multilingual-mlx").put("inputTokens", 120))

        val plan = TeamDispatchPlan.parse(payload)!!
        assertEquals(listOf("e_a", "e_b"), plan.members.map { it.employeeId })
        assertEquals("前端小美", plan.members[0].name)
        assertEquals(listOf("标签"), plan.members[0].tags)
        assertEquals(0.95, plan.members[0].probability, 0.0001)
        assertTrue(plan.members[0].isLeader)
        assertEquals(listOf("e_c"), plan.bench.map { it.employeeId })
        assertEquals(4, plan.considered)
        assertEquals(1, plan.calls)
        assertEquals("aac6fef/laya-multilingual-mlx", plan.model)
        assertEquals("从 4 名候选里建议 2 人（1 次本地判断）。", plan.note)
    }

    @Test
    fun planParsingDegradesWithoutCrashing() {
        // 缺 employeeId 的行不能冒充候选；缺 decision 时用量退化为 0，不抛错。
        val payload = JSONObject()
            .put("members", JSONArray().put(JSONObject().put("name", "没有 id")))
            .put("bench", JSONArray())
        val plan = TeamDispatchPlan.parse(payload)!!
        assertTrue(plan.members.isEmpty())
        assertEquals(0, plan.calls)
        assertNull(plan.model)
        assertEquals(AI_TEAM_MAX_MEMBERS, plan.maxMembers)
        assertNull(TeamDispatchPlan.parse(null))
        assertNull(TeamDispatchMember.parse(JSONObject()))
    }

    @Test
    fun dispatchRunParsingCarriesTeamAndTask() {
        val payload = JSONObject()
            .put("run", JSONObject().put("id", "run_1").put("teamId", "t_dispatch_1").put("taskId", "task_1")
                .put("objective", "做点事").put("status", "running").put("team", JSONObject().put("members", JSONArray())))
            .put("steps", JSONArray())
            .put("teamId", "t_dispatch_1").put("taskId", "task_1")
        val started = AiTeamDispatchRun.parse(payload)!!
        assertEquals("t_dispatch_1", started.teamId)
        assertEquals("task_1", started.taskId)
        assertEquals("run_1", started.detail.run?.id)
    }

    @Test
    fun initialSelectionFollowsServerLeaderThenFallsBack() {
        val plan = TeamDispatchPlan(
            members = listOf(member("e_a", probability = 0.9), member("e_b", probability = 0.7, isLeader = true)),
            bench = emptyList(), considered = 2, omitted = 0, threshold = 0.5, maxMembers = 3,
            note = "", calls = 1, model = null,
        )
        assertEquals("e_b", TeamDispatchSelection.initial(plan).leaderId)
        val noLeader = plan.copy(members = plan.members.map { it.copy(isLeader = false) })
        assertEquals("e_a", TeamDispatchSelection.initial(noLeader).leaderId)
        assertEquals("", TeamDispatchSelection.initial(plan.copy(members = emptyList())).leaderId)
    }

    @Test
    fun toggleKeepsLeaderValidAndReordersByProbability() {
        val selection = TeamDispatchSelection(
            members = listOf(member("e_a", probability = 0.9), member("e_b", probability = 0.7)),
            leaderId = "e_a",
        )
        val withoutLeader = toggleDispatchMember(selection, selection.members[0])
        assertEquals(listOf("e_b"), withoutLeader.members.map { it.employeeId })
        assertEquals("e_b", withoutLeader.leaderId)

        val added = toggleDispatchMember(withoutLeader, member("e_c", probability = 0.99))
        assertEquals(listOf("e_c", "e_b"), added.members.map { it.employeeId })
        assertEquals("e_b", added.leaderId)

        // 名单上限：满了就不再接受新成员（原状态返回）
        val full = TeamDispatchSelection((1..8).map { member("e_$it", probability = 0.9 - it * 0.01) }, "e_1")
        assertEquals(full, toggleDispatchMember(full, member("e_9")))
        assertFalse(canAddDispatchMember(full))
    }

    @Test
    fun leaderOnlyAmongSelectedAndPicksCarryOneLeader() {
        val selection = TeamDispatchSelection(
            members = listOf(member("e_a", probability = 0.9), member("e_b", probability = 0.7)),
            leaderId = "e_a",
        )
        assertEquals("e_b", setDispatchLeader(selection, "e_b").leaderId)
        assertEquals("e_a", setDispatchLeader(selection, "e_不在名单").leaderId)
        val picks = dispatchSelectionPicks(setDispatchLeader(selection, "e_b"))
        assertEquals(listOf("e_a" to false, "e_b" to true), picks.map { it.employeeId to it.isLeader })
    }

    @Test
    fun startBlockedReasonCoversProjectNoteAndHeadcount() {
        val selection = TeamDispatchSelection(listOf(member("e_a"), member("e_b")), "e_a")
        assertEquals("", dispatchStartBlockedReason(selection, "w1", "做点事", busy = false))
        assertEquals("正在处理…", dispatchStartBlockedReason(selection, "w1", "做点事", busy = true))
        assertEquals("先写清这次要做什么。", dispatchStartBlockedReason(selection, "w1", "   ", busy = false))
        assertEquals("先选一个项目。", dispatchStartBlockedReason(selection, "", "做点事", busy = false))
        assertTrue(
            dispatchStartBlockedReason(TeamDispatchSelection(listOf(member("e_a")), "e_a"), "w1", "做点事", busy = false)
                .contains("至少需要 ${AI_TEAM_MIN_MEMBERS} 名员工"),
        )
    }

    @Test
    fun probabilityLabelIsBoundedPercent() {
        assertEquals("95%", dispatchProbabilityLabel(0.954))
        assertEquals("0%", dispatchProbabilityLabel(0.0))
        assertEquals("100%", dispatchProbabilityLabel(1.4))
        assertEquals("", dispatchProbabilityLabel(Double.NaN))
    }

    private fun memberJson(id: String, name: String, probability: Double, leader: Boolean = false): JSONObject =
        JSONObject()
            .put("employeeId", id).put("name", name).put("duty", "$name 的职责")
            .put("tags", JSONArray().put("标签")).put("avatar", "")
            .put("probability", probability).put("isLeader", leader)
}
