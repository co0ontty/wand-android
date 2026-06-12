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
 * 端侧语音模型下载与就绪状态管理（sherpa-onnx 流式中文 CTC 小模型，约 26 MB）。
 *
 * 模型文件落在 `filesDir/asr/<模型名>/`，下载完成写 `.complete` 标记。
 * 下载源是 HuggingFace 的两个裸文件（model.int8.onnx + tokens.txt），
 * 国内镜像 hf-mirror.com 优先、官方域名兜底，**不需要解压**任何归档。
 *
 * 状态用 Compose snapshot state 暴露，对话框/控制器直接观察。
 * 下载放在进程级单例 scope 里：用户中途离开聊天页不打断下载。
 */
object SttModelManager {
    /** 模型目录名（与上游仓库名一致，方便溯源）。 */
    const val MODEL_NAME = "sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01"
    const val MODEL_FILE = "model.int8.onnx"
    const val TOKENS_FILE = "tokens.txt"
    const val DOWNLOAD_SIZE_LABEL = "约 26 MB"
    private const val COMPLETE_MARKER = ".complete"

    /** 已知文件大小（仅用于进度估算；完整性按响应 Content-Length 校验）。 */
    private const val MODEL_BYTES_ESTIMATE = 26_342_340L
    private const val TOKENS_BYTES_ESTIMATE = 13_366L

    /** 镜像源（按序尝试）：国内 hf-mirror 优先，HuggingFace 官方兜底。 */
    private val SOURCES = listOf(
        "https://hf-mirror.com/csukuangfj/$MODEL_NAME/resolve/main",
        "https://huggingface.co/csukuangfj/$MODEL_NAME/resolve/main",
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

    var state: State by mutableStateOf(State.Idle)
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

    fun modelDir(context: Context): File = File(context.filesDir, "asr/$MODEL_NAME")

    fun isReady(context: Context): Boolean {
        val dir = modelDir(context)
        return File(dir, COMPLETE_MARKER).exists() &&
            File(dir, MODEL_FILE).exists() &&
            File(dir, TOKENS_FILE).exists()
    }

    /** 同步磁盘状态到 state（下载中不打扰）。 */
    fun refresh(context: Context) {
        if (state is State.Downloading) return
        state = if (isReady(context)) State.Ready else State.Idle
    }

    fun startDownload(context: Context) {
        if (downloading) return
        if (isReady(context)) {
            state = State.Ready
            return
        }
        downloading = true
        cancelRequested = false
        state = State.Downloading(0, 0, MODEL_BYTES_ESTIMATE + TOKENS_BYTES_ESTIMATE)
        val appContext = context.applicationContext
        scope.launch {
            var lastError: Exception? = null
            for (base in SOURCES) {
                if (cancelRequested) break
                try {
                    downloadFrom(appContext, base)
                    downloading = false
                    state = State.Ready
                    return@launch
                } catch (e: Exception) {
                    lastError = e
                }
            }
            downloading = false
            state = if (cancelRequested) {
                State.Idle
            } else {
                State.Failed("下载失败：${lastError?.message ?: "网络不可达"}")
            }
        }
    }

    fun cancelDownload() {
        cancelRequested = true
    }

    private fun downloadFrom(context: Context, base: String) {
        val dir = modelDir(context)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("无法创建模型目录")
        File(dir, COMPLETE_MARKER).delete()

        // 小文件在前：tokens 先成功能更快暴露镜像不可用，避免白下 26 MB。
        val plan = listOf(TOKENS_FILE to TOKENS_BYTES_ESTIMATE, MODEL_FILE to MODEL_BYTES_ESTIMATE)
        val totalEstimate = plan.sumOf { it.second }
        var doneBytes = 0L

        for ((name, estimate) in plan) {
            if (cancelRequested) throw IOException("已取消")
            val target = File(dir, name)
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

        // 基本完整性兜底：模型文件至少 10 MB（镜像返回错误页时拦下来）。
        if (File(dir, MODEL_FILE).length() < 10L * 1024 * 1024) {
            throw IOException("模型文件异常")
        }
        File(dir, COMPLETE_MARKER).writeText("ok")
    }
}
