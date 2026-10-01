package com.construct.messenger.viewmodel

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.model.DeliveryStatus
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
    /** Files picked for the next message: each with its name and size for the strip. */
    val files: List<PickedFile> = emptyList(),
)

data class PickedFile(val uri: Uri, val name: String, val sizeBytes: Long)

/** A received file, decrypted, for the screen to hand to the app that opens it. */
data class OpenFile(val uri: Uri, val mime: String)

private data class EditTarget(val messageId: String, val original: String)

private data class Composer(val reply: ReplyRef?, val edit: EditTarget?, val photos: List<Uri>, val files: List<PickedFile>)

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
    private val files = MutableStateFlow<List<PickedFile>>(emptyList())

    private val _fileLoading = MutableStateFlow<Set<String>>(emptySet())
    val fileLoading: StateFlow<Set<String>> = _fileLoading.asStateFlow()

    private val _fileUnavailable = MutableStateFlow<Set<String>>(emptySet())
    val fileUnavailable: StateFlow<Set<String>> = _fileUnavailable.asStateFlow()

    private val _openFile = kotlinx.coroutines.flow.MutableSharedFlow<OpenFile>(extraBufferCapacity = 1)
    val openFile: kotlinx.coroutines.flow.SharedFlow<OpenFile> = _openFile

    /** Something picked could not be sent at all — no row shows it, so the screen says so. */
    enum class AttachmentProblem { TOO_LARGE, UNREADABLE }
    private val _attachmentProblem = kotlinx.coroutines.flow.MutableSharedFlow<AttachmentProblem>(extraBufferCapacity = 1)
    val attachmentProblem: kotlinx.coroutines.flow.SharedFlow<AttachmentProblem> = _attachmentProblem

    private fun report(outcome: SendOutcome) {
        val reason = (outcome as? SendOutcome.Failed)?.reason ?: return
        when {
            "too large" in reason -> _attachmentProblem.tryEmit(AttachmentProblem.TOO_LARGE)
            "unreadable" in reason -> _attachmentProblem.tryEmit(AttachmentProblem.UNREADABLE)
        }
    }

    val uiState: StateFlow<ChatUiState> = combine(
        messagesRepository.observeContact(contactId),
        contactsRepository.contacts,
        draft,
        sending,
        combine(replying, editing, attachments, files) { reply, edit, photos, picked -> Composer(reply, edit, photos, picked) },
    ) { messages, contacts, draftText, isSending, composer ->
        val (reply, edit, photos, picked) = composer
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
            files = picked,
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

    /**
     * Quote [message] on the next send. The id is lowercased to match iOS; the preview is its text,
     * or [quote] — the part the user selected (iOS Quote & Reply).
     */
    fun startReply(message: Message, quote: String? = null) {
        if (editing.value != null) {
            editing.value = null
            draft.value = ""
        }
        replying.value = ReplyRef.of(message.id, quote?.takeIf { it.isNotBlank() } ?: message.body)
    }

    fun cancelReply() {
        replying.value = null
    }

    /**
     * Edit [message], which has to be one we sent: its text, or a photo's caption. The field is
     * filled with what it says now.
     */
    fun startEdit(message: Message) {
        if (!message.isEditable) return
        replying.value = null
        editing.value = EditTarget(message.id, message.body)
        draft.value = message.body
    }

    fun cancelEdit() {
        editing.value = null
        draft.value = ""
    }

    private val _reactionFailed = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** A reaction no device took; the badge has already gone back to what it was. */
    val reactionFailed: kotlinx.coroutines.flow.SharedFlow<Unit> = _reactionFailed

    /**
     * React to [message] with [emoji] — or take it off, when it is the one already there. The
     * badge shows at once (iOS `sendReaction`).
     */
    fun react(message: Message, emoji: String) {
        viewModelScope.launch {
            if (!messagesRepository.react(contactId, message.id, emoji)) _reactionFailed.tryEmit(Unit)
        }
    }

    /** Send [message] again — ours, and no device took it. */
    fun retry(message: Message) {
        if (!message.isOutgoing || message.deliveryStatus != DeliveryStatus.FAILED) return
        viewModelScope.launch { report(messagesRepository.retry(contactId, message.id)) }
    }

    /** Drop [message] from this phone. The peer is not told. */
    fun delete(message: Message) = deleteAll(listOf(message.id))

    /** Drop the messages [ids] from this phone (iOS Delete Selected). The peer is not told. */
    fun deleteAll(ids: Collection<String>) {
        if (editing.value?.messageId in ids) cancelEdit()
        if (ids.any { replying.value?.messageId.equals(it, ignoreCase = true) }) cancelReply()
        viewModelScope.launch { ids.forEach { messagesRepository.delete(contactId, it) } }
    }

    /** Photos from the picker join the ones already chosen (iOS allows 99 in one album). */
    fun attach(uris: List<Uri>) {
        if (editing.value != null || uris.isEmpty()) return
        attachments.value = (attachments.value + uris).distinct().take(MAX_ATTACHMENTS)
    }

    fun removeAttachment(uri: Uri) {
        attachments.value = attachments.value - uri
        files.value = files.value.filterNot { it.uri == uri }
    }

    fun attachFiles(uris: List<Uri>) {
        if (editing.value != null || uris.isEmpty()) return
        val known = files.value.map { it.uri }.toSet()
        val added = uris.filterNot { it in known }.map { uri ->
            val (name, size) = runCatching { messagesRepository.describe(uri) }.getOrDefault("file" to -1L)
            PickedFile(uri, name, size)
        }
        files.value = (files.value + added).take(MAX_ATTACHMENTS)
    }

    /** The decrypted bytes of [item], for a video about to play. */
    suspend fun mediaBytes(item: com.construct.messenger.data.model.MediaItem): ByteArray = messagesRepository.mediaBytes(item)

    /** Fetch, open and hand [item] (a received or sent file) to the app that shows it. */
    fun openFile(item: com.construct.messenger.data.model.MediaItem) {
        val id = item.mediaId
        if (id in _fileLoading.value) return
        _fileLoading.value = _fileLoading.value + id
        viewModelScope.launch {
            try {
                val uri = messagesRepository.openable(item, item.filename ?: "file")
                _openFile.tryEmit(OpenFile(uri, item.mimeType))
            } catch (e: MediaUnavailable) {
                _fileUnavailable.value = _fileUnavailable.value + id
            } catch (e: Exception) {
                // Network: a tap tries again.
            } finally {
                _fileLoading.value = _fileLoading.value - id
            }
        }
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
        val picked = files.value
        if ((text.isEmpty() && photos.isEmpty() && picked.isEmpty()) || sending.value) return
        val reply = replying.value
        val edit = editing.value
        sending.value = true
        viewModelScope.launch {
            try {
                if ((photos.isNotEmpty() || picked.isNotEmpty()) && edit == null) {
                    // The bubble is in the transcript before the uploads end; the composer
                    // is free again at once, as on iOS. Photos and files go as two messages,
                    // the text with the first.
                    attachments.value = emptyList()
                    files.value = emptyList()
                    draft.value = ""
                    replying.value = null
                    sending.value = false
                    // Each started now, on its own: the files must not wait for a video to encode
                    // and upload — leaving the chat meanwhile cancelled this coroutine, and with
                    // it the files, which were never sent (stand, 2026-09-30).
                    if (photos.isNotEmpty()) viewModelScope.launch { report(messagesRepository.sendPhotos(contactId, photos, text, reply)) }
                    if (picked.isNotEmpty()) {
                        val caption = if (photos.isEmpty()) text else ""
                        viewModelScope.launch { report(messagesRepository.sendFiles(contactId, picked.map { it.uri }, caption)) }
                    }
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
