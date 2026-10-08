package com.wand.app.ui.screens

import com.wand.app.data.*
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceDirectorySelectionTest {
    private fun workspace(id: String, cwd: String) = Workspace(id, id, cwd, null, null, null, null)
    private class Port : WorkspacePort {
        override val taskChanges = MutableSharedFlow<Unit>()
        var workspaces = emptyList<Workspace>()
        var created = 0
        var failure: String? = null
        val listings = mutableMapOf<String, CompletableDeferred<DirectoryListing>>()
        override suspend fun listWorkspaces() = workspaces
        override suspend fun createWorkspace(name: String, cwd: String): Workspace {
            created++
            return Workspace("new", name, cwd, null, null, null, null).also { workspaces = workspaces + it }
        }
        override suspend fun listDirectory(path: String): DirectoryListing {
            failure?.let { error(it) }
            return listings[path]?.await() ?: DirectoryListing(emptyList(), false)
        }
        override suspend fun listWorkspaceTasks(workspaceId: String): List<WorkspaceTask> = emptyList()
        override suspend fun workspaceTask(taskId: String): WorkspaceTaskDetail = error("unused")
        override suspend fun renameWorkspaceTask(taskId: String, name: String): WorkspaceTask = error("unused")
        override suspend fun deleteWorkspaceTask(taskId: String) = Unit
        override suspend fun saveWorkspaceTaskLayout(taskId: String, layout: TaskWindowLayout?): TaskWindowLayout? = error("unused")
        override suspend fun createWorkspaceTaskWindow(target: WorkspaceSessionTarget, binding: WorkspaceBinding,
            kind: WorkspaceSessionKind, prompt: String?, model: String?, thinkingEffort: String?): SessionSnapshot = error("unused")
    }

    @Test fun existingPathsReuseWorkspacesAndUnusedDirectoryIsCreatedOnlyWhenSubmitting() = runTest {
        val port = Port().apply { workspaces = listOf(workspace("known", "/repo")) }
        val known = directorySelection(" /repo/ ", port.workspaces)
        assertEquals("known", known.workspaceId)
        assertEquals("known", resolveCreationWorkspace(port, known)?.id)
        assertEquals(0, port.created)
        val unused = directorySelection("/unused", port.workspaces)
        assertNull(unused.workspaceId)
        assertEquals(0, port.created)
        assertEquals("/unused", resolveCreationWorkspace(port, unused)?.cwd)
        assertEquals(1, port.created)
        assertEquals("new", resolveCreationWorkspace(port, unused)?.id)
        assertEquals(1, port.created)
        assertNull(resolveCreationWorkspace(port, WorkspaceDirectorySelection("")))
    }

    @Test fun globalEmptyAndDuplicateDirectoriesDoNotBecomeWorkspaceChoices() {
        val choices = selectableDirectoryWorkspaces(listOf(workspace(GLOBAL_WORKSPACE_ID, "/temp"),
            workspace("empty", ""), workspace("a", "/repo"), workspace("duplicate", "/repo/")))
        assertEquals(listOf("a"), choices.map { it.id })
        assertEquals("/", parentWorkspaceDirectory("/"))
        assertEquals("/", parentWorkspaceDirectory("/repo/"))
        assertEquals("/repo", parentWorkspaceDirectory("/repo/child"))
    }

    @Test fun removedWorkspaceDoesNotSilentlyCreateAnother() = runTest {
        val port = Port()
        try {
            resolveCreationWorkspace(port, WorkspaceDirectorySelection("/repo", "removed"))
            fail("must reject a removed workspace")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message!!.contains("重新选择"))
        }
        assertEquals(0, port.created)
    }

    @Test fun lateDirectoryResponseCannotOverrideLatestPathOrSelectionGate() = runTest {
        val old = CompletableDeferred<DirectoryListing>()
        val latest = CompletableDeferred<DirectoryListing>()
        val port = Port().apply { listings["/old"] = old; listings["/new"] = latest }
        val state = WorkspaceDirectoryPickerState(port, "/")
        val first = launch { state.browse("/old") }
        runCurrent()
        val second = launch { state.browse("/new") }
        runCurrent()
        assertFalse(state.canSelectDirectory)
        latest.complete(DirectoryListing(listOf(DirectoryItem("/new/child", "child", "dir")), false))
        runCurrent()
        assertTrue(state.canSelectDirectory)
        old.complete(DirectoryListing(listOf(DirectoryItem("/old/stale", "stale", "dir")), false))
        first.join(); second.join()
        assertEquals("/new", state.path)
        assertEquals("child", state.listing!!.items.single().name)
        assertEquals(0, port.created)
    }

    @Test fun failedDirectoryIsNotSelectableAndRetryPreservesPath() = runTest {
        val port = Port()
        val state = WorkspaceDirectoryPickerState(port, "/")
        state.browse("/valid")
        assertTrue(state.canSelectDirectory)
        port.failure = "没有权限"
        state.browse("/blocked")
        assertFalse(state.canSelectDirectory)
        assertNull(state.listing)
        assertEquals("没有权限", state.error)
        assertEquals("/blocked", state.path)
        port.failure = null
        state.browse(state.path)
        assertTrue(state.canSelectDirectory)
    }

    @Test fun threeEntrancesUseSharedPickerAndCreationFormsShareSystemFrame() {
        fun source(name: String) = File("src/main/java/com/wand/app/ui/screens/$name.kt").readText()
        assertTrue(source("TaskListScreen").contains("onNewTask = if (homeListMode == HomeListMode.Sessions)"))
        assertTrue(source("HomeChrome").contains("onClick = onNewTask"))
        for (name in listOf("TaskListScreen", "CreateBoardTaskDialog", "ChatScreen")) {
            assertTrue(name, source(name).contains("WorkspaceDirectoryPicker("))
        }
        for (name in listOf("NewTaskComposerScreen", "CreateBoardTaskDialog")) {
            assertTrue(name, source(name).contains("WandFormDialog("))
        }
        assertFalse(source("TaskBoardScreen").contains("private fun CreateBoardTaskDialog"))
        assertTrue(source("CreateBoardTaskDialog").contains("!formBusy && !teamRunRetry"))
    }
}
