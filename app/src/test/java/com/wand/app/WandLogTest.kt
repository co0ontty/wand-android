package com.wand.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WandLogTest {

    private fun entry(message: String, level: WandLog.Level = WandLog.Level.INFO) =
        WandLog.Entry(timeMs = 1_700_000_000_000L, level = level, tag = "api", message = message)

    @Test
    fun ringDropsOldestEntriesWhenFull() {
        val ring = WandLog.LogRing(3)
        (1..5).forEach { ring.add(entry("m$it")) }

        assertEquals(3, ring.size())
        assertEquals(listOf("m3", "m4", "m5"), ring.snapshot().map { it.message })
    }

    @Test
    fun formattedLineCarriesLevelAndTag() {
        val line = WandLog.formatEntry(entry("GET /api/config → 200", WandLog.Level.WARN))

        assertTrue(line.endsWith("W/api          GET /api/config → 200"))
        assertTrue(line.contains(":"))
    }

    @Test
    fun redactHidesCredentialsButKeepsSessionIds() {
        val raw = """
            POST /api/login appToken=AbCd1234EfGh5678
            Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload
            {"password":"s3cret","cookie":"wand_session=xyz"}
        """.trimIndent()

        val redacted = WandLog.redact(raw)

        assertFalse(redacted.contains("AbCd1234EfGh5678"))
        assertFalse(redacted.contains("eyJhbGciOiJIUzI1NiJ9.payload"))
        assertFalse(redacted.contains("s3cret"))
        assertFalse(redacted.contains("wand_session=xyz"))
        assertTrue(redacted.contains("appToken=***"))
        assertTrue(redacted.contains("Authorization: ***"))
        assertTrue(redacted.contains("\"password\":***"))
        // 会话 id 是排查故障的锚点，关联到同一个请求的 session 字段不能被误伤。
        assertTrue(WandLog.redact("session=9f2c-abc msgs=12").contains("session=9f2c-abc"))
    }

    @Test
    fun redactHidesStandaloneBearerCredentials() {
        val redacted = WandLog.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload")

        assertFalse(redacted.contains("eyJhbGciOiJIUzI1NiJ9.payload"))
    }

    @Test
    fun redactKeepsSessionIdentifierVisible() {
        val raw = "REST 快照 session=9f2c-abc msgs=12 status=running"

        assertEquals(raw, WandLog.redact(raw))
    }
}
