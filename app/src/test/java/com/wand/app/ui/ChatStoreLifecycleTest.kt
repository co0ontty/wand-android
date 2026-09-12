package com.wand.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatStoreLifecycleTest {
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
