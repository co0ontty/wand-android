package com.wand.app.ui.screens

import com.wand.app.data.BoardTaskAgent
import com.wand.app.data.SESSION_MODE_OPTIONS
import com.wand.app.ui.thinkingEffortOptions

internal data class EmployeeComposerDefaults(val model: String, val permission: String, val thinking: String)

/** Read only the first configured candidate. Historical sessions cannot stand in for new work. */
internal fun employeeComposerDefaults(agents: List<BoardTaskAgent>): EmployeeComposerDefaults {
    val first = agents.firstOrNull() ?: return EmployeeComposerDefaults("未配置", "未配置", "未配置")
    return EmployeeComposerDefaults(
        first.model.takeUnless { it.isBlank() || it == "default" } ?: "跟随服务端默认",
        SESSION_MODE_OPTIONS.firstOrNull { it.id == first.mode }?.label ?: first.mode.ifBlank { "待确认" },
        thinkingEffortOptions(first.provider, first.model, null, emptyList())
            .firstOrNull { it.id == first.thinkingEffort }?.label ?: first.thinkingEffort.ifBlank { "待确认" },
    )
}
