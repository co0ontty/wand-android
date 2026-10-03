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
}
