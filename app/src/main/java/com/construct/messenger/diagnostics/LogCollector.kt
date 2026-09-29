package com.construct.messenger.diagnostics

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The app's log, written to files so a session can be read after the fact and sent with a report.
 * **Canon:** iOS `LogCollector` — `current.log` plus three rotated files of 5 MB, the previous
 * session set aside at launch, the last lines held in memory for a crash, one archive to share.
 *
 * **Debug builds only** ([enabled]). A privacy-first messenger does not leave a rolling plaintext
 * of session and network metadata on disk in a release build; iOS writes nothing in Release for
 * the same reason. A disabled collector keeps nothing, in memory or on disk.
 *
 * Plain `java.io`, no Android types: the rules are tested on the JVM against a temporary directory.
 */
class LogCollector(
    private val directory: File,
    val enabled: Boolean,
    private val header: () -> String = { "" },
    private val maxFileBytes: Long = MAX_FILE_BYTES,
    private val now: () -> Date = { Date() },
) {
    private val current = File(directory, "current.log")
    private val crashes = File(directory, "crashes.log")

    /** Writes in order, off the caller's thread. */
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "log-collector").apply { isDaemon = true }
    }

    private val recent = ArrayDeque<String>()

    init {
        if (enabled) {
            directory.mkdirs()
            // The session before this one is the interesting one after a crash or a force-quit;
            // writing the new header over it lost it on iOS until 2026-08-18.
            if (shouldPreserveSession(current.takeIf { it.exists() }?.length())) rotate()
            writeHeader()
        }
    }

    fun append(level: String, tag: String, message: String) {
        if (!enabled) return
        val line = "[${timestamp()}] [$level] [$tag] $message"
        // Before the hop, so the line exists in memory even if the process does not survive to
        // write it — the crash handler reads this.
        synchronized(recent) {
            recent.addLast(line)
            while (recent.size > RECENT_CAPACITY) recent.removeFirst()
        }
        writer.execute {
            if (current.length() > maxFileBytes) rotate()
            current.appendText(line + "\n")
        }
    }

    /** The last [count] lines, newest last. Safe from any thread, including a dying one. */
    fun recentLines(count: Int): List<String> = synchronized(recent) { recent.toList().takeLast(count) }

    /**
     * An uncaught exception, written synchronously: the process is about to die and the writer
     * queue with it. The recent lines go with it because the queue may still hold them.
     */
    fun recordCrash(thread: String, error: Throwable) {
        if (!enabled) return
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        val report = buildString {
            append("── crash ${timestamp()} on thread $thread\n")
            append(trace)
            append("── last lines before it\n")
            recentLines(CRASH_CONTEXT_LINES).forEach { append("  ").append(it).append('\n') }
            append('\n')
        }
        runCatching {
            directory.mkdirs()
            // The newest reports matter; a crash loop must not grow this without bound.
            val kept = crashes.takeIf { it.exists() }?.readText()?.takeLast(MAX_CRASH_BYTES - report.length).orEmpty()
            crashes.writeText(kept + report)
        }
    }

    /** Every log file, newest first. */
    fun files(): List<File> =
        (listOf(current) + (1..ROTATED_FILES).map { File(directory, "$it.log") }).filter { it.exists() }

    fun totalBytes(): Long = files().sumOf { it.length() } + (crashes.takeIf { it.exists() }?.length() ?: 0)

    /**
     * One text file with everything: crash reports first — they are why an archive is sent after
     * an incident — then the sessions, oldest to newest.
     */
    fun createArchive(outputDir: File, deviceInfo: String): File {
        flush()
        outputDir.mkdirs()
        outputDir.listFiles()?.forEach { it.delete() }
        val archive = File(outputDir, "construct-logs-${now().time / 1000}.txt")
        archive.bufferedWriter().use { out ->
            out.write("========================================\n")
            out.write("Konstruct Debug Logs\n")
            out.write("========================================\n")
            out.write("Exported: ${timestamp()}\n")
            out.write(deviceInfo)
            out.write("\n========================================\n\n")
            if (crashes.exists() && crashes.length() > 0) {
                out.write("=== CRASH REPORTS ===\n")
                out.write(crashes.readText())
                out.write("\n")
            }
            val sessions = files().reversed()
            sessions.forEachIndexed { i, file ->
                out.write("=== Log File ${i + 1}/${sessions.size}: ${file.name} ===\n")
                out.write(file.readText())
                out.write("\n\n")
            }
        }
        return archive
    }

    /** Remove every log and crash report; a fresh header follows, so the floor is a few hundred bytes. */
    fun clear() {
        if (!enabled) return
        writer.submit {
            files().forEach { it.delete() }
            crashes.delete()
            writeHeader()
        }.get(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        synchronized(recent) { recent.clear() }
    }

    /** Wait for every queued line to reach the file. */
    fun flush() {
        if (!enabled) return
        runCatching { writer.submit {}.get(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
    }

    private fun rotate() {
        // current → 1 → 2 → 3, and the oldest falls off.
        File(directory, "$ROTATED_FILES.log").delete()
        for (i in ROTATED_FILES - 1 downTo 1) File(directory, "$i.log").renameTo(File(directory, "${i + 1}.log"))
        current.renameTo(File(directory, "1.log"))
    }

    private fun writeHeader() {
        current.writeText(
            "========================================\n" +
                "Konstruct - Log Session\n" +
                "========================================\n" +
                header() +
                "Started: ${timestamp()}\n" +
                "========================================\n\n",
        )
    }

    private fun timestamp(): String = ISO.get()!!.format(now())

    companion object {
        const val MAX_FILE_BYTES = 5L * 1024 * 1024
        const val ROTATED_FILES = 3
        const val RECENT_CAPACITY = 60
        const val CRASH_CONTEXT_LINES = 40
        const val MAX_CRASH_BYTES = 256 * 1024
        private const val FLUSH_TIMEOUT_SECONDS = 5L

        /** Empty or absent recorded nothing; rotating it would push a real session out of the window. */
        fun shouldPreserveSession(existingBytes: Long?): Boolean = (existingBytes ?: 0) > 0

        private val ISO = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        }
    }
}
