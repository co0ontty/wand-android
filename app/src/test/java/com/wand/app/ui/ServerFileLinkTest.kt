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
    fun webLinksAndWandRoutesStayExternal() {
        assertNull(WandServerFileLink.serverPath("https://example.com/file.zip"))
        assertNull(WandServerFileLink.serverPath("mailto:user@example.com"))
        assertNull(WandServerFileLink.serverPath("/api/file-raw?path=/tmp/a.txt"))
        assertNull(WandServerFileLink.serverPath("/android/download"))
    }
}
