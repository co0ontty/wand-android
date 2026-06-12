package com.wand.app.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/**
 * 「按住说话」控制器：挑选引擎 + 管理一次按住会话的 UI 状态（Compose state）。
 *
 * 引擎优先级：
 * 1. sherpa-onnx 本地模型（已下载）—— 确定性端侧，不依赖厂商服务；
 * 2. 系统 SpeechRecognizer（GMS 设备可用，优先离线）；
 * 3. 都没有 → 弹模型下载对话框（showModelDialog）。
 *
 * 交互协议对齐 Web 端 voice-btn / iOS ChatView：
 * 按住录音 → transcript 覆盖式更新 → 上滑取消 → 松手等 final（限时 1.5 s 兜底）
 * → 非空文本回调 commit（追加进输入框草稿）。
 */
class VoiceInputController(private val context: Context) {
    var pressed by mutableStateOf(false)
        private set
    var canceling by mutableStateOf(false)
        private set
    var transcript by mutableStateOf("")
        private set
    var engineLabel by mutableStateOf("")
        private set

    /** 无可用引擎时置 true，ChatScreen 据此弹模型下载对话框。 */
    var showModelDialog by mutableStateOf(false)

    var onToast: ((String) -> Unit)? = null

    private var engine: SpeechEngine? = null
    private var commit: ((String) -> Unit)? = null
    private var awaitingFinal = false
    private val main = Handler(Looper.getMainLooper())
    private var finalTimeout: Runnable? = null

    /** 松手后等 final 的最长时间，超时按当前 partial 提交（对齐 iOS finalResultGrace）。 */
    private val finalGraceMs = 1_500L

    init {
        SttModelManager.refresh(context)
        SherpaSpeechEngine.warmUp(context)
    }

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** 手指按下（已确保有麦克风权限）。 */
    fun beginPress(onCommit: (String) -> Unit) {
        if (pressed) return
        val chosen: SpeechEngine? = when {
            SttModelManager.isReady(context) -> SherpaSpeechEngine(context)
            SystemSpeechEngine.isUsable(context) -> SystemSpeechEngine(context)
            else -> null
        }
        if (chosen == null) {
            showModelDialog = true
            return
        }
        engine?.destroy()
        engine = chosen
        commit = onCommit
        pressed = true
        canceling = false
        transcript = ""
        awaitingFinal = false
        engineLabel = chosen.label
        chosen.start(object : SpeechEngine.Listener {
            override fun onPartial(text: String) {
                if (pressed || awaitingFinal) transcript = text
            }

            override fun onFinal(text: String) {
                deliver(text.ifBlank { transcript })
            }

            override fun onError(message: String) {
                onToast?.invoke(message)
                pressed = false
                canceling = false
                resetSession()
            }
        })
    }

    /** 手指移动：是否进入「松开取消」态。 */
    fun updateCancel(cancel: Boolean) {
        if (pressed) canceling = cancel
    }

    /** 手指松开：取消态丢弃，否则限时等 final 后提交。 */
    fun endPress() {
        if (!pressed) return
        pressed = false
        val cancelled = canceling
        canceling = false
        val engine = engine ?: return
        if (cancelled) {
            engine.cancel()
            resetSession()
            return
        }
        awaitingFinal = true
        engine.finish()
        val fallback = Runnable { deliver(transcript) }
        finalTimeout = fallback
        main.postDelayed(fallback, finalGraceMs)
    }

    fun destroy() {
        engine?.destroy()
        engine = null
        pressed = false
        canceling = false
        resetSession()
    }

    /** final 到达或限时兜底触发，二者只生效一次。 */
    private fun deliver(text: String) {
        if (!awaitingFinal) return
        awaitingFinal = false
        val onCommit = commit
        resetSession()
        val clean = text.trim()
        if (clean.isNotEmpty()) onCommit?.invoke(clean)
    }

    private fun resetSession() {
        finalTimeout?.let { main.removeCallbacks(it) }
        finalTimeout = null
        awaitingFinal = false
        transcript = ""
        commit = null
    }
}
