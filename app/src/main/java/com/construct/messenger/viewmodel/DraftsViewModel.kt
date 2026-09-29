package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import com.construct.messenger.data.model.Draft
import com.construct.messenger.data.repository.DraftsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** Settings → Drafts (iOS `DraftsView`). */
@HiltViewModel
class DraftsViewModel @Inject constructor(
    private val repository: DraftsRepository,
) : ViewModel() {
    val drafts: StateFlow<List<Draft>> = repository.drafts

    fun add(text: String) = repository.add(text)
    fun delete(id: String) = repository.delete(id)
}
