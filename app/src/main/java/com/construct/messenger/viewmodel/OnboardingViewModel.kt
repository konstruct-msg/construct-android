package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingUiState(
    val isInitializing: Boolean = false,
    val errorMessage: String? = null
)

sealed interface OnboardingEvent {
    data object NavigateToMain : OnboardingEvent
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(OnboardingUiState())
    private val mutableEvents = MutableSharedFlow<OnboardingEvent>()

    val uiState: StateFlow<OnboardingUiState> = mutableUiState.asStateFlow()
    val events: SharedFlow<OnboardingEvent> = mutableEvents.asSharedFlow()

    fun initializeIdentity() {
        if (mutableUiState.value.isInitializing) {
            return
        }

        mutableUiState.update {
            it.copy(isInitializing = true, errorMessage = null)
        }

        viewModelScope.launch {
            try {
                authRepository.initializeIdentity()
                mutableUiState.update { it.copy(isInitializing = false) }
                mutableEvents.emit(OnboardingEvent.NavigateToMain)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableUiState.update {
                    it.copy(
                        isInitializing = false,
                        errorMessage = error.message ?: "Unable to initialize identity"
                    )
                }
            }
        }
    }
}
