package com.wand.app.ui

import com.wand.app.data.ChatSessionEventReducer
import com.wand.app.data.ChatSessionEventState
import com.wand.app.data.SessionSnapshot
import com.wand.app.data.WandApi
import com.wand.app.data.WsIncoming
import com.wand.app.data.toSessionEvent
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatDirectorySelectionTest {
    private fun blank(extra: JSONObject = JSONObject()) = SessionSnapshot.parse(extra.put("id", "session-1")
        .put("employeeId", "employee-1").put("provider", "pi").put("sessionKind", "structured")
        .put("runner", "pi-cli-json").put("status", "idle").put("cwd", "/server/original"))

    @Test fun onlyAnUnstartedStandaloneConversationCanChangeDirectory() {
        assertTrue(canChangeBlankConversationDirectory(blank(), 0))
        assertFalse(canChangeBlankConversationDirectory(blank(), 1))
        assertFalse(canChangeBlankConversationDirectory(blank().copy(workspaceTaskId = "task"), 0))
        assertFalse(canChangeBlankConversationDirectory(blank().copy(status = "running"), 0))
        for (extra in listOf(JSONObject().put("worktreeEnabled", true), JSONObject().put("worktree", JSONObject()),
            JSONObject().put("automationId", "ai-team:run"), JSONObject().put("resumedFromSessionId", "old"),
            JSONObject().put("autoRecovered", true), JSONObject().put("sessionSource", "startup"))) {
            assertFalse(canChangeBlankConversationDirectory(blank(extra), 0))
        }
    }

    @Test fun liveDirectoryChangeUpdatesCwdAndWorkspaceWithoutChangingIdentityOrModels() {
        val snapshot = blank().copy(workspaceId = "original", selectedModel = "chosen", thinkingEffort = "pi:high")
        val packet = WsIncoming.parse(JSONObject().put("type", "status").put("sessionId", "session-1")
            .put("data", JSONObject().put("cwd", "/server/unused").put("workspaceId", "new")))
        val next = ChatSessionEventReducer.reduce(ChatSessionEventState(snapshot = snapshot), packet.toSessionEvent()!!)
        assertEquals("/server/unused", next.snapshot!!.cwd)
        assertEquals("new", next.snapshot.workspaceId)
        assertEquals("employee-1", next.snapshot.employeeId)
        assertEquals("chosen", next.snapshot.selectedModel)
        assertEquals("pi:high", next.snapshot.thinkingEffort)
        assertEquals("session-1", next.snapshot.id)
    }

    @Test fun directoryApiOnlyUpdatesSameSessionAndNeverSendsDraft() = runBlocking {
        val server = ServerSocket(0)
        val requests = LinkedBlockingQueue<Pair<String, JSONObject>>()
        val worker = thread(isDaemon = true) {
            server.accept().use { socket ->
                socket.soTimeout = 5_000
                val reader = socket.getInputStream().bufferedReader()
                val first = reader.readLine()
                val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                val length = headers.first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                val body = CharArray(length)
                var received = 0
                while (received < length) received += reader.read(body, received, length - received)
                requests.put(first to JSONObject(String(body)))
                val response = """{"id":"session-1","employeeId":"employee-1","cwd":"/server/unused","workspaceId":"new","provider":"pi","sessionKind":"structured","status":"idle"}"""
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${response.toByteArray().size}\r\n" +
                    "Connection: close\r\n\r\n$response").toByteArray())
            }
        }
        try {
            val updated = WandApi("http://127.0.0.1:${server.localPort}", null).setSessionDirectory("session-1", "/server/unused")
            assertEquals("session-1", updated.id)
            assertEquals("employee-1", updated.employeeId)
            assertEquals("/server/unused", updated.cwd)
            val request = requests.poll(5, TimeUnit.SECONDS)!!
            assertEquals("POST /api/sessions/session-1/directory HTTP/1.1", request.first)
            assertEquals(setOf("cwd"), request.second.keys().asSequence().toSet())
        } finally { server.close(); worker.join(5_000) }
    }

    @Test fun directoryChangesUseStoreOwnershipAndBlockSubmitWithoutReplacingComposer() {
        val store = File("src/main/java/com/wand/app/ui/ChatStore.kt").readText()
        val mutation = store.substringAfter("fun chooseWorkingDirectory(").substringBefore("fun setModel(")
        assertTrue(mutation.contains("api.setSessionDirectory(sessionId, cwd)"))
        assertTrue(mutation.contains("settingsMutationMutex.withLock"))
        assertTrue(mutation.contains("if (active)"))
        assertFalse(mutation.contains("drafts"))
        assertTrue(store.contains("check(!directoryChanging)"))
        val screen = File("src/main/java/com/wand/app/ui/screens/ChatScreen.kt").readText()
        assertTrue(screen.contains("ready = { !store.loading && !store.providerSwitching && !store.directoryChanging"))
        assertTrue(screen.contains("store.chooseWorkingDirectory(it.cwd)"))
    }
}
