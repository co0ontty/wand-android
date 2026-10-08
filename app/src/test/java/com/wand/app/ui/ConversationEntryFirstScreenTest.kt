package com.wand.app.ui

import androidx.compose.runtime.saveable.SaverScope
import com.wand.app.data.ConversationTarget
import com.wand.app.data.WandApi
import com.wand.app.ui.screens.HomeListMode
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AD-01 首屏合同：手机 IM 根屏是近期对话列表，详情必须由一次显式动作推上栈。
 * 这里跑真实 NavState / ConversationStore 行为，不靠按钮文案。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationEntryFirstScreenTest {
    private val saverScope = object : SaverScope { override fun canBeSaved(value: Any) = true }
    private fun restored(nav: NavState): NavState = with(NavState.Saver) { saverScope.save(nav) }!!
        .let { NavState.Saver.restore(it)!! }

    private fun source(name: String): String = File("src/main/java/com/wand/app/ui/$name.kt").readText()
    private fun screen(name: String): String = File("src/main/java/com/wand/app/ui/screens/$name.kt").readText()

    @Test fun rememberedAndAsyncDefaultSelectionNeverOpensDetailFromTheListRoot() = runTest {
        var preference = "{}"
        val store = ConversationStore(WandApi("http://127.0.0.1:1", null), SessionDraftStore(), backgroundScope, { preference }, { preference = it })
        val nav = NavState().apply { initializeHomeMode("im") }
        store.onSelection = nav::syncConversation
        // 列表刷新时的默认伙伴自动选择：selectedId 只是记忆的对象，不是「用户这次点了它」的证据。
        store.select("dm_default")
        assertEquals(Screen.SessionList, nav.current)
        assertEquals(1, nav.stack.size)
        assertEquals("dm_default", store.selectedId)
        store.shutdown()
    }

    @Test fun explicitItemTapOpensThatConversationAndBackReturnsToTheListInImMode() = runTest {
        var preference = "{}"
        val store = ConversationStore(WandApi("http://127.0.0.1:1", null), SessionDraftStore(), backgroundScope, { preference }, { preference = it })
        val nav = NavState().apply { initializeHomeMode("im") }
        store.onSelection = nav::syncConversation
        store.select("group_b")
        nav.push(Screen.Conversation("group_b"))
        assertEquals(Screen.Conversation("group_b"), nav.current)
        nav.pop()
        assertEquals(Screen.SessionList, nav.current)
        assertEquals(HomeListMode.Im, nav.homeMode)
        // 再进同一条聊天：草稿、附件与任务目标仍归原所有者。
        val composer = store.composer("group_b")
        composer.editDraft("还在的草稿")
        nav.push(Screen.Conversation("group_b"))
        assertEquals(composer, store.composer("group_b"))
        assertEquals("还在的草稿", store.composer("group_b").draft)
        store.shutdown()
    }

    @Test fun restoreKeepsAnExplicitlyOpenedDetailWhileACollapsedStackRestoresTheList() {
        val open = NavState().apply { initializeHomeMode("im"); push(Screen.Conversation("group_b")) }
        assertEquals(Screen.Conversation("group_b"), restored(open).current)

        val backToRoot = NavState().apply { initializeHomeMode("im"); push(Screen.Conversation("group_b")); pop() }
        val restoredRoot = restored(backToRoot)
        assertEquals(Screen.SessionList, restoredRoot.current)
        assertEquals(HomeListMode.Im, restoredRoot.homeMode)
    }

    @Test fun deepLinkTargetOpensItsDetailAndBackReturnsToTheSourcePage() {
        val nav = NavState().apply { initializeHomeMode("board"); push(Screen.TaskBoard(taskId = "t")) }
        nav.push(Screen.Conversation("dm_a"))
        assertTrue(nav.current is Screen.Conversation)
        assertEquals(HomeListMode.Im, nav.homeMode)
        nav.pop()
        assertEquals(Screen.TaskBoard(taskId = "t"), nav.current)
        assertEquals(HomeListMode.Tasks, nav.homeMode)
    }

    @Test fun enteringImFromOtherRootsAndRepeatingTheTabAlwaysLandsOnTheListRoot() {
        val nav = NavState().apply { initializeHomeMode("sessions"); push(Screen.WorkspaceTask("ws", "t", "W", "T")) }
        nav.selectHomeMode(HomeListMode.Im)
        assertEquals(listOf<Screen>(Screen.SessionList), nav.stack.toList())
        nav.push(Screen.Conversation("dm_a"))
        nav.selectHomeMode(HomeListMode.Im)
        assertEquals(Screen.SessionList, nav.current)
        nav.selectHomeMode(HomeListMode.Tasks)
        nav.selectHomeMode(HomeListMode.Im)
        assertEquals(Screen.SessionList, nav.current)
        assertEquals(HomeListMode.Im, nav.homeMode)
    }

    @Test fun phoneRootIsTheListAndTheDetailIsAStackEntryAboveIt() {
        val app = source("WandApp")
        assertFalse("聊天抽屉冒充首屏已废除", app.contains("ConversationLanding"))
        assertTrue(app.contains("list { id -> nav.push(Screen.Conversation(id)) }"))
        // 详情投影这条导航记录自己的对象，不被异步默认选择改写。
        assertTrue(app.contains("ConversationChatScreen(conversationState, screen.conversationId"))
        val detail = app.substringAfter("is Screen.Conversation -> {").substringBefore("is Screen.Chat -> ChatScreen(")
        assertTrue(detail.contains("onBack = { nav.pop() }"))
        assertTrue(detail.contains("showBack = showBack"))
    }

    @Test fun detailBackUsesTheIconControlAndNoDrawerEntryIsLeftInTheChatHeader() {
        val chat = screen("ConversationScreens")
        val header = chat.substringAfter("internal fun ConversationChatScreen(").substringBefore("WandInlinePanel(titleOpen")
        assertTrue(header.contains("if (showBack) WandIconButton(WandIcons.back, \"返回\", onBack"))
        assertTrue(header.contains("Modifier.align(Alignment.CenterStart), variant = WandIconButtonVariant.Chrome"))
        assertFalse("「返回」不再挤进 48dp 窄槽折成两行", chat.contains("WandButton(\"返回\", onBack, modifier = Modifier.width(48.dp)"))
        // 抽屉式「打开列表」不是首屏：列表自己是根屏，详情只需要回到来源。
        assertFalse(chat.contains("打开列表"))
        assertFalse(chat.contains("listOpen"))
        assertFalse(chat.contains("WandIcons.panelExpand"))
    }

    /** E04：列表的滚动与搜索归原所有者，进详情不清条件，返回还是同一张列表。 */
    @Test fun listScrollAndSearchSurviveOpeningAnItem() = runTest {
        var preference = "{}"
        val store = ConversationStore(WandApi("http://127.0.0.1:1", null), SessionDraftStore(), backgroundScope, { preference }, { preference = it })
        assertSame(store.listState("conversations"), store.listState("conversations"))
        val list = screen("ConversationScreens").substringAfter("internal fun ConversationList(").substringBefore("internal fun ConversationChatScreen(")
        assertFalse("点条目不得清空搜索条件", list.contains("query = \"\"; onSelect"))
        assertTrue(list.lineSequence().first { it.contains("toggleSelected(item.id) else { create = false; keyboard?.hide(); onSelect(item.id) }") }
            .contains("onSelect(item.id)"))
        assertTrue("选中态按多选或当前会话投影给无障碍", list.contains("this.selected = if (selecting) managedSelected else selected"))
        // 长按不再弹菜单：改成进多选，条目操作由从右往左划出的抽屉承担。
        assertTrue(list.contains("onLongClickLabel = if (selecting) \"取消选择对话\" else \"多选对话\""))
        assertTrue(list.contains("ConversationSwipeRowCard("))
        assertFalse(list.contains("DropdownMenu(expanded ="))
        // 列表自身算面板内：外点观察器不会在进详情前顺手关掉搜索。
        assertTrue(list.contains("setOf(\"actions\", \"form\", \"search\", \"list\")"))
        assertTrue(list.contains("layer.region(\"list\")"))
        store.shutdown()
    }

    /** E08 / E09：列表首屏不新增页面级发送锁，也不靠清 selectedId 实现。 */
    @Test fun listFirstNavigationAddsNoSendLockAndNoSelectedIdReset() {
        val chat = screen("ConversationScreens")
        assertFalse(chat.contains("selectedId = \"\""))
        assertFalse(chat.contains("select(\"\")"))
        assertFalse(chat.contains("isSending"))
        assertFalse(source("WandApp").contains("conversations.select(\"\")"))
    }

    /**
     * 负责人裁决 1：平板保留「上次明确打开」的两栏；只有记忆 selectedId（冷启动/后台轮询里
     * 默认伙伴被自动 select）时栈上没有详情记录，右栏只能占位，不能变成当前聊天输入框。
     */
    @Test fun padRestoresAnExplicitlyOpenedDetailButPlainSelectionIsNoIntent() = runTest {
        var preference = "{}"
        val store = ConversationStore(WandApi("http://127.0.0.1:1", null), SessionDraftStore(), backgroundScope, { preference }, { preference = it })
        val nav = NavState().apply { initializeHomeMode("im") }
        store.onSelection = nav::syncConversation
        store.select("dm_default")
        assertFalse("自动选中不产生显式导航意图", nav.current is Screen.Conversation)
        assertEquals(Screen.SessionList, nav.current)
        // 用户在侧栏点一条 = 显式打开，两栏恢复；这条记录本身能被 Saver 带回来。
        nav.setDetail(Screen.Conversation("group_b"))
        assertEquals(Screen.Conversation("group_b"), nav.current)
        assertEquals(Screen.Conversation("group_b"), restored(nav).current)
        store.shutdown()
    }

    @Test fun padRightPaneReadsTheStackEntryAndNeverTheProjectedSelectedId() {
        val app = source("WandApp")
        val wide = app.substringAfter("private fun WideReadyContent(")
        assertTrue("侧栏点条目要落显式详情记录", wide.contains("conversationState.select(id); nav.setDetail(Screen.Conversation(id))"))
        assertFalse("右栏不得再把 selectedId 当当前聊天渲染", wide.contains("ConversationChatScreen(conversationState, conversationState.selectedId"))
        assertFalse(app.contains("shellState.SaveableStateProvider(\"im-chat\")"))
    }

    /**
     * 负责人裁决 3：同一会话「详情→列表→再打开」，未发送草稿、项目/目标与派发意图都归这个会话；
     * 另一条会话是新对象，不继承上一个意图。这里跑真实 store，接线只补一条 Saveable key 证据。
     */
    @Test fun sameConversationRoundTripKeepsDraftTargetAndDispatchIntent() = runTest {
        var preference = "{}"
        val api = WandApi("http://127.0.0.1:1", null)
        val drafts = SessionDraftStore()
        val store = ConversationStore(api, drafts, backgroundScope, { preference }, { preference = it })
        val nav = NavState().apply { initializeHomeMode("im") }
        store.onSelection = nav::syncConversation
        val target = ConversationTarget("task_1", "run_1")
        store.select("group_b"); store.target("group_b", target)
        val composer = store.composer("group_b", target)
        composer.editDraft("派给项目的草稿")
        nav.push(Screen.Conversation("group_b"))
        nav.pop()
        nav.push(Screen.Conversation("group_b"))
        assertSame("回列表再打开还是同一个草稿对象", composer, store.composer("group_b", target))
        assertEquals("派给项目的草稿", store.composer("group_b", target).draft)
        assertEquals(target, store.targets["group_b"])
        // 派发意图存在按会话 id 的 Saveable 里：key 带 id，所以换会话不会继承上一个对象的意图。
        assertNotSame(composer, store.composer("dm_c", null))
        val app = source("WandApp")
        assertTrue(app.contains("shellState.SaveableStateProvider(\"im-detail-$" + "{screen.conversationId}\")"))
        assertTrue(screen("ConversationScreens").contains("var taskMode by rememberSaveable(id) { mutableStateOf(false) }"))
        store.persist()
        val reopened = ConversationStore(api, drafts, backgroundScope, { preference }, { preference = it })
        assertEquals("重启恢复后项目/目标仍属于这条会话", target, reopened.targets["group_b"])
        store.shutdown()
    }
}
