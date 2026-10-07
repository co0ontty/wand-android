package com.wand.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PiAutomaticConfigurationTest {
    @Test fun parsesAutomaticSwitchAndCodeModeCapabilitiesWithoutInferringSupportOnOldServers() {
        val response = PiResourcesResponse.parse(JSONObject("""{
          "settings":{"autoResources":true,"resources":{"skills":["skill-a"],"mcpServers":[]}},
          "resourceCatalog":{"supported":true,"reason":"","skills":[],"mcpServers":[]},
          "autoResourcesAvailable":true,"autoResourcesReason":"","autoCodemodeAvailable":true
        }"""))
        assertTrue(response.autoResources)
        assertTrue(response.autoResourcesAvailable)
        assertTrue(response.autoCodemodeAvailable)
        assertEquals(listOf("skill-a"), response.selection?.skills)
        assertEquals("follow", response.codemode)
        val old = PiResourcesResponse.parse(JSONObject("""{"settings":{},"resourceCatalog":{"supported":true}}"""))
        assertFalse(old.autoResources)
        assertFalse(old.autoResourcesAvailable)
        assertFalse(old.autoCodemodeAvailable)
        assertTrue(old.autoResourcesReason.contains("更新服务端"))
        val malformed = PiResourcesResponse.parse(JSONObject("""{"settings":{"autoResources":"true"},"autoResourcesAvailable":"true"}"""))
        assertFalse(malformed.autoResources)
        assertFalse(malformed.autoResourcesAvailable)
    }

    @Test fun skillLocksAndAtomicAckRequireRealServerConfirmation() {
        val json = JSONObject("""{"settings":{"resources":{"skills":["skill-a"],"mcpServers":["mcp-b"]},
          "lockedSkills":["skill-a"]},"resourceCatalog":{"supported":true},"skillLocksAvailable":true}""")
        val response = PiResourcesResponse.parse(json)
        assertTrue(response.skillLocksAvailable)
        assertEquals(PiSkillMode.Locked, response.skillMode("skill-a"))
        assertEquals(PiSkillMode.Off, response.skillMode("skill-c"))
        val unlocked = response.withSkillMode("skill-a", PiSkillMode.On)
        assertTrue(unlocked.lockedSkills.isEmpty())
        assertEquals(listOf("skill-a"), unlocked.selection.skills)
        assertEquals(listOf("mcp-b"), unlocked.selection.mcpServers)
        assertEquals(unlocked, PiSkillSelectionAck.parse(unlocked.toJson().put("unused", true)))
        assertNull(PiSkillSelectionAck.parse(JSONObject("""{"resources":{"skills":[],"mcpServers":[]}}""")))
        assertNull(PiSkillSelectionAck.parse(JSONObject("""{"resources":{"skills":[],"mcpServers":[]},"lockedSkills":[42]}""")))
        assertNull(PiSkillSelectionAck.parse(JSONObject("""{"resources":{"skills":[],"mcpServers":[]},"lockedSkills":["a","a"]}""")))
        assertFalse(PiResourcesResponse.parse(JSONObject("""{"settings":{}}""")).skillLocksAvailable)
    }

    @Test fun conversationTurnParsesBoundedServerLabelAndDoesNotConstructAChoiceFromToolOutput() {
        val turn = ConversationTurn.parse(JSONObject("""{"role":"assistant","content":[],
          "resourceSelection":{"status":"selected","label":"本轮选择 · Skills：mermaid；CodeMode：启用"}}"""))
        assertEquals("selected", turn.resourceSelection?.status)
        assertEquals("本轮选择 · Skills：mermaid；CodeMode：启用", turn.resourceSelection?.label)
        assertNull(ConversationTurn.parse(JSONObject("""{"role":"assistant","content":[]}""")).resourceSelection)
        assertNull(PiResourceSelectionNotice.parse(JSONObject("""{"label":42}""")))
        assertNull(PiResourceSelectionNotice.parse(JSONObject("""{"label":"   "}""")))
        assertEquals(1024, PiResourceSelectionNotice.parse(JSONObject().put("label", "x".repeat(3000)))?.label?.length)
    }

    @Test fun streamingAndReconnectKeepTheSettledSelectionAndDoNotLeakItIntoAnotherTurn() {
        val previous = ConversationTurn("assistant", emptyList(), createdAt = "2026-10-05T06:00:00Z",
            resourceSelection = PiResourceSelectionNotice("本轮选择 · CodeMode：关闭", "selected"))
        val missing = previous.copy(content = listOf(ContentBlock.Text("done", null)), resourceSelection = null)
        assertEquals(previous.resourceSelection, mergeConversationTurnTimes(previous, missing).resourceSelection)
        val late = previous.copy(resourceSelection = PiResourceSelectionNotice("正在自动选择…", "selecting"))
        assertEquals(previous.resourceSelection, mergeConversationTurnTimes(previous, late).resourceSelection)
        val nextTurn = missing.copy(createdAt = "2026-10-05T06:01:00Z")
        assertNull(mergeConversationTurnTimes(previous, nextTurn).resourceSelection)
        val cancelled = previous.copy(resourceSelection = PiResourceSelectionNotice("本轮自动选择已取消", "cancelled"))
        assertEquals(cancelled.resourceSelection, mergeConversationTurnTimes(previous, cancelled).resourceSelection)
    }
}
