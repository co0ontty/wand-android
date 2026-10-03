package com.wand.app.ui

import com.wand.app.data.SessionSnapshot
import com.wand.app.data.WandApi
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StructuredInputAckTest {
    @Test
    fun queueAckClearsComposerWhileOriginalTurnRemainsRunning() = checkAck(queued = true)

    @Test
    fun turnFinishingBeforeSubmissionStillAcknowledgesWithoutWaitingForTheNextReply() = checkAck(queued = false)

    private fun checkAck(queued: Boolean) = runTest {
        val server = ServerSocket(0)
        val request = CompletableFuture<JSONObject>()
        val worker = thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    check(reader.readLine() == "POST /api/sessions/chat-a/input HTTP/1.1")
                    val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                    val length = headers.first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                    val body = CharArray(length)
                    var received = 0
                    while (received < length) {
                        val count = reader.read(body, received, length - received)
                        check(count > 0)
                        received += count
                    }
                    val json = JSONObject(String(body))
                    request.complete(json)
                    val response = JSONObject().put("id", "chat-a").put("sessionKind", "structured")
                        .put("provider", "pi").put("runner", "pi-cli-json").put("status", "running")
                        .put("structuredState", JSONObject().put("inFlight", true))
                        .put("queuedMessages", JSONArray(if (queued) listOf("second") else emptyList<String>()))
                        .toString()
                    socket.getOutputStream().write(("HTTP/1.1 202 Accepted\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${response.toByteArray().size}\r\nConnection: close\r\n\r\n$response").toByteArray())
                }
            } catch (error: Throwable) {
                request.completeExceptionally(error)
            }
        }
        val drafts = SessionDraftStore(mapOf("chat-a" to "second"))
        val api = WandApi("http://127.0.0.1:${server.localPort}", null)
        var accepted: SessionSnapshot? = null
        val composer = ChatComposer("chat-a", drafts, backgroundScope, { true }, {
            accepted = api.sendStructuredInput("chat-a", it)
        }, { fail(it) })
        try {
            assertTrue(composer.submit())
            assertFalse(composer.submit())
            // Real I/O runs outside the virtual scheduler; wait on the scheduler until the ack returns.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (composer.sendPhase == SendPhase.Sending && System.nanoTime() < deadline) {
                testScheduler.runCurrent()
                withContext(Dispatchers.IO) { Thread.sleep(10) }
            }
            val body = request.get(5, TimeUnit.SECONDS)
            assertTrue(body.getBoolean("respondImmediately"))
            assertFalse(body.has("interrupt"))
            assertEquals("second", body.getString("input"))
            // Virtual time may already advance past the success icon's dwell while I/O resumes.
            assertTrue(composer.sendPhase == SendPhase.Sent || composer.sendPhase == SendPhase.Idle)
            assertEquals("", composer.draft)
            assertTrue(drafts.savedDrafts().isEmpty())
            assertTrue(accepted!!.isResponding)
            assertEquals(if (queued) listOf("second") else emptyList<String>(), accepted!!.queuedMessages)
        } finally {
            composer.shutdown()
            server.close()
            worker.join(5_000)
        }
    }
}
