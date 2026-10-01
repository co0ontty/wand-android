package com.wand.app.ui.screens

import com.wand.app.data.ConversationTurn
import com.wand.app.data.TeamReportFile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TeamReportFileTest {
    @Test
    fun parsesReportCardWithoutEmbeddingBodyAndIncludesFileInIdentity() {
        val turn = ConversationTurn.parse(JSONObject("""{
            "role":"assistant","content":[{"type":"text","text":"✅ 完成「报告」"}],
            "reportFile":{"stepId":"step-1","path":"/tmp/中文 & 报告.md","name":"中文 & 报告.md","size":2048}
        }"""))
        assertEquals(TeamReportFile("step-1", "/tmp/中文 & 报告.md", "中文 & 报告.md", 2048), turn.reportFile)
        assertEquals("✅ 完成「报告」", chatTurnText(turn))
        assertNotEquals(teamTurnFingerprint(turn), teamTurnFingerprint(turn.copy(
            reportFile = turn.reportFile!!.copy(stepId = "step-2"))))
        assertNotEquals(teamTurnFingerprint(turn), teamTurnFingerprint(turn.copy(
            reportFile = turn.reportFile!!.copy(size = 4096))))
    }

    @Test
    fun legacyAndMalformedMetadataRemainReadableWithoutFileCards() {
        assertNull(ConversationTurn.parse(JSONObject("""{"role":"assistant","content":[]}""")).reportFile)
        for (input in listOf("{}", """{"path":"/tmp/a.md","name":"a.md","size":1}""",
            """{"stepId":"s","path":"/tmp/a.md","name":"a.md","size":-1}""")) {
            assertNull(TeamReportFile.parse(JSONObject(input)))
        }
        assertEquals(0L, TeamReportFile.parse(JSONObject("""{"stepId":"s","path":"/tmp/a.md","name":"a.md","size":0}"""))!!.size)
    }

    @Test
    fun fileSizeUsesBytesAndSharedUnits() {
        assertEquals("0 B", teamReportFileSize(0))
        assertEquals("1023 B", teamReportFileSize(1023))
        assertEquals("1.0 KB", teamReportFileSize(1024))
        assertEquals("1.0 MB", teamReportFileSize(1024 * 1024))
    }
}
