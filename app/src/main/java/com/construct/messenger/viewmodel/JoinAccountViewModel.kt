package com.construct.messenger.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.R
import com.construct.messenger.data.repository.AuthRepository
import com.construct.messenger.devicelink.DeviceLinkOfferPolicy
import com.construct.messenger.devicelink.DeviceLinkQr
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.domain.usecase.JoinRequestEnded
import dagger.hilt.android.lifecycle.HiltViewModel
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class JoinAccountUiState(
    val scanning: Boolean = false,
    val busy: Boolean = false,
    /** Flow B: the QR the account's device scans, while we wait for its approval. */
    val joinQr: String? = null,
    val linked: Boolean = false,
    @StringRes val error: Int? = null,
)

/**
 * Onboarding → "I already have an account" → Link this device. Debug and tester builds only (the
 * card and route exist only when [DeviceLinkOfferPolicy.isLinkingOffered]). **Canon:** iOS `DeviceLinkViewModel` on the new
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
        if (!DeviceLinkOfferPolicy.isLinkingOffered) return
        polling?.cancel()
        state.update { it.copy(scanning = true, joinQr = null, error = null) }
    }

    fun stopScan() = state.update { it.copy(scanning = false) }

    /** Flow A: the account device's QR. */
    fun onScanned(text: String) {
        val offer = DeviceLinkQr.parseOffer(text.trim())
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

    /** Flow B: show our QR and wait — every 3 s, at most 10 min, as iOS. */
    fun showJoinRequest() {
        if (!DeviceLinkOfferPolicy.isLinkingOffered || state.value.busy) return
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
            val deadline = System.currentTimeMillis() + POLL_FOR_MS
            while (System.currentTimeMillis() < deadline) {
                delay(POLL_MS)
                try {
                    if (authRepository.pollJoinRequest(deviceId)) {
                        state.update { it.copy(joinQr = null, linked = true) }
                        return@launch
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: JoinRequestEnded) {
                    state.update { it.copy(joinQr = null, error = if (e.rejected) R.string.device_link_rejected else R.string.device_link_expired) }
                    return@launch
                } catch (e: Exception) {
                    Log.d(TAG, "join request status: ${e.message}") // transient: keep asking
                }
            }
            state.update { it.copy(joinQr = null, error = R.string.device_link_expired) }
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

    private companion object {
        const val TAG = "DeviceLink"
        const val POLL_MS = 3_000L
        const val POLL_FOR_MS = 10 * 60_000L
    }
}
