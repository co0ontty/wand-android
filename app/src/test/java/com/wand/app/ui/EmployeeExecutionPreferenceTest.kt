package com.wand.app.ui

import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.SiliconEmployee
import org.junit.Assert.*
import org.junit.Test

class EmployeeExecutionPreferenceTest {
    private fun employee(id: String = "e1", candidate: BoardTaskAgent = BoardTaskAgent.default("codex")) = SiliconEmployee(
        id = id, name = "员工", duty = "", prompt = "prompt", avatar = "", agents = listOf(candidate),
    )

    @Test fun untouchedDefaultIsNotAnOverrideAndExplicitChoiceCarriesEngine() {
        val state = EmployeeExecutionPreferenceState()
        val peer = employee()
        state.selectEmployee(peer)
        assertNull(state.forMessage(peer, true, false, false, false, SendPhase.Idle, false))
        assertTrue(state.chooseTool("wand-agent", peer, enabled = true))
        val selected = state.preference!!
        assertEquals("pi", selected.provider)
        assertEquals("sdk", selected.engine)
        assertEquals("default", selected.model)
        assertEquals("off", selected.thinkingEffort)
        assertEquals(selected, state.forMessage(peer, true, false, false, false, SendPhase.Idle, false))
    }

    @Test fun resetAndEmployeeSwitchReturnToEmployeeDefaultWithoutMutatingDefinition() {
        val state = EmployeeExecutionPreferenceState()
        val original = employee()
        state.selectEmployee(original)
        state.chooseTool("pi", original, enabled = true)
        state.reset()
        assertNull(state.preference)
        state.chooseTool("pi", original, enabled = true)
        state.selectEmployee(employee("e2"))
        assertNull(state.preference)
        assertEquals("codex", original.agents.single().provider)
    }

    @Test fun choosingModelOrEffortFromDefaultCreatesAnOverrideAndUnknownSendLocksIt() {
        val modelState = EmployeeExecutionPreferenceState()
        val peer = employee()
        modelState.selectEmployee(peer)
        assertNull(modelState.preference)
        assertTrue(modelState.chooseModel("codex-model", setOf("off", "low"), peer, enabled = true))
        assertEquals("codex", modelState.preference?.provider)
        assertEquals("codex-model", modelState.preference?.model)

        val effortState = EmployeeExecutionPreferenceState()
        effortState.selectEmployee(peer)
        assertTrue(effortState.chooseThinkingEffort("high", setOf("off", "high"), peer, enabled = true))
        assertEquals("high", effortState.preference?.thinkingEffort)
        assertTrue(effortState.chooseTool("pi", peer, enabled = true))
        assertTrue(effortState.chooseThinkingEffort("high", setOf("off", "high"), peer, enabled = true))
        assertTrue(effortState.chooseModel("pi-model", setOf("off", "low"), peer, enabled = true))
        assertEquals("off", effortState.preference?.thinkingEffort)
        assertFalse(effortState.chooseModel("", setOf("off"), peer, enabled = true))
        val explicit = effortState.preference
        assertNull(effortState.forMessage(peer, true, false, false, true, SendPhase.Idle, false))
        assertEquals(explicit, effortState.preference)
        assertNull(effortState.forMessage(peer, true, false, false, false, SendPhase.Sending, false))
    }

    @Test fun groupRunTargetDispatchAndUploadNeverCarryTemporaryPreference() {
        val state = EmployeeExecutionPreferenceState()
        val peer = employee()
        state.selectEmployee(peer)
        state.chooseTool("pi", peer, enabled = true)
        assertNull(state.forMessage(peer, false, false, false, false, SendPhase.Idle, false))
        assertNull(state.forMessage(peer, true, true, false, false, SendPhase.Idle, false))
        assertNull(state.forMessage(peer, true, false, true, false, SendPhase.Idle, false))
        assertNull(state.forMessage(peer, true, false, false, false, SendPhase.Idle, true))
    }
}
