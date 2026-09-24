package com.wand.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * 「导出运行日志」的报表拼装：
 *
 * 1. 环境头（版本、机型、系统、进程、当前服务器地址）；
 * 2. **历史异常退出**（[ApplicationExitInfo]）：Java 崩溃 / 原生崩溃 / ANR 的原因、
 *    时间和系统保留的 trace —— 进程已经死了，这是唯一能拿回崩溃现场的地方；
 * 3. 落盘日志（[WandLog.fileText]，含崩溃前同步写入的那条）；
 * 4. 当前进程内存环（[WandLog.ringText]，覆盖落盘队列尚未 flush 的尾部）；
 * 5. 当前进程 logcat 快照（尽力而为，无权限时留空）。
 *
 * 全部输出都过一遍 [WandLog.redact]，调用方不需要自己处理敏感字段。
 *
 * 导出有两条落盘路径，都不依赖「分享」这一环：
 * [saveToDownloads] 直接写进系统「下载」目录（MediaStore，一次点击就出文件），
 * [writeToUri] 是用户自选位置的 SAF 另存。分享用的缓存文件仍走 [exportToFile]。
 */
object WandDiagnostics {

    private const val MAX_REPORT_CHARS = 1_200_000
    /** 「下载」目录下的子目录名，和其他应用导出物区分开。 */
    private const val DOWNLOAD_SUBDIR = "Wand"
    private const val MAX_EXIT_INFOS = 8
    private const val MAX_EXIT_TRACE_CHARS = 8_000
    private const val MAX_LOGCAT_CHARS = 120_000

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun fileStamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun timeStamp(millis: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(millis))

    /** 拼装完整报表文本（不要在 UI 线程调用：包含文件与 logcat IO）。 */
    fun buildReport(context: Context): String {
        val builder = StringBuilder()
        builder.append("Wand Android 运行日志\n")
        builder.append(environmentHeader(context))
        builder.append(buildString {
            append("\n")
            append(section("历史异常退出（系统 ApplicationExitInfo）"))
            append(exitInfoSection(context))
            append("\n")
            append(section("运行时日志（旧 → 新）"))
            val fileText = WandLog.fileText(MAX_REPORT_CHARS / 2)
            val ringText = WandLog.ringText(MAX_REPORT_CHARS / 4)
            if (fileText.isEmpty() && ringText.isEmpty()) {
                append("（本次启动以来还没有任何日志。）\n")
            } else {
                if (fileText.isNotEmpty()) {
                    append("— 持久化日志文件的尾部 —\n")
                    append(fileText)
                    if (!fileText.endsWith("\n")) append("\n")
                }
                if (ringText.isNotEmpty()) {
                    append("— 当前进程内存缓冲 —\n")
                    append(ringText)
                    if (!ringText.endsWith("\n")) append("\n")
                }
            }
            append("\n")
            append(section("当前进程 logcat 快照"))
            val logcat = logcatSnapshot()
            append(if (logcat.isBlank()) "（系统未允许读取 logcat，已跳过。）\n" else logcat)
        })
        val report = WandLog.redact(builder.toString())
        return if (report.length > MAX_REPORT_CHARS) {
            report.take(MAX_REPORT_CHARS) + "\n…（已截断）\n"
        } else {
            report
        }
    }

    /** 导出文件名（带本地时间戳），下载/另存/分享三条路径共用同一个名字。 */
    fun exportFileName(): String = "wand-android-log-${fileStamp()}.txt"

    /**
     * 一次点击直接落进系统「下载」目录：`下载/Wand/wand-android-log-*.txt`。
     *
     * 走 MediaStore.Downloads（API 29+，本项目 minSdk 33）不需要任何存储权限，
     * 也不弹任何选择器；写完把 `IS_PENDING` 归零，文件对「文件」App 立即可见。
     * 返回**给人看的**中文位置（如 `下载/Wand/xxx.txt`），MediaStore 里落的仍是
     * 标准 `Download/Wand/`（Environment.DIRECTORY_DOWNLOADS），两名字对应同一目录。
     */
    fun saveToDownloads(context: Context): String {
        val report = buildReport(context)
        return saveReportToDownloads(context, report, exportFileName())
    }

