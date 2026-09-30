package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.model.ChatSummary
import com.construct.messenger.data.repository.ChatsRepository
import com.construct.messenger.data.repository.ConnectionRepository
import com.construct.messenger.domain.usecase.ContactActionsUseCase
import com.construct.messenger.ui.components.ConnectionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
) : ViewModel() {
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
}
