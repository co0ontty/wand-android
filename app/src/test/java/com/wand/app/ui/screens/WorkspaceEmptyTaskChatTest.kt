package com.wand.app.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空任务不再有自己的「开始这个任务 / 选择执行对象」页面：任务正文直接承载
 * 绑定当前任务的空白对话，与「新建任务」共用同一套创建入口。
 */
class WorkspaceEmptyTaskChatTest {
    private fun screen(name: String): String =
        File("src/main/java/com/wand/app/ui/screens/$name.kt").readText()

    private fun app(): String = File("src/main/java/com/wand/app/ui/WandApp.kt").readText()

    @Test
    fun emptyTaskDelegatesToTheBoundPendingConversation() {
        val task = screen("WorkspaceTaskScreen")
        assertTrue(task.contains("emptyTaskContent(state.cwd)"))
        assertFalse(task.contains("开始这个任务"))
        assertFalse(task.contains("选择执行对象"))
        assertFalse(task.contains("EmptyTaskWelcome"))

        val source = app()
        assertTrue(source.contains("emptyTaskContent = { cwd ->"))
        assertTrue(source.contains("workspaceTaskId = screen.taskId"))
        assertTrue(source.contains("embedded = true"))
    }

    @Test
    fun embeddedPendingConversationKeepsASingleTopBar() {
        val pending = screen("PendingConversationScreen")
        assertTrue(pending.contains("if (!embedded) Box("))
        assertTrue(pending.contains("ChatTopIdentityBar("))
        assertTrue(pending.contains("ChatDetailScaffold("))
        assertFalse(pending.contains("WandDetailTopBar("))
        assertTrue(screen("ChatScreen").contains("ChatDetailScaffold("))
        assertTrue(pending.contains("presentation = inputPresentation"))
        assertTrue(pending.contains("ComposerSendStopActions("))
        assertFalse(pending.contains("WandButton(label ="))
        assertTrue(pending.contains("ComposerSettingsRow("))
        assertTrue(pending.contains("ComposerChoiceSheet("))
        assertTrue(pending.contains("editorOverlayOpen = directoryOpen || choice != null"))
        assertFalse(pending.contains("optionsOpen"))
        assertFalse(pending.contains("(current + 1) %"))
        assertTrue(screen("FocusCapsuleComposer").contains("verticalScroll(rememberScrollState())"))
    }
}
