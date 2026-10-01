package com.wand.app.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmployeeAvatarTest {
    @Test
    fun eachSupportedCliKeepsItsOwnLogoIdentity() {
        listOf("claude", "codex", "opencode", "grok", "qoder", "pi", "gemini").forEach {
            assertEquals(it, employeeAvatarProvider(it))
        }
        assertEquals("pi", employeeAvatarProvider(" Pi "))
    }

    @Test
    fun unknownOrMissingCliNeverImpersonatesClaude() {
        listOf(null, "", "terminal", "future-cli").forEach {
            assertNull(employeeAvatarProvider(it))
        }
    }

    @Test
    fun badgeScalesWithAvatarWithoutChangingAvatarSize() {
        assertEquals(13.dp, employeeCliBadgeSize(24.dp))
        assertEquals(13.dp, employeeCliBadgeSize(26.dp))
        assertEquals(14.dp, employeeCliBadgeSize(28.dp))
        assertEquals(16.dp, employeeCliBadgeSize(32.dp))
        assertEquals(20.dp, employeeCliBadgeSize(44.dp))
    }
}
