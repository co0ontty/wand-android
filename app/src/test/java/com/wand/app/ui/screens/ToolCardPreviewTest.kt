package com.wand.app.ui.screens

import com.wand.app.data.ContentBlock
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCardPreviewTest {
    @Test fun compactCallKeepsActualOperationAndResultWithoutBodies() {
        val call = ContentBlock.parse(JSONObject("""{"type":"tool_use","id":"one","name":"Bash","input":{},"preview":"npm run check"}""")) as ContentBlock.ToolUse
        val result = ContentBlock.parse(JSONObject("""{"type":"tool_result","tool_use_id":"one","content":"","_truncated":true,"preview":"退出码 1 · TypeError: missing element","is_error":true}""")) as ContentBlock.ToolResult
        assertEquals("npm run check", toolInputCardPreview(call))
        assertEquals("退出码 1 · TypeError: missing element", toolResultCardPreview(result))
        assertEquals(0, call.input.length())
        assertTrue(result.text.isEmpty())
        assertTrue(result.isError)
    }

    @Test fun legacyCallUsesAvailableInputAndNeverInventsResult() {
        val call = ContentBlock.parse(JSONObject("""{"type":"tool_use","id":"old","name":"Grep","input":{"pattern":"SessionRegistry"}}""")) as ContentBlock.ToolUse
        assertEquals("SessionRegistry", toolInputCardPreview(call))
        assertEquals("", toolResultCardPreview(null))
    }

    @Test fun readImageResultKeepsOnlyTheFileNameAndFullImage() {
        val call = ContentBlock.parse(
            JSONObject("""{"type":"tool_use","id":"img","name":"Read","input":{"file_path":"/tmp/shot.png"}}""")
        ) as ContentBlock.ToolUse
        val result = ContentBlock.ToolResult(
            toolUseId = "img", text = "", isError = false, truncated = false, subagent = null,
        )
        assertEquals(listOf("/tmp/shot.png"), toolImageSources(call, result))
        assertEquals("shot.png", toolImageDisplayName("/tmp/shot.png", "/tmp/shot.png", 0, 1))
    }

    @Test fun toolResultImagesUseTheirOwnFileNames() {
        val call = ContentBlock.parse(
            JSONObject("""{"type":"tool_use","id":"img","name":"Read","input":{"file_path":"/tmp/shot.png"}}""")
        ) as ContentBlock.ToolUse
        val result = ContentBlock.ToolResult(
            toolUseId = "img", text = "", isError = false, truncated = false, subagent = null,
            images = listOf("https://h/api/x/0.png?token=1", "data:image/png;base64,AAAA"),
        )
        assertEquals(2, toolImageSources(call, result).size)
        assertEquals("0.png", toolImageDisplayName("/tmp/shot.png", "https://h/api/x/0.png?token=1", 0, 2))
        assertEquals("图片 2", toolImageDisplayName(null, "data:image/png;base64,AAAA", 1, 2))
    }

    @Test fun failedOrMissingResultKeepsTheToolCard() {
        val call = ContentBlock.parse(
            JSONObject("""{"type":"tool_use","id":"img","name":"Read","input":{"file_path":"/tmp/shot.png"}}""")
        ) as ContentBlock.ToolUse
        val failed = ContentBlock.ToolResult(
            toolUseId = "img", text = "", isError = true, truncated = false, subagent = null,
        )
        assertTrue(toolImageSources(call, failed).isEmpty())
        assertTrue(toolImageSources(call, null).isEmpty())
    }
}
