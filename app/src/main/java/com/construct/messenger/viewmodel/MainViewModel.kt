package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.local.Note
import com.construct.messenger.data.model.ChatSummary
import com.construct.messenger.data.repository.ChatsRepository
import com.construct.messenger.data.repository.ConnectionRepository
import com.construct.messenger.data.repository.MailboxGap
import com.construct.messenger.data.repository.MailboxGapNotice
import com.construct.messenger.data.repository.NotesRepository
import com.construct.messenger.domain.usecase.ContactActionsUseCase
import com.construct.messenger.ui.components.ConnectionStatus
import com.construct.messenger.ui.screens.chats.ChatsListEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val chats: List<ChatSummary> = emptyList(),
    val suggestedContactId: String = "",
    val connection: ConnectionStatus = ConnectionStatus.UNKNOWN,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val chatsRepository: ChatsRepository,
    connectionRepository: ConnectionRepository,
    private val contactActions: ContactActionsUseCase,
    private val mailboxGapNotice: MailboxGapNotice,
    notes: NotesRepository,
) : ViewModel() {
    /** The newest note, for the chat list's Notes row ([ChatsListEntry.merged]); null when none. */
    val newestNote: StateFlow<Note?> = notes.observe(NOTES_PAGE)
        .map(ChatsListEntry::newestOf)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Messages the server trimmed before this device collected them (TODO 79); null when none. */
    val mailboxGap: StateFlow<MailboxGap.Interval?> = mailboxGapNotice.interval

    init {
        viewModelScope.launch { mailboxGapNotice.load() }
    }

    fun dismissMailboxGap() {
        viewModelScope.launch { mailboxGapNotice.dismiss() }
    }

    val uiState: StateFlow<MainUiState> = combine(chatsRepository.chats, connectionRepository.status) { chats, connection ->
            MainUiState(
                chats = chats,
                suggestedContactId = chatsRepository.suggestedContactId(),
                connection = connection,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MainUiState(
                chats = chatsRepository.chats.value,
                suggestedContactId = chatsRepository.suggestedContactId()
            )
        )

    // The row's swipe actions — iOS `ChatsListView.swipeActions`.
    fun togglePin(chat: ChatSummary) {
        viewModelScope.launch { chatsRepository.setPinned(chat.contactId, !chat.isPinned) }
    }

    fun toggleUnread(chat: ChatSummary) {
        viewModelScope.launch { chatsRepository.setUnread(chat.contactId, chat.unreadCount == 0) }
    }

    fun deleteChat(chat: ChatSummary) {
        viewModelScope.launch { contactActions.deleteChat(chat.contactId) }
    }

    private companion object {
        /** Pinned notes come first; a page this long reaches the first unpinned one. */
        const val NOTES_PAGE = 20
    }
}
