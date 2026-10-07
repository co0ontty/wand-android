package com.wand.app.ui.screens

import com.wand.app.data.AiTeamDispatchRun
import com.wand.app.data.BoardDispatchResult
import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.ModelsResponse
import com.wand.app.data.Workspace
import com.wand.app.data.AiTeamRun
import com.wand.app.data.AiTeamRunDetail
import com.wand.app.data.BoardTask
import com.wand.app.data.TaskBoardPort
import com.wand.app.data.TeamDispatchMember
import com.wand.app.data.TeamDispatchPlan
import com.wand.app.data.TeamDispatchSelection
import com.wand.app.data.WorkspaceSessionKind
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三个入口（通讯录面板 / 首页新建任务 / 看板新建任务）共用的派工流程：
 * 「让决策模型选人 → 建议名单 → 确认开工」的状态机与错误原位反馈。
 */
class TeamDispatchFlowStateTest {

    private fun member(id: String, probability: Double = 0.9, leader: Boolean = false) = TeamDispatchMember(
        employeeId = id, name = id, duty = "$id 的职责", tags = emptyList(), avatar = "",
        probability = probability, isLeader = leader,
    )

    private fun plan(vararg ids: String) = TeamDispatchPlan(
        members = ids.mapIndexed { index, id -> member(id, 0.9 - index * 0.1, leader = index == 0) },
        bench = emptyList(), considered = ids.size, omitted = 0, threshold = 0.5, maxMembers = 3,
        note = "从 ${ids.size} 名候选里建议 ${ids.size} 人。", calls = 1, model = "aac6fef/laya-multilingual-mlx",
    )

    private class FakeDispatchPort : TaskBoardPort {
        var planFailure: Exception? = null
        var startFailure: Exception? = null
        var planned: TeamDispatchPlan = TeamDispatchPlan(
            members = emptyList(), bench = emptyList(), considered = 0, omitted = 0, threshold = 0.5,
            maxMembers = 3, note = "", calls = 0, model = null,
        )
        val planCalls = mutableListOf<Pair<String, Int?>>()
        val startCalls = mutableListOf<Triple<String, String, List<Pair<String, Boolean>>>>()

        override suspend fun planTeamDispatch(note: String, maxMembers: Int?): TeamDispatchPlan {
            planCalls += note to maxMembers
            planFailure?.let { throw it }
            return planned
        }

        override suspend fun startTeamDispatch(
            workspaceId: String,
            note: String,
            members: List<com.wand.app.data.TeamDispatchPick>,
        ): AiTeamDispatchRun {
            startCalls += Triple(workspaceId, note, members.map { it.employeeId to it.isLeader })
            startFailure?.let { throw it }
            return AiTeamDispatchRun(
                detail = AiTeamRunDetail(
                    run = AiTeamRun(
                        id = "run-1", teamId = "t_dispatch_1", team = null, taskId = "task_1",
                        objective = note, status = "running", statusDetail = "",
                        stepsUsed = 0, stepLimit = 30, chatSessionId = "chat-1",
                    ),
                    steps = emptyList(),
                ),
                teamId = "t_dispatch_1",
                taskId = "task_1",
            )
        }

        override suspend fun listBoardTasks(workspaceId: String?): List<BoardTask> = error("unused")
        override suspend fun getBoardTask(id: String): BoardTask? = error("unused")
        override suspend fun updateBoardTask(id: String, body: JSONObject): BoardTask = error("unused")
        override suspend fun deleteBoardTask(id: String) = error("unused")
        override suspend fun createBoardTask(
            title: String, description: String, status: String, priority: String,
            workspaceId: String?, agent: BoardTaskAgent?, parentTaskId: String?,
        ): BoardTask = error("unused")

        override suspend fun dispatchBoardTask(
            id: String, agent: BoardTaskAgent, prompt: String?, workspaceId: String?,
        ): BoardDispatchResult = error("unused")

        override suspend fun listBoardWorkspaces(): List<Workspace> = error("unused")
        override suspend fun boardModels(): ModelsResponse = error("unused")
        override suspend fun boardTaskAgentDefaults(): BoardTaskAgent = error("unused")
        override suspend fun saveBoardTaskAgentDefaults(agent: BoardTaskAgent): BoardTaskAgent = error("unused")
    }

