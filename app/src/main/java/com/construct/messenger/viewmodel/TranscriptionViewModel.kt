package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.transcription.Transcriber
import com.construct.messenger.transcription.TranscriptionSettings
import com.construct.messenger.transcription.WhisperModel
import com.construct.messenger.transcription.WhisperModels
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TranscriptionUiState(
    val settings: TranscriptionSettings.Values = TranscriptionSettings.Values(),
    val models: Map<WhisperModel, WhisperModels.State> = emptyMap(),
    val bytesOnDisk: Long = 0,
    /** This build carries the engine. */
    val available: Boolean = true,
)

/** Settings → Transcription (TODO 116). **Canon:** iOS `STTSettingsSection`. */
@HiltViewModel
class TranscriptionViewModel @Inject constructor(
    private val settings: TranscriptionSettings,
    private val models: WhisperModels,
    transcriber: Transcriber,
) : ViewModel() {
    private val available = transcriber.isAvailable

    val uiState: StateFlow<TranscriptionUiState> = combine(settings.values, models.states) { values, states ->
        TranscriptionUiState(values, states, models.bytesOnDisk(), available)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TranscriptionUiState(available = available))

    /** Downloads on the screen's scope: leaving Settings stops one; the part is deleted. */
    private val downloads = mutableMapOf<WhisperModel, Job>()

    fun setAutoTranscribe(on: Boolean) = settings.setAutoTranscribe(on)
    fun setLanguage(code: String) = settings.setLanguage(code)
    fun setTranslate(on: Boolean) = settings.setTranslate(on)
    fun use(model: WhisperModel) = settings.setModel(model)

    fun download(model: WhisperModel) {
        if (downloads[model]?.isActive == true) return
        downloads[model] = viewModelScope.launch {
            // The first model in, nothing chosen yet: it is the one used.
            if (models.download(model) && settings.values.value.model == null) settings.setModel(model)
        }
    }

    fun cancel(model: WhisperModel) {
        downloads.remove(model)?.cancel()
    }

    fun delete(model: WhisperModel) {
        models.delete(model)
        if (settings.values.value.model == model) settings.setModel(models.installed().lastOrNull())
    }
}
