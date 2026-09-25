package com.construct.messenger.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.model.Message
import com.construct.messenger.data.repository.ContactsRepository
import com.construct.messenger.data.repository.MessagesRepository
import com.construct.messenger.util.DisplayNameGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatUiState(
    val contactId: String,
    val title: String,
    val messages: List<Message> = emptyList(),
    val draft: String = "",
    val sending: Boolean = false,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messagesRepository: MessagesRepository,
    contactsRepository: ContactsRepository,
) : ViewModel() {
    val contactId: String = requireNotNull(savedStateHandle.get<String>("contactId"))

    private val draft = MutableStateFlow("")
    private val sending = MutableStateFlow(false)

    val uiState: StateFlow<ChatUiState> = combine(
        messagesRepository.observeContact(contactId),
        contactsRepository.contacts,
        draft,
        sending,
    ) { messages, contacts, draftText, isSending ->
        val contact = contacts.find { it.userId == contactId }
        val title = when {
            contact == null -> DisplayNameGenerator.generate(contactId).uppercase()
            contact.username.isNotBlank() -> "@${contact.username}"
            else -> contact.displayName.uppercase()
        }
        ChatUiState(
            contactId = contactId,
            title = title,
            messages = messages,
            draft = draftText,
            sending = isSending,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ChatUiState(
            contactId = contactId,
            title = DisplayNameGenerator.generate(contactId).uppercase(),
        ),
    )

    val draftText: StateFlow<String> = draft.asStateFlow()

    /** The screen is visible (ON_START): unread goes to zero, arrivals are read as they land. */
    fun onShown() {
        viewModelScope.launch { messagesRepository.chatShown(contactId) }
    }

    /** ON_STOP — navigated away or the app went to the background. */
    fun onHidden() = messagesRepository.chatHidden(contactId)

    override fun onCleared() = messagesRepository.chatHidden(contactId)

    fun onDraftChange(value: String) {
        draft.value = value
    }

    fun send() {
        val text = draft.value.trim()
        if (text.isEmpty() || sending.value) return
        sending.value = true
        viewModelScope.launch {
            try {
                messagesRepository.send(contactId, text)
                draft.value = ""
            } finally {
                sending.value = false
            }
        }
    }
}
