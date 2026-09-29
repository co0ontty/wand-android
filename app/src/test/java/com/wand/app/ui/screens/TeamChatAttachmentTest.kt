package com.wand.app.ui.screens

import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import com.wand.app.data.UploadedFile
import com.wand.app.ui.parseUserAttachmentText
import com.wand.app.ui.WandServerFileLink
import com.wand.app.ui.WandTextPreview
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamChatAttachmentTest {
    private fun turn(text: String, at: String) = ConversationTurn(
        role = "user",
        content = listOf(ContentBlock.Text(text, null)),
        createdAt = at,
    )

    @Test
    fun attachmentOnlyAndMixedMessagesRoundTripThroughGroupPrompt() {
        val files = listOf(
            UploadedFile("photo.jpg", "/work/.wand-uploads/photo.jpg", 123, "image/jpeg"),
            UploadedFile("report.pdf", "/work/.wand-uploads/report.pdf", 456, "application/pdf"),
        )
        val paths = files.map { it.savedPath }
        val onlyAttachments = parseUserAttachmentText(buildAttachmentPrompt(files, "").trim())
        assertEquals(paths, onlyAttachments.paths)
        assertEquals("", onlyAttachments.body)

        val withBody = parseUserAttachmentText(buildAttachmentPrompt(files, "请看附件").trim())
        assertEquals(paths, withBody.paths)
        assertEquals("请看附件", withBody.body)
        assertEquals(emptyList<String>(), parseUserAttachmentText("普通消息").paths)
    }

    @Test
    fun unknownDeliveryStaysUnconfirmedEvenWhenAnotherClientSendsIdenticalText() {
        val prompt = "[附件已上传，请查看以下文件:\n/work/.wand-uploads/photo.jpg\n]"
        val local = listOf(LocalChatTurn(prompt, sentAtMillis = 1_000L, unconfirmed = true))
        val turns = listOf(
            turn(prompt, "1970-01-01T00:00:03.000Z"),
        )
        assertEquals(local, settleLocalTurns(local, turns))
    }

    @Test
    fun onlyAUniqueNewAckFingerprintCanSettleOneAttachmentSend() {
        val prompt = "[附件已上传，请查看以下文件:\n/work/.wand-uploads/photo.jpg\n]"
        val old = turn(prompt, "1970-01-01T00:00:01.000Z")
        val own = turn(prompt, "1970-01-01T00:00:03.000Z")
        val known = listOfNotNull(teamTurnFingerprint(old))
        val ack = acknowledgedTeamChatFingerprint(listOf(old, own), prompt, known)
        assertEquals(teamTurnFingerprint(own), ack)
        val local = listOf(
            LocalChatTurn(prompt, 1_500L, accepted = true,
                knownFingerprints = known, ackFingerprint = ack),
            LocalChatTurn(prompt, 1_600L, accepted = true,
                knownFingerprints = known, ackFingerprint = ack),
        )
        assertEquals(listOf(local[1]), settleLocalTurns(local, listOf(old, own)))
        val other = turn(prompt, "1970-01-01T00:00:02.000Z")
        assertEquals(null, acknowledgedTeamChatFingerprint(listOf(old, other, own), prompt, known))
        assertEquals(null, acknowledgedTeamChatFingerprint(listOf(other, own), prompt, known))
    }

    @Test
    fun chronologicalTimeMarkerUsesVisibleGapsAndLocalDate() {
        val first = turn("第一条", "2026-09-29T12:00:00Z")
        val soon = turn("第二条", "2026-09-29T12:10:00Z")
        val later = turn("第三条", "2026-09-29T12:40:00Z")
        assertTrue(teamChatShowsTime(null, first))
        assertFalse(teamChatShowsTime(first, soon))
        assertTrue(teamChatShowsTime(soon, later))
        assertEquals(
            "今天 20:00",
            teamChatTimeLabel(
                first,
                nowMillis = Instant.parse("2026-09-29T13:00:00Z").toEpochMilli(),
                zone = ZoneId.of("Asia/Shanghai"),
            ),
        )
    }

    @Test
    fun uploadedServerPathsUseTheExistingTextPreviewOrDownloadRoute() {
        val text = "/work/.wand-uploads/notes.md"
        val binary = "/work/.wand-uploads/report.pdf"
        assertEquals(text, WandServerFileLink.serverPath(text))
        assertEquals(binary, WandServerFileLink.serverPath(binary))
        assertTrue(WandTextPreview.isPreviewableText(text))
        assertFalse(WandTextPreview.isPreviewableText(binary))
    }
}
