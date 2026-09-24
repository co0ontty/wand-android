package com.wand.app

import android.app.Application
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 应用内诊断日志 —— 内存环 + 落盘文件 + 崩溃捕获。
 *
 * 为什么不用 `android.util.Log` 就够了：用户要在设置页「导出运行日志」把关键报错
 * （网络、WebSocket、更新安装、崩溃）发给开发者，而这些日志必须**活过进程死亡**
 * —— 崩溃恰恰是进程死亡事件。所以这里把每条日志同时写进两个地方：
 *
 * 1. 内存环（[MAX_RING_ENTRIES] 条）：导出时能拿到最近上下文，不受磁盘 IO 影响；
 * 2. 落盘文件 `filesDir/logs/wand.log`：崩溃/被杀后仍然存在，超过
 *    [MAX_FILE_BYTES] 轮转成 `wand.log.1`（只保留一代，总量有界）。
 *
 * 日志同时镜像到 logcat（tag 前缀 `Wand/`），方便 adb 现场排查。
 *
 * 安全约定：**绝不记录 appToken、密码、cookie**。[redact] 是最后一道兜底，
 * 调用方仍然不应该主动传敏感值。
 */
object WandLog {

    enum class Level { DEBUG, INFO, WARN, ERROR, CRASH }

    /** 一条日志。导出时逐行渲染。 */
    data class Entry(
        val timeMs: Long,
        val level: Level,
        val tag: String,
        val message: String,
    )

    /** 环形缓冲：容量固定，满则丢最旧。纯 Kotlin，可被单元测试直接驱动。 */
    internal class LogRing(private val capacity: Int) {
        private val entries = ArrayDeque<Entry>(capacity)

        @Synchronized
        fun add(entry: Entry) {
            if (entries.size >= capacity) entries.pollFirst()
            entries.addLast(entry)
        }

        @Synchronized
        fun snapshot(): List<Entry> = entries.toList()

        @Synchronized
        fun size(): Int = entries.size
    }

    /** 单条日志上限：防止把整个响应体写进日志。 */
    private const val MAX_MESSAGE_CHARS = 4_000
    private const val MAX_RING_ENTRIES = 2_000
    private const val MAX_FILE_BYTES = 512L * 1024L
    private const val LOG_DIR_NAME = "logs"
    private const val LOG_FILE_NAME = "wand.log"
    private const val LOG_ARCHIVE_NAME = "wand.log.1"
    private const val LOGCAT_TAG_PREFIX = "Wand/"

    private val ring = LogRing(MAX_RING_ENTRIES)
    private val clockFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    @Volatile
    private var sink: FileSink? = null

    @Volatile
    private var processStartElapsedMs = 0L

    @Volatile
    private var processStartWallClockMs = 0L

    @Volatile
    private var installed = false

