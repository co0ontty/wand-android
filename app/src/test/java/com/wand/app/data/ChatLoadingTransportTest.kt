package com.wand.app.data

import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatLoadingTransportTest {
    @Test
    fun firstPageUsesCompactByteBudgetAndKeepsHistoryCursor() = runBlocking {
        val requests = LinkedBlockingQueue<Pair<String, List<String>>>()
        val server = ServerSocket(0)
        val worker = thread(isDaemon = true) {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val line = reader.readLine()
                val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                requests.put(line to headers)
                val text = """{"id":"session","sessionKind":"structured","messages":[],"messageOffset":80,"messageTotal":100,"leadingBlockOffset":20}"""
                socket.getOutputStream().write(
                    ("HTTP/1.1 200 OK\r\nContent-Length: ${text.toByteArray().size}\r\nConnection: close\r\n\r\n$text").toByteArray(),
                )
            }
        }
        try {
            val api = WandApi("http://127.0.0.1:${server.localPort}", null)
            val page = api.getSession("session")
            assertEquals(80, page.messageOffset)
            assertEquals(100, page.messageTotal)
            assertEquals(20, page.leadingBlockOffset)
            val request = requests.poll(2, TimeUnit.SECONDS)!!
            assertEquals("GET /api/sessions/session?format=chat&output=omit&blockBudget=12&byteBudget=98304 HTTP/1.1", request.first)
            assertTrue(request.second.any { it.equals("X-Wand-Tool-Projection: compact", ignoreCase = true) })
        } finally { server.close(); worker.join(2_000) }
    }

    @Test
    fun framesAreFullyDecodedBeforeMainThreadDelivery() {
        val init = WandSocket.decodeFrame(
            """{"type":"init","sessionId":"s","seq":1,"data":{"id":"s","sessionKind":"structured","status":"idle","messages":[{"role":"assistant","content":[{"type":"text","text":"ready"}]}]}}""",
        )!!
        assertEquals("s", init.incoming.sessionId)
        assertTrue(init.event is SessionEvent.Initialized)
        val output = WandSocket.decodeFrame("""{"type":"output","sessionId":"s","seq":2,"data":{"isResponding":true}}""")!!
        assertEquals(2, output.incoming.seq)
        assertTrue(output.event is SessionEvent.Output)
        val catalog = WandSocket.decodeFrame("""{"type":"notification","data":{"kind":"models"}}""")!!
        assertTrue(catalog.modelsChanged)
        val employee = WandSocket.decodeFrame("""{"type":"notification","data":{"kind":"silicon-employee-definition","employeeId":"e1"}}""")!!
        assertEquals("e1", employee.employeeId)
        assertNull(WandSocket.decodeFrame("invalid JSON"))
    }
}
