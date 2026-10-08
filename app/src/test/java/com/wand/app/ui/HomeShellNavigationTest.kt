package com.wand.app.ui

import androidx.compose.runtime.saveable.SaverScope
import com.wand.app.ui.screens.HomeListMode
import org.junit.Assert.*
import org.junit.Test

class HomeShellNavigationTest {
    private val saverScope = object : SaverScope { override fun canBeSaved(value: Any) = true }
    private fun restore(nav: NavState): NavState = NavState.Saver.restore(with(NavState.Saver) { saverScope.save(nav) }!!)!!

    @Test fun absentPreferenceStartsImButExistingWorkPreferencesRemainWork() {
        for ((value, expected) in listOf("" to HomeListMode.Im, "im" to HomeListMode.Im, "sessions" to HomeListMode.Sessions, "board" to HomeListMode.Tasks, "corrupt" to HomeListMode.Im)) {
            assertEquals(expected, NavState().initializeHomeMode(value))
        }
    }

    @Test fun legacyWorkAndExplicitConversationPathsWinOverFirstLaunchDefault() {
        assertEquals(HomeListMode.Sessions, NavState().apply { push(Screen.PtyTerminal("pty")) }.initializeHomeMode(""))
        assertEquals(HomeListMode.Tasks, NavState().apply { push(Screen.TaskBoard(taskId = "task")) }.initializeHomeMode(""))
        assertEquals(HomeListMode.Im, NavState().apply { push(Screen.Conversation("dm_a")) }.initializeHomeMode("board"))
    }

    @Test fun switchingModesRestoresEachWorkPathAndSelectedItemReturnsToRoot() {
        val nav = NavState().apply { initializeHomeMode("sessions") }
        val workspace = Screen.WorkspaceTask("ws", "task", "Workspace", "Task")
        nav.push(workspace)
        nav.push(Screen.Chat("session", taskId = "task"))
        val path = nav.stack.toList()
        nav.selectHomeMode(HomeListMode.Tasks)
        val board = Screen.TaskBoard(taskId = "board")
        nav.push(board)
        nav.selectHomeMode(HomeListMode.Im)
        assertEquals(listOf(Screen.SessionList), nav.stack.toList())
        nav.selectHomeMode(HomeListMode.Sessions)
        assertEquals(path, nav.stack.toList())
        nav.selectHomeMode(HomeListMode.Tasks)
        assertEquals(board, nav.current)
        nav.selectHomeMode(HomeListMode.Tasks)
        assertEquals(Screen.SessionList, nav.current)
    }

    @Test fun saverPreservesBothWorkPathsAndNeverRestoresStaleConversationId() {
        val nav = NavState().apply { initializeHomeMode("sessions"); push(Screen.WorkspaceTask("ws", "t", "W", "T")) }
        nav.selectHomeMode(HomeListMode.Tasks)
        nav.push(Screen.TaskBoard(taskId = "b"))
        nav.selectHomeMode(HomeListMode.Im)
        nav.push(Screen.Conversation("dm_a"))
        nav.syncConversation("dm_b")
        val restored = restore(nav)
        assertEquals(Screen.Conversation("dm_b"), restored.current)
        restored.selectHomeMode(HomeListMode.Sessions)
        assertEquals("t", (restored.current as Screen.WorkspaceTask).taskId)
        restored.selectHomeMode(HomeListMode.Tasks)
        assertEquals("b", (restored.current as Screen.TaskBoard).taskId)
    }

    @Test fun contactsIsAHomeModeThatKeepsWorkPathsAcrossSwitches() {
        val nav = NavState().apply { initializeHomeMode("board") }
        nav.push(Screen.TaskBoard(taskId = "t"))
        nav.selectHomeMode(HomeListMode.Contacts)
        // 页签不占栈记录：底下的任务路径留着，切回任务仍落回同一张卡。
        assertEquals(listOf(Screen.SessionList), nav.stack.toList())
        assertEquals(HomeListMode.Contacts, nav.homeMode)
        val restored = restore(nav)
        assertEquals(HomeListMode.Contacts, restored.homeMode)
        restored.selectHomeMode(HomeListMode.Tasks)
        assertEquals(Screen.TaskBoard(taskId = "t"), restored.current)
    }

    @Test fun executionWindowReturnsToSameObject() {
        val nav = NavState().apply { initializeHomeMode("board"); push(Screen.TaskBoard(taskId = "t")) }
        nav.push(Screen.Conversation("dm_a"))
        nav.syncConversation("dm_b")
        nav.push(Screen.PtyTerminal("pty"))
        nav.pop()
        assertEquals(Screen.Conversation("dm_b"), nav.current)
        val restored = restore(nav)
        restored.pop()
        assertEquals(Screen.TaskBoard(taskId = "t"), restored.current)
    }
}
