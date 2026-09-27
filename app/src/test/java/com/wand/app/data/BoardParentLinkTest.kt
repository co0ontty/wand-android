package com.wand.app.data

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoardParentLinkTest {
    private class FakeBoardPort(var card: BoardTask?) : TaskBoardPort {
        var writes = 0
        override suspend fun listBoardTasks(workspaceId: String?): List<BoardTask> = listOfNotNull(card)
        override suspend fun getBoardTask(id: String): BoardTask? = card
        override suspend fun createBoardTask(
            title: String, description: String, status: String, priority: String, workspaceId: String?,
            agent: BoardTaskAgent?, parentTaskId: String?,
        ): BoardTask = error("unused")
        override suspend fun updateBoardTask(id: String, body: JSONObject): BoardTask {
            writes += 1
            assertEquals(card?.id, id)
            card = card!!.copy(parentTaskId = body.getString("parentTaskId"))
            return card!!
        }
        override suspend fun deleteBoardTask(id: String) = Unit
        override suspend fun dispatchBoardTask(
            id: String, agent: BoardTaskAgent, prompt: String?, workspaceId: String?,
        ): BoardDispatchResult = error("unused")
        override suspend fun listBoardWorkspaces(): List<Workspace> = emptyList()
        override suspend fun boardModels(): ModelsResponse = error("unused")
        override suspend fun boardTaskAgentDefaults(): BoardTaskAgent = BoardTaskAgent.default()
        override suspend fun saveBoardTaskAgentDefaults(agent: BoardTaskAgent): BoardTaskAgent = agent
    }

    private fun child(parentTaskId: String? = null): BoardTask = BoardTask.parse(
        JSONObject().put("id", "child-card").put("workspaceTaskId", "workspace-child")
            .put("title", "子任务").put("parentTaskId", parentTaskId),
    )!!

    @Test
    fun oldServerCreationIsLinkedByBoardPatch() = runBlocking {
        val api = FakeBoardPort(child())
        ensureBoardTaskParent(api, "workspace-child", "parent-card")
        assertEquals("parent-card", api.card?.parentTaskId)
        assertEquals(1, api.writes)
    }

    @Test
    fun newServerCreationNeedsNoSecondWrite() = runBlocking {
        val api = FakeBoardPort(child("parent-card"))
        ensureBoardTaskParent(api, "workspace-child", "parent-card")
        assertEquals(0, api.writes)
    }

    @Test
    fun missingCardFailsInsteadOfSilentlyClaimingSuccess() {
        val api = FakeBoardPort(null)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { ensureBoardTaskParent(api, "workspace-child", "parent-card") }
        }
    }
}
