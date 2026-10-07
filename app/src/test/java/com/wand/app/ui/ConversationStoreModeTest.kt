package com.wand.app.ui

import com.wand.app.data.ConversationTarget
import com.wand.app.data.WandApi
import com.wand.app.data.UploadedFile
import com.wand.app.ui.screens.HomeListMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationStoreModeTest {
    @Test fun switchingRootsObjectsAndTargetsPreservesComposerAttachmentsAndScrollWithoutRequests() = runTest {
        var preference = ""; var selections = 0
        val drafts = SessionDraftStore()
        // No start/load/submit: this fixture must not contact even the loopback API.
        val store = ConversationStore(WandApi("http://127.0.0.1:1", null), drafts, backgroundScope, { preference }, { preference = it })
        val nav = NavState().apply { initializeHomeMode("im") }
        store.onSelection = { selections++; nav.syncConversation(it) }
        store.select("group-a")
        val target = ConversationTarget("task-a", "run-a")
        store.target("group-a", target)
        store.filter("group-a", "task-a")
        val composer = store.composer("group-a")
        composer.editDraft("keep this draft")
        composer.upload { listOf(UploadedFile("file.txt", "/uploads/file.txt", 1, "text/plain")) }
        runCurrent()
        val messages = store.listState("messages:group-a:task-a")
        val metadata = store.composeDraft("group-a").apply { title = "task title"; projectId = "project" }
        nav.selectHomeMode(HomeListMode.Tasks)
        nav.push(Screen.TaskBoard(taskId = "board"))
        nav.selectHomeMode(HomeListMode.Sessions)
        nav.selectHomeMode(HomeListMode.Im)
        store.select("dm_b")
        store.composer("dm_b").editDraft("another draft")
        store.select("group-a")
        assertSame(composer, store.composer("group-a"))
        assertSame(messages, store.listState("messages:group-a:task-a"))
        assertSame(metadata, store.composeDraft("group-a"))
        assertEquals("keep this draft", composer.draft)
        assertEquals(1, composer.attachments.size)
        assertEquals(target, store.targets["group-a"])
        assertEquals("task-a", store.filters["group-a"])
        assertEquals(SendPhase.Idle, composer.sendPhase)
        assertFalse(preference.contains("keep this draft"))
        assertFalse(preference.contains("another draft"))
        assertEquals(3, selections)
        store.shutdown()
    }

    @Test fun savedSelectionTargetAndAnchorRestoreWithoutGuessingLatest() = runTest {
        val saved = """{"selectedId":"missing-group","selectedTitle":"Original name","targets":{"missing-group":{"taskId":"task","runId":"run"}},"filters":{"missing-group":"task"},"scrolls":{"messages:missing-group:task":{"index":4,"offset":19}},"expandedGroups":["missing-group"]}"""
        val store = ConversationStore(WandApi("http://127.0.0.1:1", null), SessionDraftStore(), backgroundScope, { saved }, {})
        assertEquals("missing-group", store.selectedId)
        assertEquals("Original name", store.selectedTitle)
        assertEquals(ConversationTarget("task", "run"), store.targets["missing-group"])
        assertEquals(4, store.listState("messages:missing-group:task").firstVisibleItemIndex)
        assertEquals(19, store.listState("messages:missing-group:task").firstVisibleItemScrollOffset)
        assertEquals(listOf("missing-group"), store.expandedGroups.toList())
        store.shutdown()
    }
    @Test fun readingAnotherTaskDoesNotRedirectOrClearTheCurrentDraft() = runTest {
        val store = ConversationStore(WandApi("http://127.0.0.1:1", null), SessionDraftStore(), backgroundScope, { "" }, {})
        val target = ConversationTarget("task-a", "run-a")
        store.target("group", target)
        val composer = store.composer("group")
        composer.editDraft("给任务 A 的补充")
        store.filter("group", "task-b")
        assertEquals(target, store.targets["group"])
        assertSame(composer, store.composer("group"))
        assertEquals("给任务 A 的补充", store.composer("group").draft)
        store.target("group", null)
        store.composer("group").editDraft("群内沟通草稿")
        store.target("group", target)
        assertEquals("给任务 A 的补充", store.composer("group").draft)
        store.shutdown()
    }

}
