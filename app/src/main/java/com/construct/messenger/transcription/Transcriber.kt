package com.construct.messenger.transcription

import com.construct.messenger.diagnostics.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speech to text on this device (TODO 116). **Canon:** iOS `VoiceTranscriptionService` — the
 * audio and the text never leave the phone (`client/specs/ON_DEVICE_STT_SPEC.md`). Android has
 * whisper.cpp only: the platform recogniser reads files from API 33 and is absent without GMS.
 */
@Singleton
class Transcriber @Inject constructor(private val models: WhisperModels) {
    data class Transcript(val text: String, val language: String?)

    sealed interface Outcome {
        data class Done(val transcript: Transcript) : Outcome
        /** No model downloaded yet: Settings → Transcription. */
        data object NoModel : Outcome
        /** This build has no engine (scripts/build_whisper.sh was not run). */
        data object Unavailable : Outcome
        data object Failed : Outcome
    }

    private val running = Mutex()
    private var loaded: Pair<WhisperModel, Long>? = null

    val isAvailable: Boolean get() = WhisperNative.available

    /**
     * [audio]: the voice note's or video note's container, decrypted. [model]: the one to use, or
     * the largest installed. One at a time: a second request waits for the first.
     */
    suspend fun transcribe(
        audio: ByteArray,
        language: String = AUTO,
        translate: Boolean = false,
        model: WhisperModel? = null,
    ): Outcome = withContext(Dispatchers.Default) {
        if (!WhisperNative.available) return@withContext Outcome.Unavailable
        val use = model?.takeIf { models.file(it).isFile } ?: models.installed().lastOrNull()
            ?: return@withContext Outcome.NoModel
        running.withLock {
            try {
                val pcm = PcmDecoder.decode(audio)
                val handle = handleFor(use) ?: return@withLock Outcome.Failed
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_THREADS)
                val raw = WhisperNative.transcribe(handle, pcm, language, translate, threads) ?: return@withLock Outcome.Failed
                Outcome.Done(parse(raw))
            } catch (e: Exception) {
                Log.w(TAG, "transcription failed", e)
                Outcome.Failed
            }
        }
    }

    /** The model stays loaded between notes; another model replaces it. */
    private fun handleFor(model: WhisperModel): Long? {
        loaded?.let { (m, h) -> if (m == model) return h else WhisperNative.close(h) }
        loaded = null
        val handle = WhisperNative.open(models.file(model).path)
        if (handle == 0L) return null
        loaded = model to handle
        return handle
    }

    companion object {
        const val AUTO = "auto"
        private const val TAG = "Transcriber"
        private const val MAX_THREADS = 4

        /** The bridge's "<language>\n<text>"; whisper puts a space before each segment. */
        fun parse(raw: String): Transcript {
            val newline = raw.indexOf('\n')
            val language = raw.substring(0, newline.coerceAtLeast(0)).takeIf { it.isNotBlank() }
            val text = (if (newline >= 0) raw.substring(newline + 1) else raw).trim()
            return Transcript(text, language)
        }
    }
}
