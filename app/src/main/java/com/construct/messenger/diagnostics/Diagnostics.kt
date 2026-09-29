package com.construct.messenger.diagnostics

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.construct.messenger.BuildConfig
import java.io.File

/**
 * The process's one [LogCollector], and the share sheet that sends its archive. **Canon:** iOS
 * `LogCollector.shared` + `DiagnosticLogShare`.
 *
 * Installed first thing in `KonstructApp.onCreate`; until then — and in JVM unit tests, which
 * never install it — [collector] is null and a log line goes to logcat only.
 */
object Diagnostics {
    @Volatile
    var collector: LogCollector? = null
        private set

    /** Logs are written in debug builds only; see [LogCollector]. */
    val isEnabled: Boolean get() = collector?.enabled == true

    fun install(context: Context) {
        if (collector != null) return
        val created = LogCollector(
            // Not backed up and not user-visible: a log is this device's, never a backup's.
            directory = File(context.noBackupFilesDir, "logs"),
            enabled = BuildConfig.DEBUG,
            header = { deviceInfo() },
        )
        collector = created
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            created.recordCrash(thread.name, error)
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Build the archive and offer it to whatever the user picks — mail, a messenger, Files.
     * Returns false when there is nothing to send.
     */
    fun share(context: Context): Boolean {
        val collector = collector?.takeIf { it.enabled } ?: return false
        val archive = runCatching {
            collector.createArchive(File(context.cacheDir, EXPORT_DIR), deviceInfo())
        }.getOrElse {
            Log.e("Diagnostics", "log archive not created", it)
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", archive)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, archive.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            Log.w("Diagnostics", "nothing can receive the log archive", e)
            false
        }
    }

    private fun deviceInfo(): String =
        "App Version: ${BuildConfig.VERSION_NAME}\n" +
            "Build: ${BuildConfig.VERSION_CODE}\n" +
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n"

    /** Under `cacheDir`; the one directory `res/xml/log_export_paths.xml` shares. */
    const val EXPORT_DIR = "logs-export"
}
