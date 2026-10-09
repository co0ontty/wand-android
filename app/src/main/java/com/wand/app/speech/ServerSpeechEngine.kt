package com.wand.app.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.wand.app.data.WandApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** Capture on device; only the selected Wand endpoint receives audio after release. No cloud fallback. */
class ServerSpeechEngine(private val api: WandApi) : SpeechEngine {
    override val label = "服务端识别"
    override val finalTimeoutMs = 127_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recording = AtomicBoolean(false)
    private var released = false
    private var cancelled = false
    private var job: kotlinx.coroutines.Job? = null

    @SuppressLint("MissingPermission") // Controller requests RECORD_AUDIO before starting any engine.
    override fun start(listener: SpeechEngine.Listener) {
        cancelled = false; released = false; recording.set(true)
        job = scope.launch {
            try {
                val status = api.speechStatus()
                coroutineContext.ensureActive()
                if (!status.ready) throw IllegalStateException(status.reason ?: "服务端识别未就绪，请检查服务器语音设置")
                if (released || cancelled) { listener.onFinal(""); return@launch }
                val pcm = withContext(Dispatchers.IO) {
                    val minimum = AudioRecord.getMinBufferSize(SpeechAudio.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    if (minimum <= 0) throw IllegalStateException("设备不支持 16 kHz 麦克风录音")
                    val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SpeechAudio.SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 4096))
                    val bytes = ByteArrayOutputStream()
                    try {
                        if (audio.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("无法打开麦克风")
                        coroutineContext.ensureActive()
                        audio.startRecording()
                        val buffer = ByteArray(4096)
                        val started = System.nanoTime()
                        while (recording.get()) {
                            coroutineContext.ensureActive()
                            if (System.nanoTime() - started > 60_000_000_000L) throw IllegalStateException("语音最长 60 秒，请分段录音")
                            val count = audio.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                            if (count < 0) throw IllegalStateException("麦克风录音失败")
                            if (count > 0) {
                                if (bytes.size() + count > SpeechAudio.MAX_PCM_BYTES) throw IllegalStateException("语音最长 60 秒，请分段录音")
                                bytes.write(buffer, 0, count)
                            } else delay(10)
                        }
                        bytes.toByteArray()
                    } finally {
                        runCatching { audio.stop() }; audio.release()
                    }
                }
                coroutineContext.ensureActive()
                if (cancelled) return@launch
                val text = withTimeout(125_000) { api.transcribeSpeech(SpeechAudio.wav(pcm)) }
                if (!cancelled) listener.onFinal(text)
            } catch (e: CancellationException) {
                // Page/navigation cancellation must never revive a draft or display a stale error.
                if (!cancelled && e is kotlinx.coroutines.TimeoutCancellationException) listener.onError("服务端识别超时，请选择较小模型后重试")
            } catch (e: Exception) {
                if (!cancelled) listener.onError(e.message ?: "服务端语音识别失败")
            } finally { recording.set(false) }
        }
    }
    override fun finish() { released = true; recording.set(false) }
    override fun cancel() { cancelled = true; recording.set(false); job?.cancel(); job = null }
    override fun destroy() { cancel(); scope.cancel() }
}
