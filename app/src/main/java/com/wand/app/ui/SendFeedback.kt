package com.wand.app.ui

/**
 * 提交动作的状态机（规范见 `docs/motion-design.md` 规则 3/4）。
 *
 * 一次提交要依次经过「发送中 → 已送达 / 失败」再回到常态，全程同一个按钮、同一个位置，
 * 不允许换成弹窗、Toast 或整行替换。这里只放纯逻辑，UI 只负责把状态画出来。
 */
enum class SendPhase {
    /** 常态：按草稿有无决定是「发送」还是「不可用」。 */
    Idle,

    /** 请求已发出、还没等到服务端确认。 */
    Sending,

    /** 服务端已接收这一条。 */
    Sent,

    /** 发送失败（网络 / 服务端拒绝）。 */
    Failed,
}

/** 完成态停留时长：要让人看见，但不能黏住下一次输入。 */
const val SEND_SENT_DWELL_MS = 720L

/** 失败态停留更久：出错信息需要被读到。 */
const val SEND_FAILED_DWELL_MS = 1_500L

/**
 * 按钮此刻应该画成什么。
 * 顺序即优先级：先表达本次提交的结果（进行中 / 已送达 / 失败），
 * 再表达会话的常态（回合进行中 = 停止，有草稿 = 发送，其余 = 不可用）。
 */
enum class SendActionVisual { Send, Sending, Sent, Failed, Stop, Blocked }

fun sendActionVisual(
    phase: SendPhase,
    turnRunning: Boolean,
    hasDraft: Boolean,
): SendActionVisual = when {
    phase == SendPhase.Sending -> SendActionVisual.Sending
    phase == SendPhase.Sent -> SendActionVisual.Sent
    phase == SendPhase.Failed -> SendActionVisual.Failed
    // 回合进行中且没有新草稿：这枚按钮的语义变成「停止」，与「发送」共用同一个位置。
    turnRunning && !hasDraft -> SendActionVisual.Stop
    hasDraft -> SendActionVisual.Send
    else -> SendActionVisual.Blocked
}

/** 状态机推进：提交后进入 Sending，成功进 Sent、失败进 Failed，停留一段时间后回 Idle。 */
fun nextSendPhase(current: SendPhase, event: SendEvent): SendPhase = when (event) {
    SendEvent.Submit -> if (current == SendPhase.Sending) current else SendPhase.Sending
    SendEvent.Accepted -> SendPhase.Sent
    SendEvent.Rejected -> SendPhase.Failed
    SendEvent.Dwell -> SendPhase.Idle
}

enum class SendEvent { Submit, Accepted, Rejected, Dwell }
