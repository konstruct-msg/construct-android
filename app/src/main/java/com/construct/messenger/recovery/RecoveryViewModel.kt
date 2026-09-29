package com.construct.messenger.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the gate is. */
sealed interface RecoveryStage {
    /** Asking the server whether the account has a recovery key. */
    data object Loading : RecoveryStage
    /** No key yet: the explanation and "set up". */
    data object Explain : RecoveryStage
    /** The twelve words, to write down. */
    data class ShowWords(val words: List<String>) : RecoveryStage
    /** Three of them back, so the phrase was really written down. */
    data class Quiz(val words: List<String>, val indices: List<Int>) : RecoveryStage
    /** The account has a key this device has not seen: enter the phrase. */
    data object Confirm : RecoveryStage
    data object Working : RecoveryStage
    /** This device knows the account's address. */
    data object Ready : RecoveryStage
}

data class RecoveryUiState(
    val stage: RecoveryStage = RecoveryStage.Loading,
    val quizAnswers: Map<Int, String> = emptyMap(),
    val confirmPhrase: String = "",
    /** A string resource id for the last failure, or `null`. */
    val errorRes: Int? = null,
)

/**
 * The recovery gate: contacts need the account's address, and the address is the recovery key.
 *
 * **Canon:** iOS `RecoveryGateView` + `AccountRecoveryViewModel`.
 */
@HiltViewModel
class RecoveryViewModel @Inject constructor(
    private val repository: RecoveryRepository,
) : ViewModel() {
    private val state = MutableStateFlow(
        RecoveryUiState(stage = if (repository.hasOwnAddress()) RecoveryStage.Ready else RecoveryStage.Loading),
    )
    val uiState: StateFlow<RecoveryUiState> = state.asStateFlow()

    init {
        if (state.value.stage == RecoveryStage.Loading) loadStatus()
    }

    /**
     * On return to a screen that shows the status: the phrase may have been set up elsewhere in
     * the meantime. Never asks the server once this device knows the address — [loadStatus] would
     * turn Ready back into Confirm.
     */
    fun refresh() {
        if (repository.hasOwnAddress()) state.update { it.copy(stage = RecoveryStage.Ready) }
        else if (state.value.stage != RecoveryStage.Loading) loadStatus()
    }

    fun loadStatus() {
        viewModelScope.launch {
            val stage = try {
                if (repository.status().isSetup) RecoveryStage.Confirm else RecoveryStage.Explain
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline: offer setup; the server refuses a second key, and the error says so.
                RecoveryStage.Explain
            }
            state.update { it.copy(stage = stage) }
        }
    }

    fun startSetup() {
        state.update { it.copy(stage = RecoveryStage.ShowWords(repository.newPhrase()), errorRes = null) }
    }

    fun toQuiz() {
        val words = (state.value.stage as? RecoveryStage.ShowWords)?.words ?: return
        val indices = words.indices.shuffled().take(QUIZ_SIZE).sorted()
        state.update { it.copy(stage = RecoveryStage.Quiz(words, indices), quizAnswers = emptyMap()) }
    }

    fun setQuizAnswer(index: Int, value: String) {
        state.update { it.copy(quizAnswers = it.quizAnswers + (index to value), errorRes = null) }
    }

    fun submitSetup() {
        val quiz = state.value.stage as? RecoveryStage.Quiz ?: return
        val passed = quiz.indices.all { i ->
            state.value.quizAnswers[i]?.trim()?.lowercase() == quiz.words[i].lowercase()
        }
        if (!passed) {
            state.update { it.copy(errorRes = com.construct.messenger.R.string.recovery_quiz_failed) }
            return
        }
        state.update { it.copy(stage = RecoveryStage.Working, errorRes = null) }
        viewModelScope.launch {
            try {
                repository.setUp(quiz.words)
                state.update { it.copy(stage = RecoveryStage.Ready) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state.update {
                    it.copy(stage = quiz, errorRes = com.construct.messenger.R.string.recovery_setup_failed)
                }
            }
        }
    }

    fun setConfirmPhrase(value: String) {
        state.update { it.copy(confirmPhrase = value, errorRes = null) }
    }

    fun submitConfirm() {
        val phrase = state.value.confirmPhrase
        state.update { it.copy(stage = RecoveryStage.Working, errorRes = null) }
        viewModelScope.launch {
            val failure = try {
                repository.confirm(phrase)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ConfirmFailure.NOT_SET_UP
            }
            state.update {
                when (failure) {
                    null -> it.copy(stage = RecoveryStage.Ready, confirmPhrase = "")
                    ConfirmFailure.INVALID_PHRASE ->
                        it.copy(stage = RecoveryStage.Confirm, errorRes = com.construct.messenger.R.string.recovery_confirm_invalid_phrase)
                    ConfirmFailure.OTHER_ACCOUNT ->
                        it.copy(stage = RecoveryStage.Confirm, errorRes = com.construct.messenger.R.string.recovery_confirm_other_account)
                    ConfirmFailure.NOT_SET_UP ->
                        it.copy(stage = RecoveryStage.Confirm, errorRes = com.construct.messenger.R.string.recovery_confirm_failed)
                }
            }
        }
    }

    private companion object {
        const val QUIZ_SIZE = 3
    }
}
