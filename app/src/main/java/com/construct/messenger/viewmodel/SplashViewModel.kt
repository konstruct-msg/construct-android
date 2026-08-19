package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.local.OrientationStore
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
    Onboarding,
    Orientation,
    Main
}

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val orientationStore: OrientationStore,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(SplashUiState())

    val uiState: StateFlow<SplashUiState> = mutableUiState.asStateFlow()

    fun decideNextRoute() {
        viewModelScope.launch {
            authRepository.restoreSession()
            val route = when {
                !authRepository.authState.value.isInitialized -> SplashRoute.Onboarding
                // Registered but never oriented (fresh registration or pre-feature upgrade).
                !orientationStore.completed.first() -> SplashRoute.Orientation
                else -> SplashRoute.Main
            }
            mutableUiState.update { it.copy(route = route) }
        }
    }
}