    /**
     * 进程启动时调用一次（[WandApplication.onCreate]）：建目录、注册崩溃处理器、
     * 记录进程启动时间。重复调用无副作用。
     */
    fun install(application: Application) {
        if (installed) return
        installed = true
        processStartElapsedMs = SystemClock.elapsedRealtime()
        processStartWallClockMs = System.currentTimeMillis()
        // 落盘失败绝不能拖垮应用启动：内存环 + logcat 仍然可用。
        sink = runCatching {
            FileSink(File(File(application.filesDir, LOG_DIR_NAME), LOG_FILE_NAME))
        }.getOrNull()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            captureCrash(thread, error)
            // 交回系统默认处理器：保持系统崩溃弹窗/统计行为不变。
            previous?.uncaughtException(thread, error)
        }
    }

    /** 本进程启动时刻（墙钟）。与安装完成时刻比较以判断「旧进程是否还活着」。 */
    fun processStartWallClockMs(): Long = processStartWallClockMs

    fun d(tag: String, message: String) = write(Level.DEBUG, tag, message, null)

    @JvmStatic
    fun i(tag: String, message: String) = write(Level.INFO, tag, message, null)

    @JvmStatic
    @JvmOverloads
    fun w(tag: String, message: String, error: Throwable? = null) = write(Level.WARN, tag, message, error)

    @JvmStatic
    @JvmOverloads
    fun e(tag: String, message: String, error: Throwable? = null) = write(Level.ERROR, tag, message, error)

    /**
     * 未捕获异常的记录入口。崩溃路径必须**同步落盘**：进程下一秒就没了，
     * 任何异步队列都可能来不及写。
     */
    fun crash(tag: String, message: String, error: Throwable?): Unit =
        write(Level.CRASH, tag, message, error, synchronous = true)

    private fun write(
        level: Level,
        tag: String,
        message: String,
        error: Throwable?,
        synchronous: Boolean = false,
    ) {
        val text = buildString {
            append(redact(message))
            if (error != null) {
                append("\n")
                append(redact(error.stackTraceToString()))
            }
        }.take(MAX_MESSAGE_CHARS)
        val now = System.currentTimeMillis()
        ring.add(Entry(now, level, tag, text))
        val line = formatEntry(Entry(now, level, tag, text))
        val target = sink
        if (target != null) {
            if (synchronous) target.writeNow(line) else target.submit(line)
        }
        // 镜像到 logcat；JVM 单元测试里 android.util.Log 是抛异常的桩实现，
        // 诊断日志永远不能让业务路径失败。
        runCatching { Log.println(level.priority, LOGCAT_TAG_PREFIX + tag, text) }
    }

    /** 崩溃处理器：记录线程名 + 异常 + 最近上下文。 */
    private fun captureCrash(thread: Thread, error: Throwable) {
        crash("crash", "未捕获异常 thread=${thread.name}", error)
        flushBlocking()
    }

    internal fun formatEntry(entry: Entry): String {
        val time = clockFormat.get()?.format(Date(entry.timeMs)) ?: entry.timeMs.toString()
        val levelChar = when (entry.level) {
            Level.DEBUG -> "D"
            Level.INFO -> "I"
            Level.WARN -> "W"
            Level.ERROR -> "E"
            Level.CRASH -> "F"
        }
        return "$time $levelChar/${entry.tag.padEnd(12)} ${entry.message}"
    }

    /**
     * 敏感信息兜底脱敏，按顺序应用：
     *
     * 1. `token=…` / `password: …` / `apiKey=…` 这类键值对（带不带引号都行）；
     * 2. `Bearer xxx` / `Basic xxx` 凭据串；
     * 3. `Authorization` / `Cookie` 这类整行敏感头，分隔符之后到行尾全部丢弃。
     *
     * 会话 id **不脱敏** —— 它是排查故障时最重要的锚点，也不是凭据。
     */
    internal fun redact(text: String): String {
        var out = text
        for (rule in SECRET_RULES) {
            out = rule.pattern.replace(out, rule.replacement)
        }
        return out
    }

    private class RedactionRule(val pattern: Regex, val replacement: String)

    private val SECRET_RULES = listOf(
        RedactionRule(
            Regex(
                "(?i)\\b(\\\"?(?:app_?token|access_?token|auth_?token|refresh_?token|token" +
                    "|password|passwd|secret|api_?key)\\\"?)(\\s*[:=]\\s*)" +
                    "(\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;&\\\"']+)",
            ),
            "$1$2***",
        ),
        RedactionRule(
            Regex("(?i)(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{6,}"),
            "$1 ***",
        ),
        RedactionRule(
            Regex(
                "(?im)\\b(\\\"?(?:authorization|proxy-authorization|cookie|set-cookie)\\\"?)" +
                    "(\\s*[:=]\\s*)[^\\n]{6,}",
            ),
            "$1$2***",
        ),
    )

    /** 等待异步写队列落盘（崩溃前调用，有上限，绝不长时间阻塞）。 */
    @JvmOverloads
    fun flushBlocking(timeoutMs: Long = 400L) {
        sink?.flushBlocking(timeoutMs)
    }

    /** 当前进程内存环快照（时间正序）。 */
    /** 内存环条数（设置页展示「有内容可导出」用）。 */
    fun ringSize(): Int = ring.size()

    /** 落盘日志文件大小（含轮转文件）。 */
    fun fileBytes(): Long = sink?.totalBytes() ?: 0L

    /** 内存环渲染成文本（旧 → 新）。 */
    fun ringText(maxChars: Int = Int.MAX_VALUE): String =
        renderLines(ring.snapshot().map(::formatEntry), maxChars)

    /**
     * 落盘日志渲染成文本（旧 → 新，自动带上轮转文件）。
     * [maxChars] 截断时**保留尾部**：最新的现场最重要。
     */
    fun fileText(maxChars: Int = 400_000): String {
        val file = sink?.file ?: return ""
        if (!file.exists()) return ""
        val archive = File(file.parentFile, LOG_ARCHIVE_NAME)
        val budget = maxChars.coerceAtLeast(1_000)
        val archiveBudget = if (archive.exists()) budget / 4 else 0
        val head = if (archiveBudget > 0) readTail(archive, archiveBudget) else ""
        val tail = readTail(file, budget - head.length)
        return listOf(head, tail).filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun readTail(file: File, maxChars: Int): String {
        if (maxChars <= 0 || !file.exists()) return ""
        return runCatching {
            val length = file.length()
            if (length <= maxChars) {
                file.readText(Charsets.UTF_8)
            } else {
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(length - maxChars)
                    val buffer = ByteArray(maxChars.toInt())
                    raf.readFully(buffer)
                    String(buffer, Charsets.UTF_8)
                }
            }
        }.getOrDefault("")
    }

    /** 渲染时按字符预算**保留尾部**：最新的现场最重要。 */
    private fun renderLines(lines: List<String>, maxChars: Int): String {
        if (lines.isEmpty()) return ""
        val builder = StringBuilder()
        for (index in lines.indices.reversed()) {
            val line = lines[index]
            if (builder.length + line.length + 1 > maxChars && builder.isNotEmpty()) break
            builder.insert(0, "$line\n")
        }
        return builder.toString()
    }

    private fun appendToFile(sinkFile: File, lines: List<String>) {
        synchronized(FILE_LOCK) {
            rotateIfNeeded(sinkFile)
            runCatching {
                sinkFile.appendText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            }
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() <= MAX_FILE_BYTES) return
        val archive = File(file.parentFile, LOG_ARCHIVE_NAME)
        runCatching {
            if (archive.exists()) archive.delete()
            file.renameTo(archive)
        }
    }

    private val FILE_LOCK = Any()

    /**
     * 后台落盘线程：小队列 + 批量追加。队列满时丢弃最旧的待写行——
     * 诊断日志宁可丢几行也不能阻塞调用方（HTTP/WS 热路径）。
     */
    private class FileSink(val file: File) {
        private val queue = ArrayBlockingQueue<String>(2_048)
        private val thread = Thread({ drainLoop() }, "wand-log-writer").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }

        init {
            file.parentFile?.mkdirs()
        }

        /** 入队即返回：写日志的调用方都在 HTTP / WS / UI 热路径上。 */
        fun submit(line: String) {
            queue.offer(line)
        }

        /** 崩溃路径：直接同步写，不走队列。 */
        fun writeNow(line: String) {
            appendToFile(file, listOf(line))
        }

        fun flushBlocking(timeoutMs: Long) {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (queue.isNotEmpty() && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(10)
            }
        }

        fun totalBytes(): Long {
            val archive = File(file.parentFile, LOG_ARCHIVE_NAME)
            return file.length() + (if (archive.exists()) archive.length() else 0L)
        }

        private fun drainLoop() {
            while (true) {
                val first = queue.poll(30, TimeUnit.SECONDS) ?: continue
                val batch = ArrayList<String>(64)
                batch.add(first)
                queue.drainTo(batch, 255)
                appendToFile(file, batch)
            }
        }
    }
}

/**
 * 打点便捷入口（与 iOS 端 `wlog` 命名一致）：`wlog("chat", "打开会话 …")`。
 * 传了异常按 ERROR 级别记录，否则记 INFO。
 */
fun wlog(tag: String, message: String, error: Throwable? = null) {
    if (error == null) WandLog.i(tag, message) else WandLog.e(tag, message, error)
}

private val WandLog.Level.priority: Int
    get() = when (this) {
        WandLog.Level.DEBUG -> Log.DEBUG
        WandLog.Level.INFO -> Log.INFO
        WandLog.Level.WARN -> Log.WARN
        WandLog.Level.ERROR -> Log.ERROR
        WandLog.Level.CRASH -> Log.ASSERT
    }
