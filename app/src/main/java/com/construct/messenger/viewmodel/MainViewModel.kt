package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.model.ChatSummary
import com.construct.messenger.data.repository.ChatsRepository
import com.construct.messenger.data.repository.ConnectionRepository
import com.construct.messenger.ui.components.ConnectionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class MainUiState(
    val chats: List<ChatSummary> = emptyList(),
    val suggestedContactId: String = "",
    val connection: ConnectionStatus = ConnectionStatus.UNKNOWN,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    chatsRepository: ChatsRepository,
    connectionRepository: ConnectionRepository,
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
}
