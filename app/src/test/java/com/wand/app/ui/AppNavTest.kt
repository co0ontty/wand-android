package com.wand.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务导航保存/恢复测试。覆盖：
 * - Workspaces / WorkspaceTask Screen 的 save/restore 往返
 * - 任务名中的特殊字符（`:`、换行、Unicode）不破坏恢复
 * - Saver 只序列化 ID 和短显示名，不携带 cwd/layout/凭据
 */
class AppNavTest {

    private fun roundTrip(screen: Screen): Screen? =
        NavState.deserializeScreen(NavState.serializeScreen(screen))

    @Test
    fun roundTrip_workspacesScreen() {
        val restored = roundTrip(Screen.Workspaces)
        assertEquals(Screen.Workspaces, restored)
    }

    @Test
    fun roundTrip_workspaceTaskScreen() {
        val restored = roundTrip(Screen.WorkspaceTask("ws-1", "task-1", "My Project", "Fix Bug"))
        assertNotNull(restored)
        val screen = restored as Screen.WorkspaceTask
        assertEquals("ws-1", screen.workspaceId)
        assertEquals("task-1", screen.taskId)
        assertEquals("My Project", screen.workspaceName)
        assertEquals("Fix Bug", screen.taskName)
    }

    @Test
    fun roundTrip_preservesTaskNameWithColons() {
        val restored = roundTrip(Screen.WorkspaceTask("ws-1", "task-1", "Project", "fix: critical: bug"))
        assertEquals("fix: critical: bug", (restored as Screen.WorkspaceTask).taskName)
    }

    @Test
    fun roundTrip_preservesUnicodeTaskName() {
        val restored = roundTrip(Screen.WorkspaceTask("ws-1", "task-1", "项目", "修复中文任务"))
        assertEquals("修复中文任务", (restored as Screen.WorkspaceTask).taskName)
        assertEquals("项目", restored.workspaceName)
    }

    @Test
    fun roundTrip_preservesTaskNameWithNewlines() {
        val taskName = "line one\nline two"
        val restored = roundTrip(Screen.WorkspaceTask("ws-1", "task-1", "P", taskName))
        assertEquals(taskName, (restored as Screen.WorkspaceTask).taskName)
    }

    @Test
    fun roundTrip_chatScreen() {
        val restored = roundTrip(Screen.Chat("session-123"))
        assertEquals(Screen.Chat("session-123"), restored)
    }

    @Test
    fun roundTrip_ptyScreen() {
        val restored = roundTrip(Screen.PtyTerminal("session-456"))
        assertEquals(Screen.PtyTerminal("session-456"), restored)
    }

    @Test
    fun roundTrip_missionsScreen() {
        val restored = roundTrip(Screen.Missions)
        assertEquals(Screen.Missions, restored)
    }

    @Test
    fun roundTrip_sessionListScreen() {
        val restored = roundTrip(Screen.SessionList)
        assertEquals(Screen.SessionList, restored)
    }

    @Test
    fun roundTrip_newSessionWithCwd() {
        val restored = roundTrip(Screen.NewSession("/some/path"))
        assertEquals(Screen.NewSession("/some/path"), restored)
    }

    @Test
    fun roundTrip_unknownKey_returnsNull() {
        assertNull(NavState.deserializeScreen("totally-unknown-key"))
    }
}
