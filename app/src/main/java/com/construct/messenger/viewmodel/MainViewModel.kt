package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.model.ChatSummary
import com.construct.messenger.data.repository.ChatsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class MainUiState(
    val chats: List<ChatSummary> = emptyList(),
    val suggestedContactId: String = ""
)

@HiltViewModel
class MainViewModel @Inject constructor(
    chatsRepository: ChatsRepository
) : ViewModel() {
    val uiState: StateFlow<MainUiState> = chatsRepository.chats
        .map { chats ->
            MainUiState(
                chats = chats,
                suggestedContactId = chatsRepository.suggestedContactId()
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
