package com.wand.app.ui.screens

import com.wand.app.data.AiTeamRunDetail
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 已安装服务的只读样例解析验收，不是设备视觉验收。
 * 样例保留真实字段形状与历史开工模板，姓名、任务正文、标识和时间均已脱敏。
 */
class TeamChatServiceSampleTest {
    private fun samples(): List<AiTeamRunDetail> {
        val resource = checkNotNull(javaClass.getResourceAsStream("/team-chat-service-samples.json"))
        val cases = resource.bufferedReader().use { JSONObject(it.readText()) }.getJSONArray("cases")
        return (0 until cases.length()).map { index ->
            checkNotNull(AiTeamRunDetail.parse(cases.getJSONObject(index).getJSONObject("detail")))
        }
    }

    @Test
    fun historicalStartsBecomeTheirAuthorsMessagesWithoutChangingFeedOrder() {
        var converted = 0
        var preserved = 0
        for (detail in samples()) {
            val originals = detail.chatTurns.toList()
            val displayed = originals.map(::teamChatDisplayTurn)
            assertEquals(8, originals.size)
            assertEquals(originals.size, displayed.size)
            assertEquals(originals.map { it.createdAt }, displayed.map { it.createdAt })
            assertEquals(originals.map { it.author }, displayed.map { it.author })
            originals.zip(displayed).forEach { (original, display) ->
                val text = chatTurnText(original)
                val prefix = "${original.author?.name} 开始「"
                if (original.notice && text.startsWith(prefix) && text.endsWith("」")) {
                    converted++
                    assertNotNull(original.author)
                    assertFalse(display.notice)
                    assertEquals(original.role, display.role)
                    assertEquals(original.author, display.author)
                    assertEquals(
                        "我开始处理「${text.removePrefix(prefix).removeSuffix("」")}」这项工作。",
                        chatTurnText(display),
                    )
                } else {
                    preserved++
                    // 正式消息与其他协作提示沿用服务端回合，不能被兼容转换误伤。
                    assertSame(original, display)
                }
            }
            assertEquals(originals, detail.chatTurns)
        }
        assertEquals(14, converted)
        assertEquals(10, preserved)
    }

    @Test
    fun terminalServiceSamplesDoNotShowActivities() {
        val terminal = samples().filter { it.run.status in setOf("done", "stopped") }
        assertEquals(2, terminal.size)
        terminal.forEach { assertTrue(teamChatActivities(it).isEmpty()) }
    }

    @Test
    fun runningServiceSampleShowsItsWorkingMemberAndSession() {
        val detail = samples().single { it.run.status == "running" }
        val workingStep = detail.steps.single {
            it.status == "running" && detail.memberStates[it.sessionId] == "working"
        }
        val activity = teamChatActivities(detail).single { it.stepId == workingStep.id }
        assertEquals("working", activity.state)
        assertEquals(workingStep.title, activity.title)
        assertEquals(workingStep.sessionId, activity.sessionId)
        assertEquals(
            detail.run.team!!.members.single { it.id == workingStep.memberId }.name,
            activity.memberName,
        )
    }
}
