package com.wand.app.ui

import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.SiliconEmployee
import com.wand.app.data.agentToolOption
import com.wand.app.data.normalizeBoardTaskAgentMode
import com.wand.app.ui.SendPhase
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Ephemeral execution choice for one employee DM page; never mutates the employee definition. */
internal class EmployeeExecutionPreferenceState {
    var employeeId by mutableStateOf<String?>(null)
        private set
    var preference by mutableStateOf<BoardTaskAgent?>(null)
        private set

    fun selectEmployee(employee: SiliconEmployee?) {
        if (employeeId == employee?.id) return
        employeeId = employee?.id
        preference = null
    }

    fun reset() {
        preference = null
    }

    fun chooseTool(toolId: String, employee: SiliconEmployee?, enabled: Boolean): Boolean {
        if (!enabled || employee?.id == null || employee.id != employeeId) return false
        val option = agentToolOption(toolId) ?: return false
        preference = BoardTaskAgent(
            provider = option.provider,
            model = "default",
            thinkingEffort = "off",
            mode = normalizeBoardTaskAgentMode(option.provider, "default"),
            kind = "structured",
            engine = option.engine?.raw,
        )
        return true
    }

    fun chooseModel(
        model: String,
        supportedEfforts: Set<String>,
        employee: SiliconEmployee?,
        enabled: Boolean,
    ): Boolean {
        if (!enabled || model.isBlank() || employee?.id == null || employee.id != employeeId) return false
        val current = preference ?: employee.agents.firstOrNull()?.copy() ?: return false
        preference = current.copy(
            model = model,
            thinkingEffort = current.thinkingEffort.takeIf { it in supportedEfforts } ?: "off",
        )
        return true
    }

    fun chooseThinkingEffort(
        effort: String,
        supportedEfforts: Set<String>,
        employee: SiliconEmployee?,
        enabled: Boolean,
    ): Boolean {
        if (!enabled || effort !in supportedEfforts || employee?.id == null || employee.id != employeeId) return false
        val current = preference ?: employee.agents.firstOrNull()?.copy() ?: return false
        preference = current.copy(thinkingEffort = effort)
        return true
    }

    fun forMessage(
        employee: SiliconEmployee?,
        isDirectMessage: Boolean,
        hasRunTarget: Boolean,
        isDispatch: Boolean,
        unknown: Boolean,
        sendPhase: SendPhase,
        uploading: Boolean,
    ): BoardTaskAgent? {
        if (!isDirectMessage || hasRunTarget || isDispatch || unknown ||
            sendPhase == SendPhase.Sending || uploading || employee?.id != employeeId) return null
        return preference
    }
}
