package com.construct.messenger.transcription

import android.content.Context
import com.construct.messenger.diagnostics.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * The speech models on this device: fetched when the person asks, checked, deleted when they ask.
 * **Canon:** iOS `WhisperModelManager`. Under `files/whisper-models/`; a download in progress is
 * `<name>.part` and becomes the model only once its SHA-256 matches [WhisperModel.sha256].
 */
@Singleton
class WhisperModels internal constructor(
    private val dir: File,
    /** The bytes at a URL; the network in the app, a stream in tests. */
    private val fetch: (String) -> InputStream,
    /** The digest a model must have; the pinned one, but for tests that cannot fetch 75 MB. */
    private val pinned: (WhisperModel) -> String = { it.sha256 },
) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, DIR), ::httpGet)

    sealed interface State {
        data object Absent : State
        data class Downloading(val fraction: Float) : State
        data object Installed : State
        /** The last download did not finish or did not match; the file is gone. */
        data object Failed : State
    }

    private val _states = MutableStateFlow(WhisperModel.entries.associateWith { stateOnDisk(it) })
    val states: StateFlow<Map<WhisperModel, State>> = _states.asStateFlow()

    fun file(model: WhisperModel): File = File(dir, model.fileName)

    /** The models ready to use, smallest first. */
    fun installed(): List<WhisperModel> = WhisperModel.entries.filter { file(it).isFile }

    /** Fetch [model] and keep it if its digest is the pinned one. True when it is installed. */
    suspend fun download(model: WhisperModel): Boolean = withContext(Dispatchers.IO) {
        if (file(model).isFile) return@withContext true
        set(model, State.Downloading(0f))
        dir.mkdirs()
        val part = File(dir, model.fileName + ".part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            fetch(model.url).use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER)
                    var lastShown = 0f
                    while (true) {
                        if (!coroutineContext.isActive) throw CancellationException()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        written += n
                        val fraction = (written.toFloat() / model.bytes).coerceAtMost(1f)
                        if (fraction - lastShown >= 0.01f) {
                            lastShown = fraction
                            set(model, State.Downloading(fraction))
                        }
                    }
                }
            }
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (got != pinned(model)) {
                Log.e(TAG, "${model.fileName}: SHA-256 $got, expected ${pinned(model)} — not kept")
                part.delete()
                set(model, State.Failed)
                return@withContext false
            }
            check(part.renameTo(file(model))) { "could not move ${model.fileName} into place" }
            set(model, State.Installed)
            true
        } catch (e: CancellationException) {
            part.delete()
            set(model, State.Absent)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "${model.fileName} did not download", e)
            part.delete()
            set(model, State.Failed)
            false
        }
    }

    fun delete(model: WhisperModel) {
        file(model).delete()
        set(model, State.Absent)
    }

    /** Bytes the installed models take. */
    fun bytesOnDisk(): Long = installed().sumOf { file(it).length() }

    private fun set(model: WhisperModel, state: State) = _states.update { it + (model to state) }

    private fun stateOnDisk(model: WhisperModel): State = if (file(model).isFile) State.Installed else State.Absent

    private companion object {
        const val TAG = "WhisperModels"
        const val DIR = "whisper-models"
        const val BUFFER = 64 * 1024

        fun httpGet(url: String): InputStream {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            // Hugging Face answers with a redirect to its CDN; HttpURLConnection follows it on https.
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                error("HTTP $code for $url")
            }
            return connection.inputStream
        }
    }
}
