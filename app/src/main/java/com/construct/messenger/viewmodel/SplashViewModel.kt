package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SplashUiState(
    val route: SplashRoute? = null
)

enum class SplashRoute {
    Onboarding,
    Main
}

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(SplashUiState())

    val uiState: StateFlow<SplashUiState> = mutableUiState.asStateFlow()

    fun decideNextRoute() {
        viewModelScope.launch {
            val route = if (authRepository.authState.value.isInitialized) {
                SplashRoute.Main
            } else {
                SplashRoute.Onboarding
            }
            mutableUiState.update { it.copy(route = route) }
        }
    }
}
