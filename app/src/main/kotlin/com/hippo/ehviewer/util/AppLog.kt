package com.hippo.ehviewer.util

import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.ehviewer.core.files.exists
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.files.toOkioPath
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.Settings
import java.io.File
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okio.Path
import splitties.init.appCtx

/**
 * 文件日志。
 *
 * 两份落盘：
 * 1. 应用内 `filesDir/logs/很太之家-yyyyMMdd.log`，始终写入，崩了也留得下；
 * 2. 用户在设置里自选的文件夹（SAF 目录树），选了就把同一份内容同步过去，
 *    方便直接用文件管理器把日志发出来。
 *
 * 未捕获异常额外写一份 `crash-<时间>.log`（含设备信息和最近日志），内外部各一份。
 */
object AppLog {
    private const val TAG = "AppLog"
    private const val MEMORY_LINES = 500
    private const val MIRROR_DELAY_MS = 700L

    private val lock = Any()
    private val mirrorLock = Any()
    private val dayFormat = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMdd", Locale.US) }
    private val stampFormat = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US) }
    private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US) }
    private val memory = ArrayList<String>(MEMORY_LINES)
    private val pending = StringBuilder()

    /** 已经建好当天文件的目录（同一秒内并发写不会重复建文件）。 */
    private val mirroredDays = HashSet<String>()

    @Volatile
    private var lastFlushAt = 0L

    @Volatile
    private var ready = false

    private val writer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "app-log").apply { isDaemon = true }
    }

    /** 应用内日志目录，永远可用。 */
    val internalDir: File
        get() = File(appCtx.filesDir, "logs").apply { if (!exists()) mkdirs() }

    /** 应用内崩溃目录，永远可用。 */
    val backupDir: File
        get() = File(appCtx.filesDir, "crash").apply { if (!exists()) mkdirs() }

    fun install() {
        ready = true
        runCatching {
            writer.execute {
                runCatching {
                    internalDir
                    backupDir
                    pruneOldLogs()
                }
            }
            writer.scheduleWithFixedDelay({ runCatching { flushNow() } }, MIRROR_DELAY_MS, MIRROR_DELAY_MS, TimeUnit.MILLISECONDS)
        }.onFailure { Log.w(TAG, "install failed", it) }
        i("AppLog", "start v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) sdk=${Build.VERSION.SDK_INT} ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    fun isEnabled(): Boolean = runCatching { Settings.logEnabled.value }.getOrDefault(true)

    fun i(tag: String, message: String) = write("I", tag, message, null)

    fun w(tag: String, message: String, throwable: Throwable? = null) = write("W", tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) = write("E", tag, message, throwable)

    /**
     * 未捕获异常。
     *
     * 在默认异常处理器里调用，这里必须自己吞掉所有异常，否则会把原始崩溃盖掉。
     */
    fun crash(throwable: Throwable, thread: Thread? = null) {
        runCatching {
            val stamp = stampFormat.get()!!.format(Date())
            val text = buildString {
                append("======== Crash ========\n")
                append("TIME=").append(stamp).append('\n')
                append("THREAD=").append(thread?.name ?: Thread.currentThread().name).append('\n')
                append('\n')
                val info = StringWriter()
                runCatching { CrashHandler.collectInfo(info) }
                append(info.toString())
                append("======== CrashInfo ========\n")
                append(throwable.stackTraceToString())
                append('\n')
                append("======== RecentLog ========\n")
                append(recent(200))
                append('\n')
            }
            File(internalDir, "crash-$stamp.log").writeText(text, Charsets.UTF_8)
            // 把还在缓冲里的行立刻刷出去，否则崩溃现场最后几行会丢
            flushNow()
            if (mirrorDir() != null) {
                mirrorFile("crash-$stamp.log", text)
                appendMirror(text)
            }
        }
    }

    /** 最近 [lines] 行日志，用于设置页里直接查看。 */
    fun recent(lines: Int = 200): String = synchronized(lock) {
        val count = lines.coerceIn(1, MEMORY_LINES).coerceAtMost(memory.size)
        if (count == 0) "" else memory.subList(memory.size - count, memory.size).joinToString("\n")
    }

    /** 应用内日志占用（KB）。 */
    fun internalSizeKb(): Long = runCatching {
        val files = internalDir.listFiles() ?: return 0
        files.sumOf { it.length() } / 1024
    }.getOrDefault(0)

    /** 立即把缓冲内容写到用户选的文件夹。 */
    fun flushNow() {
        val text = synchronized(lock) {
            if (pending.isEmpty()) return
            pending.toString().also { pending.setLength(0) }
        }
        appendMirror(text)
    }

    /** 用户换了目录后，把当前完整日志补写一份到新目录。 */
    fun seedMirror() {
        synchronized(mirrorLock) {
            runCatching {
                val dir = mirrorDir() ?: return
                val day = today()
                if (day in mirroredDays) return
                val file = dir / fileNameOfDay()
                if (file.exists()) {
                    mirroredDays.add(day)
                    return
                }
                mirrorFile(fileNameOfDay(), header() + recent(2000) + "\n")
                mirroredDays.add(day)
            }.onFailure { w(TAG, "seed mirror failed", it) }
        }
    }

    /** 用户换了目录：忘掉「当天文件已创建」的标记，下次写入重新判断。 */
    fun resetMirrorCache() = synchronized(mirrorLock) { mirroredDays.clear() }

    fun clear() {
        runCatching { internalDir.listFiles()?.forEach { it.delete() } }
        runCatching { backupDir.listFiles()?.forEach { it.delete() } }
        synchronized(lock) {
            memory.clear()
            pending.setLength(0)
        }
        resetMirrorCache()
        i("AppLog", "logs cleared by user")
    }

    /** 用户选中的文件夹（没选时为 null）。 */
    fun mirrorDir(): Path? = runCatching {
        val uri = Settings.logFolderUri.value
        if (uri.isBlank()) return null
        Uri.parse(uri).toOkioPath()
    }.getOrNull()

    fun mirrorName(): String = runCatching { Settings.logFolderName.value }.getOrDefault("")

    fun fileNameOfDay(): String = "很太之家-${today()}.log"

    /** 应用内只留最近 7 天的日志，免得越攒越大。 */
    private fun pruneOldLogs() {
        val deadline = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        listOf(internalDir, backupDir).forEach { dir ->
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < deadline) file.delete()
            }
        }
    }

    private fun today(): String = dayFormat.get()!!.format(Date())

    private fun write(level: String, tag: String, message: String, throwable: Throwable?) {
        if (!ready) return
        val line = buildString {
            append(timeFormat.get()!!.format(Date())).append(' ').append(level).append('/').append(tag).append(": ").append(message)
            if (throwable != null) {
                append('\n')
                append(throwable.stackTraceToString())
            }
        }
        runCatching {
            when (level) {
                "E" -> Log.e(tag, message, throwable)
                "W" -> Log.w(tag, message, throwable)
                else -> Log.i(tag, message)
            }
        }
        synchronized(lock) {
            memory.add(line)
            while (memory.size > MEMORY_LINES) memory.removeAt(0)
            pending.append(line).append('\n')
        }
        // 落盘放到单独线程，主线程不碰磁盘（否则 StrictMode 会判定为主线程 IO）
        runCatching {
            writer.execute {
                runCatching {
                    if (isEnabled()) File(internalDir, fileNameOfDay()).appendText("$line\n", Charsets.UTF_8)
                }
            }
        }
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (runCatching { Settings.logFolderUri.value }.getOrDefault("").isBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastFlushAt < MIRROR_DELAY_MS) return
        lastFlushAt = now
        runCatching { writer.execute { runCatching { flushNow() } } }
    }

    /**
     * 追加到用户目录里的当天日志。
     *
     * 整个流程串行化：选目录时补写的那份和定时刷新的那份如果同时判断「文件还没建」，
     * SAF 会建出 `名字 (1).log` 这样的重复文件。
     */
    private fun appendMirror(text: String) {
        synchronized(mirrorLock) {
            runCatching {
                val dir = mirrorDir() ?: return
                val day = today()
                val file = dir / fileNameOfDay()
                if (day !in mirroredDays) {
                    if (!file.exists()) {
                        mirrorFile(fileNameOfDay(), header() + text + "\n")
                        mirroredDays.add(day)
                        return
                    }
                    mirroredDays.add(day)
                }
                file.openFileDescriptor("wa").let { fd ->
                    ParcelFileDescriptor.AutoCloseOutputStream(fd).use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    }
                }
            }.onFailure { Log.w(TAG, "mirror write failed", it) }
        }
    }

    private fun mirrorFile(name: String, text: String) {
        val dir = mirrorDir() ?: return
        with((dir / name).openFileDescriptor("wt")) {
            ParcelFileDescriptor.AutoCloseOutputStream(this).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun header(): String = buildString {
        append("======== 很太之家 日志 ========\n")
        append("VERSION=").append(BuildConfig.VERSION_NAME).append('(').append(BuildConfig.VERSION_CODE).append(")\n")
        append("COMMIT=").append(BuildConfig.COMMIT_SHA).append('\n')
        append("ANDROID=").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        append("DEVICE=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        append("ABI=").append(Build.SUPPORTED_ABIS.joinToString()).append('\n')
        append("START=").append(timeFormat.get()!!.format(Date())).append('\n')
        append("================================\n")
    }
}
