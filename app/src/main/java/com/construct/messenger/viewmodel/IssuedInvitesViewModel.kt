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

/**
 * One thing the user handed out: a copied link, or one showing of the QR screen with every code
 * it rotated through (iOS `InviteIssuance`). [liveJtis] are the codes still redeemable.
 */
data class IssuedAct(
    val id: String,
    val isQr: Boolean,
    val liveJtis: List<String>,
    val startedAtEpochSec: Long,
    val expiresAtEpochSec: Long,
)

data class IssuedInvitesUiState(
    /** Live acts, newest first. */
    val acts: List<IssuedAct> = emptyList(),
    val nowEpochSec: Long = System.currentTimeMillis() / 1000,
    val revokingId: String? = null,
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
                acts = actsOf(invites, now),
                nowEpochSec = now,
                revokingId = revoking,
                lastOutcome = outcome,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IssuedInvitesUiState())

    /**
     * Revoke every live code of [act]. A showing is at most ten codes — a QR lives 300 s and
     * turns every 30 — which is why Android offers it where iOS, counting from the 12 h link
     * lifetime, did not. The row goes only on answers for all of them.
     */
    fun revoke(act: IssuedAct) {
        if (action.value.first != null) return
        action.value = act.id to null
        viewModelScope.launch {
            val outcomes = act.liveJtis.map { contactsRepository.revoke(it) }
            action.value = null to combined(outcomes)
        }
    }

    companion object {
        private const val TICK_MS = 30_000L

        /** Links one row each; the codes of one QR showing one row together. Newest first. */
        fun actsOf(invites: List<IssuedInvite>, nowEpochSec: Long): List<IssuedAct> =
            invites
                .groupBy { if (it.kind == "qr" && it.sitting != null) "qr:${it.sitting}" else it.jti }
                .mapNotNull { (id, codes) ->
                    val live = codes.filter { it.isLive(nowEpochSec) }
                    if (live.isEmpty()) return@mapNotNull null
                    IssuedAct(
                        id = id,
                        isQr = codes.first().kind == "qr",
                        liveJtis = live.map { it.jti },
                        startedAtEpochSec = codes.minOf { it.issuedAtEpochSec },
                        expiresAtEpochSec = live.maxOf { it.issuedAtEpochSec + it.ttlSeconds },
                    )
                }
                .sortedByDescending { it.startedAtEpochSec }

        /** One unanswered code keeps the whole showing listed: it may still work. */
        fun combined(outcomes: List<InviteRevocation>): InviteRevocation = when {
            InviteRevocation.UNCONFIRMED in outcomes -> InviteRevocation.UNCONFIRMED
            InviteRevocation.REVOKED in outcomes -> InviteRevocation.REVOKED
            else -> InviteRevocation.ALREADY_USED
        }
    }
}
