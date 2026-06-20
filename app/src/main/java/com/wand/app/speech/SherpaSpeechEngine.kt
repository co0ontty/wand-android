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
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import java.io.File
import kotlin.concurrent.thread

/**
 * sherpa-onnx 端侧语音识别引擎：AudioRecord 16 kHz 采音 → 流式解码。
 *
 * 完全离线运行，是无谷歌服务设备（国产 ROM）的主路径；模型由 [SttModelManager]
 * 按需下载到 filesDir（中文 CTC 小模型 / 中英混合 transducer 大模型，设置页可切换），
 * 识别器常驻复用（首次加载约 1~3 秒，之后按住即用）。
 *
 * 中英混合大模型的两个针对性处理：
 * - **编程词汇热词增强**：transducer 用 modified_beam_search 解码，注入一份
 *   程序员常用词热词表（git/commit/docker/npm…），提升代码相关英文词的命中率；
 * - **英文小写化**：该模型英文 BPE 词表全大写（输出形如 "GIT COMMIT"），
 *   口述代码场景小写才是常态，统一转小写。
 *
 * 按住说话不需要端点检测（手指就是端点）：enableEndpoint = false，
 * 松手后垫 0.5 s 静音把模型 lookahead 冲出来再收尾解码。
 */
class SherpaSpeechEngine(private val context: Context) : SpeechEngine {
    override val label: String =
        SttModelManager.activeModel(context)?.shortLabel ?: "本地端侧模型"

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

        /** 热词加权分（sherpa-onnx 推荐区间 1.0~2.5，越大越偏向热词）。 */
        private const val HOTWORDS_SCORE = 1.8f

        /**
         * 程序员常用词热词表（中英混合大模型专用）。
         * 必须全大写：要与模型的英文 BPE 词表（全大写）对齐才能编码成热词图。
         * 支持多词短语（如 PULL REQUEST）。
         */
        private val DEV_HOTWORDS = listOf(
            // git / 版本控制
            "GIT", "COMMIT", "PUSH", "PULL", "MERGE", "REBASE", "BRANCH", "CHECKOUT",
            "STASH", "DIFF", "CLONE", "TAG", "RESET", "REVERT", "CHERRY PICK",
            "PULL REQUEST", "ISSUE", "GITHUB", "GITLAB", "README", "CHANGELOG",
            // 包管理 / 构建 / 前端
            "NPM", "NODE", "PNPM", "YARN", "VITE", "WEBPACK", "ESBUILD", "ESLINT",
            "BUILD", "COMPILE", "LINT", "FORMAT", "BUNDLE", "RELEASE", "DEPLOY",
            "REACT", "VUE", "COMPONENT", "RENDER", "PROPS", "HOOK",
            // 语言 / 平台
            "TYPESCRIPT", "JAVASCRIPT", "PYTHON", "KOTLIN", "SWIFT", "JAVA", "RUST",
            "ANDROID", "IOS", "MACOS", "WINDOWS", "LINUX", "UBUNTU", "CHROME",
            // 运维 / 容器
            "DOCKER", "KUBERNETES", "NGINX", "SSH", "SUDO", "BASH", "SHELL",
            "TERMINAL", "SCRIPT", "SERVER", "CLIENT", "SERVICE", "SYSTEMD",
            // 网络 / 数据
            "API", "JSON", "YAML", "HTML", "CSS", "HTTP", "HTTPS", "URL",
            "TOKEN", "COOKIE", "SESSION", "WEBSOCKET", "REQUEST", "RESPONSE",
            "HEADER", "ENDPOINT", "ROUTE", "MIDDLEWARE", "WEBHOOK", "OAUTH",
            "DATABASE", "SQL", "SQLITE", "MYSQL", "REDIS", "CACHE", "QUEUE",
            // 调试 / 质量
            "BUG", "DEBUG", "ERROR", "WARNING", "EXCEPTION", "CRASH", "TIMEOUT",
            "RETRY", "LOG", "TEST", "UNIT TEST", "REFACTOR", "REVIEW", "ROLLBACK",
            "THREAD", "ASYNC", "AWAIT", "PROMISE", "CALLBACK", "WORKFLOW", "PIPELINE",
            // AI / 本项目生态
            "CLAUDE", "CLAUDE CODE", "WAND", "AGENT", "PROMPT", "MODEL", "LLM",
            "GPT", "ANTHROPIC", "OPENAI", "MCP", "SDK", "CLI", "IDE", "VSCODE",
            "XCODE", "GRADLE", "APK", "ONNX",
        )

