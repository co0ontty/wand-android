package com.wand.app.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 展开只归卡片自己的点击，返回只归页面导航；防止再次引入「先收起再返回」。 */
class ChatBackNavigationTest {
    private fun screen(name: String): String =
        File("src/main/java/com/wand/app/ui/screens/$name.kt").readText()

    @Test
    fun toolbarBackCallsPageNavigationDirectlyWithoutDispatchingToCards() {
        val source = screen("ChatScreen")
        assertTrue(Regex("WandDetailBackButton\\(\\s*onClick = onBack").containsMatchIn(source))
        assertFalse(source.contains("rememberChatBackAction"))
        assertFalse(source.contains("dispatchChatBack"))
        assertFalse(source.contains("onBackPressedDispatcher"))
    }

    @Test
    fun defaultOpenTimelineAndFullTextNeverInterceptSystemBack() {
        val timeline = screen("ToolActivitySummary")
        assertFalse(timeline.contains("BackHandler"))
        assertFalse(timeline.contains("onBackPressedDispatcher"))
        assertTrue(timeline.contains("val nextOpen = autoTimelineEnabled && isLatestActivity && group.running"))
        assertTrue(timeline.contains("menuOpen = nextOpen"))
        assertTrue(timeline.contains("ToolActivityReveal(menuOpen, from = Alignment.Top)"))
        assertFalse(timeline.contains("enabled = drawerVisible"))
        assertTrue(timeline.contains("menuOpen = !menuOpen"))
        assertTrue(timeline.contains("open = !open"))
    }

    @Test
    fun thinkingAgentDiffAndToolCardsLeaveBackToThePage() {
        for (name in listOf("ChatBlocks", "ChatActionBlocks", "ToolActivityDetail")) {
            val source = screen(name)
            assertFalse("$name cannot intercept system Back", source.contains("BackHandler"))
            assertFalse(source.contains("onBackPressedDispatcher"))
        }
        val blocks = screen("ChatBlocks")
        assertTrue(blocks.contains("foldOverride = foldToggleCode(foldOverride, derivedDefault = false)"))
        assertTrue(blocks.contains("onClick = { expanded = false }")) // Agent 卡仍可自己收起。
    }

    @Test
    fun taskAndDirectoryCardsAlsoLeaveBackToNavigationIncludingTheWideSidebar() {
        assertFalse(screen("TaskBoardScreen").contains("BackHandler"))
        assertFalse(screen("TaskListScreen").contains("BackHandler(enabled = state.hasTemporaryExpansion"))
        // 真正的操作菜单仍可关闭，与普通卡片展开不同。
        assertTrue(screen("TaskListScreen").contains("BackHandler(enabled = terminalMenuKey != null)"))
    }

    @Test
    fun appNavigationStillPopsOnePageForSystemBack() {
        val app = File("src/main/java/com/wand/app/ui/WandApp.kt").readText()
        assertTrue(app.contains("BackHandler(enabled = nav.stack.size > 1) { nav.pop() }"))
        assertFalse(File("src/main/java/com/wand/app/ui/screens/ChatBackNavigation.kt").exists())
    }
}
