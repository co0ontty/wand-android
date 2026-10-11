package com.wand.app.ui.screens

import com.wand.app.data.BoardTaskAgent
import org.junit.Assert.assertEquals
import org.junit.Test

class EmployeeComposerDefaultsTest {
    @Test fun privateComposerUsesFirstConfiguredCandidateAndNeverTheFallback() {
        val values = employeeComposerDefaults(listOf(BoardTaskAgent("claude", "sonnet", "off", "default"),
            BoardTaskAgent("codex", "another-paid-model", "max", "full-access")))
        assertEquals(EmployeeComposerDefaults("sonnet", "标准", "自动"), values)
    }

    @Test fun unknownValuesAreNotDisguisedAsStandardOrDisabledThinking() {
        assertEquals(EmployeeComposerDefaults("custom-model", "custom-permission", "pi:ultra"),
            employeeComposerDefaults(listOf(BoardTaskAgent("pi", "custom-model", "pi:ultra", "custom-permission"))))
    }

    @Test fun missingCandidateAndServerDefaultAreDistinct() {
        assertEquals(EmployeeComposerDefaults("未配置", "未配置", "未配置"), employeeComposerDefaults(emptyList()))
        assertEquals("跟随服务端默认", employeeComposerDefaults(listOf(BoardTaskAgent("claude", "default", "off"))).model)
    }
}
