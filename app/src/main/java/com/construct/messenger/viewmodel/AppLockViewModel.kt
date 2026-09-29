package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.repository.AppLockRepository
import com.construct.messenger.data.repository.AppLockState
import com.construct.messenger.data.repository.LockDelay
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app lock for the lock screen, the PIN setup / disable screens and the Security rows.
 * PBKDF2 at 100 000 iterations takes a noticeable moment, so checks run off the main thread.
 */
@HiltViewModel
class AppLockViewModel @Inject constructor(
    private val repository: AppLockRepository,
) : ViewModel() {
    val lock: StateFlow<AppLockState> = repository.lock

    fun verify(pin: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            onResult(withContext(Dispatchers.Default) { repository.verify(pin) })
        }
    }

    /** Lock screen: a right PIN unlocks. */
    fun tryUnlock(pin: String, onWrong: () -> Unit) = verify(pin) { ok ->
        if (ok) repository.unlock() else onWrong()
    }

    fun setPin(pin: String, biometric: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.Default) { repository.setPin(pin) }
            repository.setBiometricEnabled(biometric)
            onDone()
        }
    }

    fun disablePin() = repository.disablePin()
    fun unlockWithBiometrics() = repository.unlock()
    fun setBiometricEnabled(enabled: Boolean) = repository.setBiometricEnabled(enabled)
    fun setLockDelay(delay: LockDelay) = repository.setLockDelay(delay)
    fun eraseDevice() = repository.eraseDevice()
}
