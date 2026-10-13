package com.construct.messenger.data.auth

import com.construct.messenger.diagnostics.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The server has said this device was removed from its account (TODO 126,
 * `decisions/sign-out-wipes-the-device.md`): REMOVED, over direct TLS ([DeviceRefusalReading]).
 *
 * Heard at a device sign-in (`LoginUseCase`, at start without a session) and — since 2026-10-13 —
 * at a token refresh. A device with a stored session never signs in again, it only refreshes; so
 * until then a device removed from another one lived on with all its data and every call refused.
 * The app shows the notice over whatever is on screen and erases itself (`MainActivity`).
 */
@Singleton
class DeviceRemoval @Inject constructor() {
    private val _removed = MutableStateFlow(false)
    val removed: StateFlow<Boolean> = _removed.asStateFlow()

    fun heard(where: String) {
        if (_removed.value) return
        Log.e(TAG, "the server says this device was removed ($where) — erasing")
        _removed.value = true
    }

    private companion object {
        const val TAG = "DeviceRemoval"
    }
}
