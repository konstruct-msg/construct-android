package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import com.construct.messenger.data.local.PendingChatStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** Hands the tabs a chat to open — one tapped notification, opened once. */
@HiltViewModel
class PendingChatViewModel @Inject constructor(
    private val store: PendingChatStore,
) : ViewModel() {
    val pending: StateFlow<String?> = store.pending

    fun take(): String? = store.take()
}
