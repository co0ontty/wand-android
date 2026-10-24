package com.wand.app

import com.wand.app.data.ConversationInstance
import com.wand.app.ui.screens.ConversationSwipeAction
import com.wand.app.ui.screens.conversationIdentityStatus
import com.wand.app.ui.screens.conversationSwipeActions
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DingTalkConversationTest {
    @Test fun mirrorPreservesExternalIdentityAndDoesNotInventATeam() {
        val value = ConversationInstance.parse(JSONObject("""{"id":"dingtalk_fixture","kind":"group","title":"外部群","external":{"provider":"dingtalk","groupId":"fixture","syncState":"at_only","historyComplete":false,"bindings":[{"id":"binding","employeeId":"e_fixture","state":"verifying"}]}}"""))!!
        assertNull(value.team)
        assertEquals("钉钉群 · 仅同步 @消息", conversationIdentityStatus(value))
        assertEquals("e_fixture", value.external?.bindings?.first()?.employeeId)
        assertFalse(conversationIdentityStatus(value).contains("派发任务"))
    }
    @Test fun externalGroupCannotOfferDissolveRealGroupAction() {
        val actions = conversationSwipeActions("group", pinned = false, dissolved = false, external = true)
        assertTrue(actions.contains(ConversationSwipeAction.Pin))
        assertTrue(actions.contains(ConversationSwipeAction.Delete))
        assertFalse(actions.contains(ConversationSwipeAction.Dissolve))
    }
    @Test fun nativeConnectorEntryCarriesOnlyTheAllowlistedSection() {
        val accepted = SettingsWebSession("https://example.invalid:8443/wand", "connectors")
        assertEquals("https://example.invalid:8443/wand/settings?client=app&section=connectors", accepted.startUrl)
        assertFalse(SettingsWebSession("https://example.invalid", "https://foreign.invalid").startUrl.contains("foreign"))
    }
}
