package com.wand.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/** Session-scoped focus for capture. Interruptions never restart the microphone. */
class CallSpeechEngine(context: Context, private val delegate: SpeechEngine, private val interrupted: () -> Unit) : SpeechEngine {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { if (it < 0) { delegate.cancel(); interrupted() } }.build()
    override val label get() = delegate.label
    override val finalTimeoutMs get() = delegate.finalTimeoutMs
    override fun start(listener: SpeechEngine.Listener) {
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            listener.onError("麦克风音频正被其他应用使用，请稍后继续")
            return
        }
        delegate.start(listener)
    }
    override fun finish() { delegate.finish() }
    override fun cancel() { delegate.cancel(); audio.abandonAudioFocusRequest(focus) }
    override fun destroy() { delegate.destroy(); audio.abandonAudioFocusRequest(focus) }
}
