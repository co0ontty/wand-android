package com.wand.app.ui

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class MessageLinksTest {
    private val base = "https://home.test/wand"
    private val source = MessageLinkContext("source-session", "/wrong-client-fallback")
    private fun file(value: String, context: MessageLinkContext = source) =
        WandMessageLinks.resolve(value, base, context) as MessageLinkTarget.File
    private fun root(preview: String) = String(Base64.getUrlDecoder().decode(preview.toHttpUrl().pathSegments[3]), Charsets.UTF_8)

    @Test fun absoluteAndFileUrisPreserveEncodedFilenameAndPageState() {
        for (value in listOf("/tmp/My%20%23%3F%25%2B演示.html?mode=wide&v=1#details", "file:///tmp/My%20%23%3F%25%2B演示.html?mode=wide&v=1#details", "</tmp/My%20%23%3F%25%2B演示.html?mode=wide&v=1#details>")) {
            val target = file(value)
            assertEquals("/tmp/My #?%+演示.html", target.absolutePath)
            assertEquals(target.absolutePath, target.rawUrl.toHttpUrl().queryParameter("path"))
            assertEquals(target.absolutePath, root(target.previewUrl!!))
            assertEquals("mode=wide&v=1", target.previewUrl.toHttpUrl().encodedQuery)
            assertEquals("details", target.previewUrl.toHttpUrl().fragment)
        }
    }

    @Test fun sourceSessionWinsOverCwdAndNoSourceDoesNotGuess() {
        val target = file("demo/test%20a.html?mode=wide#details")
        assertNull(target.absolutePath)
        assertEquals("/wand/api/session-file/source-session", target.rawUrl.toHttpUrl().encodedPath)
        assertEquals("demo/test a.html", target.rawUrl.toHttpUrl().queryParameter("path"))
        assertEquals("?mode=wide", target.previewUrl!!.toHttpUrl().queryParameter("search"))
        assertEquals("details", target.previewUrl.toHttpUrl().fragment)
        assertEquals("metadata", target.metadataUrl!!.toHttpUrl().queryParameter("mode"))
        assertTrue(WandMessageLinks.resolve("demo.html", base) is MessageLinkTarget.Unavailable)
        assertTrue(WandMessageLinks.resolve("demo.html", "", source) is MessageLinkTarget.Unavailable)
    }

    @Test fun explicitCwdNormalizesInsideItsBoundary() {
        val context = MessageLinkContext(cwd = "/work/source")
        assertEquals("/work/source/demo.html", file("./sub/../demo.html", context).absolutePath)
        assertTrue(WandMessageLinks.resolve("../demo.html", base, context) is MessageLinkTarget.Unavailable)
        assertTrue(WandMessageLinks.resolve("sub/../../demo.html", base, context) is MessageLinkTarget.Unavailable)
        // A session source delegates the containment check to its authoritative server cwd.
        assertEquals("../demo.html", file("../demo.html").rawUrl.toHttpUrl().queryParameter("path"))
    }

    @Test fun homeAndDirectoriesKeepServerMeaning() {
        val home = file("~/demos/index.html?theme=dark#one", MessageLinkContext())
        assertEquals("~/demos/index.html", home.absolutePath)
        assertEquals("~/demos/index.html", root(home.previewUrl!!))
        assertNotNull(file("demo/").previewUrl)
        assertEquals("preview", file("demo/").previewUrl!!.toHttpUrl().queryParameter("mode"))
        assertEquals("download", file("/tmp/site/").downloadUrl.toHttpUrl().queryParameter("__wand_file_action"))
        assertTrue(file("/tmp/site/").downloadUrl.contains("/api/local-file/"))
    }

    @Test fun authenticatedRawAndSessionLinksDecodeExactlyOnce() {
        for (prefix in listOf("", "https://home.test/wand", "/wand")) {
            val target = file("$prefix/api/file-raw?path=%2Ftmp%2Fa%2520%2Bb.html&search=%3Fx%3D1#panel")
            assertEquals("/tmp/a%20+b.html", target.absolutePath)
            assertEquals("x=1", target.previewUrl!!.toHttpUrl().encodedQuery)
            assertEquals("panel", target.previewUrl.toHttpUrl().fragment)
        }
        val forwarded = file("/api/session-file/original?path=demo%2Fa%2520%2Bb.html&mode=preview&search=%3Fx%3D1#panel")
        assertEquals("/wand/api/session-file/original", forwarded.rawUrl.toHttpUrl().encodedPath)
        assertEquals("demo/a%20+b.html", forwarded.path)
        assertEquals("?x=1", forwarded.previewUrl!!.toHttpUrl().queryParameter("search"))
        assertTrue(WandMessageLinks.resolve("/api/file-raw?path=%2Ftmp%2Fa&path=%2Ftmp%2Fb", base) is MessageLinkTarget.Unavailable)
    }

    @Test fun apiQueryUsesFormDecodingWhileLiteralFilePathsPreservePlus() {
        for ((query, expected) in listOf("a+b.txt" to "a b.txt", "a%2Bb.txt" to "a+b.txt", "a%252Bb.txt" to "a%2Bb.txt")) {
            assertEquals("/tmp/$expected", file("/api/file-raw?path=%2Ftmp%2F$query").path)
            assertEquals("sub/$expected", file("/api/session-file/original?path=sub%2F$query").path)
        }
        assertEquals("/tmp/a+b.txt", file("/tmp/a+b.txt").path)
        assertEquals("/tmp/a+b.txt", file("file:///tmp/a+b.txt").path)
        val html = file("/api/session-file/original?path=demo.html&mode=preview&search=%3Fname%3Da%2Bb")
        assertEquals("?name=a+b", html.previewUrl!!.toHttpUrl().queryParameter("search"))
    }

    @Test fun nestedLocalFileSupportsMetadataDownloadAndInteractivePages() {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString("/tmp/demo".toByteArray())
        val image = file("/api/local-file/$encoded/assets/a%23b.png?v=1#p")
        assertNull(image.previewUrl)
        assertEquals("assets/a#b.png", image.path)
        assertEquals("metadata", image.metadataUrl!!.toHttpUrl().queryParameter("__wand_file_action"))
        assertEquals("download", image.downloadUrl.toHttpUrl().queryParameter("__wand_file_action"))
        val page = file("$base/api/local-file/$encoded/pages/a.html?mode=wide&theme=dark#second")
        assertEquals("$base/api/local-file/$encoded/pages/a.html?mode=wide&theme=dark#second", page.previewUrl)
        assertEquals("wide", page.metadataUrl!!.toHttpUrl().queryParameter("mode"))
        assertEquals("wide", page.downloadUrl.toHttpUrl().queryParameter("mode"))
        assertNotNull(file("/api/local-file/$encoded/").previewUrl)
    }

    @Test fun loopbackAndAlreadyForwardedUrlsPreserveQueryFragmentAndIpv6() {
        for (value in listOf("http://localhost:5173/demo?q=1#panel", "localhost:5173/demo?q=1#panel", "127.0.0.1:5173/demo?q=1#panel", "/api/local-preview/localhost/5173/demo?q=1#panel")) {
            val result = WandMessageLinks.resolve(value, base) as MessageLinkTarget.Preview
            assertEquals("$base/api/local-preview/127.0.0.1/5173/demo?q=1#panel", result.url)
        }
        val ipv6 = WandMessageLinks.resolve("[::1]:5173/demo?q=1#panel", base) as MessageLinkTarget.Preview
        assertEquals("$base/api/local-preview/::1/5173/demo?q=1#panel", ipv6.url)
        assertEquals(ipv6.url, (WandMessageLinks.resolve("/api/local-preview/[::1]/5173/demo?q=1#panel", base) as MessageLinkTarget.Preview).url)
        assertEquals("$base/api/local-preview/127.0.0.1/80/", (WandMessageLinks.resolve("http://localhost", base) as MessageLinkTarget.Preview).url)
    }

    @Test fun foreignOriginsAndUnsafeTargetsNeverAcquireEndpointCredentials() {
        for (value in listOf("https://other.test/api/file-raw?path=%2Ftmp%2Fa.html", "https://home.test:444/wand/api/file-raw?path=%2Ftmp%2Fa", "http://home.test/wand/api/file-raw?path=%2Ftmp%2Fa", "https://home.test/other/api/file-raw?path=%2Ftmp%2Fa")) {
            assertEquals(MessageLinkTarget.External(value), WandMessageLinks.resolve(value, base))
        }
        for (value in listOf("javascript:alert(1)", "data:text/html,hi", "file://evil.test/tmp/a", "/api/local-preview/evil.test/80/", "/api/local-preview/localhost/99999/", "/api/local-file/abc/../etc", "/api/local-file/abc/%2e%2e/etc", "//evil.test/a", "/tmp/a\nb")) {
            assertTrue(value, WandMessageLinks.resolve(value, base) is MessageLinkTarget.Unavailable)
        }
    }

    @Test fun lineNumbersRemainAmbiguousAndLiteralHashesRemainFilenames() {
        val target = file("demo.html:12:3#L12C3")
        assertEquals("demo.html:12:3", target.path)
        assertNull(target.previewUrl!!.toHttpUrl().fragment)
        assertEquals("/tmp/source.kt:2025", file("/tmp/source.kt:2025").absolutePath)
        assertTrue(WandImage.isImageFilePath(file("/tmp/a%23b.png").path))
        assertTrue(WandTextPreview.isPreviewableText(file("/tmp/a%3Fb.txt").path))
    }

    @Test fun forwardedMessageAndAggregatePreviewNeverBorrowTheSelectedSession() {
        val current = MessageLinkContext("selected-session", "/selected/cwd")
        val original = com.wand.app.data.ConversationTurn("assistant", emptyList(),
            author = com.wand.app.data.TurnAuthor(name = "Original", sessionId = "original-session"))
        val resolved = messageLinkContextForTurn(original, current)
        assertEquals(MessageLinkContext("original-session"), resolved)
        assertEquals("/wand/api/session-file/original-session", file("demo.html", resolved).rawUrl.toHttpUrl().encodedPath)
        val card = original.copy(sessionLink = com.wand.app.data.ConversationSessionLink("linked-session", "Source"))
        assertEquals(MessageLinkContext("linked-session"), messageLinkContextForTurn(card, current))
        val aggregate = original.copy(conversationLink = com.wand.app.data.ConversationLink("conversation", "task", "Summary"))
        assertEquals(MessageLinkContext(), messageLinkContextForTurn(aggregate, current))
        assertEquals(current, messageLinkContextForTurn(com.wand.app.data.ConversationTurn("assistant", emptyList()), current))
    }
}
