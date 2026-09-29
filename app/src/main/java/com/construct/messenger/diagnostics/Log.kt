package com.construct.messenger.diagnostics

/**
 * `android.util.Log`, and the log file beside it. Same calls, so a file switches to it by its
 * import alone. **Canon:** iOS `Log` (`Logger.swift`), which feeds `LogCollector` the same way.
 */
object Log {
    fun d(tag: String, msg: String, tr: Throwable? = null): Int = write("DEBUG", tag, msg, tr) { android.util.Log.d(tag, msg, tr) }
    fun i(tag: String, msg: String, tr: Throwable? = null): Int = write("INFO", tag, msg, tr) { android.util.Log.i(tag, msg, tr) }
    fun w(tag: String, msg: String, tr: Throwable? = null): Int = write("WARN", tag, msg, tr) { android.util.Log.w(tag, msg, tr) }
    fun w(tag: String, tr: Throwable): Int = write("WARN", tag, "", tr) { android.util.Log.w(tag, tr) }
    fun e(tag: String, msg: String, tr: Throwable? = null): Int = write("ERROR", tag, msg, tr) { android.util.Log.e(tag, msg, tr) }

    private inline fun write(level: String, tag: String, msg: String, tr: Throwable?, logcat: () -> Int): Int {
        Diagnostics.collector?.append(level, tag, if (tr == null) msg else "$msg — ${tr::class.java.simpleName}: ${tr.message}")
        return logcat()
    }
}
