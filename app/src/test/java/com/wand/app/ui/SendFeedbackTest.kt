package com.wand.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 提交按钮状态机（`docs/motion-design.md` 规则 3 / 4）的单测。
 * 这里锁的是「按钮此刻该画成什么」，不是像素——UI 只负责把结论画出来。
 */
class SendFeedbackTest {
    @Test
    fun submitWalksThroughSendingThenResultThenBackToIdle() {
        var phase = SendPhase.Idle

        phase = nextSendPhase(phase, SendEvent.Submit)
        assertEquals(SendPhase.Sending, phase)
        phase = nextSendPhase(phase, SendEvent.Accepted)
        assertEquals(SendPhase.Sent, phase)
        phase = nextSendPhase(phase, SendEvent.Dwell)
        assertEquals(SendPhase.Idle, phase)
    }

    @Test
    fun repeatedSubmitDoesNotRestartTheSendingPhase() {
        // 连点两下不应该把已经进入的发送中打回起点，否则按钮看起来像卡住不动。
        assertEquals(SendPhase.Sending, nextSendPhase(SendPhase.Sending, SendEvent.Submit))
    }

    @Test
    fun failureIsItsOwnResultStateNotASilentReset() {
        val failed = nextSendPhase(nextSendPhase(SendPhase.Idle, SendEvent.Submit), SendEvent.Rejected)

        assertEquals(SendPhase.Failed, failed)
        assertEquals(SendActionVisual.Failed, sendActionVisual(failed, turnRunning = false, hasDraft = false))
    }

    @Test
    fun visualPrefersThisSubmissionResultOverTheTurnState() {
        // 回合正在跑，但这一枚按钮刚发出去：先表达「已送达」，不要立刻跳成停止。
        assertEquals(
            SendActionVisual.Sent,
            sendActionVisual(SendPhase.Sent, turnRunning = true, hasDraft = false),
        )
        assertEquals(
            SendActionVisual.Sending,
            sendActionVisual(SendPhase.Sending, turnRunning = true, hasDraft = true),
        )
    }

    @Test
    fun visualFallsBackToSendStopOrBlockedWhenIdle() {
        assertEquals(
            SendActionVisual.Stop,
            sendActionVisual(SendPhase.Idle, turnRunning = true, hasDraft = false),
        )
        // 回合在跑 + 有草稿：仍要能发（排队），同时左侧另有停止键。
        assertEquals(
            SendActionVisual.Send,
            sendActionVisual(SendPhase.Idle, turnRunning = true, hasDraft = true),
        )
        assertEquals(
            SendActionVisual.Send,
            sendActionVisual(SendPhase.Idle, turnRunning = false, hasDraft = true),
        )
        assertEquals(
            SendActionVisual.Blocked,
            sendActionVisual(SendPhase.Idle, turnRunning = false, hasDraft = false),
        )
    }

    @Test
    fun dwellWindowsAreLongEnoughToReadAndShortEnoughToNotStick() {
        // 完成态只是「确认一眼」，不能久留；失败态要给读错误的时间。
        assertEquals(true, SEND_SENT_DWELL_MS in 400L..1_000L)
        assertEquals(true, SEND_FAILED_DWELL_MS >= SEND_SENT_DWELL_MS)
    }
}
