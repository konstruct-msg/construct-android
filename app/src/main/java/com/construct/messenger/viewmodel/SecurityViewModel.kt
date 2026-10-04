package com.construct.messenger.viewmodel

import com.construct.messenger.security.KtLog
import com.construct.messenger.security.KtTally
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.repository.AccountRepository
import com.construct.messenger.data.repository.ContactsRepository
import com.construct.messenger.data.repository.Lockdown
import com.construct.messenger.data.repository.SecuritySettingsRepository
import com.construct.messenger.recovery.HeldPhrase
import com.construct.messenger.recovery.RecoveryPhraseVault
import com.construct.messenger.recovery.RecoveryRepository
import com.construct.messenger.recovery.RecoveryStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SecurityUiState(
    val hasUsername: Boolean = false,
    val discoverable: Boolean = false,
    val busy: Boolean = false,
    val failed: Boolean = false,
    /** Null until the server answered; then whether the phrase is set up, and its fingerprint. */
    val recovery: RecoveryStatus? = null,
    /** A silently made phrase waiting here for its copy (or lost before it was made). */
    val recoveryHeld: HeldPhrase = HeldPhrase.NONE,
    val lockdown: Lockdown = Lockdown(),
    val senderAnonymity: Boolean = true,
    /** Every KT verdict this device reached on contacts' bundles. */
    val kt: KtTally = KtTally(),
)

/**
 * Security: recovery phrase, Lockdown, sender anonymity, issued invites, discovery.
 *
 * **Canon:** iOS `SecurityView`. Turning search on is confirmed first (the UI asks); turning it
 * off is not — becoming harder to find needs no warning. Lockdown takes the contacts of the
 * moment it is switched on as the approved set, as iOS does.
 */
@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val recoveryRepository: RecoveryRepository,
    private val recoveryVault: RecoveryPhraseVault,
    private val securitySettings: SecuritySettingsRepository,
    private val contactsRepository: ContactsRepository,
    private val ktLog: KtLog,
) : ViewModel() {
    private val state = MutableStateFlow(SecurityUiState())
    val uiState: StateFlow<SecurityUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            accountRepository.account.collect { account ->
                state.update {
                    it.copy(
                        hasUsername = !account?.username.isNullOrEmpty(),
                        discoverable = account?.discoverable ?: false,
                    )
                }
            }
        }
        viewModelScope.launch { accountRepository.refresh() }
        viewModelScope.launch {
            securitySettings.lockdown.collect { lockdown -> state.update { it.copy(lockdown = lockdown) } }
        }
        state.update { it.copy(senderAnonymity = securitySettings.senderAnonymity) }
        viewModelScope.launch { ktLog.tally.collect { kt -> state.update { it.copy(kt = kt) } } }
    }

    /** Again on return from the phrase setup, which may have just set it up. */
    fun refreshRecovery() {
        val held = recoveryRepository.userId()?.let(recoveryVault::held) ?: HeldPhrase.NONE
        state.update { it.copy(recoveryHeld = held) }
        viewModelScope.launch {
            val status = try {
                recoveryRepository.status()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "recovery status unavailable: $e")
                null
            }
            state.update { it.copy(recovery = status ?: it.recovery) }
        }
    }

    fun setLockdown(enabled: Boolean) {
        if (!enabled) {
            securitySettings.disableLockdown()
            return
        }
        viewModelScope.launch {
            val approved = contactsRepository.contacts.first().map { it.userId }.toSet()
            securitySettings.enableLockdown(approved)
        }
    }

    fun setDiscoverable(enabled: Boolean) {
        if (state.value.busy) return
        state.update { it.copy(busy = true, failed = false) }
        viewModelScope.launch {
            val applied = accountRepository.setDiscoverable(enabled)
            state.update { it.copy(busy = false, failed = !applied) }
        }
    }
}

private const val TAG = "SecurityViewModel"
