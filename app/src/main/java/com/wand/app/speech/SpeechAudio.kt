package com.wand.app.speech

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The shared server contract: exactly 44-byte header + 16 kHz mono little-endian PCM16. */
object SpeechAudio {
    const val SAMPLE_RATE = 16_000
    const val MAX_SECONDS = 60
    const val MAX_PCM_BYTES = SAMPLE_RATE * 2 * MAX_SECONDS
    fun wav(pcm: ByteArray): ByteArray {
        require(pcm.size in 3_200..MAX_PCM_BYTES && pcm.size % 2 == 0) { "录音需要 0.1–60 秒" }
        val buffer = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + pcm.size)
        buffer.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        buffer.putShort(1).putShort(1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII)).putInt(pcm.size).put(pcm)
        return buffer.array()
    }
}
