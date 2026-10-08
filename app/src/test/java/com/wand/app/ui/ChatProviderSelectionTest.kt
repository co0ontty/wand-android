package com.wand.app.ui

import com.wand.app.data.SessionSnapshot
import com.wand.app.data.WandApi
import com.wand.app.data.WandAgentEngine
import com.wand.app.data.TurnAuthor
import com.wand.app.data.agentToolOption
import com.wand.app.ui.screens.agentSignatureLabel
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatProviderSelectionTest {
    private fun blank() = SessionSnapshot.parse(JSONObject().put("id", "session-1")
        .put("employeeId", "employee-1").put("provider", "pi").put("sessionKind", "structured")
        .put("runner", "pi-cli-json").put("status", "idle"))

    @Test
    fun onlyAnUnstartedBlankStructuredConversationCanSwitchTools() {
        assertTrue(canSwitchBlankConversationProvider(blank(), 0))
        assertFalse(canSwitchBlankConversationProvider(null, 0))
        assertFalse(canSwitchBlankConversationProvider(blank(), 1))
        assertFalse(canSwitchBlankConversationProvider(blank().copy(messageTotal = 3), 0))
        assertFalse(canSwitchBlankConversationProvider(blank().copy(sessionKind = "pty", runner = "pty"), 0))
        assertFalse(canSwitchBlankConversationProvider(blank().copy(status = "running"), 0))
        assertFalse(canSwitchBlankConversationProvider(blank().copy(archived = true), 0))
        assertFalse(canSwitchBlankConversationProvider(blank().copy(claudeSessionId = "native-history"), 0))
        assertFalse(canSwitchBlankConversationProvider(blank().copy(queuedMessages = listOf("已接受")), 0))
    }

    @Test
    fun providerApiUpdatesSameSessionAndDoesNotCreateAnotherOrTransmitDraft() = runBlocking {
        val server = ServerSocket(0)
        val requests = LinkedBlockingQueue<Pair<String, JSONObject>>()
        val worker = thread(isDaemon = true) {
            repeat(2) { server.accept().use { socket ->
                socket.soTimeout = 5_000
                val reader = socket.getInputStream().bufferedReader()
                val requestLine = reader.readLine()
                val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                val length = headers.first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                val body = CharArray(length)
                var received = 0
                while (received < length) received += reader.read(body, received, length - received)
                requests.put(requestLine to JSONObject(String(body)))
                val response = if (it == 0) """{"id":"session-1","employeeId":"employee-1","provider":"codex","sessionKind":"structured","runner":"codex-cli-exec","status":"idle"}"""
                    else """{"id":"session-1","employeeId":"employee-1","provider":"pi","sessionKind":"structured","runner":"pi-cli-json","status":"idle","structuredState":{"engine":"core"}}"""
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${response.toByteArray().size}\r\n" +
                    "Connection: close\r\n\r\n$response").toByteArray())
            } }
        }
        try {
            val api = WandApi("http://127.0.0.1:${server.localPort}", null)
            val updated = api.setProvider("session-1", "codex")
            assertEquals("session-1", updated.id)
            assertEquals("employee-1", updated.employeeId)
            assertEquals("codex", updated.provider)
            val request = requests.poll(5, TimeUnit.SECONDS)!!
            assertEquals("POST /api/sessions/session-1/provider HTTP/1.1", request.first)
            assertEquals(setOf("provider"), request.second.keys().asSequence().toSet())
            assertEquals("codex", request.second.getString("provider"))
            val sdk = api.setProvider("session-1", "pi", WandAgentEngine.Sdk)
            assertEquals("wand-agent", sdk.toolId)
            assertEquals("Wand Agent", sdk.providerLabel)
            val sdkRequest = requests.poll(5, TimeUnit.SECONDS)!!
            assertEquals(setOf("provider", "engine"), sdkRequest.second.keys().asSequence().toSet())
            assertEquals("pi", sdkRequest.second.getString("provider"))
            assertEquals("sdk", sdkRequest.second.getString("engine"))
        } finally {
            server.close()
            worker.join(5_000)
        }
    }

    @Test
    fun engineLabelsRemainExactForSessionsAuthorsAndEmployeeCandidates() {
        assertEquals("pi", blank().toolId)
        assertEquals("Pi", blank().providerLabel)
        assertEquals("Wand Agent", agentToolOption("wand-agent")!!.label)
        val author = TurnAuthor.parse(JSONObject().put("name", "成员").put("provider", "pi").put("engine", "sdk"))!!
        assertEquals("sdk", author.engine)
        assertEquals("Wand Agent", agentSignatureLabel(author.provider, null, null, engine = author.engine))
        assertEquals("Pi", agentSignatureLabel("pi", null, null))
        for (source in listOf("SiliconEmployeesScreen.kt", "AiTeamDetailScreen.kt")) {
            assertTrue(File("src/main/java/com/wand/app/ui/screens/$source").readText()
                .contains("boardTaskAgentLabel(agent.provider, agent.engine)"))
        }
    }

    @Test
    fun logoOwnsAnchoredMenuAndFeedbackWithoutReplacingComposerOrUsingToast() {
        val screen = File("src/main/java/com/wand/app/ui/screens/ChatScreen.kt").readText()
        val picker = screen.substringAfter("private fun LaunchProviderPicker(").substringBefore("/**\n * Provider 品牌标")
        assertTrue(picker.contains("store.chooseProvider(tool.id)"))
        assertTrue(picker.contains("AGENT_TOOL_OPTIONS"))
        assertTrue(picker.contains("store.snapshot?.toolId == tool.id"))
        assertTrue(picker.contains("DropdownMenu("))
        assertTrue(picker.contains("WandInPlaceSwap("))
        assertTrue(picker.contains("BackHandler(enabled = menuOpen)"))
        assertTrue(picker.contains("WandStatusIconSlot("))
        assertTrue(picker.contains("height(38.dp)"))
        assertFalse(picker.contains("Toast"))
        assertFalse(picker.contains("WandBottomSheet"))
        assertTrue(screen.contains("ready = { !store.loading && !store.providerSwitching"))
        val store = File("src/main/java/com/wand/app/ui/ChatStore.kt").readText()
        val choose = store.substringAfter("fun chooseProvider(").substringBefore("fun setModel(")
        assertTrue(choose.contains("api.setProvider(sessionId, tool.provider, tool.engine)"))
        assertTrue(choose.contains("snap.toolId == toolId"))
        assertTrue(choose.contains("if (active)"))
        assertTrue(choose.contains("settingsMutationMutex.withLock"))
        assertTrue(choose.contains("apply(snap)"))
        assertTrue(choose.contains("loadModels()"))
        assertFalse(choose.contains("drafts"))
        assertFalse(choose.contains("toast ="))
        assertTrue(store.contains("check(!providerSwitching)"))
    }
}
