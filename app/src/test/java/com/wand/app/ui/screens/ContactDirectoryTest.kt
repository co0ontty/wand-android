package com.wand.app.ui.screens

import com.wand.app.data.AiTeam
import com.wand.app.data.AiTeamDirectRun
import com.wand.app.data.AiTeamRun
import com.wand.app.data.AiTeamRunDetail
import com.wand.app.data.BoardDispatchResult
import com.wand.app.data.BoardTask
import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.GLOBAL_WORKSPACE_ID
import com.wand.app.data.ModelsResponse
import com.wand.app.data.SiliconEmployee
import com.wand.app.data.TaskBoardPort
import com.wand.app.data.WandApiException
import com.wand.app.data.Workspace
import com.wand.app.data.WorkspaceBinding
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactDirectoryTest {
    private fun employee(
        id: String,
        name: String = id,
        archivedAt: String? = null,
        agents: List<BoardTaskAgent> = listOf(BoardTaskAgent.default("codex")),
        systemKey: String? = null,
    ) = SiliconEmployee(id, name, "", "", "", agents, systemKey, archivedAt)

    private fun team(id: String = "team-1", name: String = "开发组") =
        AiTeam(id, name, "", emptyList())

    private fun workspace(
        id: String = "ws-1",
        cwd: String = "/repo",
        createdAt: String? = "2026-10-02T00:00:00Z",
    ) = Workspace(id, "项目-$id", cwd, null, null, createdAt, null)

    @Test
    fun directoryKeepsEveryActiveEmployeeIncludingBuiltinAndDropsArchived() {
        val visible = contactDirectoryEmployees(listOf(
            employee("e_wand_ops", "勤劳的初二", systemKey = "wand-ops"),
            employee("e_wand_default", "赛博虎妞", systemKey = "wand-default"),
            employee("e_user", "架构师"),
            employee("e_old", "旧人", archivedAt = "2026-09-01"),
        ))
        assertEquals(listOf("e_wand_ops", "e_wand_default", "e_user"), visible.map { it.id })
    }

    @Test
    fun directoryListsTeamNamesWithoutFiltering() {
        val teams = listOf(team("t1", "一组"), team("t2", "二组"))
        assertEquals(teams, contactDirectoryTeams(teams))
    }

    @Test
    fun directorySearchMatchesNamesDutiesAndTagsWithoutRevivingArchivedEmployees() {
        val architect = employee("architect", "架构师").copy(duty = "Android Équipe", tags = listOf("研发"))
        val archived = architect.copy(id = "archived", archivedAt = "2026-10-02")
        val builtin = employee("ops", "勤劳的初二", systemKey = "wand-ops")
        val employees = listOf(architect, archived, builtin)
        assertEquals(listOf(architect), contactDirectoryEmployees(employees, "  ANDROID  研发  "))
        assertEquals(listOf(architect), contactDirectoryEmployees(employees, "équipe"))
        assertEquals(listOf(builtin), contactDirectoryEmployees(employees, "系统用户"))
        assertTrue(contactDirectoryEmployees(employees, "不存在").isEmpty())
        assertEquals(listOf(architect, builtin), contactDirectoryEmployees(employees, "  "))
    }

    @Test
    fun directoryTeamSearchFindsDescriptionAndMemberWithoutChangingOrder() {
        val first = team("one", "客户端组").copy(description = "Android 和 iOS")
        val second = team("two", "审查组").copy(members = listOf(
            com.wand.app.data.AiTeamMember("member", "安全专家", "检查", emptyList(), true),
        ))
        val teams = listOf(first, second)
        assertEquals(listOf(first), contactDirectoryTeams(teams, "android"))
        assertEquals(listOf(second), contactDirectoryTeams(teams, "审查 安全"))
        assertEquals(teams, contactDirectoryTeams(teams, "组"))
        assertTrue(contactDirectoryTeams(teams, "Android 安全").isEmpty())
    }

    @Test
    fun onlyCurrentAvailableEmployeeCanReceiveNewConversation() {
        val current = employee("e-1", "虎妞")
        assertEquals(current, contactAssignableEmployee("e-1", listOf(current)))
        assertNull(contactAssignableEmployee("e-1", emptyList()))
        assertNull(contactAssignableEmployee("e-1", listOf(employee("other"))))
        assertNull(contactAssignableEmployee("e-1", listOf(current.copy(archivedAt = "2026-10-01"))))
        assertNull(contactAssignableEmployee("e-1", listOf(current.copy(agents = emptyList()))))
    }

    @Test
    fun newConversationUsesDefaultDirectoryWithoutOldTask() {
        val project = workspace()
        assertEquals(
            WorkspaceBinding("ws-1", cwd = "/repo"),
            contactConversationBinding("/repo", listOf(project)),
        )
        assertEquals(
            WorkspaceBinding(cwd = "/repo"),
            contactConversationBinding("/repo", listOf(workspace(GLOBAL_WORKSPACE_ID, "/repo"))),
        )
        assertEquals(
            WorkspaceBinding("ws-1", cwd = "/repo"),
            contactConversationBinding(null, listOf(project)),
        )
        assertNull(contactConversationBinding(null, emptyList()))
        assertNull(contactConversationBinding("  ", listOf(workspace(GLOBAL_WORKSPACE_ID, ""))))
        assertEquals("ws-1", contactTeamStartWorkspaceId(listOf(project)))
        assertEquals("", contactTeamStartWorkspaceId(listOf(workspace(GLOBAL_WORKSPACE_ID, "/tmp"))))
    }

    @Test
    fun oneClickStartsTeamChatWithoutOpeningManagement() = runBlocking {
        val api = FakeTeamPort()
        val request = RecentTeamConversation("team-1", "ws-1")
        val created = request.create(api, team())
        assertEquals(listOf(StartRequest("team-1", "ws-1", CONTACT_TEAM_NEW_CHAT_NOTE)), api.starts)
        assertEquals("run-1", created?.detail?.run?.id)
        assertEquals("run-1", request.runId)
        assertNull(request.error)
        assertFalse(request.busy)
        assertNull(request.create(api, team()))
        assertEquals(1, api.starts.size)
    }

    @Test
    fun missingTeamOrProjectNeverStartsRun() = runBlocking {
        val api = FakeTeamPort()
        val request = RecentTeamConversation("team-1", "ws-1")
        assertNull(request.create(api, null))
        assertNull(request.create(api, team("other")))
        assertNotNull(request.error)
        val noProject = RecentTeamConversation("team-1", "")
        assertNull(noProject.create(api, team()))
        assertEquals("AI 团队需要先选择一个已有项目", noProject.error)
        assertTrue(api.starts.isEmpty())
    }

    @Test
    fun duplicateClickWhileCreatingDoesNotStartTwoRuns() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val api = FakeTeamPort().apply { startGate = gate }
        val request = RecentTeamConversation("team-1", "ws-1")
        val creating = async { request.create(api, team()) }
        api.startStarted.await()
        assertTrue(request.busy)
        assertNull(request.create(api, team()))
        gate.complete(Unit)
        assertNotNull(creating.await())
        assertEquals(1, api.starts.size)
        assertFalse(request.busy)
    }

    @Test
    fun unknownCreationNeverBlindlyCreatesDuplicate() = runBlocking {
        for (status in listOf(null, 500, 408, 409)) {
            val api = FakeTeamPort().apply { failure = WandApiException(status, "结果未知") }
            val request = RecentTeamConversation("team-1", "ws-1")
            assertNull(request.create(api, team()))
            assertTrue(request.creationUnconfirmed)
            assertTrue(request.error!!.contains("核对"))
            assertNull(request.create(api, team()))
            assertEquals(1, api.starts.size)
        }
    }

    @Test
    fun cancellationKeepsUnknownDeliveryProtection() {
        val api = FakeTeamPort().apply { failure = CancellationException("页面离开") }
        val request = RecentTeamConversation("team-1", "ws-1")
        assertThrows(CancellationException::class.java) {
            runBlocking { request.create(api, team()) }
        }
        assertTrue(request.creationUnconfirmed)
        assertFalse(request.busy)
    }

    @Test
    fun contactsLandingIsDirectoryNotManagement() {
        val screen = File("src/main/java/com/wand/app/ui/screens/ContactsScreen.kt").readText()
        assertTrue(screen.contains("点名字开新对话，点头像改资料"))
        assertTrue(screen.contains("管理\${employee.name}的信息"))
        assertTrue(screen.contains("与\${employee.name}新建对话"))
        assertTrue(screen.contains("与\${team.name}新建群聊"))
        assertTrue(screen.contains("RecentEmployeeConversation"))
        assertTrue(screen.contains("RecentTeamConversation"))
        assertTrue(screen.contains("onOpenEmployee(employee.id)"))
        assertFalse(screen.contains("SiliconEmployeeListSection"))
        assertFalse(screen.contains("AiTeamListSection"))
        assertFalse(screen.contains("showArchived"))
        assertTrue(screen.contains("onOpenTeam(team.id)"))
        assertFalse(screen.contains("ContactsTab"))
        val app = File("src/main/java/com/wand/app/ui/WandApp.kt").readText()
        val region = app.substringAfter("is Screen.Contacts -> ContactsScreen(")
            .substringBefore("is Screen.SiliconEmployeeEditor")
        assertTrue(region.contains("SiliconEmployeeEditor(it)"))
        assertTrue(region.contains("onOpenSession"))
        assertTrue(region.contains("route.toScreen()"))
        assertTrue(region.contains("AiTeamChat"))
        assertTrue(region.contains("onOpenTeam = { nav.push(Screen.AiTeamDetail(it)) }"))
        assertFalse(region.contains("onEditTeam"))
    }

    private data class StartRequest(val teamId: String, val workspaceId: String, val note: String)

    private class FakeTeamPort : TaskBoardPort {
        val starts = mutableListOf<StartRequest>()
        val startStarted = CompletableDeferred<Unit>()
        var startGate: CompletableDeferred<Unit>? = null
        var failure: Exception? = null
        var result = AiTeamDirectRun(
            detail = AiTeamRunDetail(
                run = AiTeamRun(
                    id = "run-1", teamId = "team-1", team = null, taskId = "task-1",
                    objective = CONTACT_TEAM_NEW_CHAT_NOTE, status = "running",
                    statusDetail = "", stepsUsed = 0, stepLimit = 30, chatSessionId = "chat-1",
                ),
                steps = emptyList(),
            ),
            taskId = "task-1",
        )

        override suspend fun startDirectTeamRun(
            teamId: String, workspaceId: String, note: String,
        ): AiTeamDirectRun {
            starts += StartRequest(teamId, workspaceId, note)
            startStarted.complete(Unit)
            startGate?.await()
            failure?.let { throw it }
            return result
        }

        override suspend fun listBoardTasks(workspaceId: String?): List<BoardTask> = error("unused")
        override suspend fun getBoardTask(id: String): BoardTask? = error("unused")
        override suspend fun createBoardTask(
            title: String, description: String, status: String, priority: String,
            workspaceId: String?, agent: BoardTaskAgent?, parentTaskId: String?,
        ): BoardTask = error("unused")
        override suspend fun updateBoardTask(id: String, body: JSONObject): BoardTask = error("unused")
        override suspend fun deleteBoardTask(id: String) = error("unused")
        override suspend fun dispatchBoardTask(
            id: String, agent: BoardTaskAgent, prompt: String?, workspaceId: String?,
        ): BoardDispatchResult = error("unused")
        override suspend fun listBoardWorkspaces(): List<Workspace> = error("unused")
        override suspend fun boardModels(): ModelsResponse = error("unused")
        override suspend fun boardTaskAgentDefaults(): BoardTaskAgent = error("unused")
        override suspend fun saveBoardTaskAgentDefaults(agent: BoardTaskAgent): BoardTaskAgent =
            error("unused")
    }
}
