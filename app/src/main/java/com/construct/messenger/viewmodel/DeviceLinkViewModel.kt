package com.construct.messenger.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.BuildConfig
import com.construct.messenger.R
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.repository.DevicesRepository
import com.construct.messenger.devicelink.DeviceCertificateIssuer
import com.construct.messenger.devicelink.DeviceLinkQr
import com.construct.messenger.diagnostics.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.construct_core.newDeviceLinkSecret

data class DeviceLinkUiState(
    /** Flow A: the QR this device shows, and when its token expires (unix seconds). */
    val offerQr: String? = null,
    val offerExpiresAt: Long = 0,
    val busy: Boolean = false,
    val scanning: Boolean = false,
    /** Flow B: a scanned join request waiting for the user to approve it. */
    val pending: DeviceLinkQr.JoinRequest? = null,
    /** Flow B: the name of the device just approved. */
    val approved: String? = null,
    @StringRes val error: Int? = null,
)

/**
 * Settings → Linked devices → Link a device, on the account's device. Debug builds only, as on
 * iOS (`DeviceLinkOfferPolicy`); release builds have no way here. **Canon:** iOS
 * `DeviceLinkViewModel.generateLinkCode` / `approveJoinRequest`.
 *
 * Either flow ends by vouching for the new device (`DeviceCertificateIssuer`) once its keys are
 * in the directory, so its contacts read it as vouched for, not as a device the server added.
 */
@HiltViewModel
class DeviceLinkViewModel @Inject constructor(
    private val devices: DevicesRepository,
    private val cryptoManager: CryptoManager,
    private val issuer: DeviceCertificateIssuer,
) : ViewModel() {
    private val state = MutableStateFlow(DeviceLinkUiState())
    val uiState: StateFlow<DeviceLinkUiState> = state.asStateFlow()

    /** Flow A: a token from the server, and a QR with our fingerprint and the link secret. */
    fun showOffer() {
        if (!BuildConfig.DEBUG || state.value.busy) return
        state.update { it.copy(busy = true, error = null, scanning = false, approved = null) }
        viewModelScope.launch {
            try {
                val (token, expiresAt) = devices.initiateLink()
                val fingerprint = runCatching { cryptoManager.linkFingerprint() }
                    .onFailure { Log.w(TAG, "no link fingerprint: ${it.message}") }
                    .getOrNull()
                // The secret the new device proves its keys under. In the QR and nowhere else.
                val secret = newDeviceLinkSecret()
                issuer.awaitLink(token, secret)
                state.update { it.copy(busy = false, offerQr = DeviceLinkQr.offer(token, fingerprint, secret), offerExpiresAt = expiresAt) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "initiateDeviceLink failed: ${e.message}")
                state.update { it.copy(busy = false, error = R.string.device_link_failed) }
            }
        }
    }

    fun startScan() {
        if (!BuildConfig.DEBUG) return
        state.update { it.copy(scanning = true, offerQr = null, error = null, approved = null) }
    }

    fun stopScan() = state.update { it.copy(scanning = false) }

    /** Flow B: a scanned code. Only a join request is ours; it waits for [approve]. */
    fun onScanned(text: String) {
        val request = DeviceLinkQr.parseJoinRequest(text.trim())
        state.update {
            if (request == null) it.copy(scanning = false, error = R.string.device_link_invalid_qr)
            else it.copy(scanning = false, pending = request)
        }
    }

    fun dismissPending() = state.update { it.copy(pending = null) }

    fun approve() {
        val request = state.value.pending ?: return
        if (!BuildConfig.DEBUG) return
        state.update { it.copy(pending = null, busy = true, error = null) }
        viewModelScope.launch {
            try {
                devices.approveJoinRequest(request.deviceId)
                // The QR's `fp` names the new device's keys; the core signs them only if the
                // directory serves exactly those. A QR without it leaves the device unvouched.
                val fp = request.fingerprint
                if (fp != null) issuer.vouch(request.deviceId, fp)
                else Log.i(TAG, "device=${request.deviceId.take(8)}… QR names no fingerprint — not vouched")
                state.update { it.copy(busy = false, approved = request.name) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "approveJoinRequest failed: ${e.message}")
                state.update { it.copy(busy = false, error = R.string.device_link_failed) }
            }
        }
    }

    fun dismissError() = state.update { it.copy(error = null) }

    private companion object {
        const val TAG = "DeviceLink"
    }
}
