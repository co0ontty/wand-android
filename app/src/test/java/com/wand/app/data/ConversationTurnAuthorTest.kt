package com.wand.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 群聊署名（团队运行 relay 会话）：安卓要靠 author/notice 把「负责人（主任务）」
 * 和「成员（子任务）」分开显示，缺失时按普通助手回复降级。
 */
class ConversationTurnAuthorTest {
    @Test
    fun parsesTeamChatNoticeWithAuthor() {
        val turn = ConversationTurn.parse(
            JSONObject()
                .put("role", "assistant")
                .put("notice", true)
                .put(
                    "author",
                    JSONObject()
                        .put("id", "m_dev")
                        .put("name", "实现者")
                        .put("provider", "qoder")
                        .put("sessionId", "s1"),
                )
                .put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "实现者 开始「T1」")))
                .put("createdAt", "2026-09-27T01:00:00.000Z"),
        )

        assertTrue(turn.notice)
        assertEquals("实现者", turn.author?.name)
        assertEquals("qoder", turn.author?.provider)
        assertEquals("s1", turn.author?.sessionId)
        assertFalse(turn.author?.leader ?: true)
    }

    @Test
    fun marksTheLeaderAuthor() {
        val turn = ConversationTurn.parse(
            JSONObject()
                .put("role", "assistant")
                .put("author", JSONObject().put("name", "负责人").put("leader", true))
                .put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "本轮派 T1"))),
        )

        assertTrue(turn.author?.leader == true)
        assertFalse(turn.notice)
    }

    @Test
    fun plainSessionsStayWithoutAuthor() {
        val turn = ConversationTurn.parse(
            JSONObject()
                .put("role", "assistant")
                .put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "普通回复"))),
        )

        assertNull(turn.author)
        assertFalse(turn.notice)
    }

    @Test
    fun authorWithoutNameIsIgnored() {
        val turn = ConversationTurn.parse(
            JSONObject()
                .put("role", "assistant")
                .put("author", JSONObject().put("id", "m_x").put("leader", true))
                .put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "没有名字")))
        )

        assertNull(turn.author)
    }
}
