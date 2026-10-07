package com.wand.app.ui.screens

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 04-A1/A2：面板开合与派发意图分离。转移表和请求路径是生产代码里的纯函数，
 * 这里直接跑真实逻辑；只在 JVM 无法渲染 Compose 的接线上用源码断言补足。
 */
class ConversationDispatchPanelEventTest {
    private val screen = File("src/main/java/com/wand/app/ui/screens/ConversationScreens.kt").readText()

    private fun composer(menu: Boolean, taskMode: Boolean, vararg events: ConversationComposerEvent): Pair<Boolean, Boolean> =
        events.fold(menu to taskMode) { (nextMenu, nextTaskMode), event ->
            conversationComposerState(nextMenu, nextTaskMode, event)
        }

    @Test fun panelToggleKeepsTheDispatchIntentInBothDirections() {
        assertEquals(true to false, conversationComposerState(false, false, ConversationComposerEvent.PanelOpen))
        assertEquals(false to false, conversationComposerState(true, false, ConversationComposerEvent.PanelClose))
        // 已经在派发：加号开合、正文点击、键盘收起、系统返回都只动面板。
        assertEquals(true to true, conversationComposerState(false, true, ConversationComposerEvent.PanelOpen))
        assertEquals(false to true, conversationComposerState(true, true, ConversationComposerEvent.PanelClose))
    }

    @Test fun onlyExplicitCancelOrASettledSendClearsTheDispatchIntent() {
        for (event in listOf(ConversationComposerEvent.DispatchCancel, ConversationComposerEvent.DispatchSettled)) {
            assertEquals(event.name, false to false, conversationComposerState(true, true, event))
        }
        assertEquals(true to true, conversationComposerState(false, false, ConversationComposerEvent.DispatchStart))
    }

    /**
     * 负责人裁决 3：「详情→列表→再打开」和切根都不是取消。离开页面只结束临时面板，
     * 派发意图必须留着，否则回来后同一段草稿又会被当成普通消息发进 /messages。
     */
    @Test fun leavingTheScreenOrCollapsingPanelsIsNeverAnImplicitCancel() {
        for (event in listOf(ConversationComposerEvent.LeftScreen, ConversationComposerEvent.PanelClose, ConversationComposerEvent.PanelOpen)) {
            assertEquals("${event.name} 不得清掉派发意图", true, conversationComposerState(true, true, event).second)
        }
        assertEquals(false to true, conversationComposerState(true, true, ConversationComposerEvent.LeftScreen))
        val afterLeaving = composer(true, true, ConversationComposerEvent.LeftScreen, ConversationComposerEvent.PanelClose)
        assertEquals("/api/conversations/dm_a/tasks", conversationSendPath("dm_a", afterLeaving.second))
        // 新对象不继承：没有派发意图的会话，同样的事件序列也不会凭空进入派发。
        assertEquals(false to false, composer(false, false, ConversationComposerEvent.LeftScreen, ConversationComposerEvent.PanelClose))
    }

    /** 04-A1：选项目派新任务后，点正文 / 收面板 / 键盘开合 / 关更多仍是派发。 */
    @Test fun dispatchSurvivesTheWholeCollapseSequenceAndKeepsTheTasksPath() {
        val afterSequence = composer(false, false,
            ConversationComposerEvent.DispatchStart,    // 派新任务 + 选工作项目
            ConversationComposerEvent.PanelClose,       // 点正文（外点观察器）
            ConversationComposerEvent.PanelClose,       // 输入法收起 / 系统返回
            ConversationComposerEvent.PanelOpen,        // 再看一眼更多面板
            ConversationComposerEvent.PanelClose,       // 关掉它
        )
        assertEquals(false to true, afterSequence)
        assertEquals("/api/conversations/group-a/tasks", conversationSendPath("group-a", afterSequence.second))
    }

    /** 04-A2：只有显式取消才恢复普通消息，按钮语义与真实路径同源。 */
    @Test fun explicitCancelIsTheOnlyWayBackToTheMessagesPath() {
        val cancelled = composer(false, false,
            ConversationComposerEvent.DispatchStart,
            ConversationComposerEvent.PanelClose,
            ConversationComposerEvent.DispatchCancel,
        )
        assertEquals(false to false, cancelled)
        assertEquals("/api/conversations/group-a/messages", conversationSendPath("group-a", cancelled.second))
        assertEquals("/api/conversations/group-a/messages", conversationSendPath("group-a", false))
    }

    @Test fun collapseBeforeSendCannotDowngradeTheRequestPath() {
        // 发送槽在 onSend 之前先收面板；这一步不得把这次请求从 /tasks 变成 /messages。
        val dispatch = composer(false, false, ConversationComposerEvent.DispatchStart, ConversationComposerEvent.PanelClose).second
        val path = conversationSendPath("dm_b", dispatch)
        assertTrue(path.endsWith("/tasks"))
        assertFalse(path.endsWith("/messages"))
    }

    @Test fun everyTaskModeWriteGoesThroughTheReducer() {
        assertFalse("派发意图只能在转移表里被清掉", screen.contains("taskMode = false"))
        assertTrue(screen.contains("menu = nextMenu; taskMode = nextTaskMode"))
        val attach = screen.substringAfter("onAttachOpenChange = { open ->").substringBefore("onMenuDismissFocus")
        assertTrue(attach.contains("ConversationComposerEvent.PanelOpen"))
        assertTrue(attach.contains("ConversationComposerEvent.PanelClose"))
        assertFalse("收面板的回调里不得再夹带派发意图", attach.contains("taskMode"))
        val cancel = screen.substringAfter("WandButton(\"取消派发\"").lineSequence().first()
        assertTrue(cancel.contains("ConversationComposerEvent.DispatchCancel"))
    }

    @Test fun sendUsesTheSharedPathAndKeepsTheCapturedIntent() {
        assertTrue(screen.contains("val capturedKey = key; val capturedId = id; val dispatch = taskMode"))
        assertTrue(screen.contains("conversationSendPath(capturedId, true)"))
        assertTrue(screen.contains("conversationSendPath(capturedId, false)"))
        assertTrue(screen.contains("applyComposer(ConversationComposerEvent.DispatchSettled)"))
        assertTrue(screen.contains("(!dispatch || currentTaskMode)"))
    }

    /** 04-A5 / E10：系统返回只结束临时面板，普通消息卡展开不吞返回。 */
    @Test fun systemBackClosesTemporaryLayersOnly() {
        assertTrue(screen.contains("ConversationLayerBackHandler(menu || invite || members || taskDetails || titleOpen)"))
        assertFalse(screen.contains("expandedMessage != null"))
        assertFalse(screen.contains("state.expandedMessages.remove(expandedMessage)"))
    }
}