    @Test
    fun planThenSubmitWalksTheSharedFlow() = runBlocking {
        val port = FakeDispatchPort().apply { planned = plan("e_a", "e_b") }
        val flow = TeamDispatchFlowState()
        assertEquals(TeamDispatchPhase.Idle, flow.phase)
        assertTrue(!flow.hasPlan)

        flow.loadPlan(port, "  做一个登录页  ")
        assertEquals(listOf("做一个登录页" to 3), port.planCalls)
        assertEquals(TeamDispatchPhase.Planned, flow.phase)
        assertEquals(listOf("e_a", "e_b"), flow.selection.members.map { it.employeeId })
        assertEquals("e_a", flow.selection.leaderId)

        val started = flow.submit(port, "w1", "做一个登录页")
        assertEquals("t_dispatch_1", started?.teamId)
        assertEquals(listOf(Triple("w1", "做一个登录页", listOf("e_a" to true, "e_b" to false))), port.startCalls)
        // 成功后清空名单，避免同一份名单被再提交一次
        assertNull(flow.plan)
        assertEquals(TeamDispatchPhase.Idle, flow.phase)
    }

    @Test
    fun planRequiresANoteAndKeepsSelectionEmptyOnFailure() = runBlocking {
        val port = FakeDispatchPort()
        val flow = TeamDispatchFlowState()
        flow.loadPlan(port, "   ")
        assertTrue(port.planCalls.isEmpty())
        assertEquals(TeamDispatchPhase.Idle, flow.phase)

        port.planFailure = IllegalStateException("本地决策未启用（localDecision.enabled=false），无法用决策模型选人。")
        flow.loadPlan(port, "做点事")
        // 失败：就地在位报错，且回到 Idle 让按钮可再点；不编造名单。
        assertEquals(TeamDispatchPhase.Idle, flow.phase)
        assertEquals("本地决策未启用（localDecision.enabled=false），无法用决策模型选人。", flow.message)
        assertNull(flow.plan)
    }

    @Test
    fun submitRefusesWithoutPlanOrWorkspaceAndReportsServerError() = runBlocking {
        val port = FakeDispatchPort().apply { planned = plan("e_a", "e_b") }
        val flow = TeamDispatchFlowState()
        // 还没出名单：不提交
        assertNull(flow.submit(port, "w1", "做点事"))
        assertTrue(port.startCalls.isEmpty())

        flow.loadPlan(port, "做点事")
        // 没选项目：不提交（服务端也会拒，客户端先拦住）
        assertNull(flow.submit(port, "", "做点事"))
        assertTrue(port.startCalls.isEmpty())

        port.startFailure = IllegalStateException("团队开工至少需要 2 名员工（含负责人）。")
        assertNull(flow.submit(port, "w1", "做点事"))
        assertEquals(TeamDispatchPhase.Idle, flow.phase)
        assertEquals("团队开工至少需要 2 名员工（含负责人）。", flow.message)
        assertEquals(1, port.startCalls.size)
    }

    @Test
    fun maxMembersIsBoundedAndResetsStaleRoster() {
        val flow = TeamDispatchFlowState()
        flow.updateMaxMembers(99)
        assertEquals(com.wand.app.data.AI_TEAM_MAX_MEMBERS, flow.maxMembers)
        flow.updateMaxMembers(0)
        assertEquals(2, flow.maxMembers)
        flow.updateMaxMembers(4)
        assertEquals(4, flow.maxMembers)
    }

    @Test
    fun primaryActionLabelAndWorkspaceGuardMatchTheServerContract() {
        assertEquals("让决策模型选人", dispatchPrimaryActionLabel(TeamDispatchPhase.Idle, false))
        assertEquals("正在判断…", dispatchPrimaryActionLabel(TeamDispatchPhase.Planning, false))
        assertEquals("确认开工", dispatchPrimaryActionLabel(TeamDispatchPhase.Planned, true))
        assertEquals("正在开工…", dispatchPrimaryActionLabel(TeamDispatchPhase.Starting, true))
        assertEquals("已开工", dispatchPrimaryActionLabel(TeamDispatchPhase.Started, true))
        assertNull(dispatchWorkspaceBlockedReason("w1"))
        assertEquals("临时派工需要选择一个已有项目。", dispatchWorkspaceBlockedReason(""))
        assertEquals("临时派工需要选择一个已有项目。", dispatchWorkspaceBlockedReason(null))
        assertEquals("临时派工需要选择一个已有项目。", dispatchWorkspaceBlockedReason(com.wand.app.data.GLOBAL_WORKSPACE_ID))
    }

    @Test
    fun selectionGuardsComeFromTheDataLayer() {
        // 复用同一份规则：这里只确认流程对象没有另一套默认值。
        assertEquals(TeamDispatchSelection(), TeamDispatchFlowState().selection)
        assertEquals(WorkspaceSessionKind.Structured, WorkspaceSessionKind.Structured)
    }
}
