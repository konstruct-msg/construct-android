package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.repository.StorageRepository
import com.construct.messenger.data.repository.StorageSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DataStorageUiState(
    val settings: StorageSettings = StorageSettings(),
    /** What the media cache takes now; null until measured. */
    val cachedBytes: Long? = null,
    val clearing: Boolean = false,
)

/** Settings → Data & storage. */
@HiltViewModel
class DataStorageViewModel @Inject constructor(
    private val storage: StorageRepository,
) : ViewModel() {
    private val usage = MutableStateFlow(DataStorageUiState())

    val uiState: StateFlow<DataStorageUiState> = combine(storage.settings, usage) { settings, u ->
        u.copy(settings = settings)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DataStorageUiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { usage.update { it.copy(cachedBytes = storage.cachedBytes()) } }
    }

    fun setLimit(bytes: Long) {
        viewModelScope.launch {
            storage.setLimit(bytes)
            usage.update { it.copy(cachedBytes = storage.cachedBytes()) }
        }
    }

    fun setKeepDays(days: Int) {
        viewModelScope.launch {
            storage.setKeepDays(days)
            usage.update { it.copy(cachedBytes = storage.cachedBytes()) }
        }
    }

    fun clear() {
        if (usage.value.clearing) return
        usage.update { it.copy(clearing = true) }
        viewModelScope.launch {
            storage.clear()
            usage.update { it.copy(clearing = false, cachedBytes = storage.cachedBytes()) }
        }
    }
}
