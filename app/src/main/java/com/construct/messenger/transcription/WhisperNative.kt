package com.construct.messenger.transcription

import com.construct.messenger.diagnostics.Log

/**
 * whisper.cpp through `libconstruct_whisper.so` (native/whisper, built by scripts/build_whisper.sh;
 * TODO 116). The library is not in git: a checkout that has not built it runs without
 * transcription ([available] false) rather than failing to start.
 */
internal object WhisperNative {
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("construct_whisper") }
            .onFailure { Log.w("Whisper", "libconstruct_whisper.so not here — no transcription (scripts/build_whisper.sh)") }
            .isSuccess
    }

    /** A loaded model, or 0. */
    external fun open(path: String): Long

    external fun close(handle: Long)

    /** "<language>\n<text>", or null when the model failed. [language]: ISO 639-1, or "auto". */
    external fun transcribe(handle: Long, pcm: FloatArray, language: String, translate: Boolean, threads: Int): String?
}
