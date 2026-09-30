package com.construct.messenger.viewmodel

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.model.Message
import com.construct.messenger.data.model.MessageMedia
import com.construct.messenger.data.repository.MediaUnavailable
import com.construct.messenger.media.VoicePlayer
import com.construct.messenger.media.VoiceRecorder
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
    private val recorder: VoiceRecorder,
    private val player: VoicePlayer,
) : ViewModel() {
    /** The voice note being recorded or waiting to be sent; the composer shows its bar. */
    val recording: StateFlow<VoiceRecorder.State> = recorder.state

    /** The voice note playing, anywhere in this chat. */
    val playing: StateFlow<VoicePlayer.Playing?> = player.state

    private val _voiceLoading = MutableStateFlow<Set<String>>(emptySet())
    val voiceLoading: StateFlow<Set<String>> = _voiceLoading.asStateFlow()

    private val _voiceUnavailable = MutableStateFlow<Set<String>>(emptySet())
    val voiceUnavailable: StateFlow<Set<String>> = _voiceUnavailable.asStateFlow()

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

    override fun onCleared() {
        messagesRepository.chatHidden(contactId)
        player.stop()
        if (recorder.state.value !is VoiceRecorder.State.Idle) recorder.cancel()
    }

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

    /** False when the microphone would not open. The screen has RECORD_AUDIO by now. */
    fun startRecording(): Boolean {
        player.stop()
        return recorder.start()
    }

    fun stopRecording() = recorder.stop()

    fun cancelRecording() = recorder.cancel()

    fun sendRecording() {
        val done = recorder.state.value as? VoiceRecorder.State.Recorded ?: return
        recorder.handedOff()
        viewModelScope.launch {
            messagesRepository.sendVoice(contactId, done.file, done.durationMs, done.waveform)
        }
    }

    /**
     * Play or pause [voice]. iOS fetches a voice note only when it is played, never ahead; so
     * does this.
     */
    fun toggleVoice(voice: MessageMedia.Voice) {
        val id = voice.audio.mediaId
        if (player.state.value?.mediaId == id) {
            player.toggle(id, ByteArray(0))
            return
        }
        if (id in _voiceLoading.value) return
        _voiceLoading.value = _voiceLoading.value + id
        viewModelScope.launch {
            try {
                player.toggle(id, messagesRepository.mediaBytes(voice.audio))
            } catch (e: MediaUnavailable) {
                _voiceUnavailable.value = _voiceUnavailable.value + id
            } catch (e: Exception) {
                // Network: the button stays, a tap tries again.
            } finally {
                _voiceLoading.value = _voiceLoading.value - id
            }
        }
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
