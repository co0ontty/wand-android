package com.wand.app.ui

import com.wand.app.data.MessageUpdate
import com.wand.app.data.SessionChanges
import com.wand.app.data.SessionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatStoreLifecycleTest {
    @Test
    fun queueReceiptsIgnoreUnrelatedTrafficButYieldToEveryQueueSnapshot() {
        assertFalse(SessionEvent.Output("s", MessageUpdate.None, true, SessionChanges(title = "working")).updatesQueue())
        assertFalse(SessionEvent.StatusChanged("s", null, true, SessionChanges(status = "running")).updatesQueue())
        assertFalse(SessionEvent.TaskChanged("s", "new title").updatesQueue())
        assertFalse(SessionEvent.Error("s", "unrelated error").updatesQueue())
        for (queue in listOf(emptyList(), listOf("same queued text"))) {
            assertTrue(SessionEvent.Output("s", MessageUpdate.None, true, SessionChanges(queuedMessages = queue)).updatesQueue())
            assertTrue(SessionEvent.StatusChanged("s", null, true, SessionChanges(queuedMessages = queue)).updatesQueue())
            assertTrue(SessionEvent.Ended("s", null, "idle", null, SessionChanges(queuedMessages = queue)).updatesQueue())
        }
        assertTrue(SessionEvent.Initialized("s", null, null, null, SessionChanges()).updatesQueue())
    }

    @Test
    fun firstEnterConnectsImmediately() {
        assertEquals(
            ChatRealtimeStartKind.FirstConnect,
            chatRealtimeStartKind(active = false, started = false),
        )
    }

    @Test
    fun visiblePageDoesNotOpenASecondSocket() {
        assertEquals(
            ChatRealtimeStartKind.Skip,
            chatRealtimeStartKind(active = true, started = true),
        )
    }

    @Test
    fun leavingAndReenteringReopensTheClosedSocket() {
        // started 仍为 true（对象被 Navigation / remember 复用），但 shutdown 已把 active 清掉。
        // 旧逻辑 if (started) return 会让详情一直挂着「连接已断开」，退出再进才好。
        assertEquals(
            ChatRealtimeStartKind.Reconnect,
            chatRealtimeStartKind(active = false, started = true),
        )
    }
}
