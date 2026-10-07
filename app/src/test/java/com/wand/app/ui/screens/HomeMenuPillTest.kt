package com.wand.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 产品入口命名与既有根模式映射；导航改视觉不改变各模式恢复语义。 */
class HomeMenuPillTest {
    @Test fun itemsPutChatAndPeopleBeforeWorkTools() {
        assertEquals(listOf("聊天", "通讯录", "工作区", "任务"), HomeMenuPillItem.entries.map { it.label })
        assertEquals(listOf("打开聊天", "打开通讯录", "打开工作区", "打开任务面板"),
            HomeMenuPillItem.entries.map { it.description })
    }

    @Test fun selectionFollowsModesAfterChangingDisplayOrder() {
        assertEquals(HomeMenuPillItem.Chats.ordinal, homeMenuPillSelection(HomeListMode.Sessions))
        assertEquals(HomeMenuPillItem.Tasks.ordinal, homeMenuPillSelection(HomeListMode.Tasks))
        assertEquals(HomeMenuPillItem.Im.ordinal, homeMenuPillSelection(HomeListMode.Im))
        HomeListMode.entries.forEach {
            assertNotEquals(HomeMenuPillItem.Contacts.ordinal, homeMenuPillSelection(it))
        }
    }

    @Test fun phoneAndTabletRetainTheSameNavigationAndDirectoryReturnPath() {
        val app = source("WandApp.kt")
        val phone = app.substringAfter("private fun SinglePaneContent(").substringBefore("private fun WideReadyContent(")
        val tablet = app.substringAfter("private fun WideReadyContent(").substringBefore("private fun SidebarResizeHandle(")
        assertTrue(phone.contains("TaskListScreen("))
        assertTrue(tablet.contains("TaskListScreen("))
        assertTrue(tablet.contains("if (nav.current !is Screen.Contacts) nav.push(Screen.Contacts())"))
        assertFalse(app.contains("showMenuPill"))
    }

    @Test fun contentReservesMeasuredNavigationHeightIncludingLargerText() {
        val screen = source("screens/TaskListScreen.kt")
        assertTrue(screen.contains("bottomClearance = menuClearance"))
        assertTrue(screen.contains("bottom = maxOf(88.dp, menuClearance)"))
        assertTrue(screen.contains("menuHeight"))
        assertTrue(screen.contains(".onSizeChanged { menuHeight"))
        assertTrue(screen.contains("if (!selecting) {\n                HomeMenuPill("))
        val navigation = source("screens/HomeChrome.kt").substringAfter("internal fun HomeMenuPill(")
            .substringBefore("// MARK: - 共享微件")
        // 按真实约束测量中文标签，多行由字体缩放决定，不再丢图标或强制插入换行。
        assertTrue(navigation.contains("constraints = Constraints(maxWidth"))
        assertTrue(navigation.contains("labels.maxOf { it.lineCount }"))
        assertTrue(navigation.contains("Icon(item.icon"))
        assertFalse(navigation.contains("enabled && !selected"))
    }

    private fun source(path: String): String = listOf(
        File("src/main/java/com/wand/app/ui", path), File("app/src/main/java/com/wand/app/ui", path),
    ).first { it.isFile }.readText()
}
