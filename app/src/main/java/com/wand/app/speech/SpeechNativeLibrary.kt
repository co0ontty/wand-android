package com.wand.app.speech

import android.content.Context
import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * sherpa-onnx v1.13.2 is optional: the APK contains only its JVM API, not the 22 MB JNI library.
 * Fetch the pinned upstream AAR on explicit user request, verify both AAR and arm64 .so, then
 * load the private, read-only .so by absolute path (the upstream eager loadLibrary is removed).
 */
object SpeechNativeLibrary {
    private const val AAR_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.2/" +
            "sherpa-onnx-static-link-onnxruntime-1.13.2.aar"
    private const val AAR_SHA256 = "9b2a290b8c7f31bd0aba35abb4628e87fe8d0eb71796a98aa12f3acd089ceaed"
    private const val SO_SHA256 = "68b0b3fad8d4b08644b4aa529087c2d6a31bbde6cb6fc8022740411bbefcdfa0"
    private const val SO_ENTRY = "jni/arm64-v8a/libsherpa-onnx-jni.so"
    private const val SO_NAME = "libsherpa-onnx-jni.so"
    private const val SO_SIZE = 22_304_376L
    const val DOWNLOAD_SIZE = 38_208_264L

    private var loaded = false

    private fun library(context: Context): File =
        File(context.noBackupFilesDir, "speech-native/sherpa-onnx-1.13.2/$SO_NAME")

    private fun marker(context: Context): File = File(library(context).parentFile, ".complete")

    fun isInstalled(context: Context): Boolean {
        val file = library(context)
        return file.isFile && file.length() == SO_SIZE &&
            marker(context).takeIf { it.isFile }?.readText() == SO_SHA256
    }

    fun download(
        context: Context,
        http: OkHttpClient,
        onProgress: (Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        if (isInstalled(context)) return
        if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
            throw IOException("端侧语音仅支持 arm64 设备")
        }
        val appContext = context.applicationContext
        val archive = File(appContext.cacheDir, "sherpa-onnx-1.13.2.aar.part")
        try {
            val request = Request.Builder().url(AAR_URL).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("语音引擎下载失败：HTTP ${response.code}")
                val body = response.body ?: throw IOException("语音引擎下载内容为空")
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                archive.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (isCancelled()) throw IOException("已取消")
                            val n = input.read(buffer)
                            if (n < 0) break
                            received += n
                            if (received > DOWNLOAD_SIZE) throw IOException("语音引擎文件大小异常")
                            digest.update(buffer, 0, n)
                            out.write(buffer, 0, n)
                            onProgress(received)
                        }
                    }
                }
                if (received != DOWNLOAD_SIZE || digest.hex() != AAR_SHA256) {
                    throw IOException("语音引擎校验失败，请重试")
                }
            }
            if (isCancelled()) throw IOException("已取消")
            installFromArchive(archive, library(appContext))
        } finally {
            archive.delete()
        }
    }

    /** Only this exact entry can be extracted; bound its size to prevent zip bombs. */
    internal fun installFromArchive(archive: File, target: File) {
        val parent = target.parentFile ?: throw IOException("无法创建语音引擎目录")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("无法创建语音引擎目录")
        val tmp = File(parent, "$SO_NAME.part")
        val complete = File(parent, ".complete")
        complete.delete()
        try {
            ZipFile(archive).use { zip ->
                val entry = zip.getEntry(SO_ENTRY) ?: throw IOException("语音引擎缺少 arm64 库")
                if (entry.size != SO_SIZE) throw IOException("语音引擎文件大小异常")
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                tmp.outputStream().use { out ->
                    zip.getInputStream(entry).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            received += n
                            if (received > SO_SIZE) throw IOException("语音引擎文件大小异常")
                            digest.update(buffer, 0, n)
                            out.write(buffer, 0, n)
                        }
                    }
                }
                if (received != SO_SIZE || digest.hex() != SO_SHA256) {
                    throw IOException("语音引擎校验失败，请重试")
                }
            }
            // Android 17+ requires dynamically loaded native code to be read-only before load.
            if (!tmp.setReadOnly()) throw IOException("无法保护语音引擎文件")
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("无法安装语音引擎")
            complete.writeText(SO_SHA256)
        } finally {
            tmp.delete()
        }
    }

    /** Always verify on disk on the background recognition/warm-up thread before executing it. */
    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        if (!isInstalled(context)) throw IOException("语音引擎未下载")
        val file = library(context)
        if (file.canWrite() || sha256(file) != SO_SHA256) {
            marker(context).delete()
            throw IOException("语音引擎校验失败，请重新下载")
        }
        try {
            System.load(file.absolutePath)
        } catch (error: UnsatisfiedLinkError) {
            marker(context).delete()
            throw IOException("语音引擎加载失败，请重新下载", error)
        }
        loaded = true
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.hex()
    }

    private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }
}
