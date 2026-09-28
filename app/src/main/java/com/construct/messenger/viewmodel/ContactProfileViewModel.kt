package com.construct.messenger.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.local.db.UserDao
import com.construct.messenger.domain.usecase.ContactActionsUseCase
import com.construct.messenger.invite.AccountAddress
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
    val address: String? = null,
    val isBlocked: Boolean = false,
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
) : ViewModel() {
    val userId: String = requireNotNull(savedStateHandle.get<String>("contactId"))
    private val busy = MutableStateFlow(false)
    private val reported = MutableStateFlow<Boolean?>(null)

    val uiState: StateFlow<ContactProfileUiState> = combine(
        userDao.observeById(userId),
        busy,
        reported,
    ) { row, isBusy, report ->
        if (row == null) {
            ContactProfileUiState(userId = userId, removed = true)
        } else {
            ContactProfileUiState(
                userId = userId,
                name = row.displayName.ifBlank { DisplayNameGenerator.generate(userId) },
                username = row.username,
                fingerprint = row.identityPublic?.let(IdentityFingerprint::short),
                address = row.accountAddress?.takeIf { it.size == AccountAddress.LENGTH }?.let(AccountAddress::wire),
                isBlocked = row.isBlocked,
                busy = isBusy,
                reportAccepted = report,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ContactProfileUiState(userId = userId))

    fun setBlocked(blocked: Boolean) = run { actions.setBlocked(userId, blocked) }

    fun reportSpam() = run { reported.value = actions.reportSpam(userId) }

    fun delete() = run { actions.delete(userId) }

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
