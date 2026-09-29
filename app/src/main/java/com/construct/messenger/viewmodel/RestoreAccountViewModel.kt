package com.construct.messenger.viewmodel

import com.construct.messenger.diagnostics.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.R
import com.construct.messenger.data.repository.AuthRepository
import com.construct.messenger.domain.usecase.RecoverRefusal
import com.construct.messenger.domain.usecase.RecoverRefused
import dagger.hilt.android.lifecycle.HiltViewModel
import io.grpc.Status
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A recovery phrase is 12 words — iOS `AccountRecoveryViewModel.enteredWords`. */
const val RECOVERY_WORD_COUNT = 12

data class RestoreAccountUiState(
    val identifier: String = "",
    val words: List<String> = List(RECOVERY_WORD_COUNT) { "" },
    val working: Boolean = false,
    val done: Boolean = false,
    @StringRes val errorRes: Int? = null,
) {
    val phrase: String get() = words.joinToString(" ") { it.trim() }
    val canSubmit: Boolean get() = identifier.isNotBlank() && words.all { it.isNotBlank() }
}

@HiltViewModel
class RestoreAccountViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val state = MutableStateFlow(RestoreAccountUiState())
    val uiState: StateFlow<RestoreAccountUiState> = state.asStateFlow()

    fun setIdentifier(value: String) = state.update { it.copy(identifier = value, errorRes = null) }

    /**
     * One cell of the grid. Text with spaces in it — the whole phrase pasted into one cell — is
     * spread over this cell and the ones after it, so pasting works as it did in a single field.
     */
    fun setWord(index: Int, value: String) = state.update { current ->
        val parts = value.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val words = current.words.toMutableList()
        if (parts.size <= 1) {
            words[index] = value.trim().lowercase()
        } else {
            parts.take(RECOVERY_WORD_COUNT - index).forEachIndexed { offset, word ->
                words[index + offset] = word.lowercase()
            }
        }
        current.copy(words = words, errorRes = null)
    }

    fun submit() {
        val current = state.value
        if (current.working) return
        state.update { it.copy(working = true, errorRes = null) }
        viewModelScope.launch {
            val error = runCatching { authRepository.recoverAccount(current.identifier, current.phrase) }
                .exceptionOrNull()
            state.update {
                if (error == null) {
                    // The words are not kept in memory past the moment they were needed.
                    it.copy(working = false, done = true, words = List(RECOVERY_WORD_COUNT) { "" })
                } else {
                    Log.w(TAG, "recovery failed", error)
                    it.copy(working = false, errorRes = errorFor(error))
                }
            }
        }
    }

    companion object {
        private const val TAG = "RestoreAccount"

        @StringRes
        internal fun errorFor(error: Throwable): Int = when {
            error is RecoverRefused && error.reason == RecoverRefusal.INVALID_PHRASE -> R.string.recovery_confirm_invalid_phrase
            error is RecoverRefused -> R.string.restore_error_identifier
            Status.fromThrowable(error).code in setOf(Status.Code.NOT_FOUND, Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED) ->
                R.string.restore_error_rejected
            Status.fromThrowable(error).code == Status.Code.RESOURCE_EXHAUSTED -> R.string.restore_error_rate_limited
            else -> R.string.restore_error_network
        }
    }
}
