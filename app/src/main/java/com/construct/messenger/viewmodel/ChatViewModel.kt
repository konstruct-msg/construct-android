package com.construct.messenger.viewmodel

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.model.Message
import com.construct.messenger.data.model.ReplyRef
import com.construct.messenger.data.model.SecurityNotice
import com.construct.messenger.data.repository.ContactsRepository
import com.construct.messenger.data.repository.MessagesRepository
import com.construct.messenger.domain.usecase.SendOutcome
import com.construct.messenger.security.SecurityNotices
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
    /** The message the composer is quoting. Null when the next send is not a reply. */
    val replyingTo: ReplyRef? = null,
    /** Text of the message being edited, shown in the bar. Null when the next send is a new message. */
    val editingOriginal: String? = null,
    /** Their unacknowledged security event; the banner shows while it is not NONE. */
    val securityNotice: SecurityNotice = SecurityNotice.NONE,
    /** Name for the banner — alias or generated, never the raw id. */
    val contactName: String = "",
    /** Photos picked for the next message, in order; its text is then their caption. */
    val attachments: List<Uri> = emptyList(),
)

private data class EditTarget(val messageId: String, val original: String)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messagesRepository: MessagesRepository,
    contactsRepository: ContactsRepository,
    private val securityNotices: SecurityNotices,
) : ViewModel() {
    val contactId: String = requireNotNull(savedStateHandle.get<String>("contactId"))

    private val draft = MutableStateFlow("")
    private val sending = MutableStateFlow(false)
    private val replying = MutableStateFlow<ReplyRef?>(null)
    private val editing = MutableStateFlow<EditTarget?>(null)
    private val attachments = MutableStateFlow<List<Uri>>(emptyList())

    val uiState: StateFlow<ChatUiState> = combine(
        messagesRepository.observeContact(contactId),
        contactsRepository.contacts,
        draft,
        sending,
        combine(replying, editing, attachments) { reply, edit, photos -> Triple(reply, edit, photos) },
    ) { messages, contacts, draftText, isSending, composer ->
        val (reply, edit, photos) = composer
        val contact = contacts.find { it.userId == contactId }
        val title = when {
            contact == null -> DisplayNameGenerator.generate(contactId).uppercase()
            contact.localName != null -> contact.localName.uppercase()
            contact.username.isNotBlank() -> "@${contact.username}"
            else -> contact.displayName.uppercase()
        }
        ChatUiState(
            contactId = contactId,
            title = title,
            messages = messages,
            draft = draftText,
            sending = isSending,
            replyingTo = reply,
            editingOriginal = edit?.original,
            securityNotice = contact?.securityNotice ?: SecurityNotice.NONE,
            contactName = contact?.let { it.localName ?: if (it.username.isNotBlank()) "@${it.username}" else it.displayName }
                ?: DisplayNameGenerator.generate(contactId),
            attachments = photos,
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

    /** The user checked the event (or chose to carry on): the banner goes. */
    fun acknowledgeSecurityNotice() {
        viewModelScope.launch { securityNotices.acknowledge(contactId) }
    }

    fun onDraftChange(value: String) {
        draft.value = value
    }

    /** Quote [message] on the next send. The id is lowercased to match iOS, and the preview is its text. */
    fun startReply(message: Message) {
        if (editing.value != null) {
            editing.value = null
            draft.value = ""
        }
        replying.value = ReplyRef.of(message.id, message.body)
    }

    fun cancelReply() {
        replying.value = null
    }

    /** Edit [message], which has to be one we sent. The field is filled with its current text. */
    fun startEdit(message: Message) {
        if (!message.isOutgoing || message.body.isBlank()) return
        replying.value = null
        editing.value = EditTarget(message.id, message.body)
        draft.value = message.body
    }

    fun cancelEdit() {
        editing.value = null
        draft.value = ""
    }

    /** Drop [message] from this phone. The peer is not told. */
    fun delete(message: Message) {
        if (editing.value?.messageId == message.id) cancelEdit()
        if (replying.value?.messageId.equals(message.id, ignoreCase = true)) cancelReply()
        viewModelScope.launch { messagesRepository.delete(contactId, message.id) }
    }

    /** Photos from the picker join the ones already chosen (iOS allows 99 in one album). */
    fun attach(uris: List<Uri>) {
        if (editing.value != null || uris.isEmpty()) return
        attachments.value = (attachments.value + uris).distinct().take(MAX_ATTACHMENTS)
    }

    fun removeAttachment(uri: Uri) {
        attachments.value = attachments.value - uri
    }

    fun send() {
        val text = draft.value.trim()
        val photos = attachments.value
        if ((text.isEmpty() && photos.isEmpty()) || sending.value) return
        val reply = replying.value
        val edit = editing.value
        sending.value = true
        viewModelScope.launch {
            try {
                if (photos.isNotEmpty() && edit == null) {
                    // The bubble is in the transcript before the uploads end; the composer
                    // is free again at once, as on iOS.
                    attachments.value = emptyList()
                    draft.value = ""
                    replying.value = null
                    sending.value = false
                    messagesRepository.sendPhotos(contactId, photos, text, reply)
                    return@launch
                }
                val outcome = if (edit != null) {
                    messagesRepository.edit(contactId, edit.messageId, text)
                } else {
                    messagesRepository.send(contactId, text, reply)
                }
                if (outcome is SendOutcome.Sent) {
                    draft.value = ""
                    replying.value = null
                    editing.value = null
                }
            } finally {
                sending.value = false
            }
        }
    }

    private companion object {
        const val MAX_ATTACHMENTS = 99
    }
}
