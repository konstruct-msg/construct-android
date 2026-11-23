package com.construct.messenger.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.local.Note
import com.construct.messenger.data.model.ForwardItem
import com.construct.messenger.data.model.MessageMedia
import com.construct.messenger.data.repository.ForwardDrafts
import com.construct.messenger.data.repository.MessagesRepository
import com.construct.messenger.data.repository.NotesRepository
import com.construct.messenger.media.VoicePlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Settings → Notes: the list, and what a search finds. **Canon:** iOS `NotesModel`. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NotesViewModel @Inject constructor(
    private val repository: NotesRepository,
) : ViewModel() {
    /** [notes] is null until the first read: an empty list is not yet "no notes". */
    data class UiState(val notes: List<Note>? = null, val query: String = "") {
        val isSearching: Boolean get() = query.isNotBlank()
    }

    private val query = MutableStateFlow("")
    private val limit = MutableStateFlow(PAGE)

    val ui: StateFlow<UiState> = combine(query, limit, ::Pair).flatMapLatest { (q, n) ->
        (if (q.isBlank()) repository.observe(n) else repository.search(q)).map { UiState(it, q) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun setQuery(value: String) {
        query.value = value
    }

    /** Called as row [index] shows: the next page once the last one held is near. */
    fun shown(index: Int) {
        val state = ui.value
        if (!state.isSearching && index >= limit.value - PREFETCH && (state.notes?.size ?: 0) >= limit.value) limit.value += PAGE
    }

    /** [onSaved] runs once the note is stored; until then the text stays in the editor. */
    fun add(text: String, onSaved: () -> Unit) {
        viewModelScope.launch { if (repository.add(text)) onSaved() }
    }

    fun togglePin(note: Note) {
        viewModelScope.launch { repository.setPinned(note, !note.isPinned) }
    }

    fun delete(note: Note) {
        viewModelScope.launch { repository.delete(note) }
    }

    private companion object {
        const val PAGE = 60
        const val PREFETCH = 12
    }
}

/** One note. **Canon:** iOS `NoteView`. */
@HiltViewModel
class NoteViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: NotesRepository,
    private val forwardDrafts: ForwardDrafts,
    private val player: VoicePlayer,
    messages: MessagesRepository,
) : ViewModel() {
    private val id: String = requireNotNull(savedStateHandle.get<String>("noteId"))

    /** [gone]: it was here and is not. */
    data class UiState(val note: Note? = null, val gone: Boolean = false)

    val ui: StateFlow<UiState> = repository.observe(id).map { UiState(it, gone = it == null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    val actions = MediaActions(viewModelScope, messages)
    val playing: StateFlow<VoicePlayer.Playing?> = player.state
    val voiceRate: StateFlow<Float> get() = player.rate

    private val _problem = MutableStateFlow(false)
    /** The last write was refused. */
    val problem: StateFlow<Boolean> = _problem.asStateFlow()

    fun problemShown() {
        _problem.value = false
    }

    fun edit(note: Note, text: String, onSaved: () -> Unit) {
        viewModelScope.launch { if (repository.edit(note, text)) onSaved() else _problem.value = true }
    }

    fun togglePin(note: Note) {
        viewModelScope.launch { repository.setPinned(note, !note.isPinned) }
    }

    fun delete(note: Note) {
        viewModelScope.launch { repository.delete(note) }
    }

    /**
     * The picked chat sends it as its own message, as it sends a forward: nothing of the note —
     * whose words they were, when — goes with it. True when there is something to send.
     */
    fun send(note: Note, toContactId: String): Boolean {
        val item = ForwardItem.of(note.asMessage()) ?: return false
        forwardDrafts.hand(listOf(item), toContactId)
        return true
    }

    /** Played here, in no chat: the now-playing banner has nowhere to open. */
    fun toggleVoice(voice: MessageMedia.Voice) {
        val audioId = voice.audio.mediaId
        if (player.state.value?.mediaId == audioId) player.toggle(audioId, ByteArray(0))
        else actions.withBytes(voice.audio) { player.toggle(audioId, it) }
    }

    fun seekVoice(voice: MessageMedia.Voice, fraction: Float) {
        val audioId = voice.audio.mediaId
        if (player.state.value?.mediaId == audioId) player.seek(audioId, ByteArray(0), fraction)
        else actions.withBytes(voice.audio) { player.seek(audioId, it, fraction) }
    }

    fun cycleVoiceRate() = player.cycleRate()
}
