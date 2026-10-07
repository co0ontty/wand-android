package com.wand.app.ui.screens

import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source contracts supplement navigation/owner tests; they are not rendered geometry evidence. */
class ConversationPresentationContractTest {
    private fun source(path: String): String = listOf(File("src/main/java/com/wand/app", path), File("app/src/main/java/com/wand/app", path)).first { it.isFile }.readText()

    @Test fun rootsProjectImAndWorkSeparatelyInBothLayouts() {
        val app = source("ui/WandApp.kt")
        val phone = app.substringAfter("private fun SinglePaneContent(").substringBefore("private fun WideReadyContent(")
        val wide = app.substringAfter("private fun WideReadyContent(").substringBefore("private fun SidebarResizeHandle(")
        // 新的用户合同：手机 IM 首屏是近期对话列表，详情必须由一次显式点击推上导航栈。
        assertFalse(app.contains("ConversationLanding"))
        assertFalse(phone.contains("if (homeListMode == HomeListMode.Im)"))
        assertTrue(phone.contains("list { id -> nav.push(Screen.Conversation(id)) }"))
        assertTrue(wide.contains("if (homeListMode == HomeListMode.Im)"))
        assertTrue(wide.contains("else PadLandingScreen"))
        assertTrue(wide.contains("sidebarCollapsed && homeListMode != HomeListMode.Im"))
        assertTrue(wide.contains("coerceIn(280.dp, minOf(400.dp, windowWidth - 360.dp))"))
        assertTrue(app.contains("configuration.screenHeightDp.dp"))
        // 负责人裁决 1：平板能恢复「上次明确打开」的两栏，但根屏右栏不得由 selectedId 变出一个聊天输入框。
        assertFalse(app.contains("shellState.SaveableStateProvider(\"im-chat\")"))
        assertFalse(wide.contains("ConversationChatScreen(conversationState, conversationState.selectedId"))
        assertTrue(wide.contains("nav.setDetail(Screen.Conversation(id))"))
        // 负责人裁决 3：详情按会话 id 存 Saveable，回列表再打开留草稿与派发意图，换会话不继承。
        assertTrue(app.contains("shellState.SaveableStateProvider(\"im-detail-$" + "{screen.conversationId}\")"))
        val list = source("ui/screens/TaskListScreen.kt")
        assertFalse(list.contains("legacyConversations"))
        assertFalse(list.contains("工作窗口 / 历史对话"))
        assertTrue(list.contains("homeListMode != HomeListMode.Im && homeActivityStripVisible"))
    }

    @Test fun bodyContentChangesDoNotChangeMessageExpansionIdentity() {
        val original = ConversationTurn("assistant", listOf(ContentBlock.Text("short", null)), createdAt = "2026-10-06T00:00:00Z", messageId = "message")
        assertEquals(conversationMessageKey(original), conversationMessageKey(original.copy(content = listOf(ContentBlock.Text("updated", null)))))
        assertEquals(conversationMessageKey(original.copy(messageId = null)), conversationMessageKey(original.copy(messageId = null, content = emptyList())))
    }

    @Test fun emptyFeedbackHasNoFixedBlankRowAndComposerStillUsesItsSingleOwner() {
        val screen = source("ui/screens/ConversationScreens.kt")
        val feedback = screen.substringAfter("val feedback = actionOperation.feedback").substringBefore("val permissions = protocols.values")
        assertTrue(feedback.contains("if (feedback.isNotBlank()) Text"))
        assertFalse(feedback.contains(".height(44.dp)"))
        assertFalse(feedback.contains(".height(22.dp)"))
        assertTrue(feedback.contains("heightIn(max = 96.dp).verticalScroll"))
        assertTrue(screen.contains("composer.submit(deliver"))
        assertFalse(screen.contains("fixedViewport = true"))
        assertTrue(screen.contains("alive.value && state.selectedId == capturedId"))
        assertTrue(screen.contains("val displayedDetail = state.details[displayedId]"))
        assertTrue(screen.contains("messages:$" + "displayedId:$" + "displayedFilter"))
        val row = source("ui/screens/AiTeamChatScreen.kt").substringAfter("internal fun ConversationInstanceTurn(").substringBefore("private fun TeamTurnRow(")
        assertTrue(row.contains("TurnView(turn, showHeader = false"))
        assertTrue(row.contains("parseUserAttachmentText"))
        assertTrue(row.contains("WandTeamReportFileCard"))
        assertFalse(row.contains("hashCode()"))
    }

