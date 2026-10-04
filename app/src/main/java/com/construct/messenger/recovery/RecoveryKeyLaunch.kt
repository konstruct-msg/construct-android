package com.construct.messenger.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Every launch of a signed-in account: accounts registered before the silent key get theirs here,
 * and a key whose upload never got an answer is retried. Never shows a screen — a launch is not the
 * moment for setup. **Canon:** iOS `ContentView` `.task(id: isAuthenticated && orientation…)`.
 */
@HiltViewModel
class RecoveryKeyLaunchViewModel @Inject constructor(
    repository: RecoveryRepository,
    provisioner: RecoveryKeyProvisioner,
) : ViewModel() {
    init {
        repository.userId()?.let { userId ->
            viewModelScope.launch { provisioner.ensureKey(userId) }
        }
    }
}
