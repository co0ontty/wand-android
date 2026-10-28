package com.wand.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import kotlinx.coroutines.runBlocking

class ServerFileLinkTest {
    @Test
    fun absoluteServerPathsPreserveAmbiguousColonSuffixes() {
        assertEquals(
            "/Users/me/project/report.pdf:42:7",
            WandServerFileLink.serverPath("/Users/me/project/report.pdf:42:7"),
        )
        assertEquals(
            "/tmp/build output/app.apk",
            WandServerFileLink.serverPath("</tmp/build%20output/app.apk#L12C3>"),
        )
    }

    @Test
    fun fileUrlsResolveToServerPaths() {
        assertEquals(
            "/Users/me/My Report.pdf",
            WandServerFileLink.serverPath("file:///Users/me/My%20Report.pdf"),
        )
        assertEquals(
            "/tmp/a+b.txt",
            WandServerFileLink.serverPath("/tmp/a+b.txt"),
        )
        assertEquals("/tmp/report:2025", WandServerFileLink.serverPath("/tmp/report:2025"))
        assertEquals("/tmp/report:2025", WandServerFileLink.serverPath("file:///tmp/report%3A2025"))
    }

    @Test
    fun authenticatedDownloadLinksUseTheActiveEndpoint() {
        val base = "http://192.168.0.10:8181"
        assertEquals("/tmp/PlaylistBridge.ipa", WandServerFileLink.downloadPath(
            "$base/api/file-raw?path=%2Ftmp%2FPlaylistBridge.ipa&download=1", base))
        assertEquals("/tmp/a%20+b.ipa", WandServerFileLink.downloadPath(
            "/api/file-raw?path=%2Ftmp%2Fa%2520%2Bb.ipa&download=1", base))
        assertEquals("/tmp/a.ipa", WandServerFileLink.downloadPath("/tmp/a.ipa", base))
        assertEquals("/tmp/a.ipa", WandServerFileLink.downloadPath(
            "https://home.example/wand/api/file-raw?path=%2Ftmp%2Fa.ipa", "https://home.example:443/wand/"))
    }

    @Test
    fun downloadLinksCannotCrossOriginsOrProxyNamespaces() {
        val base = "https://home.example/wand/"
        for (target in listOf(
            "http://home.example/wand/api/file-raw?path=%2Ftmp%2Fa.ipa",
            "https://home.example:8443/wand/api/file-raw?path=%2Ftmp%2Fa.ipa",
            "https://other.example/wand/api/file-raw?path=%2Ftmp%2Fa.ipa",
            "https://home.example/api/file-raw?path=%2Ftmp%2Fa.ipa",
            "https://home.example/wand-other/api/file-raw?path=%2Ftmp%2Fa.ipa",
            "https://user@home.example/wand/api/file-raw?path=%2Ftmp%2Fa.ipa",
            "//home.example/wand/api/file-raw?path=%2Ftmp%2Fa.ipa",
            "https://home.example/wand/../wand/api/file-raw?path=%2Ftmp%2Fa.ipa",
            "https://home.example/wand/api/file-raw?path=relative.ipa",
            "https://home.example/wand/api/file-raw?path=%2Ftmp%2Fa.ipa&path=%2Ftmp%2Fb.ipa",
            "https://home.example/wand/api/file-raw?path=%2Ftmp%2Fa%00.ipa",
        )) assertNull(target, WandServerFileLink.downloadPath(target, base))
    }

    @Test
    fun webLinksAndWandRoutesStayExternal() {
        assertNull(WandServerFileLink.serverPath("https://example.com/file.zip"))
        assertNull(WandServerFileLink.serverPath("mailto:user@example.com"))
        assertNull(WandServerFileLink.serverPath("/api/file-raw?path=/tmp/a.txt"))
        assertNull(WandServerFileLink.serverPath("/android/download"))
    }

    @Test
    fun realColonFileWinsWithoutRequestingTheStrippedName() = runBlocking {
        val requested = mutableListOf<String>()
        val result = WandServerFileLink.requestFile(
            "https://server.test/wand", "/tmp/report:2025", client = client { request ->
                requested += request.url.queryParameter("path").orEmpty()
                assertEquals("/wand/api/file-raw", request.url.encodedPath)
                assertEquals("1", request.url.queryParameter("download"))
                response(request, 200)
            },
        )
        result.response.use {
            assertEquals("/tmp/report:2025", result.path)
            assertEquals(listOf("/tmp/report:2025"), requested)
        }
    }

    @Test
    fun missingEditorSuffixFallsBackForGetAndHead() = runBlocking {
        for (method in listOf("GET", "HEAD")) {
            for (missing in listOf(400, 404)) {
                val requested = mutableListOf<String>()
                val result = WandServerFileLink.requestFile(
                    "https://server.test", "/tmp/My%20+Report.txt:42:7", method = method,
                    client = client { request ->
                        assertEquals(method, request.method)
                        requested += request.url.queryParameter("path").orEmpty()
                        response(request, if (requested.size == 1) missing else 200)
                    },
                )
                result.response.use {
                    assertEquals("/tmp/My%20+Report.txt", result.path)
                    assertEquals(listOf("/tmp/My%20+Report.txt:42:7", "/tmp/My%20+Report.txt"), requested)
                }
            }
        }
    }

    @Test
    fun authorizationAndServerFailuresNeverRequestAnotherFile() = runBlocking {
        for (code in listOf(401, 403, 408, 409, 413, 500, 503)) {
            var calls = 0
            val result = WandServerFileLink.requestFile(
                "https://server.test", "/tmp/report:2025", client = client { request ->
                    calls++
                    response(request, code)
                },
            )
            result.response.use {
                assertEquals(code, it.code)
                assertEquals(1, calls)
            }
        }
        var calls = 0
        try {
            WandServerFileLink.requestFile("https://server.test", "/tmp/report:2025", client = client {
                calls++
                throw IOException("offline")
            })
            throw AssertionError("Expected network error")
        } catch (_: IOException) {
            assertEquals(1, calls)
        }
    }

    @Test
    fun editorSuffixClassificationDoesNotChangeTheRequestedPath() {
        assertTrue(WandTextPreview.isPreviewableText(WandServerFileLink.previewTypePath("/tmp/source.kt:12:3")))
        assertTrue(WandImage.isImagePath(WandServerFileLink.previewTypePath("/tmp/image.png:12")))
        assertEquals("/tmp/source.kt:12:3", WandServerFileLink.downloadPath("/tmp/source.kt:12:3", "https://server.test"))
    }

    @Test
    fun successfulResponseKeepsTheActualServerFilename() {
        val request = Request.Builder().url("https://server.test/api/file-raw").build()
        response(request, 200).newBuilder()
            .header("Content-Disposition", "attachment; filename*=UTF-8''%E6%8A%A5%E5%91%8A%20a%2520%2Bb.txt")
            .build().use {
                assertEquals("报告 a%20+b.txt", WandServerFileLink.downloadFileName(it, "/tmp/report.txt:42"))
            }
        response(request, 200).use {
            assertEquals("report:2025", WandServerFileLink.downloadFileName(it, "/tmp/report:2025"))
        }
    }

    private fun client(handler: (Request) -> Response) = OkHttpClient.Builder()
        .addInterceptor { handler(it.request()) }.build()

    private fun response(request: Request, code: Int) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
        .body("fixture".toResponseBody()).build()
}
