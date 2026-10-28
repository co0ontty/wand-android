package com.wand.app.ui

import com.wand.app.data.WandHttp
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/** Explicit, read-only installed-service acceptance; ordinary test runs never access user data. */
class MessageLinksInstalledAcceptanceTest {
    @Test fun installedServiceSupportsNativeFileAndPreviewTargets() = runBlocking {
        assumeTrue(System.getenv("WAND_LINK_ACCEPTANCE") == "1")
        val repository = File(requireNotNull(System.getenv("WAND_LINK_REPO_CWD"))).canonicalFile
        val sessionId = requireNotNull(System.getenv("WAND_LINK_SOURCE_SESSION"))
        val connection = JSONObject(File(System.getProperty("user.home"), ".wand/acceptance-connection.json").readText())
        val endpoint = WandHttp.normalizeBaseUrl(connection.getString("serverURL"))
        val decoded = String(Base64.getDecoder().decode(connection.getString("connectionCode")), Charsets.UTF_8)
        val separator = decoded.lastIndexOf('#')
        check(separator > 0 && separator < decoded.lastIndex) { "Invalid private acceptance connection" }
        val token = decoded.substring(separator + 1)
        val login = Request.Builder().url("$endpoint/api/login")
            .post(JSONObject().put("appToken", token).toString().toRequestBody("application/json".toMediaType())).build()
        val client = WandHttp.clientFor(endpoint)
        client.newCall(login).execute().use { assertTrue("Native acceptance login failed", it.isSuccessful) }

        val fixtures = File(repository, "output/link-formats-20261027/fixtures")
        val source = File(fixtures, "source.txt").canonicalFile
        val note = File(fixtures, "site/sub/note.md").canonicalFile
        val site = File(fixtures, "site").canonicalFile
        val sourceContext = MessageLinkContext(sessionId)
        val completed = mutableListOf<String>()
        for ((name, href) in listOf("absolute" to source.path, "fileUri" to source.toURI().toASCIIString(),
            "relative" to source.relativeTo(repository).invariantSeparatorsPath,
            "fileRaw" to "$endpoint/api/file-raw?path=${WandMessageLinks.encode(source.path)}")) {
            val target = WandMessageLinks.resolve(href, endpoint, sourceContext) as MessageLinkTarget.File
            assertEquals(source.path, WandMessageLinkTransport.resolveFilePath(endpoint, target))
            WandMessageLinkTransport.request(endpoint, target.downloadUrl).use { response ->
                assertTrue(name, response.isSuccessful)
                assertArrayEquals(source.readBytes(), response.body!!.bytes())
            }
            completed += name
        }
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(site.path.toByteArray())
        val nested = WandMessageLinks.resolve("/api/local-file/$encoded/sub/note.md?mode=download", endpoint) as MessageLinkTarget.File
        assertEquals(note.path, WandMessageLinkTransport.resolveFilePath(endpoint, nested))
        WandMessageLinkTransport.request(endpoint, nested.downloadUrl).use { response ->
            assertTrue(response.isSuccessful)
            assertArrayEquals(note.readBytes(), response.body!!.bytes())
        }
        completed += "nestedLocalFile"

        // Exercise the raw historical URL too: the resolver normally canonicalizes a trailing slash.
        val directoryWithoutSlash = "$endpoint/api/local-file/$encoded"
        WandMessageLinkTransport.request(endpoint, "$directoryWithoutSlash?__wand_file_action=metadata").use { response ->
            assertTrue("Directory metadata without a trailing slash", response.isSuccessful)
            assertEquals(File(site, "index.html").canonicalPath, JSONObject(response.body!!.string()).getString("path"))
        }
        val directory = WandMessageLinks.resolve(directoryWithoutSlash, endpoint) as MessageLinkTarget.File
        assertEquals(File(site, "index.html").canonicalPath, WandMessageLinkTransport.resolveFilePath(endpoint, directory))
        completed += "localFileDirectoryMetadataWithoutTrailingSlash"

        val largeSite = File(fixtures, "site.with.dots").canonicalFile
        val largeIndex = File(largeSite, "index.html").canonicalFile
        val encodedLarge = Base64.getUrlEncoder().withoutPadding().encodeToString(largeSite.path.toByteArray())
        val largeRoot = "$endpoint/api/local-file/$encodedLarge"
        WandMessageLinkTransport.request(endpoint, "$largeRoot?__wand_file_action=metadata").use { response ->
            assertEquals(413, response.code)
            assertEquals(largeIndex.path, JSONObject(response.body!!.string()).getString("path"))
        }
        val largeTarget = WandMessageLinks.resolve(largeRoot, endpoint) as MessageLinkTarget.File
        assertNotNull(WandMessageLinkTransport.preparePreview(endpoint, largeTarget.previewUrl!!))
        WandMessageLinkTransport.request(endpoint, largeTarget.downloadUrl).use { response ->
            assertTrue(response.isSuccessful)
            assertArrayEquals(largeIndex.readBytes(), response.body!!.bytes())
        }
        completed += "largeHtmlDirectoryMetadata413PathPreviewAndDownload"

        val html = File(site, "index.html").relativeTo(repository).invariantSeparatorsPath + "?mode=download&x=1#panel"
        val target = WandMessageLinks.resolve(html, endpoint, sourceContext) as MessageLinkTarget.File
        val prepared = WandMessageLinkTransport.preparePreview(endpoint, target.previewUrl!!)
        assertTrue(prepared.contains("mode=download&x=1"))
        assertTrue(prepared.endsWith("#panel"))
        val scope = WandMessageLinkTransport.previewScope(prepared, endpoint)!!
        WandMessageLinkTransport.request(endpoint, prepared, allowed = { WandMessageLinkTransport.isInPreviewScope(it, endpoint, scope) }).use {
            assertTrue(it.isSuccessful)
            assertTrue(WandMessageLinkTransport.hasIsolatedHtmlPolicy(it.header("Content-Security-Policy")))
            assertArrayEquals(File(site, "index.html").readBytes(), it.body!!.bytes())
        }
        completed += "relativeHtmlQueryFragmentCsp"
        assertTrue(WandMessageLinks.resolve("source.txt", endpoint) is MessageLinkTarget.Unavailable)
        completed += "missingSourceRejected"

        // The installed Wand server proxies this local HTTP fixture; no device or browser is launched.
        val local = java.net.ServerSocket(0, 2, java.net.InetAddress.getByName("127.0.0.1")).apply { soTimeout = 15000 }
        val binary = "%PDF-1.4\nWand link acceptance\n".toByteArray()
        val serving = java.util.concurrent.FutureTask<Unit> {
            repeat(2) { local.accept().use { socket ->
                socket.soTimeout = 15000
                val reader = socket.getInputStream().bufferedReader()
                val method = reader.readLine().substringBefore(' ')
                while (!reader.readLine().isNullOrEmpty()) { }
                val headers = "HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Type: application/pdf\r\n" +
                    "Content-Disposition: inline; filename*=UTF-8''loopback-report.pdf\r\nContent-Length: ${binary.size}\r\n\r\n"
                socket.getOutputStream().apply { write(headers.toByteArray()); if (method != "HEAD") write(binary); flush() }
            } }
        }
        val worker = Thread(serving, "wand-link-acceptance-fixture").apply { isDaemon = true; start() }
        try {
            val preview = WandMessageLinks.resolve("http://127.0.0.1:${local.localPort}/report.pdf", endpoint) as MessageLinkTarget.Preview
            try { WandMessageLinkTransport.preparePreview(endpoint, preview.url); fail("PDF must use native file handling") }
            catch (_: WandMessageLinkTransport.NotHtml) {
                WandMessageLinkTransport.requestPreviewFile(endpoint, preview.url).use { response ->
                    assertTrue(response.isSuccessful)
                    assertEquals("application/pdf", response.header("Content-Type"))
                    assertEquals("loopback-report.pdf", WandServerFileLink.downloadFileName(response, "report"))
                    assertArrayEquals(binary, response.body!!.bytes())
                }
            }
            serving.get(15, java.util.concurrent.TimeUnit.SECONDS)
            completed += "loopbackPdfNativeDownload"
        } finally { local.close(); worker.join(1000) }
        File(repository, "output/link-formats-20261027/android-installed-service.json").writeText(
            JSONObject().put("passed", true).put("cases", org.json.JSONArray(completed))
                .put("nativeWebViewInteractionTested", false).put("deviceInstalled", false).toString(2) + "\n")
    }
}
