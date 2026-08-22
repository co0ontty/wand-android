package com.wand.app.speech

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 端侧语音模型下载与就绪状态管理（sherpa-onnx，多模型注册表）。
 *
 * 内置两个可选模型：
 * - 中文小模型（流式 Zipformer-CTC，约 26 MB）：默认，省内存省存储；
 * - 中英混合大模型（流式 Zipformer transducer，约 190 MB）：
 *   由 k2fsa 中英混说（code-switching）模型导出，适合中英夹杂的口述
 *   （配合 SherpaSpeechEngine 的程序员热词增强，对代码词汇更友好）。
 *
 * 模型文件落在 `filesDir/asr/<模型目录>/`，下载完成写 `.complete` 标记。
 * 下载源是 HuggingFace 裸文件（不解压归档），国内镜像 hf-mirror.com 优先。
 *
 * 状态用 Compose snapshot state 暴露，对话框/设置页直接观察。
 * 下载放在进程级单例 scope 里：用户中途离开页面不打断下载。
 */
object SttModelManager {
    enum class ModelType { ZIPFORMER2_CTC, TRANSDUCER }

    data class SttModel(
        val id: String,
        /** 上游仓库名 = 本地目录名，方便溯源。 */
        val dirName: String,
        val label: String,
        /** 转写气泡里的引擎短标签。 */
        val shortLabel: String,
        val description: String,
        val sizeLabel: String,
        /** 文件名 → 字节数估算（仅用于进度展示；小文件在前，先暴露镜像不可用）。 */
        val files: List<Pair<String, Long>>,
        val type: ModelType,
    ) {
        val totalBytesEstimate: Long get() = files.sumOf { it.second }
    }

    val MODEL_ZH_SMALL = SttModel(
        id = "zh-small",
        dirName = "sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01",
        label = "中文 · 小模型",
        shortLabel = "端侧 · 中文",
        description = "纯中文识别，轻量省电",
        sizeLabel = "约 26 MB",
        files = listOf(
            "tokens.txt" to 13_366L,
            "model.int8.onnx" to 26_342_340L,
        ),
        type = ModelType.ZIPFORMER2_CTC,
    )

    val MODEL_ZH_EN_BIG = SttModel(
        id = "zh-en-big",
        dirName = "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
        label = "中英混合 · 大模型",
        shortLabel = "端侧 · 中英",
        description = "中英夹杂识别 + 编程词汇热词增强，内存占用较高",
        sizeLabel = "约 190 MB",
        files = listOf(
            "tokens.txt" to 56_317L,
            "bpe.vocab" to 12_564L,
            "joiner-epoch-99-avg-1.int8.onnx" to 3_228_404L,
            "decoder-epoch-99-avg-1.onnx" to 13_876_452L,
            "encoder-epoch-99-avg-1.int8.onnx" to 181_895_032L,
        ),
        type = ModelType.TRANSDUCER,
    )

    val MODELS = listOf(MODEL_ZH_SMALL, MODEL_ZH_EN_BIG)

    private const val COMPLETE_MARKER = ".complete"
    private const val PREFS_NAME = "wand_stt"
    private const val KEY_SELECTED_MODEL = "selected_model"

    /** 镜像源（按序尝试）：国内 hf-mirror 优先，HuggingFace 官方兜底。 */
    private fun sources(model: SttModel) = listOf(
        "https://hf-mirror.com/csukuangfj/${model.dirName}/resolve/main",
        "https://huggingface.co/csukuangfj/${model.dirName}/resolve/main",
    )

    sealed class State {
        /** 未下载。 */
        data object Idle : State()
        data class Downloading(
            val percent: Int,
            val downloadedBytes: Long,
            val totalBytes: Long,
        ) : State()
        data object Ready : State()
        data class Failed(val message: String) : State()
    }

    /** 当前所选模型的状态（下载对话框观察它）。 */
    var state: State by mutableStateOf(State.Idle)
        private set

    /** 正在下载的模型 id（设置页按行展示进度用）。 */
    var downloadingModelId: String? by mutableStateOf(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var downloading = false

    @Volatile
    private var cancelRequested = false

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun modelById(id: String?): SttModel = MODELS.firstOrNull { it.id == id } ?: MODEL_ZH_SMALL

    fun selectedModel(context: Context): SttModel {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return modelById(prefs.getString(KEY_SELECTED_MODEL, MODEL_ZH_SMALL.id))
    }

    fun setSelectedModel(context: Context, id: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED_MODEL, id).apply()
        refresh(context)
    }

    fun modelDir(context: Context, model: SttModel): File =
        File(context.filesDir, "asr/${model.dirName}")

    fun isReady(context: Context, model: SttModel): Boolean {
        val dir = modelDir(context, model)
        return File(dir, COMPLETE_MARKER).exists() &&
            model.files.all { (name, _) -> File(dir, name).exists() }
    }

    /**
     * 实际生效的模型：所选模型就绪用所选；没就绪（比如大模型还在下载）
     * 回退到任一已就绪模型，保证「切换模型下载中」期间语音输入不中断。
     */
    fun activeModel(context: Context): SttModel? {
        val selected = selectedModel(context)
        if (isReady(context, selected)) return selected
        return MODELS.firstOrNull { isReady(context, it) }
    }

    /** 任一模型就绪即可用（VoiceInputController 据此挑引擎）。 */
    fun isReady(context: Context): Boolean = activeModel(context) != null

