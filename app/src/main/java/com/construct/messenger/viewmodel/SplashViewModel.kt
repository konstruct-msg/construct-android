package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.local.OrientationStore
import com.construct.messenger.data.repository.AppLockRepository
import com.construct.messenger.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SplashUiState(
    val route: SplashRoute? = null
)

enum class SplashRoute {
    /** The server removed this device from its account: say so, then erase it. */
    Removed,
    Onboarding,
    Orientation,
    Main
}

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val orientationStore: OrientationStore,
    private val appLock: AppLockRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(SplashUiState())

    val uiState: StateFlow<SplashUiState> = mutableUiState.asStateFlow()

    fun decideNextRoute() {
        viewModelScope.launch {
            authRepository.restoreSession()
            val route = when {
                authRepository.authState.value.removed -> SplashRoute.Removed
                !authRepository.authState.value.isInitialized -> SplashRoute.Onboarding
                // Registered but never oriented (fresh registration or pre-feature upgrade).
                !orientationStore.completed.first() -> SplashRoute.Orientation
                else -> SplashRoute.Main
            }
            mutableUiState.update { it.copy(route = route) }
        }
    }

    /**
     * Erase everything the account left here — the same erase as account deletion, which also
     * ends the process; the next start is a fresh install. **Canon:** iOS `LocalDataWipe` after
     * `AuthViewModel.isRemovedDevice`. Only on [SplashRoute.Removed].
     */
    fun eraseRemovedDevice() {
        if (uiState.value.route == SplashRoute.Removed) appLock.eraseDevice()
    }
}
