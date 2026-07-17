package com.wand.app.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * 系统语音识别引擎：android.speech.SpeechRecognizer。
 *
 * - API 31+ 且系统有端侧识别服务时用 createOnDeviceSpeechRecognizer（音频不出设备）；
 *   否则用默认识别器并带上 EXTRA_PREFER_OFFLINE 提示。
 * - 国产无 GMS 设备上 isRecognitionAvailable 往往直接 false（OPPO）或挂着假服务（华为），
 *   [isUsable] 返回 false 时上层应改走 sherpa-onnx 本地模型。
 *
 * SpeechRecognizer 要求在主线程创建和调用，回调也在主线程，无需再切线程。
 */
class SystemSpeechEngine(private val context: Context) : SpeechEngine {
    override val label: String =
        if (onDeviceAvailable(context)) "系统端侧识别" else "系统识别"

    private var recognizer: SpeechRecognizer? = null
    private var listener: SpeechEngine.Listener? = null
    /** 最近一次 partial，onResults 偶发空结果时兜底用。 */
    private var lastText = ""
    private var cancelled = false

    companion object {
        /** 系统识别是否可用（不可用时上层换 sherpa 路径）。 */
        fun isUsable(context: Context): Boolean =
            SpeechRecognizer.isRecognitionAvailable(context) || onDeviceAvailable(context)

        fun onDeviceAvailable(context: Context): Boolean =
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }

    override fun start(listener: SpeechEngine.Listener) {
        this.listener = listener
        lastText = ""
        cancelled = false
        destroyRecognizer()

        val recognizer = try {
            if (onDeviceAvailable(context)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (e: Exception) {
            listener.onError("系统语音识别启动失败：${e.message}")
            return
        }
        this.recognizer = recognizer

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle?) {
                val text = firstResult(partialResults) ?: return
                if (text.isNotEmpty()) {
                    lastText = text
                    this@SystemSpeechEngine.listener?.onPartial(text)
                }
            }

            override fun onResults(results: Bundle?) {
                if (cancelled) return
                val text = firstResult(results) ?: lastText
                this@SystemSpeechEngine.listener?.onFinal(text)
            }

            override fun onError(error: Int) {
                if (cancelled) return
                // 松手后的「无匹配/超时」不算错误：把已有 partial 当最终结果交回。
                if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    this@SystemSpeechEngine.listener?.onFinal(lastText)
                    return
                }
                this@SystemSpeechEngine.listener?.onError(describeError(error))
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, preferredLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        recognizer.startListening(intent)
    }

    override fun finish() {
        // stopListening 后系统会尽快回 onResults / onError(NO_MATCH)。
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
            listener?.onFinal(lastText)
        }
    }

    override fun cancel() {
        cancelled = true
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }
        lastText = ""
    }

    override fun destroy() {
        listener = null
        destroyRecognizer()
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    /** 跟系统语言走；中文环境统一报 zh-CN（端侧听写模型按语言包匹配）。 */
    private fun preferredLanguageTag(): String {
        val locale = Locale.getDefault()
        return if (locale.language.equals("zh", ignoreCase = true)) "zh-CN" else locale.toLanguageTag()
    }

    private fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "录音失败，请重试"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麦克风权限"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络错误，识别服务不可达"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务忙，请稍后重试"
        SpeechRecognizer.ERROR_SERVER -> "识别服务出错"
        SpeechRecognizer.ERROR_CLIENT -> "识别已中断"
        12, 13 -> "系统未下载当前语言的离线语音包" // ERROR_LANGUAGE_NOT_SUPPORTED / _UNAVAILABLE (API 33)
        else -> "语音识别失败（$error）"
    }
}
