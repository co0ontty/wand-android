package com.wand.app.ui

import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import com.wand.app.data.WorkspaceSessionSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTopicTest {
    @After
    fun tearDown() {
        SessionTitleStore.clear()
    }

    @Test
    fun composerChunksTagBothTextAndEnterSoServerCanSummarizeTheCommand() {
        val chunks = ptyComposerSubmitChunks("修权限弹窗的文案", "terminal")
        assertEquals(2, chunks.size)
        assertEquals("修权限弹窗的文案", chunks[0].input)
        assertEquals("enter_text", chunks[0].shortcutKey)
        assertEquals("\r", chunks[1].input)
        assertEquals("enter_text", chunks[1].shortcutKey)
        assertEquals("terminal", chunks[0].view)
        assertEquals("terminal", chunks[1].view)
    }

    @Test
    fun inputIsClippedToAShortTitleAndSkipsTheParentTaskName() {
        val blocked = sessionTopicBlocklist(
            taskName = "重构会话恢复流程",
            workspaceName = "wand",
            cwd = "/Users/me/wand",
        )
        assertEquals(
            "先把 resume-policy 的时间窗收紧",
            summarizeSessionTitleFromInput(
                "重构会话恢复流程\n先把 resume-policy 的时间窗收紧",
                blocked,
            ),
        )
        val clipped = summarizeSessionTitleFromInput(
            "请把侧栏每个终端按我这条命令总结成短标题，不要再显示整个任务名",
        )
        assertTrue(clipped.startsWith("请把侧栏每个终端"))
        assertTrue(clipped.length <= SESSION_TITLE_MAX_LENGTH)
        assertFalse(clipped.contains("不要再显示整个任务名"))
    }

    @Test
    fun chromeTitlePrefersCommandSummaryOverTaskName() {
        val blocked = sessionTopicBlocklist(taskName = "修登录", workspaceName = "wand")
        assertEquals(
            "收紧 resume 时间窗",
            sessionChromeTitle(
                title = "修登录",
                latestUserInput = "收紧 resume 时间窗",
                blockedTitles = blocked,
                fallback = "对话详情",
            ),
        )
        assertEquals(
            "收紧 resume 时间窗",
            sessionChromeTitle(
                title = null,
                liveTitle = "收紧 resume 时间窗",
                blockedTitles = blocked,
                fallback = "对话详情",
            ),
        )
    }

    @Test
    fun latestUserTurnBecomesTheFallbackTitle() {
        val messages = listOf(
            ConversationTurn("assistant", listOf(ContentBlock.Text("先看看", null))),
            ConversationTurn("user", listOf(ContentBlock.Text("把顶栏改成命令摘要", null))),
        )
        assertEquals("把顶栏改成命令摘要", latestUserInputText(messages))
        assertEquals(
            "把顶栏改成命令摘要",
            sessionChromeTitle(
                title = "会话",
                latestUserInput = latestUserInputText(messages),
                fallback = "对话详情",
            ),
        )
    }

    @Test
    fun liveTitleOverlaysThePolledListRow() {
        val session = WorkspaceSessionSummary(
            id = "session-1",
            provider = "claude",
            sessionKind = "structured",
            runner = "sdk",
            title = "wand",
            status = "running",
            cwd = "/Users/me/wand",
            startedAt = null,
        )
        assertEquals("wand", session.withLiveTitle().title)
        SessionTitleStore.apply("session-1", title = "修权限弹窗", generating = true)
        assertEquals("修权限弹窗", session.withLiveTitle().title)
        assertTrue(SessionTitleStore.isGenerating("session-1"))
        SessionTitleStore.apply("session-1", generating = false)
        assertFalse(SessionTitleStore.isGenerating("session-1"))
    }
}
