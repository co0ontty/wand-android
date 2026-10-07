package com.wand.app.ui.screens

import com.wand.app.data.WandApi
import com.wand.app.data.WandAuth
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 只读验收：使用已安装服务真实 DTO，既不派工也不批准或停止现有任务。 */
class InstalledConversationProjectionTest {
    @Test fun installedConversationSourcesCanBindNativeInteractionOwners() = runBlocking {
        assumeTrue(System.getenv("WAND_ANDROID_INSTALLED_ACCEPTANCE") == "1")
        val connection = JSONObject(File(System.getenv("HOME"), ".wand/acceptance-connection.json").readText())
        val decoded = WandAuth.decodeConnectCode(connection.getString("connectionCode"))
        assertNotNull("Acceptance connection must decode", decoded)
        val api = WandApi(connection.getString("serverURL"), decoded!!.second)
        val rows = api.conversations()
        assertTrue("Installed service must have conversations for this acceptance", rows.isNotEmpty())
        var bound = 0
        var available = 0
        var historical = 0
        var taskCards = 0
        // Pinned/empty groups may occupy the first five rows; they are not evidence of missing history.
        val sample = rows.take(20)
        for (row in sample) {
            val detail = api.conversation(row.id)
            assertEquals(row.id, detail.id)
            val results = conversationSessionToolResults(detail)
            for (turn in detail.messages.filter { it.conversationLink != null }) {
                val preview = turn.taskPreview ?: continue // Older servers may lack the additive projection.
                assertTrue(preview.text.length <= 4000)
                if (preview.status != "unavailable") {
                    val link = turn.conversationLink!!
                    val group = api.conversation(link.conversationId)
                    assertTrue(group.tasks.any { it.task.id == link.taskId })
                    preview.runId?.let { runId -> assertTrue(group.tasks.any { task -> task.runs.any { it.id == runId && it.taskId == link.taskId } }) }
                    taskCards++
                }
            }
            for (turn in detail.messages.filter { it.role == "assistant" && !it.notice }) {
                val sessionId = conversationTurnSessionId(turn, detail)
                if (sessionId != null) {
                    bound++
                    val expected = detail.messages.filter { conversationTurnSessionId(it, detail) == sessionId }
                    assertEquals(conversationToolResults(expected), results[sessionId])
                }
            }
            detail.communicationSessionId?.let { sessionId ->
                try {
                    assertEquals(sessionId, api.getSession(sessionId).id)
                    available++
                } catch (failure: com.wand.app.data.WandApiException) {
                    // 服务重启后历史消息仍保留来源，但旧执行窗口可能已不存在；UI 必须禁用旧问答。
                    assertEquals("Only an explicitly missing historical execution may be unavailable", 404, failure.status)
                    historical++
                }
            }
            assertTrue(conversationInteractiveSessions(detail).all { it.isNotBlank() })
        }
        assertTrue("At least one real assistant message has a source session", bound > 0)
        // DM dispatch no longer needs a live communication channel; retained channels may all be historical.
        assertTrue("Retained communication sources must be checked as readable or explicitly historical", available + historical > 0)
        assertTrue("At least one real DM task card must retain its exact task/run", taskCards > 0)
        println("Installed IM projection: ${sample.size} conversations; $bound source-bound messages; $available current and $historical unavailable historical sessions; $taskCards exact task cards")
    }
}
