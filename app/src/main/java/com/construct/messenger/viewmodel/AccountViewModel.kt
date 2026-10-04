package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.repository.AccountRepository
import com.construct.messenger.data.repository.AppLockRepository
import com.construct.messenger.data.repository.AuthRepository
import com.construct.messenger.data.repository.OwnAccount
import com.construct.messenger.data.repository.UsernameChange
import com.construct.messenger.domain.usecase.ShareProfileUseCase
import com.construct.messenger.recovery.RecoveryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class UsernameError { LENGTH, UNAVAILABLE, FAILED }

data class AccountUiState(
    val account: OwnAccount? = null,
    val editing: Boolean = false,
    val draftUsername: String = "",
    val draftDisplayName: String = "",
    val saving: Boolean = false,
    val usernameError: UsernameError? = null,
    val signingOut: Boolean = false,
    /** True once the server said the recovery phrase is not set up, or a silently made one is
     * not copied yet — sign-out warns first. */
    val recoveryMissing: Boolean = false,
    val deletion: Deletion = Deletion.Idle,
)

/** iOS `DeleteAccountConfirmationView`: idle, the 10 s abort window, the request, a refusal. */
sealed interface Deletion {
    data object Idle : Deletion
    data class Counting(val secondsLeft: Int) : Deletion
    data object Requesting : Deletion
    /** The server did not confirm; [message] says why. Local-only deletion is offered. */
    data class Failed(val message: String?) : Deletion
}

sealed interface AccountEvent {
    data object SignedOut : AccountEvent
}

/**
 * The Account screen: alias, display name, fingerprint, account id, sign-out.
 *
 * **Canon:** iOS `AccountSettingsView`. Editing is a mode — "edit" opens the alias and the display
 * name for change, "save" keeps the name, checks the alias and leaves the mode only when the
 * server took it.
 */
@HiltViewModel
class AccountViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val authRepository: AuthRepository,
    private val recoveryRepository: RecoveryRepository,
    private val appLock: AppLockRepository,
    private val shareProfile: ShareProfileUseCase,
) : ViewModel() {
    private var deletionJob: Job? = null

    private val state = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = state.asStateFlow()

    private val events = MutableSharedFlow<AccountEvent>(extraBufferCapacity = 1)
    val eventsFlow: SharedFlow<AccountEvent> = events.asSharedFlow()

    init {
        viewModelScope.launch {
            accountRepository.account.collect { account -> state.update { it.copy(account = account) } }
        }
        viewModelScope.launch { accountRepository.refresh() }
        viewModelScope.launch {
            // Unknown (no answer) is not "missing": only a known "not set up" warns.
            val setUp = try {
                recoveryRepository.status().isSetup
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            // A silent key not yet copied warns as a missing one does (iOS `needsBackup`).
            state.update { it.copy(recoveryMissing = setUp == false || recoveryRepository.copyOwed()) }
        }
    }

    fun startEditing() {
        state.update {
            it.copy(
                editing = true,
                draftUsername = it.account?.username.orEmpty(),
                draftDisplayName = it.account?.displayName.orEmpty(),
                usernameError = null,
            )
        }
    }

    fun cancelEditing() {
        state.update { it.copy(editing = false, usernameError = null) }
    }

    fun onDraftChange(value: String) {
        val cleaned = value.lowercase().filterNot(Char::isWhitespace)
            .take(AccountRepository.USERNAME_LENGTH.last)
        state.update { it.copy(draftUsername = cleaned, usernameError = null) }
    }

    fun onDisplayNameDraftChange(value: String) {
        state.update { it.copy(draftDisplayName = value.take(AccountRepository.DISPLAY_NAME_MAX)) }
    }

    /**
     * The name first, then the alias, as iOS `saveProfileEdits`. A new name goes again to everyone
     * the profile is shared with — iOS `saveDisplayName` — in the background, like the avatar.
     */
    fun save() {
        val current = state.value
        if (current.saving) return
        val nameChanged = current.draftDisplayName.trim() != current.account?.displayName
        val usernameChanged = current.draftUsername != current.account?.username
        if (!nameChanged && !usernameChanged) {
            cancelEditing()
            return
        }
        state.update { it.copy(saving = true, usernameError = null) }
        viewModelScope.launch {
            if (nameChanged) {
                val before = current.account?.displayName
                val shown = accountRepository.setDisplayName(current.draftDisplayName)
                if (shown != null && shown != before) shareProfile.rebroadcast()
            }
            val error = if (!usernameChanged) {
                null
            } else {
                when (accountRepository.changeUsername(current.draftUsername)) {
                    is UsernameChange.Saved -> null
                    UsernameChange.InvalidLength -> UsernameError.LENGTH
                    is UsernameChange.Unavailable -> UsernameError.UNAVAILABLE
                    UsernameChange.Failed -> UsernameError.FAILED
                }
            }
            state.update {
                it.copy(
                    saving = false,
                    usernameError = error,
                    editing = error != null,
                    draftDisplayName = it.account?.displayName.orEmpty(),
                )
            }
        }
    }

    /**
     * [picture], cropped square, becomes our avatar; then the profile goes again to everyone it
     * is shared with, as iOS `saveAvatar` does, in the background — leaving the screen is fine.
     */
    fun setAvatar(picture: android.graphics.Bitmap) {
        viewModelScope.launch(NonCancellable) {
            if (accountRepository.setAvatar(picture)) shareProfile.rebroadcast()
        }
    }

    /**
     * Our avatar goes; then the profile goes again to everyone it is shared with, carrying
     * "removed", so they clear it too (iOS Desktop `removeAvatar`).
     */
    fun removeAvatar() {
        viewModelScope.launch(NonCancellable) {
            if (accountRepository.removeAvatar()) shareProfile.rebroadcast()
        }
    }

    /** [allDevices]: the server ends every session of the account, this one included. */
    fun signOut(allDevices: Boolean = false) {
        if (state.value.signingOut) return
        state.update { it.copy(signingOut = true) }
        viewModelScope.launch {
            authRepository.logout(allDevices)
            events.emit(AccountEvent.SignedOut)
        }
    }

    /**
     * The delete button: opens the ten-second window in which the same button aborts (iOS's
     * undo-send pattern), then asks the server. On its yes, everything on this device is erased.
     */
    fun startDeletion() {
        if (state.value.deletion is Deletion.Counting || state.value.deletion is Deletion.Requesting) return
        deletionJob = viewModelScope.launch {
            for (left in DELETE_ABORT_SECONDS downTo 1) {
                state.update { it.copy(deletion = Deletion.Counting(left)) }
                delay(1_000)
            }
            state.update { it.copy(deletion = Deletion.Requesting) }
            try {
                authRepository.deleteAccount()
                appLock.eraseDevice()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(deletion = Deletion.Failed(e.message)) }
            }
        }
    }

    fun abortDeletion() {
        deletionJob?.cancel()
        deletionJob = null
        state.update { it.copy(deletion = Deletion.Idle) }
    }

    /** The server could not confirm: erase this device anyway (the account may live on). */
    fun deleteLocally() = appLock.eraseDevice()

    private companion object {
        /** iOS `DeleteAccountSheetLayout.abortWindowSeconds`. */
        const val DELETE_ABORT_SECONDS = 10
    }
}
