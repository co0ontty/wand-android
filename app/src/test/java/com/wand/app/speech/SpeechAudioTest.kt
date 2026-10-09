package com.wand.app.speech

import com.wand.app.data.ServerSpeechStatus
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpeechAudioTest {
    @Test fun `WAV matches server canonical audio contract`() {
        val pcm = ByteArray(3200) { (it % 127).toByte() }
        val wav = SpeechAudio.wav(pcm)
        val view = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(wav.size - 8, view.getInt(4))
        assertEquals("WAVEfmt ", String(wav, 8, 8, Charsets.US_ASCII))
        assertEquals(1, view.getShort(20).toInt()); assertEquals(1, view.getShort(22).toInt())
        assertEquals(16000, view.getInt(24)); assertEquals(32000, view.getInt(28))
        assertEquals(16, view.getShort(34).toInt()); assertEquals(pcm.size, view.getInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }
    @Test fun `WAV rejects empty too short odd and excessive recordings`() {
        for (size in listOf(0, 100, 3201, SpeechAudio.MAX_PCM_BYTES + 2)) {
            try { SpeechAudio.wav(ByteArray(size)); fail("Accepted $size") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun `server readiness DTO reports unavailable safely`() {
        val status = ServerSpeechStatus.parse(JSONObject("""{"ready":false,"reason":"模型未下载","settings":{"model":"base"},"runtime":{"backend":"cpu"}}"""))
        assertFalse(status.ready); assertEquals("模型未下载", status.reason); assertEquals("base", status.model); assertEquals("cpu", status.backend)
        assertFalse(ServerSpeechStatus.parse(JSONObject()).ready)
    }
}
