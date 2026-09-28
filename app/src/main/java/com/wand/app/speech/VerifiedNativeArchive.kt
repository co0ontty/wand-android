package com.wand.app.speech

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile

internal data class NativeArchiveEntry(val path: String, val size: Long, val sha256: String)

/** Extract exactly one pinned entry with bounded IO, then publish read-only bytes and marker. */
internal fun installVerifiedNativeEntry(archive: File, target: File, expected: NativeArchiveEntry) {
    val parent = target.parentFile ?: throw IOException("无法创建语音引擎目录")
    if (!parent.exists() && !parent.mkdirs()) throw IOException("无法创建语音引擎目录")
    val tmp = File(parent, "${target.name}.part")
    val complete = File(parent, ".complete")
    complete.delete()
    try {
        ZipFile(archive).use { zip ->
            val entry = zip.getEntry(expected.path) ?: throw IOException("语音引擎缺少 arm64 库")
            if (entry.size != expected.size) throw IOException("语音引擎文件大小异常")
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            tmp.outputStream().use { out ->
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        received += n
                        if (received > expected.size) throw IOException("语音引擎文件大小异常")
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (received != expected.size || hash != expected.sha256) {
                throw IOException("语音引擎校验失败，请重试")
            }
        }
        // Dynamically loaded code must be protected before it is made available to the loader.
        if (!tmp.setReadOnly()) throw IOException("无法保护语音引擎文件")
        if (target.exists() && !target.delete()) throw IOException("无法替换语音引擎")
        if (!tmp.renameTo(target)) throw IOException("无法安装语音引擎")
        complete.writeText(expected.sha256)
    } finally {
        tmp.delete()
    }
}
