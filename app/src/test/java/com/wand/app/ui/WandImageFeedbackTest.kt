package com.wand.app.ui

import coil.network.HttpException
import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn
import org.json.JSONObject
import java.io.IOException
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WandImageFeedbackTest {
    private fun http(code: Int) = HttpException(Response.Builder()
        .request(Request.Builder().url("https://example.invalid/image-fixture").build())
        .protocol(Protocol.HTTP_1_1).code(code).message("fixture").build())

    @Test fun deniedImagesHaveExplicitAccessFeedback() {
        assertEquals("没有图片访问权限，请核对连接。", wandImageFailureMessage(http(401)))
        assertEquals(wandImageFailureMessage(http(401)), wandImageFailureMessage(http(403)))
    }

    @Test fun missingDoesNotPretendToBeDeniedOrDataLoss() {
        assertEquals("图片未找到，请核对消息来源。", wandImageFailureMessage(http(404)))
        assertNotEquals(wandImageFailureMessage(http(403)), wandImageFailureMessage(http(404)))
    }

    @Test fun transientFailuresDoNotExposeRequestOrExceptionText() {
        val generic = "图片加载失败，请检查连接后重试。"
        assertEquals(generic, wandImageFailureMessage(http(503)))
        assertEquals(generic, wandImageFailureMessage(IOException("private request text must not enter feedback")))
    }

    @Test fun inlineImagesShareOneAdaptiveBoundingBoxWithIos() {
        // 与 iOS WandImageLayout 同值：按自身比例在框内完整显示，两端一起调。
        assertEquals(320, WandImageLayout.thumbnailMaxWidthDp)
        assertEquals(360, WandImageLayout.thumbnailMaxHeightDp)
    }

    @Test fun imageFiguresStayWithinReadingAreaProportions() {
        for (width in listOf(280f, 390f, 768f)) {
            assertEquals(minOf(width * 0.8f, 320f), WandImageLayout.figureMaxWidth(width), 0.001f)
        }
        for (height in listOf(240f, 600f, 1000f)) {
            assertEquals(minOf(height * 0.4f, 360f), WandImageLayout.figureMaxHeight(height), 0.001f)
        }
        assertEquals(0f, WandImageLayout.figureMaxWidth(-1f), 0f)
        assertEquals(360f, WandImageLayout.figureMaxHeight(0f), 0f)
    }

    @Test fun viewerStartClampsAndGroupKeepsEveryImage() {
        assertEquals(2, viewerStart(index = 2, count = 3))
        assertEquals(2, viewerStart(index = 7, count = 3))
        assertEquals(0, viewerStart(index = -1, count = 3))
        assertEquals(0, viewerStart(index = 0, count = 0))
        // 未分组时只有一张；分组时全部进入，工具图片仍按直链加载。
        assertEquals(listOf(ViewerImagePage("/work/a.png", false)),
            viewerPages(emptyList(), "/work/a.png", directUrl = false))
        assertEquals(
            listOf(ViewerImagePage("/api/sessions/s/tool-images/0", true),
                ViewerImagePage("data:image/png;base64,AA", true)),
            viewerPages(listOf("/api/sessions/s/tool-images/0", "data:image/png;base64,AA"),
                "/api/sessions/s/tool-images/0", directUrl = true),
        )
    }

    @Test fun conversationGalleryCrossesResultsWithoutLosingSourceModes() {
        val turns = listOf(
            ConversationTurn("user", listOf(ContentBlock.Text("[附件已上传，请查看以下文件:\n/work/a.png\n/work/doc.pdf\n]", null))),
            ConversationTurn("assistant", listOf(
                ContentBlock.ToolUse("one", "Read", null, JSONObject().put("path", "/work/b.png"), null),
                ContentBlock.ToolResult("one", "", false, false, null),
                ContentBlock.ToolResult("two", "", false, false, null, images = listOf("/api/image/c", "/api/image/c", "data:image/png;base64,AA")),
                ContentBlock.ToolResult("bad", "", true, false, null, images = listOf("/api/image/failed")),
            )),
        )
        val pages = conversationImagePages(turns)
        assertEquals(listOf(ViewerImagePage("/work/a.png", false), ViewerImagePage("/work/b.png", false),
            ViewerImagePage("/api/image/c", true), ViewerImagePage("data:image/png;base64,AA", true)), pages)
        val selected = imageGallerySelection(pages, listOf("/api/image/c"), "/api/image/c", true)
        assertEquals(2, selected.startIndex)
        assertEquals(pages, selected.pages)
        val fallback = imageGallerySelection(emptyList(), listOf("/a", "/b"), "/b", true)
        assertEquals(1, fallback.startIndex)
        assertEquals(2, fallback.pages.size)
    }

    @Test fun toolImageSourcesBecomeAbsoluteOnlyWhenRelative() {
        assertEquals("https://host.test/api/sessions/s/tool-images/0",
            absoluteToolImageSource("/api/sessions/s/tool-images/0", "https://host.test"))
        assertEquals("https://other.test/x.png", absoluteToolImageSource("https://other.test/x.png", "https://host.test"))
        assertEquals("data:image/png;base64,AA", absoluteToolImageSource("data:image/png;base64,AA", "https://host.test"))
    }
}
