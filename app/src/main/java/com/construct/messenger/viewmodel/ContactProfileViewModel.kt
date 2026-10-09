package com.construct.messenger.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.local.ContactStore
import com.construct.messenger.data.local.resolvedName
import com.construct.messenger.data.model.ContactTrustAlert
import com.construct.messenger.data.model.KtStatus
import com.construct.messenger.data.model.SecurityNotice
import com.construct.messenger.data.repository.SessionSecurity
import com.construct.messenger.data.repository.SessionSecurityRepository
import com.construct.messenger.domain.usecase.ContactActionsUseCase
import com.construct.messenger.domain.usecase.ShareProfileUseCase
import com.construct.messenger.security.SecurityNotices
import com.construct.messenger.util.DisplayNameGenerator
import com.construct.messenger.util.IdentityFingerprint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ContactProfileUiState(
    val userId: String,
    /** What the app calls them everywhere — their local name when the user gave one. */
    val name: String = "",
    /** The name they go by themselves: shared, or generated from the id. */
    val displayName: String = "",
    /** The user's own name for them, or null. */
    val localName: String? = null,
    val username: String = "",
    val fingerprint: String? = null,
    val avatar: ByteArray? = null,
    val isBlocked: Boolean = false,
    /** Their security event, or a failed KT proof. */
    val trustAlert: ContactTrustAlert? = null,
    /** Null until the core has been asked. */
    val session: SessionSecurity? = null,
    /** The row is gone — deleted here or never existed. The screen leaves. */
    val removed: Boolean = false,
    val busy: Boolean = false,
    /** One-shot outcome of a report, for the dialog: null until one ran. */
    val reportAccepted: Boolean? = null,
    /** We share our profile with them. */
    val amSharing: Boolean = false,
    /** They shared their profile with us. */
    val sharingWithMe: Boolean = false,
    val sharing: Boolean = false,
    /** One-shot outcome of share / stop, for the dialog. */
    val shareOutcome: ShareOutcome? = null,
)

enum class ShareOutcome { SHARED, FAILED, STOPPED }

@HiltViewModel
class ContactProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    contactStore: ContactStore,
    private val actions: ContactActionsUseCase,
    private val securityNotices: SecurityNotices,
    private val sessionSecurity: SessionSecurityRepository,
    private val shareProfile: ShareProfileUseCase,
) : ViewModel() {
    val userId: String = requireNotNull(savedStateHandle.get<String>("contactId"))

    /** Opened from their chat: "open chat" would only lead back (iOS `showMessageButton`). */
    val fromChat: Boolean = savedStateHandle.get<Boolean>("fromChat") ?: false
    private val busy = MutableStateFlow(false)
    private val reported = MutableStateFlow<Boolean?>(null)
    private val session = MutableStateFlow<SessionSecurity?>(null)
    private val share = MutableStateFlow(Pair<Boolean, ShareOutcome?>(false, null))

    init {
        refreshSession()
    }

    val uiState: StateFlow<ContactProfileUiState> = combine(
        contactStore.observe(userId),
        busy,
        reported,
        session,
        share,
    ) { row, isBusy, report, sessionState, (isSharing, outcome) ->
        if (row == null) {
            ContactProfileUiState(userId = userId, removed = true)
        } else {
            ContactProfileUiState(
                userId = userId,
                name = row.resolvedName(userId),
                displayName = row.displayName.ifBlank { DisplayNameGenerator.generate(userId) },
                localName = row.localName,
                username = row.username,
                // The row keeps the key of a contact added before the device registry did.
                fingerprint = (row.identityPublic ?: sessionState?.identityPublic)?.let(IdentityFingerprint::short),
                avatar = row.avatarData,
                isBlocked = row.isBlocked,
                trustAlert = ContactTrustAlert.of(SecurityNotice.of(row.securityNotice), KtStatus.of(row.ktStatus)),
                session = sessionState,
                busy = isBusy,
                reportAccepted = report,
                amSharing = row.amSharingWith,
                sharingWithMe = row.isSharingWithMe,
                sharing = isSharing,
                shareOutcome = outcome,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ContactProfileUiState(userId = userId))

    fun setBlocked(blocked: Boolean) = run { actions.setBlocked(userId, blocked) }

    /** iOS `handleShareToggle`: on sends our profile, off only stops marking it shared. */
    fun toggleSharing() {
        if (share.value.first) return
        val stopping = uiState.value.amSharing
        share.value = true to null
        viewModelScope.launch {
            val outcome = if (stopping) {
                shareProfile.stop(userId)
                ShareOutcome.STOPPED
            } else if (shareProfile.share(userId)) {
                ShareOutcome.SHARED
            } else {
                ShareOutcome.FAILED
            }
            share.value = false to outcome
        }
    }

    fun shareOutcomeShown() {
        share.value = share.value.first to null
    }

    /** Blank clears it. */
    fun setLocalName(name: String?) {
        viewModelScope.launch { actions.setLocalName(userId, name) }
    }

    fun reportSpam() = run { reported.value = actions.reportSpam(userId) }

    fun delete() = run { actions.delete(userId) }

    fun acknowledgeSecurityNotice() {
        viewModelScope.launch { securityNotices.acknowledge(userId) }
    }

    /** The session can open or change while the profile is behind another screen. */
    fun refreshSession() {
        viewModelScope.launch { session.value = sessionSecurity.of(userId) }
    }

    fun reportShown() {
        reported.value = null
    }

    private fun run(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                busy.value = false
            }
        }
    }
}
