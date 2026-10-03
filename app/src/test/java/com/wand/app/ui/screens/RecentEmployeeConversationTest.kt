package com.wand.app.ui.screens

import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.SiliconEmployee
import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.TaskWindowLayout
import com.wand.app.data.WandApiException
import com.wand.app.data.Workspace
import com.wand.app.data.WorkspaceBinding
import com.wand.app.data.WorkspacePort
import com.wand.app.data.WorkspaceSessionKind
import com.wand.app.data.WorkspaceSessionSummary
import com.wand.app.data.WorkspaceSessionTarget
import com.wand.app.data.WorkspaceTask
import com.wand.app.data.WorkspaceTaskDetail
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RecentEmployeeConversationTest {
    private fun employee(id: String = "e-1") = SiliconEmployee(
        id, "虎妞", "开发", "角色规则", "",
        listOf(BoardTaskAgent.default("codex"), BoardTaskAgent.default("pi")),
    )

    private fun directory(id: String = "ws-1", synthetic: Boolean = false) = TaskDirectoryGroup(
        workspaceId = id, workspaceName = "项目-$id", workspaceCwd = "/repo",
        synthetic = synthetic, tasks = emptyList(), standaloneSessions = emptyList(),
    )

    private fun group(directory: TaskDirectoryGroup = directory()) = HomeGroup(
        key = "employee:e-1", kind = HomeGroupKind.Employee, title = "旧名字", employeeId = "e-1",
        conversations = listOf(HomeRecentConversation(directory, null,
            WorkspaceSessionSummary.parse(JSONObject().put("id", "old-session")
                .put("employeeId", "e-1").put("provider", "claude")
                .put("workspaceTaskId", "old-task").put("cwd", "/repo/.wand-worktrees/old"))!!)),
    )

    private fun request() = RecentEmployeeConversation("e-1", WorkspaceBinding("ws-1", cwd = "/repo"))

    @Test
    fun onlyCurrentAvailableEmployeeCanReceiveNewConversation() {
        val group = group()
        val renamed = employee().copy(name = "新名字")
        assertEquals(renamed, homeGroupAssignableEmployee(group, listOf(renamed)))
        for (unavailable in listOf(emptyList(), listOf(employee("another")),
            listOf(employee().copy(archivedAt = "2026-10-01")),
            listOf(employee().copy(agents = emptyList())))) {
            assertNull(homeGroupAssignableEmployee(group, unavailable))
        }
        assertNull(homeGroupAssignableEmployee(group.copy(kind = HomeGroupKind.Team), listOf(renamed)))
        assertNull(homeGroupAssignableEmployee(group.copy(kind = HomeGroupKind.Pty), listOf(renamed)))
    }

    @Test
    fun newConversationUsesProjectDirectoryWithoutOldTaskOrWorktree() {
        assertEquals(WorkspaceBinding("ws-1", cwd = "/repo"), homeGroupConversationBinding(group()))
        assertEquals(WorkspaceBinding(cwd = "/repo"),
            homeGroupConversationBinding(group(directory("cwd:/repo", synthetic = true))))
        assertEquals(WorkspaceBinding(cwd = "/repo"),
            homeGroupConversationBinding(group(directory("wand-global"))))
        assertNull(homeGroupConversationBinding(group().copy(conversations = emptyList())))
    }

    @Test
    fun oneClickCreatesBlankEmployeeConversationWithoutPromptOrCliDefaults() = runBlocking {
        val api = FakeWorkspacePort()
        val request = request()
        val created = request.create(api, employee())
        assertEquals(CreateRequest("e-1", WorkspaceBinding("ws-1", cwd = "/repo"), null), api.creates.single())
        assertEquals("created-session", created?.id)
        assertSame(created, request.snapshot)
        assertTrue(created!!.isStructured)
        assertTrue(created.messages.isNullOrEmpty())
        assertNull(request.error)
        assertFalse(request.busy)
        assertNull(request.create(api, employee()))
        assertEquals(1, api.creates.size)
    }

    @Test
    fun laterDeliberatePlusCanCreateAnotherBlankConversation() = runBlocking {
        val api = FakeWorkspacePort()
        assertNotNull(request().create(api, employee()))
        assertNotNull(request().create(api, employee()))
        assertEquals(2, api.creates.size)
        assertTrue(api.creates.all { it.prompt == null && it.binding.workspaceTaskId == null })
    }

    @Test
    fun unavailableEmployeeNeverCallsCliOrCreatesSession() = runBlocking {
        val api = FakeWorkspacePort()
        val request = request()
        for (unavailable in listOf(null, employee("other"),
            employee().copy(archivedAt = "2026-10-01"), employee().copy(agents = emptyList()))) {
            assertNull(request.create(api, unavailable))
            assertNotNull(request.error)
        }
        assertTrue(api.creates.isEmpty())
    }

    @Test
    fun duplicateClickWhileCreatingDoesNotCreateTwoSessions() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val api = FakeWorkspacePort().apply { createGate = gate }
        val request = request()
        val creating = async { request.create(api, employee()) }
        api.createStarted.await()
        assertTrue(request.busy)
        assertNull(request.create(api, employee()))
        gate.complete(Unit)
        assertNotNull(creating.await())
        assertEquals(1, api.creates.size)
        assertFalse(request.busy)
    }

    @Test
    fun explicitRejectionAllowsRetryButKeepsEmployeeAndDirectory() = runBlocking {
        val api = FakeWorkspacePort().apply { failure = WandApiException(400, "项目不可用") }
        val request = request()
        assertNull(request.create(api, employee()))
        assertEquals("项目不可用", request.error)
        assertFalse(request.creationUnconfirmed)
        api.failure = null
        assertNotNull(request.create(api, employee()))
        assertEquals(2, api.creates.size)
        assertTrue(api.creates.all { it.employeeId == "e-1" && it.binding.cwd == "/repo" })
    }

    @Test
    fun unknownCreationNeverBlindlyCreatesDuplicate() = runBlocking {
        for (status in listOf(null, 500, 408, 409)) {
            val api = FakeWorkspacePort().apply { failure = WandApiException(status, "结果未知") }
            val request = request()
            assertNull(request.create(api, employee()))
            assertTrue(request.creationUnconfirmed)
            assertTrue(request.error!!.contains("核对"))
            assertNull(request.create(api, employee()))
            assertEquals(1, api.creates.size)
        }
    }

    @Test
    fun malformedPtyOrWrongEmployeeAckNeverOpensWrongConversation() = runBlocking {
        for (response in listOf(snapshot().copy(id = ""), snapshot().copy(sessionKind = "pty", runner = "pty"),
            snapshot().copy(employeeId = "other"))) {
            val api = FakeWorkspacePort().apply { result = response }
            val request = request()
            assertNull(request.create(api, employee()))
            assertNull(request.snapshot)
            assertTrue(request.creationUnconfirmed)
            assertNull(request.create(api, employee()))
            assertEquals(1, api.creates.size)
        }
    }

    @Test
    fun cancellationKeepsUnknownDeliveryProtection() {
        val api = FakeWorkspacePort().apply { failure = CancellationException("页面离开") }
        val request = request()
        assertThrows(CancellationException::class.java) { runBlocking { request.create(api, employee()) } }
        assertTrue(request.creationUnconfirmed)
        assertFalse(request.busy)
    }

    @Test
    fun employeeEntryHidesCliBadgeButConversationRowsKeepActualProvider() {
        val chrome = File("src/main/java/com/wand/app/ui/screens/HomeChrome.kt").readText()
        val header = chrome.substringAfter("private fun HomeGroupHeader(")
            .substringBefore("/** 分组卡里")
        assertTrue(header.contains("EmployeeAvatar("))
        assertFalse(header.contains("group.provider"))
        val recentRow = chrome.substringAfter("private fun HomeGroupSessionRow(")
            .substringBefore("// MARK: - 会话行")
        assertTrue(recentRow.contains("showEmployeeCliBadge = false"))
        val sessionRow = chrome.substringAfter("internal fun HomeSessionRow(")
        assertTrue(sessionRow.contains("session.provider.takeIf { showEmployeeCliBadge }"))
        assertTrue(sessionRow.contains("WandProviderMark(provider = session.provider"))
    }

    @Test
    fun plusCreatesAndRoutesDirectlyWithoutAssignmentFormAndIgnoresLateNavigation() {
        val chrome = File("src/main/java/com/wand/app/ui/screens/HomeChrome.kt").readText()
        val card = chrome.substringAfter("internal fun HomeGroupCard(").substringBefore("/** 一级行")
        assertTrue(card.contains("trailingAction = conversationButton"))
        assertTrue(card.contains("WandStatusIconSlot("))
        assertTrue(card.contains("Modifier.size(44.dp)"))
        assertFalse(card.contains("assignmentExpanded"))
        val screen = File("src/main/java/com/wand/app/ui/screens/TaskListScreen.kt").readText()
        assertTrue(screen.contains("request.create(api,"))
        assertTrue(screen.contains("openingId == recentConversationOpeningId"))
        assertTrue(screen.contains("onOpenSession(TaskSessionRoute("))
        assertFalse(screen.contains("RecentEmployeeTaskComposer"))
        assertFalse(screen.contains("dispatchBoardSubject"))
    }

    private data class CreateRequest(val employeeId: String, val binding: WorkspaceBinding, val prompt: String?)

    private class FakeWorkspacePort : WorkspacePort {
        val creates = mutableListOf<CreateRequest>()
        val createStarted = CompletableDeferred<Unit>()
        var createGate: CompletableDeferred<Unit>? = null
        var failure: Exception? = null
        var result = snapshot()
        override suspend fun createEmployeeWorkspaceTaskWindow(
            employeeId: String, binding: WorkspaceBinding, prompt: String?,
        ): SessionSnapshot {
            creates += CreateRequest(employeeId, binding, prompt)
            createStarted.complete(Unit)
            createGate?.await()
            failure?.let { throw it }
            return result
        }
        override suspend fun createWorkspaceTaskWindow(
            target: WorkspaceSessionTarget, binding: WorkspaceBinding, kind: WorkspaceSessionKind,
            prompt: String?, model: String?, thinkingEffort: String?,
        ): SessionSnapshot = error("不得回退 CLI")
        override suspend fun listWorkspaces(): List<Workspace> = error("unused")
        override suspend fun listWorkspaceTasks(workspaceId: String): List<WorkspaceTask> = error("unused")
        override suspend fun renameWorkspaceTask(taskId: String, name: String): WorkspaceTask = error("unused")
        override suspend fun deleteWorkspaceTask(taskId: String) = error("unused")
        override suspend fun workspaceTask(taskId: String): WorkspaceTaskDetail = error("不得读取旧任务")
        override suspend fun saveWorkspaceTaskLayout(taskId: String, layout: TaskWindowLayout?): TaskWindowLayout? =
            error("不得改旧任务布局")
    }

    companion object {
        private fun snapshot() = SessionSnapshot.parse(JSONObject().put("id", "created-session")
            .put("employeeId", "e-1").put("sessionKind", "structured").put("runner", "codex-cli")
            .put("provider", "codex").put("cwd", "/repo"))
    }
}
