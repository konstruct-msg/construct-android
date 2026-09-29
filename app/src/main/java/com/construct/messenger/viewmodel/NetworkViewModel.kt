package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.repository.ConnectionRepository
import com.construct.messenger.transport.TransportRoute
import com.construct.messenger.transport.TransportRouter
import com.construct.messenger.ui.components.ConnectionStatus
import com.construct.messenger.veil.VeilMode
import com.construct.messenger.veil.VeilProxy
import com.construct.messenger.veil.VeilStartInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NetworkUiState(
    val connection: ConnectionStatus = ConnectionStatus.UNKNOWN,
    val lastHeartbeatAt: Long? = null,
    val mode: VeilMode = VeilMode.AUTO,
    val route: TransportRoute.State = TransportRoute.State.Direct(0),
    val info: VeilStartInfo = VeilStartInfo(),
)

/** Network screen: whether the stream is up, the protection mode, and — internal builds — the path. */
@HiltViewModel
class NetworkViewModel @Inject constructor(
    private val router: TransportRouter,
    connection: ConnectionRepository,
    veil: VeilProxy,
) : ViewModel() {
    val uiState: StateFlow<NetworkUiState> =
        combine(connection.status, connection.lastHeartbeatAt, veil.modeState, router.route, veil.startInfo, ::NetworkUiState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetworkUiState(mode = veil.modeState.value))

    fun setMode(mode: VeilMode) {
        viewModelScope.launch { router.setMode(mode) }
    }
}
