package com.construct.messenger.transcription

import javax.inject.Inject
import javax.inject.Singleton

/** What a chat needs to read its notes: the engine with the person's settings applied (TODO 116). */
@Singleton
class NoteTranscription @Inject constructor(
    private val transcriber: Transcriber,
    private val settings: TranscriptionSettings,
) {
    /** False in a build without the engine: no button is shown. */
    val available: Boolean get() = transcriber.isAvailable

    /** Voice notes are read as they appear (Settings; off by default, as on iOS). */
    val autoTranscribe: Boolean get() = settings.values.value.autoTranscribe

    suspend fun read(audio: ByteArray): Transcriber.Outcome {
        val v = settings.values.value
        return transcriber.transcribe(audio, language = v.language, translate = v.translate, model = v.model)
    }
}