    @Test fun imHierarchyKeepsRealClocksSeparateFromExecutionFactsAndSharedInput() {
        val screen = source("ui/screens/ConversationScreens.kt")
        val list = screen.substringAfter("internal fun ConversationList(").substringBefore("internal fun ConversationContacts(")
        assertTrue(list.contains("conversationListClock(item.messageAt)"))
        assertTrue(list.contains("if (clock.isNotBlank()) Text(clock"))
        assertTrue(list.contains("if (status != null) Text(conversationRunLabel(status)"))
        assertTrue(list.contains("if (status == \"failed\") WandColors.danger"))
        assertFalse(list.contains("status?.let(::conversationRunLabel) ?:"))
        assertTrue(list.contains("size(width = 96.dp, height = 48.dp)"))
        assertTrue(list.contains("this.selected = selected"))
        assertTrue(screen.contains("detail.team?.members.orEmpty().size + 1"))
        assertTrue(list.contains("touchSize = 48.dp"))
        assertTrue(screen.contains("ConversationInsetDivider(start = 68.dp)"))
        assertFalse(screen.contains("HorizontalDivider(color = WandColors.border)"))
        assertTrue(screen.contains("inlineControls = true"))
        assertTrue(screen.contains("SharedMessageComposer(backdrop = null, sessionKey = composer.sessionId"))
        assertTrue(screen.contains("sendActionVisual(composer.sendPhase"))
        assertTrue(screen.contains("liveRegion = LiveRegionMode.Polite"))
        val row = source("ui/screens/AiTeamChatScreen.kt").substringAfter("internal fun ConversationInstanceTurn(").substringBefore("/** Stable DTO identity")
        assertTrue(row.contains("TeamMessageRow(own,"))
        assertTrue(row.contains("val clock = conversationBubbleClock(turn)"))
        assertTrue(row.contains("if (clock.isNotBlank()) Text(clock"))
        assertTrue(row.contains("turn.reportFile != null -> WandTeamReportFileCard"))
        assertTrue(row.contains("WandInlinePanel(expanded, growFrom = Alignment.Top) { MarkdownText(text) }"))
        assertTrue(row.contains("onOpenSession(sessionId)"))
        assertTrue(row.contains("conversationNeedsCollapse(text)"))
    }

    @Test fun contactsBackClosesLayersBeforeReturningFromPresetList() {
        val contacts = source("ui/screens/ConversationScreens.kt").substringAfter("internal fun ConversationContacts(").substringBefore("internal fun ConversationChatScreen(")
        val back = contacts.substringAfter("fun backFromContacts() {").substringBefore("ConversationLayerBackHandler")
        assertTrue(back.contains("preset != null || expanded.isNotEmpty() -> closeLayer()"))
        assertTrue(back.indexOf("-> closeLayer()") < back.indexOf("tab == 1 -> tab = 0"))
        assertTrue(back.indexOf("tab == 1 -> tab = 0") < back.indexOf("else -> onBack()"))
        assertTrue(contacts.contains("ConversationLayerBackHandler(preset != null || expanded.isNotEmpty() || tab == 1) { backFromContacts() }"))
        assertTrue(contacts.contains("WandIconButton(WandIcons.back, \"返回\", { backFromContacts() }"))
        assertTrue(contacts.contains("KeyEventType.KeyUp && (preset != null || expanded.isNotEmpty() || tab == 1)) { backFromContacts(); true }"))
    }

    @Test fun sharedMorphKeepsPressInsideFixedTouchBoxAndRetiringLayerCannotReceiveInput() {
        val kit = source("ui/components/WandMotionKit.kt")
        val button = kit.substringAfter("fun WandMorphIconButton(").substringBefore("// MARK: - 状态就地切换")
        assertFalse(button.substringBefore("WandMorphingIcon(").contains(".graphicsLayer"))
        assertTrue(button.substringAfter("WandMorphingIcon(").contains("scaleX = pressScale"))
        val swap = kit.substringAfter("fun WandInPlaceSwap(").substringBefore("// MARK: - 工具 / 命令卡")
        assertTrue(swap.contains("clearAndSetSemantics"))
        assertTrue(swap.contains("canFocus = active"))
        assertTrue(swap.contains("PointerEventPass.Initial"))
        assertTrue(swap.contains("it.consume()"))
    }
}
