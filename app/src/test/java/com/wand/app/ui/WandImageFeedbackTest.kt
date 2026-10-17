package com.wand.app.ui

import coil.network.HttpException
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
}
