package com.wand.app.ui

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务导航保存/恢复测试。覆盖：
 * - 旧 Workspaces 根路由迁移与 WorkspaceTask 往返
 * - 任务名中的特殊字符（`:`、换行、Unicode）不破坏恢复
 * - Saver 只序列化 ID 和短显示名，不携带 cwd/layout/凭据
 */
class AppNavTest {

    /** JVM 单测里 canBeSaved 恒真；生产走 SavedStateRegistry 的实现。 */
    private val saverScope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    private fun saveStack(vararg screens: Screen): List<Any?> {
        val nav = NavState().apply { screens.forEach(::push) }
        return with(NavState.Saver) { saverScope.save(nav) } as List<Any?>
    }

    private fun restoreStack(saved: List<Any?>): List<Screen> =
        NavState.Saver.restore(saved)?.stack.orEmpty()

    /** 直接喂持久化 key，覆盖老版本写入的 key 的迁移。 */
    private fun restoreKeys(vararg keys: String): List<Screen> = restoreStack(keys.toList())

    /** 走真实 Saver 的往返，不再依赖生产代码里的测试专用转发函数。 */
    private fun roundTrip(screen: Screen): Screen? =
        restoreStack(saveStack(screen)).lastOrNull()

    @Test
    fun legacyWorkspacesKeyRestoresTaskRoot() {
        assertEquals(Screen.SessionList, restoreKeys("workspaces").firstOrNull())
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
    fun roundTrip_chatScreenPreservesTaskIdentity() {
        val expected = Screen.Chat(
            "session-123",
            "Wand 项目",
            "修复顶部栏",
            "workspace-1",
            "task-1",
        )
        assertEquals(expected, roundTrip(expected))
    }

    @Test
    fun roundTrip_ptyScreen() {
        val restored = roundTrip(Screen.PtyTerminal("session-456"))
        assertEquals(Screen.PtyTerminal("session-456"), restored)
    }

    @Test
    fun roundTrip_ptyScreenPreservesTaskIdentity() {
        val expected = Screen.PtyTerminal(
            "session-456",
            "Project",
            "Run checks",
            "workspace-2",
            "task-2",
        )
        assertEquals(expected, roundTrip(expected))
    }

    @Test
    fun roundTrip_missionsScreen() {
        val restored = roundTrip(Screen.Missions())
        assertEquals(Screen.Missions(), restored)
    }

    @Test
    fun roundTrip_linkedMissionPreservesTaskContext() {
        val expected = Screen.Missions("task-1", "/repo/.wand-worktrees/task-1", "Fix")
        assertEquals(expected, roundTrip(expected))
    }

    @Test
    fun roundTrip_taskBoardScreen() {
        assertEquals(Screen.TaskBoard(), roundTrip(Screen.TaskBoard()))
        assertEquals(Screen.TaskBoard("ws-9"), roundTrip(Screen.TaskBoard("ws-9")))
    }

    @Test
    fun roundTrip_sessionListScreen() {
        val restored = roundTrip(Screen.SessionList)
        assertEquals(Screen.SessionList, restored)
    }

    @Test
    fun chatAndPtyUseHeavyDetailTransition() {
        assertTrue(usesHeavyDetailTransition(Screen.Chat("session-1")))
        assertTrue(usesHeavyDetailTransition(Screen.PtyTerminal("session-2")))
        assertEquals(false, usesHeavyDetailTransition(Screen.SessionList))
        assertEquals(false, usesHeavyDetailTransition(Screen.Settings))
        assertEquals(false, usesHeavyDetailTransition(Screen.WorkspaceTask("ws", "task", "P", "T")))
    }

    @Test
    fun oldNewSessionKeyRestoresToTaskHome() {
        assertEquals(Screen.SessionList, restoreKeys("new-session").firstOrNull())
        assertEquals(Screen.SessionList, restoreKeys("new-session:/tmp/project").firstOrNull())
    }

    @Test
    fun unknownKeyDegradesToRootInsteadOfThrowing() {
        // Saver 只在栈底仍是会话列表时提交恢复结果；认不出的 key 全部丢弃，回到根路由。
        assertEquals(listOf(Screen.SessionList), restoreKeys("totally-unknown-key"))
        assertEquals(listOf(Screen.SessionList), restoreKeys("totally-unknown-key", "chat:abc"))
    }

    @Test
    fun popToRootClearsWorkspaceOrSessionDetail() {
        val nav = NavState().apply {
            push(Screen.WorkspaceTask("ws-1", "task-1", "Project", "Task"))
            push(Screen.Chat("session-1", "Project", "Task"))
        }

        nav.popToRoot()

        assertEquals(listOf(Screen.SessionList), nav.stack)
    }

    @Test
    fun setDetailReplacesTheEntireDetailStack() {
        val nav = NavState().apply {
            push(Screen.WorkspaceTask("ws-1", "task-1", "Project", "Task"))
            push(Screen.Chat("session-1", "Project", "Task"))
        }

        nav.setDetail(Screen.WorkspaceTask("ws-2", "task-2", "Other", "Next"))

        assertEquals(
            listOf(
                Screen.SessionList,
                Screen.WorkspaceTask("ws-2", "task-2", "Other", "Next"),
            ),
            nav.stack.toList(),
        )
    }

    @Test
    fun closeWorkspaceTaskPopsNestedSession() {
        val nav = NavState().apply {
            push(Screen.WorkspaceTask("ws-1", "task-1", "Project", "Task"))
            push(Screen.Chat("session-1", "Project", "Task", "ws-1", "task-1"))
        }

        nav.closeWorkspaceTask("task-1")

        assertEquals(listOf(Screen.SessionList), nav.stack.toList())
    }

    @Test
    fun closeWorkspaceTaskPopsInlineTaskSessionWithoutTaskParent() {
        val nav = NavState().apply {
            push(Screen.Chat("session-1", "Project", "Task", "ws-1", "task-1"))
        }

        nav.closeWorkspaceTask("task-1")

        assertEquals(listOf(Screen.SessionList), nav.stack.toList())
    }

    @Test
    fun renameWorkspaceTaskKeepsNestedSession() {
        val nav = NavState().apply {
            push(Screen.WorkspaceTask("ws-1", "task-1", "Project", "Task"))
            push(Screen.Chat("session-1", "Project", "Task", "ws-1", "task-1"))
        }

        nav.renameWorkspaceTask("task-1", "Renamed")

        assertEquals(
            Screen.WorkspaceTask("ws-1", "task-1", "Project", "Renamed"),
            nav.stack[1],
        )
        assertEquals(
            Screen.Chat("session-1", "Project", "Renamed", "ws-1", "task-1"),
            nav.current,
        )
    }

    @Test
    fun roundTrip_settingsScreen() {
        assertEquals(Screen.Settings, roundTrip(Screen.Settings))
    }
}
