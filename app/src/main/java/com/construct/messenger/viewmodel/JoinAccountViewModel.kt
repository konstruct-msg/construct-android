package com.construct.messenger.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.R
import com.construct.messenger.data.repository.AuthRepository
import com.construct.messenger.diagnostics.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.construct_core.DeviceJoinEnd
import uniffi.construct_core.DeviceLinkQr
import uniffi.construct_core.readDeviceLinkQr

data class JoinAccountUiState(
    val scanning: Boolean = false,
    val busy: Boolean = false,
    /** Flow B: the QR the account's device scans, while we wait for its approval. */
    val joinQr: String? = null,
    val linked: Boolean = false,
    @StringRes val error: Int? = null,
)

/**
 * Onboarding → "I already have an account" → Link this device. **Canon:** iOS `DeviceLinkViewModel` on the new
 * device: `scanAndLink` / `confirmLink` (flow A), `generateJoinRequestQR` + polling (flow B).
 */
@HiltViewModel
class JoinAccountViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val state = MutableStateFlow(JoinAccountUiState())
    val uiState: StateFlow<JoinAccountUiState> = state.asStateFlow()
    private var polling: Job? = null

    fun startScan() {
        polling?.cancel()
        state.update { it.copy(scanning = true, joinQr = null, error = null) }
    }

    fun stopScan() = state.update { it.copy(scanning = false) }

    /** Flow A: the account device's QR. */
    fun onScanned(text: String) {
        val offer = readDeviceLinkQr(text) as? DeviceLinkQr.Offer
        if (offer == null) {
            state.update { it.copy(scanning = false, error = R.string.device_link_invalid_qr) }
            return
        }
        if (offer.secret == null) Log.i(TAG, "link QR carries no secret — the account device cannot vouch for this one")
        state.update { it.copy(scanning = false, busy = true, error = null) }
        viewModelScope.launch {
            try {
                authRepository.linkDevice(offer)
                state.update { it.copy(busy = false, linked = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "confirmDeviceLink failed: ${e.message}")
                state.update { it.copy(busy = false, error = errorFor(e)) }
            }
        }
    }

    /** Flow B: show our QR and wait for the account's device. */
    fun showJoinRequest() {
        if (state.value.busy) return
        polling?.cancel()
        state.update { it.copy(busy = true, scanning = false, joinQr = null, error = null) }
        polling = viewModelScope.launch {
            val (qr, deviceId) = try {
                authRepository.submitJoinRequest()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "submitJoinRequest failed: ${e.message}")
                state.update { it.copy(busy = false, error = errorFor(e)) }
                return@launch
            }
            state.update { it.copy(busy = false, joinQr = qr) }
            val end = authRepository.awaitJoin(deviceId)
            if (end != null) Log.e(TAG, "join request ended: $end")
            state.update { it.copy(joinQr = null, linked = end == null, error = end?.let(::errorFor)) }
        }
    }

    fun dismissError() = state.update { it.copy(error = null) }

    @StringRes
    private fun errorFor(e: Exception): Int {
        val code = (e as? StatusException)?.status?.code ?: (e as? StatusRuntimeException)?.status?.code
        return when (code) {
            Status.Code.UNAUTHENTICATED -> R.string.device_link_expired
            else -> R.string.device_link_failed
        }
    }

    internal companion object {
        private const val TAG = "DeviceLink"

        @StringRes
        fun errorFor(end: DeviceJoinEnd): Int = when (end) {
            DeviceJoinEnd.REJECTED -> R.string.device_link_rejected
            DeviceJoinEnd.EXPIRED -> R.string.device_link_expired
            DeviceJoinEnd.SIGN_IN_REFUSED -> R.string.device_link_sign_in_refused
            DeviceJoinEnd.LET_IN_UNREACHABLE -> R.string.device_link_let_in_unreachable
        }
    }
}
