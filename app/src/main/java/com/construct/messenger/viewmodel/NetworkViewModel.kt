package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.veil.VeilMode
import com.construct.messenger.veil.VeilProxy
import com.construct.messenger.veil.VeilState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Network screen: the VEIL switch and what the tunnel is doing. */
@HiltViewModel
class NetworkViewModel @Inject constructor(
    private val veil: VeilProxy,
) : ViewModel() {
    val veilState: StateFlow<VeilState> = veil.uiState

    fun setVeil(on: Boolean) {
        viewModelScope.launch { veil.setMode(if (on) VeilMode.ON else VeilMode.OFF) }
    }
}
