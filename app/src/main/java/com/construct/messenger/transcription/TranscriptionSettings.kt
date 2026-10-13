package com.construct.messenger.transcription

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings → Transcription. **Canon:** iOS `STTSettingsSection` — auto-transcribe off by default
 * (`stt_auto_transcribe`), the language (auto-detect or one of whisper's), translate to English,
 * and which downloaded model reads. Kept on this device only.
 */
@Singleton
class TranscriptionSettings @Inject constructor(@ApplicationContext context: Context) {
    data class Values(
        val autoTranscribe: Boolean = false,
        /** ISO 639-1, or [Transcriber.AUTO]. */
        val language: String = Transcriber.AUTO,
        val translate: Boolean = false,
        /** Null: the largest installed. */
        val model: WhisperModel? = null,
    )

    private val prefs = context.getSharedPreferences("transcription", Context.MODE_PRIVATE)
    private val _values = MutableStateFlow(read())
    val values: StateFlow<Values> = _values.asStateFlow()

    fun setAutoTranscribe(on: Boolean) = write { it.copy(autoTranscribe = on) }
    fun setLanguage(code: String) = write { it.copy(language = code) }
    fun setTranslate(on: Boolean) = write { it.copy(translate = on) }
    fun setModel(model: WhisperModel?) = write { it.copy(model = model) }

    private fun read() = Values(
        autoTranscribe = prefs.getBoolean(KEY_AUTO, false),
        language = prefs.getString(KEY_LANGUAGE, null) ?: Transcriber.AUTO,
        translate = prefs.getBoolean(KEY_TRANSLATE, false),
        model = prefs.getString(KEY_MODEL, null)?.let { runCatching { WhisperModel.valueOf(it) }.getOrNull() },
    )

    private fun write(change: (Values) -> Values) {
        val v = change(_values.value)
        prefs.edit()
            .putBoolean(KEY_AUTO, v.autoTranscribe)
            .putString(KEY_LANGUAGE, v.language)
            .putBoolean(KEY_TRANSLATE, v.translate)
            .putString(KEY_MODEL, v.model?.name)
            .apply()
        _values.value = v
    }

    companion object {
        private const val KEY_AUTO = "auto_transcribe"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_TRANSLATE = "translate"
        private const val KEY_MODEL = "model"

        /** iOS's list (`STTSettingsSection`), in its order. */
        val LANGUAGES = listOf(
            "en", "ru", "de", "fr", "es", "pt", "it", "nl", "pl", "uk", "tr", "ar", "ja", "zh", "ko",
            "sv", "da", "fi", "no", "cs", "sk", "ro", "hu", "bg", "hr", "sr", "vi", "id", "ms", "th",
        )
    }
}
