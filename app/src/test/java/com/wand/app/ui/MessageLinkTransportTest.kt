package com.wand.app.ui

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class MessageLinkTransportTest {
    private val base = "https://home.test/wand"
    private val safePolicy = "sandbox allow-scripts allow-forms; connect-src 'none'; form-action 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'"

    @Test fun previewScopeCannotReachAnotherArtifactApiOrEndpoint() {
        val scope = WandMessageLinkTransport.previewScope("$base/api/local-file/L3RtcC9kZW1v/index.html", base)!!
        assertTrue(WandMessageLinkTransport.isInPreviewScope("$base/api/local-file/L3RtcC9kZW1v/style.css?v=1", base, scope))
        for (url in listOf("$base/api/config", "$base/api/local-file/another/index.html", "$base/api/local-file/L3RtcC9kZW1v/%2e%2e/secret", "https://home.test:444/wand/api/local-file/L3RtcC9kZW1v/a", "https://home.test/else/api/local-file/L3RtcC9kZW1v/a")) {
            assertFalse(url, WandMessageLinkTransport.isInPreviewScope(url, base, scope))
        }
        val service = WandMessageLinkTransport.previewScope("$base/api/local-preview/127.0.0.1/5173/", base)!!
        assertTrue(WandMessageLinkTransport.isInPreviewScope("$base/api/local-preview/127.0.0.1/5173/app.js", base, service))
        assertFalse(WandMessageLinkTransport.isInPreviewScope("$base/api/local-preview/127.0.0.1/8181/api/config", base, service))
    }

    @Test fun missingOrPermissiveCspNeverEnablesHtmlExecution() {
        assertTrue(WandMessageLinkTransport.hasIsolatedHtmlPolicy(safePolicy))
        assertTrue(WandMessageLinkTransport.hasIsolatedHtmlPolicy(safePolicy.uppercase()))
        for (policy in listOf(null, "", "default-src 'self'", "sandbox allow-scripts", safePolicy.replace("allow-forms", "allow-same-origin"), safePolicy.replace("connect-src 'none'", "connect-src *"), safePolicy.replace("form-action 'none'", "form-action 'self'"), safePolicy.replace("allow-forms", "allow-popups-to-escape-sandbox"))) {
            assertFalse(policy.orEmpty(), WandMessageLinkTransport.hasIsolatedHtmlPolicy(policy))
        }
    }

    @Test fun sameEndpointFileRedirectsPreserveFragmentAndStopOnForeignTarget() {
        val requested = mutableListOf<String>()
        val client = client { request ->
            requested += request.url.toString()
            if (requested.size == 1) response(request, 307, "/wand/api/file-raw?path=%2Ftmp%2Fa%2520.txt") else response(request, 200)
        }
        WandMessageLinkTransport.request(base, "$base/api/session-file/source?path=a%2520.txt#details", client = client).use {
            assertEquals("/tmp/a%20.txt", it.request.url.queryParameter("path"))
            assertEquals("details", it.request.url.fragment)
            assertEquals(2, requested.size)
        }
        for (location in listOf("https://evil.test/file", "https://home.test:444/wand/api/file-raw?path=x", "/api/file-raw?path=x", "/wand/api/config")) {
            var calls = 0
            try {
                WandMessageLinkTransport.request(base, "$base/api/session-file/source?path=a.txt", client = client {
                    calls++
                    response(it, 307, location)
                })
                fail("Foreign redirect must fail")
            } catch (_: IOException) { assertEquals(1, calls) }
        }
    }

    @Test fun loopsAreBoundedAndForbiddenStartNeverMakesARequest() {
        var calls = 0
        try {
            WandMessageLinkTransport.request(base, "$base/api/file-raw?path=a", client = client {
                calls++
                response(it, 307, "/wand/api/file-raw?path=a")
            })
            fail("Expected redirect limit")
        } catch (_: IOException) { assertEquals(6, calls) }
        calls = 0
        try {
            WandMessageLinkTransport.request(base, "https://other.test/api/file-raw?path=a", client = client {
                calls++
                response(it, 200)
            })
            fail("Expected endpoint rejection")
        } catch (_: IOException) { assertEquals(0, calls) }
    }

    @Test fun relativeMetadataAndPreviewUseTheServerResolvedLocation() = runBlocking {
        Fixture { _, path -> when {
            path.startsWith("/api/session-file/") || path.contains("__wand_file_action=metadata") -> Reply(307, mapOf("Location" to
                if (path.contains("mode=preview")) "/api/local-file/L3RtcC9kZW1vLmh0bWw/?theme=dark" else "/api/file-preview?path=%2Ftmp%2Fsource%23name.txt"))
            path.startsWith("/api/file-preview") -> Reply(200, mapOf("Content-Type" to "application/json"), "{\"path\":\"/tmp/source#name.txt\",\"kind\":\"text\"}")
            else -> Reply(200, mapOf("Content-Type" to "text/html; charset=utf-8", "Content-Security-Policy" to safePolicy))
        } }.use { server ->
            val endpoint = server.endpoint
            val file = WandMessageLinks.resolve("source%23name.txt", endpoint, MessageLinkContext("source")) as MessageLinkTarget.File
            assertEquals("/tmp/source#name.txt", WandMessageLinkTransport.resolveFilePath(endpoint, file))
            val nested = WandMessageLinks.resolve("/api/local-file/L3RtcC9kZW1v/assets/source%23name.txt", endpoint) as MessageLinkTarget.File
            assertEquals("/tmp/source#name.txt", WandMessageLinkTransport.resolveFilePath(endpoint, nested))
            val html = WandMessageLinks.resolve("demo.html?theme=dark#panel", endpoint, MessageLinkContext("source")) as MessageLinkTarget.File
            val url = WandMessageLinkTransport.preparePreview(endpoint, html.previewUrl!!)
            assertEquals("$endpoint/api/local-file/L3RtcC9kZW1vLmh0bWw/?theme=dark#panel", url)
        }
    }

    @Test fun headUnsupportedFallsBackToGetAndMissingPolicyFailsClosed() = runBlocking {
        for (status in listOf(405, 501)) {
            val methods = mutableListOf<String>()
            Fixture { method, _ ->
                methods += method
                if (method == "HEAD") Reply(status) else Reply(200, mapOf("Content-Type" to "text/html", "Content-Security-Policy" to safePolicy), "<button>demo</button>")
            }.use { server ->
                val url = server.endpoint + "/api/local-preview/127.0.0.1/5173/demo#panel"
                assertEquals(url, WandMessageLinkTransport.preparePreview(server.endpoint, url))
                assertEquals(listOf("HEAD", "GET"), methods)
            }
        }
        Fixture { _, _ -> Reply(200, mapOf("Content-Type" to "text/html"), "<script>unsafe</script>") }.use { server ->
            try {
                WandMessageLinkTransport.preparePreview(server.endpoint, server.endpoint + "/api/local-file/L3RtcC9kZW1v/")
                fail("Missing isolation must fail")
            } catch (failure: IOException) { assertTrue(failure.message.orEmpty().contains("隔离")) }
        }
    }

    @Test fun largeMetadataRetainsAnUnboundedAuthenticatedDownload() = runBlocking {
        Fixture { _, path -> if (path.contains("mode=metadata")) Reply(413) else Reply(200, body = "complete file") }.use { server ->
            val target = WandMessageLinks.resolve("large.txt", server.endpoint, MessageLinkContext("source")) as MessageLinkTarget.File
            try { WandMessageLinkTransport.resolveFilePath(server.endpoint, target); fail("Expected download fallback") }
            catch (_: WandMessageLinkTransport.NeedsDownload) {
                WandMessageLinkTransport.request(server.endpoint, target.downloadUrl).use { assertEquals("complete file", it.body!!.string()) }
            }
        }
    }

    @Test fun legacyLocalFileRootUsesActualMimeAndCanReturnToNativeFilePreview() = runBlocking {
        Fixture { _, path -> when {
            path.contains("__wand_file_action=metadata") -> Reply(200, mapOf("Content-Type" to "application/json"), "{\"path\":\"/tmp/report.pdf\",\"kind\":\"pdf\"}")
            else -> Reply(200, mapOf("Content-Type" to "application/pdf"), "%PDF-fixture")
        } }.use { server ->
            val root = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("/tmp/report.pdf".toByteArray())
            val target = WandMessageLinks.resolve("/api/local-file/$root/", server.endpoint) as MessageLinkTarget.File
            try { WandMessageLinkTransport.preparePreview(server.endpoint, target.previewUrl!!); fail("Must route PDF to native handling") }
            catch (_: WandMessageLinkTransport.NotHtml) {
                assertEquals("/tmp/report.pdf", WandMessageLinkTransport.resolveFilePath(server.endpoint, target))
                WandMessageLinkTransport.request(server.endpoint, target.downloadUrl).use { assertEquals("%PDF-fixture", it.body!!.string()) }
            }
        }
    }

    @Test fun loopbackPdfAndImagesUseTheSameScopeForNativeDownload() = runBlocking {
        for (mime in listOf("application/pdf", "image/png")) {
            Fixture { _, path -> when {
                path.endsWith("/start") -> Reply(307, mapOf("Location" to "/api/local-preview/127.0.0.1/5173/file"))
                else -> Reply(200, mapOf("Content-Type" to mime), "binary-fixture")
            } }.use { server ->
                val url = server.endpoint + "/api/local-preview/127.0.0.1/5173/start"
                try { WandMessageLinkTransport.preparePreview(server.endpoint, url); fail("Expected native file routing") }
                catch (_: WandMessageLinkTransport.NotHtml) {
                    WandMessageLinkTransport.requestPreviewFile(server.endpoint, url).use {
                        assertEquals(mime, it.header("Content-Type"))
                        assertEquals("binary-fixture", it.body!!.string())
                    }
                }
            }
        }
    }

    @Test fun loopbackFileDownloadCannotEscapeItsPortOrAcceptRacedHtml() {
        for (location in listOf("/api/local-preview/127.0.0.1/8181/secret", "/api/config", "https://other.test/file.pdf")) {
            Fixture { _, _ -> Reply(307, mapOf("Location" to location)) }.use { server ->
                try {
                    WandMessageLinkTransport.requestPreviewFile(server.endpoint, server.endpoint + "/api/local-preview/127.0.0.1/5173/file")
                    fail("Expected scope rejection")
                } catch (_: IOException) { }
            }
        }
        Fixture { _, _ -> Reply(200, mapOf("Content-Type" to "text/html"), "<script>unsafe</script>") }.use { server ->
            try {
                WandMessageLinkTransport.requestPreviewFile(server.endpoint, server.endpoint + "/api/local-preview/127.0.0.1/5173/file")
                fail("A changed HTML response must return to isolated preview")
            } catch (failure: IOException) { assertTrue(failure.message.orEmpty().contains("网页")) }
        }
    }

    private data class Reply(val status: Int, val headers: Map<String, String> = emptyMap(), val body: String = "")
    private class Fixture(handler: (String, String) -> Reply) : java.io.Closeable {
        private val socket = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val endpoint = "http://127.0.0.1:${socket.localPort}"
        private val worker = kotlin.concurrent.thread(isDaemon = true) {
            try {
                while (!socket.isClosed) socket.accept().use { connection ->
                    val input = connection.getInputStream().bufferedReader()
                    val start = input.readLine().split(' ')
                    while (!input.readLine().isNullOrEmpty()) { }
                    val reply = handler(start[0], start[1])
                    val bytes = reply.body.toByteArray()
                    val headers = "HTTP/1.1 ${reply.status} Fixture\r\nConnection: close\r\nContent-Length: ${bytes.size}\r\n" +
                        reply.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n"
                    connection.getOutputStream().apply { write(headers.toByteArray()); if (start[0] != "HEAD") write(bytes); flush() }
                }
            } catch (_: java.net.SocketException) { }
        }
        override fun close() { socket.close(); worker.join(1000); com.wand.app.data.WandHttp.resetClient(endpoint) }
    }

    private fun client(handler: (Request) -> Response) = OkHttpClient.Builder().addInterceptor { handler(it.request()) }.build()
    private fun response(request: Request, code: Int, location: String? = null): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body("fixture".toResponseBody())
        .apply { if (location != null) header("Location", location) }.build()
}
