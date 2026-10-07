package com.wand.app.ui

import androidx.compose.runtime.saveable.SaverScope
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationGroupDraftTest {
    private val saverScope = object : SaverScope { override fun canBeSaved(value: Any) = true }

    @Test fun stableEmployeeAndLegacyDutyKeysAreSerializedOnlyForThisGroup() {
        val template = JSONObject("""{"id":"preset","members":[{"id":"m_old","duty":"原预设职责","isLeader":true}]}""")
        val employee = JSONObject("""{"id":"e_a","name":"同名员工","duty":"员工资料职责"}""")
        val beforeTemplate = template.toString(); val beforeEmployee = employee.toString()
        val draft = ConversationGroupDraft().apply {
            selected = listOf("e_a"); templateId = "preset"; excluded = listOf("m_excluded")
            leaderId = "m_old"; name = "本群"
            duties = mapOf("e_a" to "本群员工职责", "m_old" to "本群旧成员职责")
        }
        val body = draft.groupInput(draft.duties, inviting = false)
        assertEquals("本群员工职责", body.getJSONObject("duties").getString("e_a"))
        assertEquals("本群旧成员职责", body.getJSONObject("duties").getString("m_old"))
        assertEquals("m_old", body.getString("leaderId")); assertEquals("preset", body.getString("templateId"))
        assertEquals("m_excluded", body.getJSONArray("excludedMemberIds").getString(0))
        assertEquals(beforeTemplate, template.toString()); assertEquals(beforeEmployee, employee.toString())
    }

    @Test fun defaultDutyPrefillsAreSentAndInvitationDoesNotReplaceLeaderOrName() {
        val draft = ConversationGroupDraft().apply { selected = listOf("e_b"); templateId = "preset"; leaderId = "old-leader"; name = "旧群" }
        val body = draft.groupInput(mapOf("e_b" to "预填职责"), inviting = true)
        assertEquals("预填职责", body.getJSONObject("duties").getString("e_b"))
        assertFalse(body.has("leaderId")); assertFalse(body.has("name"))
        assertEquals(listOf("e_b"), draft.selected)
    }

    @Test fun groupSaverKeepsDutyEditsWithSelectionLeaderAndPreset() {
        val draft = ConversationGroupDraft().apply {
            selected = listOf("e_a"); templateId = "preset"; leaderId = "e_a"
            duties = mapOf("e_a" to "仅此群调整", "m_old" to "旧配置职责")
        }
        val saved = with(ConversationGroupDraft.Saver) { saverScope.save(draft) }!!
        val restored = ConversationGroupDraft.Saver.restore(saved)!!
        assertEquals(draft.selected, restored.selected); assertEquals(draft.templateId, restored.templateId)
        assertEquals(draft.leaderId, restored.leaderId); assertEquals(draft.duties, restored.duties)
    }

    @Test fun oldSixFieldGroupStateRestoresWithoutInventingDuties() {
        val restored = ConversationGroupDraft.Saver.restore(listOf(listOf("e_a"), "preset", emptyList<String>(), "e_a", "群", 0))!!
        assertTrue(restored.duties.isEmpty()); assertEquals("e_a", restored.leaderId); assertEquals("preset", restored.templateId)
    }
}
