package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.model.Draft
import com.construct.messenger.data.repository.DraftsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Settings → Drafts (iOS `DraftsView`). */
@HiltViewModel
class DraftsViewModel @Inject constructor(
    private val repository: DraftsRepository,
) : ViewModel() {
    val drafts: StateFlow<List<Draft>> = repository.drafts

    /** [onSaved] runs once the note is stored; until then the text stays in the editor. */
    fun add(text: String, onSaved: () -> Unit) {
        viewModelScope.launch { if (repository.add(text)) onSaved() }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }
}
