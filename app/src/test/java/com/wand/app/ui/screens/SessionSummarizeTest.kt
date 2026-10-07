package com.wand.app.ui.screens

import com.wand.app.data.GLOBAL_WORKSPACE_ID
import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.Workspace
import com.wand.app.data.WorkspacePort
import com.wand.app.data.WorkspaceSessionKind
import com.wand.app.data.WorkspaceSessionTarget
import com.wand.app.data.WorkspaceTask
import com.wand.app.data.WorkspaceTaskCreation
import com.wand.app.data.WorkspaceTaskDetail
import com.wand.app.data.WorkspaceTaskStatus
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.TaskWindowLayout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 未分组会话「归纳为新任务」的建卡方式（纯逻辑）：
 * 真实项目在项目下建卡；合成目录与全局空间没有可建卡的项目实体，挂会话所在目录建独立任务。
 * 名字一律留空 —— 服务端先按会话内容写临时标题，再交给模型改写。
 */
class SessionSummarizeTest {

    private data class CardRequest(
        val workspaceId: String?,
        val name: String,
        val cwd: String?,
        val worktree: Boolean?,
    )

    private class FakePort : WorkspacePort {
        val cardRequests = mutableListOf<CardRequest>()
        val moves = mutableListOf<Pair<String, String>>()

        override suspend fun createWorkspaceTask(
            workspaceId: String,
            name: String,
            baseRef: String?,
            worktree: Boolean?,
            cwd: String?,
            description: String?,
            parentTaskId: String?,
        ): WorkspaceTaskCreation {
            cardRequests += CardRequest(workspaceId, name, cwd, worktree)
            return creation("card-project-${cardRequests.size}", workspaceId)
        }

        override suspend fun createStandaloneTask(
            name: String,
            cwd: String?,
            worktree: Boolean?,
            description: String?,
            parentTaskId: String?,
        ): WorkspaceTaskCreation {
            cardRequests += CardRequest(null, name, cwd, worktree)
            return creation("card-standalone-${cardRequests.size}", GLOBAL_WORKSPACE_ID)
        }

        override suspend fun moveWorkspaceSession(taskId: String, sessionId: String) {
            moves += taskId to sessionId
        }

        private fun creation(id: String, workspaceId: String) = WorkspaceTaskCreation(
            id = id,
            workspaceId = workspaceId,
            name = "",
            worktree = null,
            status = WorkspaceTaskStatus.Active,
            cwd = "/repo",
        )

        override suspend fun listWorkspaces(): List<Workspace> = emptyList()
        override suspend fun listWorkspaceTasks(workspaceId: String): List<WorkspaceTask> = emptyList()
        override suspend fun renameWorkspaceTask(taskId: String, name: String): WorkspaceTask = error("unused")
        override suspend fun deleteWorkspaceTask(taskId: String) = error("unused")
        override suspend fun workspaceTask(taskId: String): WorkspaceTaskDetail = error("unused")
        override suspend fun saveWorkspaceTaskLayout(
            taskId: String,
            layout: TaskWindowLayout?,
        ): TaskWindowLayout? = error("unused")

        override suspend fun createWorkspaceTaskWindow(
            target: WorkspaceSessionTarget,
            binding: com.wand.app.data.WorkspaceBinding,
            kind: WorkspaceSessionKind,
            prompt: String?,
            model: String?,
            thinkingEffort: String?,
        ): SessionSnapshot = error("归纳只建卡与移动，不起新会话")
    }

    private fun group(workspaceId: String, cwd: String, synthetic: Boolean, global: Boolean) =
        TaskDirectoryGroup(
            workspaceId = workspaceId,
            workspaceName = workspaceId,
            workspaceCwd = cwd,
            synthetic = synthetic,
            tasks = emptyList(),
            standaloneSessions = emptyList(),
            global = global,
        )

    @Test
    fun existingProjectSummarizesIntoProjectCardWithoutName() = runBlocking {
        val port = FakePort()

        val card = summarizeSessionIntoNewTask(
            port,
            group("ws-1", "/repo", synthetic = false, global = false),
            "s-1",
        )

        assertEquals("card-project-1", card.id)
        assertEquals(listOf(CardRequest("ws-1", "", null, false)), port.cardRequests)
        assertEquals(listOf("card-project-1" to "s-1"), port.moves)
    }

    @Test
    fun syntheticDirectoryMountsStandaloneCardOnItsOwnPath() = runBlocking {
        val port = FakePort()

        val card = summarizeSessionIntoNewTask(
            port,
            group("cwd:/tmp/scratch", "/tmp/scratch", synthetic = true, global = false),
            "s-2",
        )

        assertEquals("card-standalone-1", card.id)
        assertEquals(listOf(CardRequest(null, "", "/tmp/scratch", false)), port.cardRequests)
        assertEquals(listOf("card-standalone-1" to "s-2"), port.moves)
    }

    @Test
    fun globalScratchNeverBuildsAProjectCard() = runBlocking {
        val port = FakePort()

        summarizeSessionIntoNewTask(
            port,
            group(GLOBAL_WORKSPACE_ID, "", synthetic = false, global = true),
            "s-3",
        )

        // 全局暂存区没有工作目录可挂，cwd 留空由服务端用自己的临时目录兜底。
        assertEquals(listOf(CardRequest(null, "", null, false)), port.cardRequests)
        assertEquals(listOf("card-standalone-1" to "s-3"), port.moves)
    }
}
