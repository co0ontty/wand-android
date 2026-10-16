package com.wand.app.data

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal class FeedbackTestServer(
    private val redirectTo: String? = null,
    private val respond: (String) -> Pair<Int, String>,
) : Closeable {
    private val server = ServerSocket(0)
    val baseUrl = "http://127.0.0.1:${server.localPort}"
    val requests = LinkedBlockingQueue<Pair<String, String>>()
    private val worker = thread(isDaemon = true) {
        while (!server.isClosed) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 4000
                    val input = socket.getInputStream()
                    val headers = StringBuilder()
                    while (!headers.endsWith("\r\n\r\n")) {
                        val byte = input.read(); check(byte >= 0); headers.append(byte.toChar())
                    }
                    val size = Regex("(?im)^Content-Length: (\\d+)").find(headers)?.groupValues?.get(1)?.toInt() ?: 0
                    val body = input.readNBytes(size).toString(Charsets.UTF_8)
                    requests.put(headers.toString() to body)
                    val (status, response) = respond(headers.toString())
                    val bytes = response.toByteArray()
                    val redirectHeader = redirectTo?.let { "Location: $it\r\n" }.orEmpty()
                    socket.getOutputStream().write(("HTTP/1.1 $status Test\r\n${redirectHeader}Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
                }
            } catch (_: Exception) { if (server.isClosed) break }
        }
    }
    fun next(): Pair<String, String> = requests.poll(4, TimeUnit.SECONDS) ?: error("No request")
    override fun close() { server.close(); worker.join(1000) }
}

class ClientFeedbackTest {
    @Test fun `diagnostics only permit HTTPS or literal loopback and reject embedded credentials`() {
        listOf("https://wand.example", "http://localhost:8443", "http://127.0.0.2", "http://[::1]")
            .forEach { assertTrue(it, ClientFeedback.allowsDiagnostics(it)) }
        listOf("http://192.168.1.1", "http://example.com", "http://localhost.evil", "https://user:pass@wand.example", "invalid")
            .forEach { assertFalse(it, ClientFeedback.allowsDiagnostics(it)) }
    }

    @Test fun `raw errors preserved except known secrets and credential patterns before byte limits`() {
        val text = "磁盘失败 /tmp/a\nraw-token-value\ntoken=abc\npassword: 'value with space'\napi_key=key\nAuthorization: Bearer a\nCookie: x=y\nhttps://u:p@host/path"
        val redacted = ClientFeedback.redact(text, listOf("raw-token-value"))
        listOf("raw-token-value", "abc", "value with space", "=key", "x=y", "u:p").forEach { assertFalse(redacted.contains(it)) }
        assertTrue(redacted.startsWith("磁盘失败 /tmp/a\n[REDACTED]"))
        assertEquals("磁盘", ClientFeedback.utf8Prefix("磁盘🙂失败", 7))
        val credentials = ClientFeedback.redact("client_secret=hide app-secret='hide too'\n-----BEGIN PRIVATE KEY-----\nprivate material\n-----END PRIVATE KEY-----", emptyList())
        assertFalse(credentials.contains("hide")); assertFalse(credentials.contains("private material"))
        assertTrue(ClientFeedback.utf8Prefix("中🙂".repeat(4000), 8192).toByteArray().size <= 8192)
        assertEquals("原始错误\n  at app.File:12", ClientFeedback.redact("原始错误\n  at app.File:12", emptyList()))
    }

    @Test fun `rate limit deduplicates five minutes bounds bursts and recovers`() {
        val gate = FeedbackRateLimit()
        assertTrue(gate.accept("same", 0)); assertFalse(gate.accept("same", 60_000))
        repeat(9) { assertTrue(gate.accept("event-$it", 0)) }
        assertFalse(gate.accept("overflow", 0))
        assertTrue(gate.accept("fresh", 60_000))
        assertTrue(gate.accept("same", 300_000))
    }

    @Test fun `all canonical API route families map stable features including nested paths and queries`() {
        // Explicit mirror of FEEDBACK_API_FEATURES in the server's src/feedback-feature-map.ts.
        val familiesByFeature = mapOf(
            "sessions" to listOf("sessions", "structured-sessions", "commands", "session-list", "session-directories"),
            "conversations" to listOf("conversations"),
            "tasks" to listOf("wand-tasks", "wand-task-agent-defaults", "tasks", "workspace-tasks", "workspaces", "wand-milestones", "missions", "scheduled-tasks"),
            "files" to listOf("files", "upload", "directory", "session-files", "file-preview", "file-read", "file-write", "recent-paths"),
            "speech" to listOf("speech"),
            "settings" to listOf("settings", "config", "user-profile", "auto-update", "update-channel"),
            "models" to listOf("models", "model-groups", "local-models"),
            "authentication" to listOf("login", "logout", "app-login", "session-check", "set-password"),
            "server_update" to listOf("update", "update-status", "check-update"),
            "app_update" to listOf("android-apk-update", "android-apk", "macos-app-update", "macos-dmg-update", "macos-dmg", "ios-ipa-update", "ios-ipa"),
            "provider_update" to listOf("provider-cli-updates"),
            "daemon_update" to listOf("daemon-maintenance"),
            "storage" to listOf("storage-cleanup"),
            "repository" to listOf("git", "github"),
        )
        familiesByFeature.forEach { (feature, families) ->
            families.forEach { family ->
                listOf("/api/$family", "/api/$family/id/detail", "/api/$family?filter=one", "/api/$family/id?filter=one")
                    .forEach { path -> assertEquals(path, feature, ClientFeedback.featureForPath(path)) }
            }
        }
    }

