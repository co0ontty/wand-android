package com.wand.app.ui.screens

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * 会话顶栏身份（与 iOS `chatTopIdentity` / `ChatTopAttention` 同语义）：主标题优先任务名，
 * 小字是「员工名 · 目录缩写」，运行与异常只由真实状态位决定一个状态点。
 */
class ChatTopIdentityTest {
    @Test fun prefersTaskNameAndAbbreviatesDirectory() {
        val task = chatTopIdentity(" 图片自适应放大 ", "会话标题", "赛博虎妞", "/Users/co0ontty/Self/vibe_coding/wand")
        assertEquals("图片自适应放大", task.title)
        assertEquals("赛博虎妞 · wand", task.subtitle)

        val standalone = chatTopIdentity("   ", "会话标题", null, null)
        assertEquals("会话标题", standalone.title)
        assertEquals("", standalone.subtitle)

        assertEquals("当前会话", chatTopIdentity(null, "", "赛博虎妞", "/").title)
        assertEquals("赛博虎妞", chatTopIdentity(null, "会话", "赛博虎妞", "/").subtitle)
        assertEquals("赛博虎妞 · project", chatTopIdentity(null, "会话", "赛博虎妞", "C:\\Users\\dev\\project\\").subtitle)
    }

    @Test fun attentionFollowsRealStateNotDisplayText() {
        assertEquals(ChatTopAttention.Fault,
            chatTopAttention(connected = false, blocked = true, sending = true, responding = true, failed = false))
        assertEquals(ChatTopAttention.Fault,
            chatTopAttention(connected = true, blocked = true, sending = false, responding = false, failed = true))
        assertEquals(ChatTopAttention.NeedsYou,
            chatTopAttention(connected = true, blocked = true, sending = true, responding = true, failed = false))
        assertEquals(ChatTopAttention.Running,
            chatTopAttention(connected = true, blocked = false, sending = true, responding = false, failed = false))
        assertEquals(ChatTopAttention.Running,
            chatTopAttention(connected = true, blocked = false, sending = false, responding = true, failed = false))
        assertEquals(ChatTopAttention.Quiet,
            chatTopAttention(connected = true, blocked = false, sending = false, responding = false, failed = false))
    }

    @Test fun readbackKeepsTheStatusTheBarNoLongersShows() {
        assertEquals("修复图片显示，赛博虎妞 · wand，等待确认", chatTopReadback("修复图片显示", "赛博虎妞 · wand", "等待确认"))
        assertEquals("修复图片显示", chatTopReadback("修复图片显示", "", ""))
    }

    @Test fun sessionBarDrawsNoAvatarAndKeepsOneIdentityOwner() {
        val bar = File("src/main/java/com/wand/app/ui/screens/ChatTopIdentityBar.kt").readText()
        val screen = File("src/main/java/com/wand/app/ui/screens/ChatScreen.kt").readText()
        assertTrue(screen.contains("chatTopIdentity(taskName, store.snapshot?.displayTitle"))
        assertTrue(screen.contains("chatTopAttention("))
        assertFalse("会话页仍在画头像", screen.contains("TopChatCompanion("))
        assertTrue(bar.contains("contentDescription = chatTopReadback("))
        assertFalse(bar.contains("EmployeeAvatar("))
        // IM 私聊仍以头像为身份，顶栏头像版伙伴只能被那条链路使用。
        assertTrue(File("src/main/java/com/wand/app/ui/screens/ConversationScreens.kt").readText().contains("TopChatCompanion("))
    }
}
