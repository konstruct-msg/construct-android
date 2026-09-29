package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.repository.ContactsRepository
import com.construct.messenger.data.repository.InviteRevocation
import com.construct.messenger.data.repository.IssuedInvite
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class IssuedInvitesUiState(
    /** Live invites, newest first. */
    val invites: List<IssuedInvite> = emptyList(),
    val nowEpochSec: Long = System.currentTimeMillis() / 1000,
    val revokingJti: String? = null,
    val lastOutcome: InviteRevocation? = null,
)

/**
 * Settings → Security → Issued invites (iOS `IssuedInvitesView`).
 *
 * Per-device by construction: the server holds no record of an invite until it is redeemed or
 * revoked, so this device's journal is the only list there is. The clock ticks so a row does not
 * keep claiming time it no longer has.
 */
@HiltViewModel
class IssuedInvitesViewModel @Inject constructor(
    private val contactsRepository: ContactsRepository,
) : ViewModel() {
    private val action = MutableStateFlow(Pair<String?, InviteRevocation?>(null, null))

    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis() / 1000)
            delay(TICK_MS)
        }
    }

    val uiState: StateFlow<IssuedInvitesUiState> =
        combine(contactsRepository.issuedInvites, clock, action) { invites, now, (revoking, outcome) ->
            IssuedInvitesUiState(
                invites = invites.filter { it.isLive(now) }.sortedByDescending { it.issuedAtEpochSec },
                nowEpochSec = now,
                revokingJti = revoking,
                lastOutcome = outcome,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IssuedInvitesUiState())

    fun revoke(invite: IssuedInvite) {
        if (action.value.first != null) return
        action.value = invite.jti to null
        viewModelScope.launch {
            val outcome = contactsRepository.revoke(invite.jti)
            action.value = null to outcome
        }
    }

    private companion object {
        const val TICK_MS = 30_000L
    }
}
