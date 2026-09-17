package com.wand.app.data

import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskHierarchyTest {
    @Test
    fun taskChangesExcludeReadsAndLayoutWrites() {
        assertFalse(changesTaskHierarchy("GET", "/api/tasks"))
        assertFalse(changesTaskHierarchy("PUT", "/api/workspace-tasks/a/layout"))
        assertTrue(changesTaskHierarchy("POST", "/api/workspace-tasks/a/sessions"))
        assertTrue(changesTaskHierarchy("PATCH", "/api/wand-tasks/a"))
        assertTrue(changesTaskHierarchy("POST", "/api/structured-sessions"))
        assertFalse(changesTaskHierarchy("POST", "/api/login"))
    }

    @Test
    fun promptBelongsToSessionAndUsesTheCorrectRunnerField() {
        val binding = WorkspaceBinding("workspace", "task", "/repo")
        val structured = createWorkspaceTaskWindowRequest(WorkspaceSessionTarget.Claude, binding,
            WorkspaceSessionKind.Structured, "  fix tests  ")
        assertEquals("fix tests", structured.body.getString("prompt"))
        assertFalse(structured.body.has("initialInput"))
        assertFalse(structured.body.has("name"))
        val pty = createWorkspaceTaskWindowRequest(WorkspaceSessionTarget.Claude, binding,
            WorkspaceSessionKind.Pty, "  fix tests  ")
        assertEquals("fix tests", pty.body.getString("initialInput"))
        assertFalse(pty.body.has("prompt"))
        val shell = createWorkspaceTaskWindowRequest(WorkspaceSessionTarget.Shell, binding,
            WorkspaceSessionKind.Pty, "do not execute in shell")
        assertFalse(shell.body.has("prompt"))
        assertFalse(shell.body.has("initialInput"))
        assertEquals("task", pty.body.getString("workspaceTaskId"))
    }

    @Test
    fun boardTaskParsesStableWorkspaceTaskIdentity() {
        val task = BoardTask.parse(JSONObject().put("id", "board-id").put("workspaceTaskId", "container-id"))
        assertEquals("container-id", task!!.workspaceTaskId)
        assertNull(BoardTask.parse(JSONObject().put("id", "legacy-id"))!!.workspaceTaskId)
    }

    @Test
    fun moveIsOnePostWithoutCwdAndPublishesOnlySuccessfulChanges() = runBlocking {
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val requests = LinkedBlockingQueue<Pair<String, JSONObject>>()
        val worker = thread(isDaemon = true) {
            repeat(2) { index -> server.accept().use { socket ->
                socket.soTimeout = 2_000
                val reader = socket.getInputStream().bufferedReader()
                val requestLine = reader.readLine()
                val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                val length = headers.first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                val chars = CharArray(length)
                var count = 0
                while (count < length) { val read = reader.read(chars, count, length - count); check(read > 0); count += read }
                requests.put(requestLine to JSONObject(String(chars)))
                val text = if (index == 0) "{}" else "{\"error\":\"not supported\"}"
                val code = if (index == 0) "200 OK" else "404 Not Found"
                socket.getOutputStream().write(("HTTP/1.1 $code\r\nContent-Length: ${text.length}\r\nConnection: close\r\n\r\n$text").toByteArray())
            } }
        }
        try {
            val api = WandApi("http://127.0.0.1:${server.localPort}", null)
            val event = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(2_000) { api.taskChanges.first() } }
            api.moveWorkspaceSession("target", "session")
            event.await()
            val (requestLine, body) = requests.poll(2, TimeUnit.SECONDS)!!
            assertEquals("POST /api/workspace-tasks/target/sessions HTTP/1.1", requestLine)
            assertEquals(setOf("sessionId"), body.keys().asSequence().toSet())
            assertEquals("session", body.getString("sessionId"))
            val noEvent = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(200) { api.taskChanges.first() } }
            try {
                api.moveWorkspaceSession("target", "session")
                fail("A missing endpoint must not pretend the session was moved")
            } catch (error: WandApiException) { assertEquals(404, error.status) }
            assertNull(noEvent.await())
        } finally { server.close(); worker.join(2_000) }
    }
}
