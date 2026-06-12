package com.wand.app.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import java.io.File
import kotlin.concurrent.thread

/**
 * sherpa-onnx 端侧语音识别引擎：AudioRecord 16 kHz 采音 → 流式 Zipformer-CTC 解码。
 *
 * 完全离线运行，是无谷歌服务设备（国产 ROM）的主路径；模型由 [SttModelManager]
 * 按需下载到 filesDir，识别器常驻复用（首次加载约 1~2 秒，之后按住即用）。
 *
 * 按住说话不需要端点检测（手指就是端点）：enableEndpoint = false，
 * 松手后垫 0.5 s 静音把模型 lookahead 冲出来再收尾解码。
 */
class SherpaSpeechEngine(private val context: Context) : SpeechEngine {
    override val label: String = "本地端侧模型"

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var listener: SpeechEngine.Listener? = null

    @Volatile
    private var phase = PHASE_IDLE

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val PHASE_IDLE = 0
        private const val PHASE_RECORDING = 1
        private const val PHASE_FINISHING = 2
        private const val PHASE_CANCELLED = 3

        /** 识别器跨实例常驻（加载慢、内存可复用）；模型目录变化时重建。 */
        @Volatile
        private var sharedRecognizer: OnlineRecognizer? = null
        private var loadedDir: String? = null

        @Synchronized
        private fun obtainRecognizer(context: Context): OnlineRecognizer {
            val dir = SttModelManager.modelDir(context)
            sharedRecognizer?.let { if (loadedDir == dir.absolutePath) return it }
            sharedRecognizer?.release()
            sharedRecognizer = null
            val recognizer = OnlineRecognizer(
                assetManager = null, // 传 null 走 newFromFile：从绝对路径加载
                config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        zipformer2Ctc = OnlineZipformer2CtcModelConfig(
                            model = File(dir, SttModelManager.MODEL_FILE).absolutePath,
                        ),
                        tokens = File(dir, SttModelManager.TOKENS_FILE).absolutePath,
                        numThreads = 2,
                        provider = "cpu",
                    ),
                    enableEndpoint = false,
                ),
            )
            sharedRecognizer = recognizer
            loadedDir = dir.absolutePath
            return recognizer
        }

        /** 预热：模型就绪后后台加载一次，首次按住不卡顿。 */
        fun warmUp(context: Context) {
            val appContext = context.applicationContext
            thread(name = "wand-stt-warmup") {
                try {
                    if (SttModelManager.isReady(appContext)) obtainRecognizer(appContext)
                } catch (_: Throwable) {
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun start(listener: SpeechEngine.Listener) {
        this.listener = listener
        phase = PHASE_RECORDING
        thread(name = "wand-stt") {
            val recognizer = try {
                obtainRecognizer(context)
            } catch (e: Throwable) {
                phase = PHASE_IDLE
                postError("加载语音模型失败：${e.message}")
                return@thread
            }
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer * 2, SAMPLE_RATE / 2),
                )
            } catch (_: Throwable) {
                null
            }
            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                record?.release()
                phase = PHASE_IDLE
                postError("无法打开麦克风")
                return@thread
            }
            val stream = recognizer.createStream()
            try {
                record.startRecording()
                val buffer = ShortArray(SAMPLE_RATE / 10) // 100 ms 一读
                var lastText = ""
                while (phase == PHASE_RECORDING) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n <= 0) continue
                    val samples = FloatArray(n) { buffer[it] / 32768.0f }
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    val text = recognizer.getResult(stream).text
                    if (text != lastText) {
                        lastText = text
                        main.post {
                            if (phase != PHASE_CANCELLED) listener.onPartial(text)
                        }
                    }
                }
                try {
                    record.stop()
                } catch (_: Throwable) {
                }
                if (phase == PHASE_CANCELLED) return@thread
                // 垫 0.5 s 静音冲掉流式模型的右侧上下文，再收尾解码出最终文本。
                stream.acceptWaveform(FloatArray(SAMPLE_RATE / 2), SAMPLE_RATE)
                stream.inputFinished()
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                val finalText = recognizer.getResult(stream).text
                main.post {
                    if (phase != PHASE_CANCELLED) listener.onFinal(finalText)
                }
            } catch (e: Throwable) {
                postError("识别失败：${e.message}")
            } finally {
                try {
                    record.release()
                } catch (_: Throwable) {
                }
                try {
                    stream.release()
                } catch (_: Throwable) {
                }
                phase = PHASE_IDLE
            }
        }
    }

    override fun finish() {
        if (phase == PHASE_RECORDING) phase = PHASE_FINISHING
    }

    override fun cancel() {
        phase = PHASE_CANCELLED
    }

    override fun destroy() {
        phase = PHASE_CANCELLED
        listener = null
    }

    private fun postError(message: String) {
        main.post { listener?.onError(message) }
    }
}
