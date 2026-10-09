package com.wand.app.data

import com.wand.app.speech.SpeechAudio
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ServerSpeechApiTest {
    @Test fun `server speech uses endpoint raw WAV and propagates errors without fallback`() = runBlocking {
        val server = ServerSocket(0)
        val requests = LinkedBlockingQueue<Pair<String, ByteArray>>()
        val worker = thread(isDaemon = true) {
            listOf(200 to """{"text":"测试转写 hello","model":"base","backend":"cpu"}""", 503 to """{"error":"模型未就绪"}""").forEach { (code, text) ->
                server.accept().use { socket ->
                    socket.soTimeout = 2000
                    val input = socket.getInputStream()
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) {
                        val byte = input.read(); check(byte >= 0); header.append(byte.toChar()); check(header.length < 8192)
                    }
                    val length = Regex("(?im)^Content-Length: (\\d+)").find(header)!!.groupValues[1].toInt()
                    val body = ByteArray(length)
                    var read = 0
                    while (read < length) { val count = input.read(body, read, length - read); check(count > 0); read += count }
                    requests.put(header.toString() to body)
                    val response = text.toByteArray()
                    socket.getOutputStream().write(("HTTP/1.1 $code OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n").toByteArray() + response)
                }
            }
        }
        try {
            val api = WandApi("http://127.0.0.1:${server.localPort}", null)
            val wav = SpeechAudio.wav(ByteArray(3200))
            assertEquals("测试转写 hello", api.transcribeSpeech(wav))
            val request = requests.poll(2, TimeUnit.SECONDS)!!
            assertTrue(request.first.startsWith("POST /api/speech/transcribe HTTP/1.1"))
            assertTrue(request.first.contains("Content-Type: audio/wav", ignoreCase = true)); assertArrayEquals(wav, request.second)
            try { api.transcribeSpeech(wav); fail("Expected rejection") }
            catch (error: WandApiException) { assertEquals(503, error.status); assertEquals("模型未就绪", error.message) }
        } finally { server.close(); worker.join(2000) }
    }
}