    /** 复用调用方已拼好的报表文本，避免重复拼装 1MB 级文本。 */
    fun saveReportToDownloads(
        context: Context,
        report: String,
        fileName: String = exportFileName(),
    ): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBDIR",
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("系统下载目录不可写")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                output.write(report.toByteArray(Charsets.UTF_8))
                output.flush()
            } ?: throw IllegalStateException("无法写入下载目录")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return "下载/$DOWNLOAD_SUBDIR/$fileName"
    }

    /** 写进用户通过系统「另存为」选中的位置（SAF URI）。 */
    fun writeToUri(context: Context, uri: Uri, report: String) {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(report.toByteArray(Charsets.UTF_8))
            output.flush()
        } ?: throw IllegalStateException("无法写入所选位置")
    }

    /**
     * 把报表写进 `cacheDir/exports`，返回可分享的文件。
     * 分享走 FileProvider（`<packageName>.fileprovider`，见 res/xml/file_paths.xml）。
     */
    fun exportToFile(context: Context): File =
        writeCacheFile(context, buildReport(context), exportFileName())

    /** 复用已拼好的报表文本写缓存文件（分享路径用）。 */
    fun writeCacheFile(
        context: Context,
        report: String,
        fileName: String = exportFileName(),
    ): File {
        val dir = File(context.cacheDir, "exports")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("无法创建导出目录")
        }
        val file = File(dir, fileName)
        file.writeText(report, Charsets.UTF_8)
        // 只保留最近 5 份导出，避免 cache 目录无限增长。
        dir.listFiles { child -> child.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(5)
            ?.forEach { runCatching { it.delete() } }
        return file
    }

    /** 启动横幅用：最近几次异常退出的一行摘要（没有则返回空列表）。 */
    fun previousExitSummaries(context: Context, limit: Int = 3): List<String> {
        val infos = historicalExitInfos(context) ?: return emptyList()
        return infos.take(limit).map { info ->
            buildString {
                append(describeReason(info.reason))
                append(" at ")
                append(timeStamp(info.timestamp))
                val description = info.description
                if (!description.isNullOrBlank()) append(" · ").append(description)
            }
        }
    }

    private fun environmentHeader(context: Context): String {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val versionName = packageInfo?.versionName ?: "?"
        val versionCode = packageInfo?.longVersionCode ?: 0L
        val endpoint = runCatching { ServerStore(context).activeServerProfile?.baseUrl }
            .getOrNull()
            ?.let { WandHttpHost.describe(it) }
            ?: "（未连接）"
        val uptimeMs = SystemClock.elapsedRealtime()
        return buildString {
            append("导出时间: ").append(stamp()).append("\n")
            append("App: v").append(versionName).append(" (").append(versionCode).append(")\n")
            append("包名: ").append(context.packageName).append("\n")
            append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(" · ").append(Build.DEVICE).append(" · ").append(Build.SUPPORTED_ABIS.joinToString(","))
            append("\n")
            append("系统: Android ").append(Build.VERSION.RELEASE)
            append(" (SDK ").append(Build.VERSION.SDK_INT).append(")")
            append(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                " · " + Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL
            } else {
                ""
            })
            append("\n")
            append("区域: ").append(Locale.getDefault().toString())
            append(" · 时区 ").append(TimeZone.getDefault().id)
            append("\n")
            append("进程: pid ").append(android.os.Process.myPid())
            append(" · 启动于 ").append(timeStamp(WandLog.processStartWallClockMs()))
            append(" · 已运行 ").append(formatDuration(uptimeMs))
            append("\n")
            append("服务器: ").append(endpoint).append("\n")
            val fileBytes = WandLog.fileBytes()
            append("日志文件: ").append(fileBytes / 1024).append(" KB · 内存环 ")
                .append(WandLog.ringSize()).append(" 条\n")
        }
    }

    private fun section(title: String): String = "———— $title ————\n"

    /**
     * 历史退出信息（最新在前）。API 36 起只有带包名的重载，pid=0 表示不限进程。
     * 只读自己的包名，不会拿到别的应用的记录。
     */
    private fun historicalExitInfos(context: Context): List<ApplicationExitInfo>? {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        return runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_INFOS)
        }.getOrNull()
    }

    private fun exitInfoSection(context: Context): String {
        val infos = historicalExitInfos(context)
            ?: return "（当前系统不支持读取历史退出信息。）\n"
        if (infos.isEmpty()) return "（没有历史退出记录。）\n"
        val builder = StringBuilder()
        infos.take(MAX_EXIT_INFOS).forEachIndexed { index, info ->
            builder.append(index + 1).append(". ")
                .append(timeStamp(info.timestamp))
                .append("  ").append(describeReason(info.reason))
                .append("  importance=").append(info.importance)
                .append("  pss=").append(info.pss).append("KB")
                .append("  rss=").append(info.rss).append("KB")
                .append("\n")
            val description = info.description
            if (!description.isNullOrBlank()) {
                builder.append("   描述: ").append(description).append("\n")
            }
            val trace = readTrace(info)
            if (trace.isNotBlank()) {
                builder.append("   trace:\n")
                trace.lines().forEach { line -> builder.append("     ").append(line).append("\n") }
            }
        }
        return builder.toString()
    }

    private fun readTrace(info: ApplicationExitInfo): String = runCatching {
        info.traceInputStream?.use { stream ->
            stream.bufferedReader().use { reader ->
                val text = reader.readText()
                if (text.length > MAX_EXIT_TRACE_CHARS) {
                    text.take(MAX_EXIT_TRACE_CHARS) + "\n…（trace 已截断）"
                } else {
                    text
                }
            }
        }.orEmpty()
    }.getOrDefault("")

    private fun describeReason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR（无响应）"
        ApplicationExitInfo.REASON_CRASH -> "CRASH（Java 崩溃）"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE（原生崩溃）"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF（主动退出）"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY（被系统回收）"
        ApplicationExitInfo.REASON_OTHER -> "OTHER（被系统杀死 / 更新安装）"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED（信号终止）"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "REASON_$reason"
    }

    /**
     * 当前进程 logcat 尾部快照。Android 只允许应用读自己的日志；
     * 机型/版本差异会让它失败，失败就静默跳过（内存环 + 文件日志已经覆盖主要信息）。
     *
     * 注意不要用 `logcat -t N`：`-t` 作用在**过滤前的整段缓冲区**上，系统很吵时
     * 尾部 N 行几乎全是别人的日志，过滤完只剩零星几行。这里按 pid 过滤后自己在
     * 内存里截尾。
     */
    private fun logcatSnapshot(): String {
        val pid = android.os.Process.myPid()
        val process = runCatching {
            ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=$pid")
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return ""
        return runCatching {
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(4, TimeUnit.SECONDS)) process.destroy()
            if (output.length > MAX_LOGCAT_CHARS) output.takeLast(MAX_LOGCAT_CHARS) else output
        }.getOrDefault("")
    }

    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "${hours}h${minutes}m"
        } else if (minutes > 0) {
            "${minutes}m${seconds}s"
        } else {
            "${seconds}s"
        }
    }
}

/** 导出头部只写服务器 origin，不带 path/query（path 可能含连接码）。 */
private object WandHttpHost {
    fun describe(raw: String): String = runCatching {
        val uri = java.net.URI(raw)
        val port = if (uri.port > 0) ":${uri.port}" else ""
        "${uri.scheme}://${uri.host}$port"
    }.getOrDefault(raw)
}
