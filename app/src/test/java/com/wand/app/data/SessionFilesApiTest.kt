package com.wand.app.data

import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionFilesApiTest {
    private class HttpFixture(private val responses: List<Pair<Int, String>>) : AutoCloseable {
        private val server = ServerSocket(0)
        val baseUrl = "http://127.0.0.1:${server.localPort}"
        val requests = LinkedBlockingQueue<Pair<String, String>>()
        private val worker = thread(isDaemon = true) {
            responses.forEach { (code, text) ->
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    val input = socket.getInputStream()
                    val headerBytes = java.io.ByteArrayOutputStream()
                    while (!headerBytes.toString("UTF-8").endsWith("\r\n\r\n")) {
                        val byte = input.read()
                        check(byte >= 0) { "Incomplete HTTP fixture request" }
                        headerBytes.write(byte)
                    }
                    val headers = headerBytes.toString("UTF-8").split("\r\n")
                    val size = headers.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim()?.toInt() ?: 0
                    requests.put(headers.first() to String(input.readNBytes(size), Charsets.UTF_8))
                    socket.getOutputStream().write(("HTTP/1.1 $code Response\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${text.toByteArray().size}\r\nConnection: close\r\n\r\n$text").toByteArray())
                }
            }
        }
        fun next() = requests.poll(2, TimeUnit.SECONDS)!!
        override fun close() { server.close(); worker.join(2_000) }
    }

    @Test fun previewUsesEncodedServerPathAndSaveIncludesExactConcurrencyMetadata() = runBlocking {
        val path = "/repo/笔记 + #?.md"
        val previewJson = JSONObject().put("path", path).put("name", "笔记 + #?.md").put("kind", "text")
            .put("size", 3).put("mtime", "2026-10-08T01:02:03.004Z").put("content", "old").toString()
        HttpFixture(listOf(200 to previewJson, 200 to """{"ok":true,"size":4,"mtime":"new"}""")).use { fixture ->
            val api = WandApi(fixture.baseUrl, null)
            val file = api.previewFile(path)
            assertTrue(file.canEdit)
            assertEquals("old", file.content)
            val previewRequest = fixture.next().first
            assertTrue(previewRequest.startsWith("GET /api/file-preview?path="))
            assertTrue(previewRequest.contains("%2B"))
            assertTrue(previewRequest.contains("%23%3F.md"))
            assertFalse(previewRequest.contains("笔记"))
            val saved = api.writeFile(file, "new\n")
            assertEquals(4L, saved.size)
            val request = fixture.next()
            assertEquals("POST /api/file-write HTTP/1.1", request.first)
            val body = JSONObject(request.second)
            assertEquals(path, body.getString("path"))
            assertEquals("new\n", body.getString("content"))
            assertEquals(file.mtime, body.getString("expectedMtime"))
            assertEquals(3L, body.getLong("expectedSize"))
        }
    }

    @Test fun saveConflictAndPermissionRejectionRemainStructuredHttpErrors() = runBlocking {
        for (status in listOf(409, 403)) {
            HttpFixture(listOf(status to """{"error":"文件已变化"}""")).use { fixture ->
                val api = WandApi(fixture.baseUrl, null)
                try {
                    api.writeFile(ServerFilePreview("/a", "a", "text", 3, "mtime", "old"), "new")
                    fail("must reject")
                } catch (failure: WandApiException) {
                    assertEquals(status, failure.status)
                    assertEquals("文件已变化", failure.message)
                }
            }
        }
    }

    @Test fun missingAcknowledgementIsNotReportedAsASuccessfulSave() = runBlocking {
        HttpFixture(listOf(200 to """{"ok":false}""")).use { fixture ->
            try {
                WandApi(fixture.baseUrl, null).writeFile(ServerFilePreview("/a", "a", "text", 0, "mtime", ""), "x")
                fail("must reject missing acknowledgement")
            } catch (failure: WandApiException) {
                assertNull(failure.status)
            }
        }
    }

    @Test fun directoryRetainsSizesAndTruncationButOlderResponsesStillParse() {
        val listing = DirectoryListing.parse(JSONObject("""{"items":[{"path":"/a","name":"a","type":"file","size":0}],"truncated":true,"total":201}"""))
        assertEquals(0L, listing.items.single().size)
        assertEquals(201, listing.total)
        assertEquals(true, listing.truncated)
        val old = DirectoryListing.parse(JSONObject("""{"items":[{"path":"/a","name":"a","type":"file"}]}"""))
        assertNull(old.total)
        assertNull(old.items.single().size)
    }

    @Test fun emptyTextIsEditableButMissingContentAndMetadataAreNot() {
        val json = JSONObject("""{"path":"/a","name":"a","kind":"text","size":0,"mtime":"now","content":""}""")
        assertTrue(ServerFilePreview.parse(json).canEdit)
        json.remove("content")
        assertFalse(ServerFilePreview.parse(json).canEdit)
        json.put("content", "").remove("mtime")
        assertFalse(ServerFilePreview.parse(json).canEdit)
    }
}
