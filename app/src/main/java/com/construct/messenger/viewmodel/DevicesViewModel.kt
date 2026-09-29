package com.construct.messenger.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.R
import com.construct.messenger.data.model.LinkedDevice
import com.construct.messenger.data.repository.AuthRepository
import com.construct.messenger.data.repository.DevicesRepository
import com.construct.messenger.diagnostics.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DevicesUiState(
    val devices: List<LinkedDevice> = emptyList(),
    val loading: Boolean = false,
    /** What failed, as a string resource; shown once and cleared by [DevicesViewModel.dismissError]. */
    @StringRes val error: Int? = null,
    val signingOut: Boolean = false,
) {
    val current: LinkedDevice? get() = devices.firstOrNull { it.isCurrent }
    val others: List<LinkedDevice> get() = devices.filterNot { it.isCurrent }
}

/**
 * Settings → Linked devices.
 *
 * **Canon:** iOS `DevicesView` — list, revoke one, sign out this / the others / all. Revoking the
 * others is one `RevokeDevice` each, as on iOS; the primary device is skipped because the server
 * refuses it.
 */
@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val devicesRepository: DevicesRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val state = MutableStateFlow(DevicesUiState())
    val uiState: StateFlow<DevicesUiState> = state.asStateFlow()

    private val events = MutableSharedFlow<AccountEvent>(extraBufferCapacity = 1)
    val eventsFlow: SharedFlow<AccountEvent> = events.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val result = attempt(R.string.devices_load_failed) { devicesRepository.list() }
            state.update { it.copy(loading = false, devices = result ?: it.devices) }
        }
    }

    fun revoke(device: LinkedDevice) {
        viewModelScope.launch {
            if (attempt(R.string.devices_revoke_failed) { devicesRepository.revoke(device.id) } != null) refresh()
        }
    }

    fun revokeOthers() {
        viewModelScope.launch {
            state.value.others.filterNot { it.isPrimary }.forEach { device ->
                attempt(R.string.devices_revoke_failed) { devicesRepository.revoke(device.id) }
            }
            refresh()
        }
    }

    fun signOut(allDevices: Boolean) {
        if (state.value.signingOut) return
        state.update { it.copy(signingOut = true) }
        viewModelScope.launch {
            authRepository.logout(allDevices)
            events.emit(AccountEvent.SignedOut)
        }
    }

    fun dismissError() = state.update { it.copy(error = null) }

    /** Runs [block]; a failure is logged, becomes [DevicesUiState.error] and a null result. */
    private suspend fun <T> attempt(@StringRes error: Int, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "device request failed", e)
        state.update { it.copy(error = error) }
        null
    }

    private companion object {
        const val TAG = "DevicesViewModel"
    }
}
