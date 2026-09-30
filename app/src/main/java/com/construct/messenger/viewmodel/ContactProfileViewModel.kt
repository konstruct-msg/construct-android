package com.construct.messenger.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.local.db.UserDao
import com.construct.messenger.data.model.SecurityNotice
import com.construct.messenger.data.repository.SessionSecurity
import com.construct.messenger.data.repository.SessionSecurityRepository
import com.construct.messenger.security.SecurityNotices
import com.construct.messenger.domain.usecase.ContactActionsUseCase
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
    val name: String = "",
    val username: String = "",
    val fingerprint: String? = null,
    val avatar: ByteArray? = null,
    val isBlocked: Boolean = false,
    val securityNotice: SecurityNotice = SecurityNotice.NONE,
    /** Null until the core has been asked. */
    val session: SessionSecurity? = null,
    /** The row is gone — deleted here or never existed. The screen leaves. */
    val removed: Boolean = false,
    val busy: Boolean = false,
    /** One-shot outcome of a report, for the dialog: null until one ran. */
    val reportAccepted: Boolean? = null,
)

@HiltViewModel
class ContactProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    userDao: UserDao,
    private val actions: ContactActionsUseCase,
    private val securityNotices: SecurityNotices,
    private val sessionSecurity: SessionSecurityRepository,
) : ViewModel() {
    val userId: String = requireNotNull(savedStateHandle.get<String>("contactId"))

    /** Opened from their chat: "open chat" would only lead back (iOS `showMessageButton`). */
    val fromChat: Boolean = savedStateHandle.get<Boolean>("fromChat") ?: false
    private val busy = MutableStateFlow(false)
    private val reported = MutableStateFlow<Boolean?>(null)
    private val session = MutableStateFlow<SessionSecurity?>(null)

    init {
        refreshSession()
    }

    val uiState: StateFlow<ContactProfileUiState> = combine(
        userDao.observeById(userId),
        busy,
        reported,
        session,
    ) { row, isBusy, report, sessionState ->
        if (row == null) {
            ContactProfileUiState(userId = userId, removed = true)
        } else {
            ContactProfileUiState(
                userId = userId,
                name = row.displayName.ifBlank { DisplayNameGenerator.generate(userId) },
                username = row.username,
                // The row keeps the key of a contact added before the device registry did.
                fingerprint = (row.identityPublic ?: sessionState?.identityPublic)?.let(IdentityFingerprint::short),
                avatar = row.avatarData,
                isBlocked = row.isBlocked,
                securityNotice = SecurityNotice.of(row.securityNotice),
                session = sessionState,
                busy = isBusy,
                reportAccepted = report,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ContactProfileUiState(userId = userId))

    fun setBlocked(blocked: Boolean) = run { actions.setBlocked(userId, blocked) }

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
