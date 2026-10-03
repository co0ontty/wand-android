package com.wand.app.ui.screens

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.wand.app.ui.components.indicatorShapeRadiusPx
import com.wand.app.ui.theme.WandShapes
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页底部悬浮菜单胶囊的纯逻辑：入口顺序 / 文案，以及「对话 / 任务」两个视图态的下标。
 * 通讯录是入口而不是当前视图，指示条永远不落在它上面。
 */
class HomeMenuPillTest {

    @Test
    fun itemsAreOrderedChatsTasksContacts() {
        assertEquals(
            listOf("对话", "任务", "通讯录"),
            HomeMenuPillItem.entries.map { it.label },
        )
    }

    @Test
    fun everyItemCarriesAnActionDescription() {
        assertEquals(
            listOf("切到对话列表", "切到任务面板", "打开通讯录"),
            HomeMenuPillItem.entries.map { it.description },
        )
    }

    @Test
    fun selectionFollowsListMode() {
        assertEquals(HomeMenuPillItem.Chats.ordinal, homeMenuPillSelection(HomeListMode.Sessions))
        assertEquals(HomeMenuPillItem.Tasks.ordinal, homeMenuPillSelection(HomeListMode.Tasks))
    }

    @Test
    fun contactsIsNeverTheSelectedSlot() {
        assertNotEquals(HomeMenuPillItem.Contacts.ordinal, homeMenuPillSelection(HomeListMode.Sessions))
        assertNotEquals(HomeMenuPillItem.Contacts.ordinal, homeMenuPillSelection(HomeListMode.Tasks))
    }

    @Test
    fun phoneAndExpandedTabletSidebarShareTheFloatingMenu() {
        val app = source("WandApp.kt")
        val phone = app.substringAfter("private fun SinglePaneContent(")
            .substringBefore("private fun WideReadyContent(")
        val tablet = app.substringAfter("private fun WideReadyContent(")
            .substringBefore("private fun SidebarResizeHandle(")
        assertTrue(phone.contains("TaskListScreen("))
        assertTrue(tablet.contains("TaskListScreen("))
        // 左栏一直可见：已经打开通讯录时重复点击不能再堆一层相同页面。
        assertTrue(tablet.contains("if (nav.current !is Screen.Contacts) nav.push(Screen.Contacts())"))
        // 不再保留只给平板关闭胶囊的配置，也不在顶栏重复一份菜单入口。
        assertFalse(app.contains("showMenuPill"))
        assertFalse(source("screens/TaskListScreen.kt").contains("showMenuPill"))
        assertFalse(source("screens/HomeChrome.kt").contains("showWorkbenchItems"))
    }

    @Test
    fun bothListModesReserveSpaceAboveTheFloatingMenu() {
        val screen = source("screens/TaskListScreen.kt")
        assertTrue(screen.contains("bottomClearance = 68.dp"))
        assertTrue(screen.contains("bottom = 88.dp"))
        assertTrue(screen.contains(".align(Alignment.BottomCenter)"))
        // 多选管理态仍然让位，不让胶囊覆盖批量操作。
        assertTrue(screen.contains("if (!selecting) {\n                HomeMenuPill("))
    }

    @Test
    fun narrowTabletSidebarPreservesLabelsWithoutIcons() {
        // 左栏最窄 220dp，两侧各留 12dp；小折叠屏的默认栏宽也是窄档。
        for (sidebarWidth in listOf(220.dp, 232.dp, 252.dp)) {
            assertFalse(homeMenuPillShowsIcons(sidebarWidth - 24.dp, 42.dp))
        }
    }

    @Test
    fun fullWidthMenuKeepsIconsAndLabels() {
        assertTrue(homeMenuPillShowsIcons(HomeMenuPillMaxWidth, 42.dp))
        assertTrue(homeMenuPillShowsIcons(280.dp - 24.dp, 42.dp))
    }

    @Test
    fun iconsOnlyAppearWhenTheWidestLabelFitsEverySegment() {
        assertFalse(homeMenuPillShowsIcons(244.dp, 42.dp))
        assertTrue(homeMenuPillShowsIcons(245.dp, 42.dp))
    }

    @Test
    fun largerMeasuredTextGivesPriorityToLabels() {
        assertFalse(homeMenuPillShowsIcons(HomeMenuPillMaxWidth, 55.dp))
        assertTrue(homeMenuPillShowsIcons(HomeMenuPillMaxWidth, 53.dp))
    }

    private fun source(path: String): String = listOf(
        File("src/main/java/com/wand/app/ui", path),
        File("app/src/main/java/com/wand/app/ui", path),
    ).first { it.isFile }.readText()

    /**
     * 内层弧度必须等于「外层半径 − 边距」，也就是内层行高的一半：
     * 指示条取整圆端形状后按行高收成半个高，两层才是同一条弧度（同心），
     * 不会出现内层比外层「方」的观感。
     */
    @Test
    fun innerArcIsConcentricWithTheOuterCapsule() {
        val density = Density(2f, 1f)
        val outerRadiusDp = HomeMenuPillHeight.value / 2f
        val innerHeightPx = (HomeMenuPillHeight - HomeMenuPillInset * 2).value * density.density
        val innerRadiusPx = indicatorShapeRadiusPx(WandShapes.full, density, fallbackPx = 0f)
            .coerceAtMost(innerHeightPx / 2f)

        assertEquals(outerRadiusDp - HomeMenuPillInset.value, innerRadiusPx / density.density, 0.01f)
    }
}
