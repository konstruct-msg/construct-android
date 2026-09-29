package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.repository.AuthRepository
import com.construct.messenger.data.repository.ContactsRepository
import com.construct.messenger.domain.usecase.RegistrationStep
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    /** The public alias as typed, lowercased (iOS lowercases as you type). */
    val alias: String = "",
    val aliasStatus: AliasStatus = AliasStatus.NONE,
) {
    /** iOS `OnboardingView.canProceed`: no alias, or one the server said is free. */
    val canProceed: Boolean
        get() = alias.isBlank() || aliasStatus == AliasStatus.AVAILABLE

    val isInitializing: Boolean
        get() = step != null && step !is RegistrationStep.Complete && step !is RegistrationStep.Error

    val errorMessage: String?
        get() = (step as? RegistrationStep.Error)?.message
}

/** What the line under the alias field says. **Canon:** iOS `OnboardingView.validateUsername`. */
enum class AliasStatus { NONE, TOO_SHORT, INVALID_CHARS, CHECKING, AVAILABLE, UNAVAILABLE, CHECK_FAILED }

sealed interface OnboardingEvent {
    data object NavigateToMain : OnboardingEvent
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val contactsRepository: ContactsRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(OnboardingUiState())
    private val mutableEvents = MutableSharedFlow<OnboardingEvent>()

    val uiState: StateFlow<OnboardingUiState> = mutableUiState.asStateFlow()
    val events: SharedFlow<OnboardingEvent> = mutableEvents.asSharedFlow()

    private var availabilityJob: Job? = null

    /**
     * Lowercases, checks length and characters locally, then asks the server 400 ms after the
     * last keystroke — iOS `scheduleUsernameAvailabilityCheck`. An answer for a stale alias is
     * dropped.
     */
    fun setAlias(value: String) {
        val alias = value.lowercase()
        val trimmed = alias.trim()
        val local = when {
            trimmed.isEmpty() -> AliasStatus.NONE
            trimmed.length < 3 -> AliasStatus.TOO_SHORT
            !trimmed.all { it.isLetterOrDigit() || it == '_' } -> AliasStatus.INVALID_CHARS
            else -> AliasStatus.CHECKING
        }
        mutableUiState.update { it.copy(alias = alias, aliasStatus = local) }
        availabilityJob?.cancel()
        if (local != AliasStatus.CHECKING) return
        availabilityJob = viewModelScope.launch {
            delay(AVAILABILITY_DEBOUNCE_MS)
            val answer = contactsRepository.checkUsername(trimmed)
            ensureActive()
            if (mutableUiState.value.alias.trim() != trimmed) return@launch
            val status = when {
                answer.checkFailed -> AliasStatus.CHECK_FAILED
                answer.available -> AliasStatus.AVAILABLE
                else -> AliasStatus.UNAVAILABLE
            }
            mutableUiState.update { it.copy(aliasStatus = status) }
        }
    }

    fun initializeIdentity() = initializeIdentity(mutableUiState.value.alias)

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
                if (username != null) {
                    val availability = contactsRepository.checkUsername(username)
                    if (!availability.available) {
                        mutableUiState.update {
                            it.copy(step = RegistrationStep.Error(availability.reason ?: "alias taken"))
                        }
                        return@launch
                    }
                }
                authRepository.initializeIdentity(username) { step ->
                    mutableUiState.update {
                        it.copy(step = step, deviceId = (step as? RegistrationStep.Complete)?.deviceId ?: it.deviceId)
                    }
                }
                val state = authRepository.authState.value
                mutableUiState.update {
                    it.copy(
                        step = RegistrationStep.Complete(state.deviceId),
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

    private companion object {
        const val AVAILABILITY_DEBOUNCE_MS = 400L
    }

    /** Called from the "error" screen's try-again button — mirrors iOS `onDismiss`. */
    fun dismissError() {
        mutableUiState.update { it.copy(step = null) }
    }
}
