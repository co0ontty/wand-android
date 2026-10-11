package com.wand.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Only installed, offline platform voices. Never downloads or enables a cloud voice. */
class LocalTextSpeaker(context: Context, private val onInterruption: () -> Unit) : TextSpeaker {
    override var isSpeaking by mutableStateOf(false)
        private set
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { change -> if (change < 0) { stop(); onInterruption() } }.build()
    private var initialized = false
    private var generation = 0
    private var completion: (() -> Unit)? = null
    private var failure: ((String) -> Unit)? = null
    private var pending: (() -> Unit)? = null
    private var destroyed = false
    private var tts: TextToSpeech? = null
    private fun initialize() {
        if (tts != null || destroyed) return
        tts = TextToSpeech(appContext) { status ->
            main.post {
                if (destroyed) return@post
                initialized = status == TextToSpeech.SUCCESS
                if (initialized) pending?.invoke() else failure?.invoke("系统文字转语音不可用，回复已保留为文本")
                pending = null
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { main.post { if (utteranceId == generation.toString()) isSpeaking = true } }
            override fun onDone(utteranceId: String?) { main.post { if (utteranceId == generation.toString()) { isSpeaking = false; audio.abandonAudioFocusRequest(focus); completion?.invoke() } } }
            @Deprecated("Platform callback")
            override fun onError(utteranceId: String?) { main.post { if (utteranceId == generation.toString()) { isSpeaking = false; audio.abandonAudioFocusRequest(focus); failure?.invoke("播报失败，回复已保留为文本") } } }
        })
    }
    override fun speak(text: String, onDone: () -> Unit, onError: (String) -> Unit) {
        stop()
        completion = onDone
        failure = onError
        val token = generation
        initialize()
        val operation = {
            val offline = tts?.voices?.filter { !it.isNetworkConnectionRequired && it.locale.language == Locale.SIMPLIFIED_CHINESE.language }
                ?.sortedByDescending { it.quality }?.firstOrNull()
            when {
                offline == null -> onError("未安装中文端侧语音，请在系统语音设置中配置；文本回复仍可查看")
                audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> onError("音频正被其他应用使用，请稍后继续")
                else -> {
                    tts?.voice = offline
                    tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    if (token == generation && tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, token.toString()) != TextToSpeech.SUCCESS) onError("播报启动失败，回复已保留为文本")
                }
            }
        }
        if (initialized) operation() else {
            pending = operation
            main.postDelayed({ if (token == generation && !initialized && pending != null) { pending = null; onError("系统语音初始化超时，请稍后继续") } }, 5_000)
        }
    }
    override fun stop() { generation++; isSpeaking = false; pending = null; completion = null; failure = null; tts?.stop(); audio.abandonAudioFocusRequest(focus) }
    override fun destroy() { destroyed = true; stop(); tts?.shutdown(); tts = null }
}
