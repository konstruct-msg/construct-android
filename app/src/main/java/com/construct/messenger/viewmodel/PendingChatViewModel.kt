package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import com.construct.messenger.data.local.PendingChatStore
import com.construct.messenger.data.local.PendingInviteStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** Hands the tabs a chat to open — one tapped notification, opened once — and says when an
 * invite waits for the Synaps tab, which redeems it ([com.construct.messenger.viewmodel.SynapsViewModel]). */
@HiltViewModel
class PendingChatViewModel @Inject constructor(
    private val store: PendingChatStore,
    invites: PendingInviteStore,
) : ViewModel() {
    val pending: StateFlow<String?> = store.pending

    val inviteWaiting: StateFlow<String?> = invites.pending

    fun take(): String? = store.take()
}
