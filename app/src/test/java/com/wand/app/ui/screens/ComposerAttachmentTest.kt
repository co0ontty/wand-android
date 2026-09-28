package com.wand.app.ui.screens

import com.wand.app.data.WandApiException
import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ComposerAttachmentTest {
    @Test
    fun boundedReadPreservesOrdinaryAttachmentBytes() {
        val bytes = "file content".toByteArray()
        assertArrayEquals(bytes, readBoundedAttachment(ByteArrayInputStream(bytes), "file.txt"))
    }

    @Test
    fun oversizedAttachmentIsRejectedBeforeTheEntireFileIsBuffered() {
        val input = ByteArrayInputStream(ByteArray(12 * 1_024 * 1_024))
        val error = assertThrows(WandApiException::class.java) {
            readBoundedAttachment(input, "large.bin")
        }
        assertEquals(413, error.status)
        assertEquals(true, input.available() > 1_024 * 1_024)
    }
}
