package com.wand.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerFileLinkTest {
    @Test
    fun absoluteServerPathsDropEditorLineSuffixes() {
        assertEquals(
            "/Users/me/project/report.pdf",
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
}
