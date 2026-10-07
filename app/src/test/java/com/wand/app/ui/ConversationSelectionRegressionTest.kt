package com.wand.app.ui

import androidx.compose.runtime.saveable.SaverScope
import com.wand.app.data.ConversationTarget
import com.wand.app.data.WandApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** F6 tests the CURRENT merged NavState/Store, without modifying parallel navigation sources. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationSelectionRegressionTest {
    private val saverScope = object : SaverScope { override fun canBeSaved(value: Any) = true }
    private fun restored(nav: NavState) = NavState.Saver.restore(with(NavState.Saver) { saverScope.save(nav) }!!)!!

    @Test fun dispatchGroupLinkPeerAndExecutionReturnKeepCurrentObjectAndExactTargetThroughSaver() = runTest {
        val api = WandApi("http://127.0.0.1:1", null) // No HTTP/model/WS calls in this test.
        var saved = "{}"
        fun store() = ConversationStore(api, SessionDraftStore(), backgroundScope, { saved }, { saved = it })
        var state = store()
        var nav = NavState().apply { push(Screen.Conversation("dm_a")) }
        state.onSelection = nav::syncConversation // Same binding as current WandApp.
        state.select("dm_a")
        val groupTarget = ConversationTarget("task_b", "run_b")
        state.select("group_b"); state.target("group_b", groupTarget)
        assertEquals(Screen.Conversation("group_b"), nav.current)
        nav.push(Screen.Chat("execution_b", taskId = "task_b")); nav.pop()
        assertEquals(Screen.Conversation("group_b"), nav.current)
        assertEquals(groupTarget, state.targets["group_b"])
        state.select("dm_employee_c")
        assertEquals(Screen.Conversation("dm_employee_c"), nav.current)
        state.select("group_b") // Real associated group path, never the stale entry A.
        assertEquals(groupTarget, state.targets[state.selectedId])
        nav = restored(nav); state.shutdown(); state = store()
        state.onSelection = nav::syncConversation
        val screen = nav.current as Screen.Conversation
        state.select(screen.conversationId) // Current entry effect after restore/re-entry.
        assertEquals("group_b", state.selectedId)
        assertEquals(Screen.Conversation("group_b"), nav.current)
        assertEquals(groupTarget, state.targets[state.selectedId])
        assertFalse(saved.contains("dm_a\":{\"taskId"))
        state.shutdown()
    }
}