    @Test fun `unknown missing and near match route families remain api regardless of query`() {
        listOf("", "/", "/api", "/api/", "/api/new-route", "/api/app-update", "/api/workspaces-extra",
            "/api/WORKSPACES", "/api/unknown?feature=tasks", "/api/unknown/id?path=/api/workspaces")
            .forEach { path -> assertEquals(path, "api", ClientFeedback.featureForPath(path)) }
    }

    @Test fun `Throwable posts schema stack and existing auth without usernames and never follows redirect`() {
        FeedbackTestServer { 503 to "unavailable" }.use { destination ->
            FeedbackTestServer(redirectTo = destination.baseUrl) { 307 to "" }.use { server ->
                // A rejected report must not cause recursive diagnostics or any business retry.
                ClientFeedback(server.baseUrl, "secret-token").report("update_failed", "app_update",
                    IOException("原始失败 secret-token token=hidden"))
                val (headers, body) = server.next()
                assertTrue(headers.startsWith("POST /api/feedback/client "))
                assertTrue(headers.contains("Authorization: Bearer secret-token"))
                val json = JSONObject(body)
                assertEquals(1, json.getInt("schema")); assertEquals("android", json.getString("source"))
                assertEquals("app_update", json.getString("feature")); assertFalse(json.has("username"))
                assertTrue(json.getString("version").isNotBlank())
                val error = json.getJSONObject("error")
                assertEquals("IOException", error.getString("name"))
                assertTrue(error.getString("stack").contains("ClientFeedbackTest"))
                assertTrue(error.getString("message").startsWith("原始失败"))
                assertFalse(body.contains("secret-token")); assertFalse(body.contains("hidden"))
                assertNull(server.requests.poll(300, TimeUnit.MILLISECONDS))
                assertNull(destination.requests.poll(100, TimeUnit.MILLISECONDS))
            }
        }
    }

    @Test fun `API 5xx reports only explicit error not request or response body and does not replay`() = runBlocking {
        FeedbackTestServer { headers ->
            if (headers.startsWith("POST /api/feedback/client")) 503 to "feedback failed"
            else 503 to """{"error":"原始接口异常 token=hidden","chat":"PRIVATE RESPONSE"}"""
        }.use { server ->
            try { WandApi(server.baseUrl, null).listBoardTasks(); fail("Expected 503") }
            catch (error: WandApiException) { assertEquals(503, error.status) }
            assertTrue(server.next().first.startsWith("GET /api/wand-tasks "))
            val feedback = server.next()
            val json = JSONObject(feedback.second)
            assertEquals("http_5xx", json.getString("event")); assertEquals("tasks", json.getString("feature"))
            assertEquals("503", json.getJSONObject("error").getString("code"))
            assertEquals("原始接口异常 token=[REDACTED]", json.getJSONObject("error").getString("message"))
            assertFalse(feedback.second.contains("PRIVATE RESPONSE"))
            assertNull(server.requests.poll(300, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun `global transport permits one running and one queued report then drops`() {
        val release = java.util.concurrent.CountDownLatch(1)
        FeedbackTestServer { release.await(2, TimeUnit.SECONDS); 202 to "{}" }.use { server ->
            val reporter = ClientFeedback(server.baseUrl, null)
            reporter.report("update_failed", "app_update", IOException("bounded-one"))
            assertTrue(server.next().second.contains("bounded-one"))
            reporter.report("update_failed", "app_update", IOException("bounded-two"))
            reporter.report("update_failed", "app_update", IOException("bounded-three"))
            release.countDown()
            assertTrue(server.next().second.contains("bounded-two"))
            assertNull(server.requests.poll(300, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun `cancellations remote HTTP and feedback failures are excluded`() {
        assertTrue(ClientFeedback.isCancellation(java.util.concurrent.CancellationException()))
        assertTrue(ClientFeedback.isCancellation(IOException("Canceled")))
        assertTrue(ClientFeedback.isCancellation(RuntimeException(InterruptedException())))
        FeedbackTestServer { 202 to "{}" }.use { server ->
            val reporter = ClientFeedback(server.baseUrl, null)
            reporter.report("update_failed", "app_update", java.util.concurrent.CancellationException())
            reporter.httpFailure("/api/feedback/client", 503, "{}")
            reporter.httpFailure("/api/models", 404, "{}")
            assertNull(server.requests.poll(300, TimeUnit.MILLISECONDS))
        }
    }
}
