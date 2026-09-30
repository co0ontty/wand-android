package com.wand.app.data

import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolProjectionApiTest {
    @Test
    fun messagesOptInButToolDetailReturnsFullInputAndResult() = runBlocking {
        val server = ServerSocket(0)
        val requests = LinkedBlockingQueue<Pair<String, List<String>>>()
        val worker = thread(isDaemon = true) {
            val responses = listOf(
                "[]",
                """{"tool_use_id":"tool-1","input":{"command":"pwd"},"content":"/tmp","is_error":false,"resultAvailable":true}""",
            )
            responses.forEach { text ->
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    val reader = socket.getInputStream().bufferedReader()
                    val requestLine = reader.readLine()
                    val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                    requests.put(requestLine to headers)
                    socket.getOutputStream().write(
                        ("HTTP/1.1 200 OK\r\nContent-Length: ${text.toByteArray().size}\r\n" +
                            "Connection: close\r\n\r\n$text").toByteArray(),
                    )
                }
            }
        }
        try {
            val api = WandApi("http://127.0.0.1:${server.localPort}", null)
            assertTrue(api.listSessions().isEmpty())
            val detail = api.fetchToolDetail("session-1", "tool-1")
            assertEquals("pwd", detail.input.getString("command"))
            assertEquals("/tmp", detail.result?.text)
            assertFalse(detail.result?.isError ?: true)

            val messageRequest = requests.poll(2, TimeUnit.SECONDS)!!
            val detailRequest = requests.poll(2, TimeUnit.SECONDS)!!
            assertEquals("GET /api/sessions HTTP/1.1", messageRequest.first)
            assertTrue(messageRequest.second.any {
                it.equals("X-Wand-Tool-Projection: compact", ignoreCase = true)
            })
            assertEquals("GET /api/sessions/session-1/tool-content/tool-1 HTTP/1.1", detailRequest.first)
            assertNull(detailRequest.second.firstOrNull {
                it.startsWith("X-Wand-Tool-Projection:", ignoreCase = true)
            })
        } finally {
            server.close()
            worker.join(2_000)
        }
    }
}
