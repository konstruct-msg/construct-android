package com.construct.messenger.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.crypto.Cipher
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    /** The twelve words, to write down. [backup]: a silently made key's, already on the server. */
    data class ShowWords(val words: List<String>, val backup: Boolean = false) : RecoveryStage
    /**
     * Three of them back, so the phrase was really written down: for each index, pick the word
     * from [options] — the right one and three others of the same phrase (owner's decision
     * 2026-10-04; typing on a phone was the wall).
     */
    data class Quiz(
        val words: List<String>,
        val indices: List<Int>,
        val options: Map<Int, List<String>>,
        val backup: Boolean = false,
    ) : RecoveryStage
    /** The key was made silently and waits for its copy: unlock, then the words. */
    data object BackupIntro : RecoveryStage
    /** The held phrase is gone — the screen lock was removed — and no copy was made. */
    data object BackupLost : RecoveryStage
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
    /** Whether a silently made phrase waits on this device for its copy. */
    val held: HeldPhrase = HeldPhrase.NONE,
) {
    /** Settings ask for a copy while there is no key here, or the silent one is not copied yet. */
    val needsBackup: Boolean
        get() = stage == RecoveryStage.Explain || stage == RecoveryStage.Confirm || held != HeldPhrase.NONE
}

/**
 * The recovery gate: contacts need the account's address, and the address is the recovery key.
 *
 * **Canon:** iOS `RecoveryGateView` + `AccountRecoveryViewModel`.
 */
