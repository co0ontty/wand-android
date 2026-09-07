package com.wand.app.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WandSnackbarTest {
    @Test
    fun failureAndDisconnectUseLongNotice() {
        assertTrue(wandNoticeIsLong("发送失败"))
        assertTrue(wandNoticeIsLong("加载更早消息失败"))
        assertTrue(wandNoticeIsLong("终端命令发送失败"))
        assertTrue(wandNoticeIsLong("连接已断开，正在重连"))
        assertTrue(wandNoticeIsLong("出现未知错误"))
    }

    @Test
    fun ordinaryStatusUsesShortNotice() {
        assertFalse(wandNoticeIsLong("已加入排队，等当前回复完成会自动发送。"))
        assertFalse(wandNoticeIsLong("会话已恢复"))
        assertFalse(wandNoticeIsLong("已上传 2 个附件"))
    }
}
