package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.repository.AuthRepository
import com.construct.messenger.domain.usecase.RegistrationStep
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

/**
 * **Canon:** iOS `RegistrationFlowView.RegistrationStageView` — [step] drives the same
 * stages (`null` = idle, otherwise mirrors [RegistrationStep]); [username]/[deviceId] back
 * the "complete" detail screen.
 */
data class OnboardingUiState(
    val step: RegistrationStep? = null,
    val username: String? = null,
    val deviceId: String? = null,
) {
    val isInitializing: Boolean
        get() = step != null && step !is RegistrationStep.Complete && step !is RegistrationStep.Error

    val errorMessage: String?
        get() = (step as? RegistrationStep.Error)?.message
}

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

    fun initializeIdentity(publicAlias: String) {
        if (mutableUiState.value.isInitializing) {
            return
        }

        val username = publicAlias.trim().ifEmpty { null }
        mutableUiState.update {
            it.copy(step = RegistrationStep.GeneratingKeys, username = username, deviceId = null)
        }

        viewModelScope.launch {
            try {
                authRepository.initializeIdentity(username) { step ->
                    mutableUiState.update { it.copy(step = step) }
                }
                val state = authRepository.authState.value
                mutableUiState.update {
                    it.copy(
                        step = RegistrationStep.Complete,
                        username = state.username,
                        deviceId = state.deviceId,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableUiState.update {
                    it.copy(step = RegistrationStep.Error(error.message ?: "Unable to initialize identity"))
                }
            }
        }
    }

    /** Called from the "complete" screen's continue button — mirrors iOS `onComplete`. */
    fun continueToMain() {
        if (mutableUiState.value.step is RegistrationStep.Complete) {
            viewModelScope.launch { mutableEvents.emit(OnboardingEvent.NavigateToMain) }
        }
    }

    /** Called from the "error" screen's try-again button — mirrors iOS `onDismiss`. */
    fun dismissError() {
        mutableUiState.update { it.copy(step = null) }
    }
}
