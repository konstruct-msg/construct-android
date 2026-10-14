package com.construct.messenger.diagnostics

/**
 * `android.util.Log`, and the log file beside it. Same calls, so a file switches to it by its
 * import alone. **Canon:** iOS `Log` (`Logger.swift`), which feeds `LogCollector` the same way.
 */
object Log {
    fun d(tag: String, msg: String, tr: Throwable? = null): Int = write("DEBUG", tag, msg, tr) { m, t -> android.util.Log.d(tag, m, t) }
    fun i(tag: String, msg: String, tr: Throwable? = null): Int = write("INFO", tag, msg, tr) { m, t -> android.util.Log.i(tag, m, t) }
    fun w(tag: String, msg: String, tr: Throwable? = null): Int = write("WARN", tag, msg, tr) { m, t -> android.util.Log.w(tag, m, t) }
    fun w(tag: String, tr: Throwable): Int = write("WARN", tag, "", tr) { _, t -> android.util.Log.w(tag, t ?: tr) }
    fun e(tag: String, msg: String, tr: Throwable? = null): Int = write("ERROR", tag, msg, tr) { m, t -> android.util.Log.e(tag, m, t) }

    /** Every line, to logcat and the file alike, without a VEIL front's name in it ([LogRedaction]). */
    private inline fun write(level: String, tag: String, msg: String, tr: Throwable?, logcat: (String, Throwable?) -> Int): Int {
        val line = LogRedaction.redact(msg)
        val error = tr?.let(LogRedaction::redact)
        Diagnostics.collector?.append(level, tag, if (tr == null) line else "$line — ${tr::class.java.simpleName}: ${LogRedaction.redact(tr.message.orEmpty())}")
        return logcat(line, error)
    }
}
