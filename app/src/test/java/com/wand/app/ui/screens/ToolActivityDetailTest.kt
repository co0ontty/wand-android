package com.wand.app.ui.screens

import com.wand.app.data.ContentBlock
import com.wand.app.data.ToolActivity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolActivityDetailTest {
    private fun tool(name: String, input: JSONObject = JSONObject(), activity: ToolActivity? = null) =
        ContentBlock.ToolUse("call", name, null, input, null, activity = activity)

    @Test
    fun fileActionsUseFileSemanticsForCompactAndLegacyCallsButNeverSearchOrCommands() {
        assertTrue(toolActivityOpensFile(tool("pi/read", activity = ToolActivity("read_file", "查看文件", "anonymous"))))
        assertTrue(toolActivityOpensFile(tool("Read", JSONObject().put("file_path", "src/main.kt"))))
        assertTrue(toolActivityOpensFile(tool("edit", JSONObject().put("path", "src/main.kt"))))
        assertFalse(toolActivityOpensFile(tool("Grep", JSONObject().put("path", "src/main.kt"))))
        assertFalse(toolActivityOpensFile(tool("Bash", JSONObject().put("command", "cat src/main.kt"))))
        assertFalse(toolActivityOpensFile(tool("todo")))
    }

    @Test
    fun relativeFilePathsResolveAgainstTheServerSessionNotTheAndroidWorkingDirectory() {
        assertEquals("/repo/src/main.kt", toolActivityFilePath(JSONObject().put("path", "src/main.kt"), "/repo"))
        assertEquals("/repo/main.kt", toolActivityFilePath(JSONObject().put("file_path", "../main.kt"), "/repo/src"))
        assertEquals("/repo/main.kt", toolActivityFilePath(JSONObject().put("file_path", "/repo/src/../main.kt"), null))
        assertNull(toolActivityFilePath(JSONObject().put("path", "src/main.kt"), null))
        assertNull(toolActivityFilePath(JSONObject().put("path", "src/main.kt"), "relative/cwd"))
    }

    @Test
    fun filePathSupportsRealAliasesAndMovedFilesWithoutGuessingFromDisplayText() {
        for (key in listOf("file_path", "path", "filename", "file", "notebook_path")) {
            assertEquals("/repo/main.kt", toolActivityFilePath(JSONObject().put(key, " main.kt "), "/repo"))
        }
        assertEquals("/repo/new.kt", toolActivityFilePath(JSONObject()
            .put("file_path", "old.kt").put("move_path", "new.kt"), "/repo"))
        assertEquals("/repo/main.kt", toolActivityFilePath(JSONObject()
            .put("file_path", JSONObject()).put("path", "main.kt"), "/repo"))
        assertNull(toolActivityFilePath(JSONObject().put("preview", "查看 /repo/secret.kt")
            .put("fileKey", "anonymous"), "/repo"))
        assertNull(toolActivityFilePath(JSONObject().put("path", "~/main.kt"), "/repo"))
        assertNull(toolActivityFilePath(JSONObject().put("path", "\u0000"), "/repo"))
    }
}
