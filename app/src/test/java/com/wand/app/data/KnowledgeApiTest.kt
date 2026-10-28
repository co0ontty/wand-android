package com.wand.app.data

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KnowledgeApiTest {
    private fun document(owner: String = "employee/a", revision: Int = 1) = JSONObject()
        .put("id", "doc/a").put("scope", "personal").put("ownerId", owner)
        .put("title", "标题").put("content", "# 正文\n内容").put("folder", "约定")
        .put("revision", revision).put("createdAt", "2026-10-27T00:00:00Z").put("updatedAt", "2026-10-27T00:00:00Z")

    @Test fun requestsCarryExplicitOwnerAndRevisionAndReadCanonicalResponses() = runBlocking {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val requests = LinkedBlockingQueue<Pair<String, String>>()
        val responses = listOf(
            JSONObject().put("employees", org.json.JSONArray().put(JSONObject().put("id", "employee/a").put("name", "员工")))
                .put("projects", org.json.JSONArray()).toString(),
            JSONObject().put("scope", "personal").put("ownerId", "employee/a").put("ownerName", "员工")
                .put("readOnly", false).put("total",1).put("limit",200).put("entries",org.json.JSONArray().put(document())).toString(),
            JSONObject().put("entry", document()).put("created", true).toString(),
            JSONObject().put("entry", document(revision = 2)).toString(),
            "{\"ok\":true}",
        )
        val worker = thread(isDaemon = true) {
            responses.forEach { response ->
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream()
                    fun line(): String = buildString {
                        while (true) { val ch = input.read(); if (ch < 0 || ch == 10) break; if (ch != 13) append(ch.toChar()) }
                    }
                    val first = line()
                    val headers = generateSequence { line().takeIf { it.isNotEmpty() } }.toList()
                    val length = headers.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(":")?.trim()?.toInt() ?: 0
                    requests.put(first to String(input.readNBytes(length), Charsets.UTF_8))
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.toByteArray().size}\r\nConnection: close\r\n\r\n$response").toByteArray())
                }
            }
        }
        try {
            val api = WandApi("http://127.0.0.1:${server.localPort}", null)
            assertEquals("employee/a", api.knowledgeOwners().employees.single().id)
            val library = api.knowledgeLibrary(KnowledgeScope.Personal, "employee/a")
            assertEquals(200, library.limit)
            val draft = KnowledgeDraft("标题", "# 正文\n内容", "约定")
            val created = api.createKnowledge(KnowledgeScope.Personal, "employee/a", draft)
            val saved = api.updateKnowledge(created, draft)
            api.deleteKnowledge(saved)
            val captured = (0..4).map { requests.poll(2, TimeUnit.SECONDS)!! }
            assertEquals("GET /api/knowledge/owners HTTP/1.1", captured[0].first)
            assertEquals("GET /api/knowledge?scope=personal&ownerId=employee%2Fa HTTP/1.1", captured[1].first)
            assertEquals("POST /api/knowledge HTTP/1.1", captured[2].first)
            assertEquals("PATCH /api/knowledge/doc%2Fa HTTP/1.1", captured[3].first)
            assertEquals("DELETE /api/knowledge/doc%2Fa HTTP/1.1", captured[4].first)
            for (record in captured.drop(2)) {
                val body = JSONObject(record.second)
                assertEquals("personal", body.getString("scope")); assertEquals("employee/a", body.getString("ownerId"))
            }
            assertFalse(JSONObject(captured[2].second).has("revision"))
            assertEquals(1, JSONObject(captured[3].second).getInt("revision"))
            assertEquals(2, JSONObject(captured[4].second).getInt("revision"))
            assertEquals(draft.content, JSONObject(captured[2].second).getString("content"))
        } finally { server.close(); worker.join(2000) }
    }

    @Test fun mixedOwnerPayloadIsRejected() {
        val json = JSONObject().put("scope", "personal").put("ownerId", "a").put("ownerName", "甲")
            .put("total",1).put("limit",200).put("entries", org.json.JSONArray().put(document("b")))
        assertTrue(runCatching { KnowledgeLibrary.parse(json) }.isFailure)
    }

    @Test fun invalidScopeAndVersionAreRejected() {
        assertTrue(runCatching { KnowledgeDocument.parse(document().put("scope", "global")) }.isFailure)
        assertTrue(runCatching { KnowledgeDocument.parse(document(revision = 0)) }.isFailure)
    }
}