@HiltViewModel
class RecoveryViewModel @Inject constructor(
    private val repository: RecoveryRepository,
    private val vault: RecoveryPhraseVault,
    private val provisioner: RecoveryKeyProvisioner,
) : ViewModel() {
    private val state = MutableStateFlow(
        RecoveryUiState(
            stage = if (repository.hasOwnAddress()) RecoveryStage.Ready else RecoveryStage.Loading,
            held = held(),
        ),
    )
    val uiState: StateFlow<RecoveryUiState> = state.asStateFlow()
    private var loading: Job? = null

    init {
        if (state.value.stage == RecoveryStage.Loading) loadStatus()
    }

    private fun held(): HeldPhrase = repository.userId()?.let(vault::held) ?: HeldPhrase.NONE

    /**
     * After the first orientation: the key is made without a screen, and the setup is shown only
     * when it could not be — no screen lock, or the account has a key this device has not seen.
     * **Canon:** iOS `ContentView.provisionRecoveryKey(promptIfNeeded: true)`.
     */
    fun provisionFirst() {
        if (state.value.stage == RecoveryStage.Ready) return
        loading?.cancel()
        state.update { it.copy(stage = RecoveryStage.Loading) }
        viewModelScope.launch {
            val userId = repository.userId()
            val outcome = userId?.let { provisioner.ensureKey(it) } ?: RecoveryKeyProvisioner.Outcome.DEFERRED
            when (outcome) {
                // Deferred: the launch path retries; a launch is not the moment for a screen.
                RecoveryKeyProvisioner.Outcome.PROVISIONED,
                RecoveryKeyProvisioner.Outcome.ALREADY_KNOWN,
                RecoveryKeyProvisioner.Outcome.DEFERRED,
                -> state.update { it.copy(stage = RecoveryStage.Ready, held = held()) }
                RecoveryKeyProvisioner.Outcome.NEEDS_VISIBLE_SETUP,
                RecoveryKeyProvisioner.Outcome.SET_ELSEWHERE,
                -> loadStatus()
            }
        }
    }

    /**
     * The Settings entry: the copy of a silently made key when one waits here, the gate's own
     * flow otherwise. Called before the screen reads its state.
     */
    fun prepareSetup() {
        val held = held()
        state.update {
            it.copy(
                held = held,
                stage = when {
                    !repository.hasOwnAddress() -> it.stage
                    held == HeldPhrase.HELD -> RecoveryStage.BackupIntro
                    held == HeldPhrase.LOST -> RecoveryStage.BackupLost
                    else -> RecoveryStage.Ready
                },
                errorRes = null,
            )
        }
    }

    /** How the held phrase opens now: a cipher for the prompt, the credential first, or lost. */
    fun unlock(): RecoveryPhraseVault.Unlock = vault.unlock()

    /** The authenticated [cipher] opened the phrase: show it. */
    fun openHeld(cipher: Cipher) {
        val userId = repository.userId() ?: return
        val phrase = vault.open(cipher, userId)
        if (phrase == null) {
            state.update { it.copy(held = held()) }
            openFailed()
            return
        }
        state.update { it.copy(stage = RecoveryStage.ShowWords(phrase.split(" "), backup = true), errorRes = null) }
    }

    /** The prompt or the credential did not open the key; the person may try again. */
    fun openFailed() {
        state.update { it.copy(errorRes = com.construct.messenger.R.string.recovery_backup_unlock_failed) }
    }

    /** The key was found gone while unlocking. */
    fun heldLost() {
        state.update { it.copy(held = held(), stage = RecoveryStage.BackupLost) }
    }

    /**
     * On return to a screen that shows the status: the phrase may have been set up elsewhere in
     * the meantime. Never asks the server once this device knows the address — [loadStatus] would
     * turn Ready back into Confirm.
     */
    fun refresh() {
        state.update { it.copy(held = held()) }
        if (repository.hasOwnAddress()) state.update { it.copy(stage = RecoveryStage.Ready) }
        else if (state.value.stage != RecoveryStage.Loading) loadStatus()
    }

    fun loadStatus() {
        loading = viewModelScope.launch {
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
        val shown = state.value.stage as? RecoveryStage.ShowWords ?: return
        val words = shown.words
        val indices = words.indices.shuffled().take(QUIZ_SIZE).sorted()
        val options = indices.associateWith { quizOptions(words, it) }
        state.update {
            it.copy(stage = RecoveryStage.Quiz(words, indices, options, shown.backup), quizAnswers = emptyMap(), errorRes = null)
        }
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
        if (quiz.backup) {
            // The key is on the server already; copying it was all that was left. The phrase
            // leaves the device now, and "never stored" holds again.
            vault.forgetHeld()
            state.update { it.copy(stage = RecoveryStage.Ready, held = held(), quizAnswers = emptyMap(), errorRes = null) }
            return
        }
        state.update { it.copy(stage = RecoveryStage.Working, errorRes = null) }
        viewModelScope.launch {
            val outcome = try {
                repository.setUp(quiz.words)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                SetUpOutcome.FAILED
            }
            state.update {
                when (outcome) {
                    SetUpOutcome.DONE -> it.copy(stage = RecoveryStage.Ready)
                    // A key from an earlier attempt is the account's for good: only that phrase
                    // opens it now, so ask for it instead of offering these words again.
                    SetUpOutcome.OTHER_PHRASE_SET -> it.copy(
                        stage = RecoveryStage.Confirm,
                        errorRes = com.construct.messenger.R.string.recovery_setup_other_phrase,
                    )
                    // The same words stay: a retry either sets them or finds them set.
                    SetUpOutcome.FAILED -> it.copy(stage = quiz, errorRes = com.construct.messenger.R.string.recovery_setup_failed)
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

    companion object {
        private const val QUIZ_SIZE = 3
        private const val QUIZ_OPTIONS = 4

        /**
         * The word at [index] and up to three others from the same phrase, shuffled. **Canon:** iOS
         * `AccountRecoveryViewModel.quizOptions`. Decoys from the phrase itself are the point: all
         * are words the person just saw, so the pick checks the position they wrote down, not
         * whether a word looks familiar.
         */
        fun quizOptions(words: List<String>, index: Int, random: Random = Random.Default): List<String> {
            val answer = words.getOrNull(index) ?: return emptyList()
            val decoys = words.shuffled(random).filter { it != answer }.distinct().take(QUIZ_OPTIONS - 1)
            return (listOf(answer) + decoys).shuffled(random)
        }
    }
}