    /** 同步所选模型的磁盘状态到 state（下载中不打扰）。 */
    fun refresh(context: Context) {
        if (state is State.Downloading) return
        state = if (isReady(context, selectedModel(context))) State.Ready else State.Idle
    }

    fun startDownload(context: Context, model: SttModel = selectedModel(context)) {
        if (downloading) return
        if (isReady(context, model)) {
            refresh(context)
            return
        }
        downloading = true
        cancelRequested = false
        downloadingModelId = model.id
        state = State.Downloading(0, 0, model.totalBytesEstimate)
        val appContext = context.applicationContext
        scope.launch {
            var lastError: Exception? = null
            for (base in sources(model)) {
                if (cancelRequested) break
                try {
                    downloadFrom(appContext, model, base)
                    downloading = false
                    downloadingModelId = null
                    refreshAfterDownload(appContext)
                    return@launch
                } catch (e: Exception) {
                    lastError = e
                }
            }
            downloading = false
            downloadingModelId = null
            state = if (cancelRequested) {
                State.Idle
            } else {
                State.Failed("下载失败：${lastError?.message ?: "网络不可达"}")
            }
        }
    }

    private fun refreshAfterDownload(context: Context) {
        state = if (isReady(context, selectedModel(context))) State.Ready else State.Idle
    }

    fun cancelDownload() {
        cancelRequested = true
    }

    /** 进程内只清扫一次，避免每次进设置页/会话页都扫盘。 */
    @Volatile
    private var prunedOnce = false

    /**
     * 清理 ASR 目录里的无效产物（历史遗留照清）：
     * - 不在注册表里的模型目录：旧版本改过 dirName 或下架的模型，永远不会被再用；
     * - 缺 .complete 或文件不全的目录：下载失败/中断的残骸（正在下载的模型跳过）；
     * - 就绪目录里的 *.part：取消/失败的重试残留。
     * 不动「已就绪但未选中」的模型——那是用户主动下载的数据，删除意味着几百 MB
     * 的重新下载，只能由用户在设置页主动处理。
     */
    fun pruneInvalidArtifacts(context: Context) {
        if (prunedOnce) return
        prunedOnce = true
        val appContext = context.applicationContext
        scope.launch {
            try {
                val asrRoot = File(appContext.filesDir, "asr")
                if (!asrRoot.isDirectory) return@launch
                val knownDirs = MODELS.map { it.dirName }.toSet()
                asrRoot.listFiles { file -> file.isDirectory }?.forEach { dir ->
                    val model = MODELS.firstOrNull { it.dirName == dir.name }
                    when {
                        dir.name !in knownDirs || model == null ->
                            dir.deleteRecursively()
                        // 实时复查 downloadingModelId：prune 扫描期间用户可能刚点了下载，
                        // 快照里没有但目录正在被写入，误删会让 rename 失败报「写入模型文件失败」。
                        downloadingModelId?.let { id -> modelById(id).dirName } == dir.name -> Unit
                        !isReady(appContext, model) ->
                            dir.deleteRecursively()
                        else ->
                            dir.listFiles { f -> f.isFile && f.name.endsWith(".part") }
                                ?.forEach { it.delete() }
                    }
                }
            } catch (_: Exception) {
                // 扫描失败下次启动再试。
                prunedOnce = false
            }
        }
    }

    private fun downloadFrom(context: Context, model: SttModel, base: String) {
        val dir = modelDir(context, model)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("无法创建模型目录")
        File(dir, COMPLETE_MARKER).delete()

        val totalEstimate = model.totalBytesEstimate
        var doneBytes = 0L

        for ((name, estimate) in model.files) {
            if (cancelRequested) throw IOException("已取消")
            val target = File(dir, name)
            // 已完整存在的文件跳过（失败重试不用从头下大文件）。
            if (target.exists() && target.length() == estimate) {
                doneBytes += estimate
                continue
            }
            val tmp = File(dir, "$name.part")
            val request = Request.Builder().url("$base/$name").build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("空响应")
                val contentLength = body.contentLength()
                tmp.outputStream().use { out ->
                    val input = body.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    var fileDone = 0L
                    var lastUiUpdate = 0L
                    while (true) {
                        if (cancelRequested) throw IOException("已取消")
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        fileDone += n
                        val now = System.currentTimeMillis()
                        if (now - lastUiUpdate > 100) {
                            lastUiUpdate = now
                            val downloaded = doneBytes + fileDone
                            val total = totalEstimate.coerceAtLeast(downloaded)
                            state = State.Downloading(
                                percent = ((downloaded * 100) / total).toInt().coerceIn(0, 99),
                                downloadedBytes = downloaded,
                                totalBytes = total,
                            )
                        }
                    }
                }
                if (tmp.length() == 0L) throw IOException("下载内容为空")
                if (contentLength > 0 && tmp.length() != contentLength) throw IOException("文件不完整")
            }
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("写入模型文件失败")
            doneBytes += estimate
        }

        // 基本完整性兜底：最大的模型文件至少 10 MB（镜像返回错误页时拦下来）。
        val largest = model.files.maxBy { it.second }.first
        if (File(dir, largest).length() < 10L * 1024 * 1024) {
            throw IOException("模型文件异常")
        }
        File(dir, COMPLETE_MARKER).writeText("ok")
    }
}
