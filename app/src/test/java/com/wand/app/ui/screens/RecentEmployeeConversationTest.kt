package com.wand.app.ui.screens

import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.SiliconEmployee
import com.wand.app.data.TaskDirectoryGroup
import com.wand.app.data.WorkspaceBinding
import com.wand.app.data.WorkspaceSessionSummary
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun onlyCurrentAvailableEmployeeCanBeSelectedForNewConversation() {
        val renamed = employee().copy(name = "新名字")
        assertEquals(renamed, homeGroupAssignableEmployee(group(), listOf(renamed)))
        for (unavailable in listOf(emptyList(), listOf(employee("another")),
            listOf(employee().copy(archivedAt = "2026-10-01")),
            listOf(employee().copy(agents = emptyList())))) {
            assertNull(homeGroupAssignableEmployee(group(), unavailable))
        }
        assertNull(homeGroupAssignableEmployee(group().copy(kind = HomeGroupKind.Team), listOf(renamed)))
        assertNull(homeGroupAssignableEmployee(group().copy(kind = HomeGroupKind.Pty), listOf(renamed)))
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
    fun plusRoutesToPendingEmployeeFormWithoutCreatingSession() {
        val screen = File("src/main/java/com/wand/app/ui/screens/TaskListScreen.kt").readText()
        val handler = screen.substringAfter("onCreateConversation = create@{")
            .substringBefore("                            )\n                        }")
        val employeePath = handler.substringAfter("return@create")
        assertTrue(employeePath.contains("onNewConversation("))
        assertTrue(employeePath.contains("employee.id"))
        assertFalse(employeePath.contains("createRecentTerminal"))
        assertFalse(employeePath.contains("createEmployeeWorkspaceTaskWindow"))

        val app = File("src/main/java/com/wand/app/ui/WandApp.kt").readText()
        assertTrue(app.contains("initialEmployeeId = employeeId"))
        assertTrue(app.contains("initialEmployeeId = screen.initialEmployeeId"))
        assertTrue(app.contains("initialCreateMode = screen.initialCreateMode"))
        assertFalse(screen.contains("RecentEmployeeConversation("))
        assertFalse(screen.contains("request.create(api,"))
    }
}
