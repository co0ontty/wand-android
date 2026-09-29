package com.wand.app

import com.wand.app.data.ServerProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeServerProfileChangesTest {
    private val first = ServerProfile("server_first", "https://first.example", "first-token")
    private val second = ServerProfile("server_second", "https://second.example")
    private val original = listOf(first, second)

    @Test
    fun renamingEitherProfileKeepsTheCurrentRuntime() {
        val renamed = listOf(
            first.copy(customName = "工作室"),
            second.copy(customName = "测试机"),
        )

        assertTrue(hasSameServerConnectionIdentity(original, first.id, renamed, first.id))
        assertTrue(hasSameServerConnectionIdentity(renamed, first.id, original, first.id))
    }

    @Test
    fun routingOrCredentialChangesStillRequireRuntimeReload() {
        assertFalse(hasSameServerConnectionIdentity(
            original, first.id, original, second.id,
        ))
        assertFalse(hasSameServerConnectionIdentity(
            original, first.id, original.reversed(), first.id,
        ))
        assertFalse(hasSameServerConnectionIdentity(
            original, first.id,
            listOf(first.copy(token = "new-token"), second), first.id,
        ))
        assertFalse(hasSameServerConnectionIdentity(
            original, first.id,
            listOf(first.copy(baseUrl = "https://moved.example"), second), first.id,
        ))
        assertFalse(hasSameServerConnectionIdentity(
            original, first.id, listOf(first), first.id,
        ))
    }
}
