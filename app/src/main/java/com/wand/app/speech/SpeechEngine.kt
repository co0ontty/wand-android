package com.wand.app.speech

/**
 * 「按住说话」语音识别引擎抽象。
 *
 * 一次按住 = 一次会话：start() 开始采音转写 → 期间 onPartial 持续给
 * 「当前最优完整文本」（覆盖式，非增量，与 Web 端 updateVoiceTranscript /
 * iOS SpeechRecognizerService 协议一致）→ 松手 finish() 后引擎尽快回 onFinal
 * → 上滑取消走 cancel() 直接丢弃。
 *
 * 实现：
 * - [SystemSpeechEngine] —— 系统 android.speech.SpeechRecognizer（GMS 设备可用，优先离线）
 * - [SherpaSpeechEngine] —— sherpa-onnx 端侧开源模型（国产无谷歌服务设备的主路径，需先下载模型）
 */
interface SpeechEngine {
    /** 引擎展示名（气泡里提示用户走的是哪条路径）。 */
    val label: String

    /** 开始一次会话。所有回调都必须发生在主线程。 */
    fun start(listener: Listener)

    /** 松手：停止采音，引擎应尽快回 onFinal（可以为空文本）。 */
    fun finish()

    /** 上滑取消：丢弃本次会话，不再回调 onFinal。 */
    fun cancel()

    /** 释放底层资源（离开聊天页时调用）。 */
    fun destroy()

    interface Listener {
        /** 覆盖式完整文本（每次都是整段当前结果）。 */
        fun onPartial(text: String)

        /** 会话结束的最终文本；可能为空串（没识别出内容）。 */
        fun onFinal(text: String)

        /** 出错（文案直接面向用户展示）；出错后本次会话终止。 */
        fun onError(message: String)
    }
}