        /** 匹配纯大写英文词（含撇号），用于中英模型输出的小写化。 */
        private val UPPER_WORD = Regex("[A-Z][A-Z']*")

        /** 识别器跨实例常驻（加载慢、内存可复用）；模型目录变化时重建。 */
        @Volatile
        private var sharedRecognizer: OnlineRecognizer? = null
        private var loadedDir: String? = null
        private var loadedType: SttModelManager.ModelType? = null

        @Synchronized
        private fun obtainRecognizer(context: Context): OnlineRecognizer {
            val model = SttModelManager.activeModel(context)
                ?: throw IllegalStateException("语音模型未就绪")
            val dir = SttModelManager.modelDir(context, model)
            sharedRecognizer?.let { if (loadedDir == dir.absolutePath) return it }
            sharedRecognizer?.release()
            sharedRecognizer = null
            val recognizer = OnlineRecognizer(
                assetManager = null, // 传 null 走 newFromFile：从绝对路径加载
                config = buildConfig(context, model, dir),
            )
            sharedRecognizer = recognizer
            loadedDir = dir.absolutePath
            loadedType = model.type
            return recognizer
        }

        private fun buildConfig(
            context: Context,
            model: SttModelManager.SttModel,
            dir: File,
        ): OnlineRecognizerConfig = when (model.type) {
            SttModelManager.ModelType.ZIPFORMER2_CTC -> OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    zipformer2Ctc = OnlineZipformer2CtcModelConfig(
                        model = File(dir, "model.int8.onnx").absolutePath,
                    ),
                    tokens = File(dir, "tokens.txt").absolutePath,
                    numThreads = 2,
                    provider = "cpu",
                ),
                enableEndpoint = false,
            )

            SttModelManager.ModelType.TRANSDUCER -> OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(dir, "encoder-epoch-99-avg-1.int8.onnx").absolutePath,
                        decoder = File(dir, "decoder-epoch-99-avg-1.onnx").absolutePath,
                        joiner = File(dir, "joiner-epoch-99-avg-1.int8.onnx").absolutePath,
                    ),
                    tokens = File(dir, "tokens.txt").absolutePath,
                    // 大模型 encoder 更重，多给两个线程保住实时率。
                    numThreads = 4,
                    provider = "cpu",
                    modelType = "zipformer",
                    // 热词按「中文按字 + 英文按 BPE」编码，bpe.vocab 随模型一起下载。
                    modelingUnit = "cjkchar+bpe",
                    bpeVocab = File(dir, "bpe.vocab").absolutePath,
                ),
                // 热词只在 modified_beam_search 下生效（greedy 不查热词图）。
                decodingMethod = "modified_beam_search",
                hotwordsFile = ensureHotwordsFile(context).absolutePath,
                hotwordsScore = HOTWORDS_SCORE,
                enableEndpoint = false,
            )
        }

        /** 把内置热词表落盘（内容变化时覆写），返回文件路径。 */
        private fun ensureHotwordsFile(context: Context): File {
            val file = File(context.filesDir, "asr/hotwords-dev.txt")
            val content = DEV_HOTWORDS.joinToString("\n")
            try {
                if (!file.exists() || file.readText() != content) {
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                }
            } catch (_: Throwable) {
            }
            return file
        }

        /**
         * 输出规整：中英模型的英文全大写 → 小写（口述代码场景小写是常态，
         * 大写形态如 API/CI 无法可靠区分，统一小写换可读性）。中文模型原样返回。
         */
        private fun normalizeText(text: String): String {
            if (loadedType != SttModelManager.ModelType.TRANSDUCER) return text
            return UPPER_WORD.replace(text) { it.value.lowercase() }
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
            } catch (e: Exception) {
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
                    val text = normalizeText(recognizer.getResult(stream).text)
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
                val finalText = normalizeText(recognizer.getResult(stream).text)
                main.post {
                    if (phase != PHASE_CANCELLED) listener.onFinal(finalText)
                }
            } catch (e: Exception) {
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
