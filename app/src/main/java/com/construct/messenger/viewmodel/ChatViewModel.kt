package com.construct.messenger.viewmodel

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.data.model.Message
import com.construct.messenger.data.model.MessageMedia
import com.construct.messenger.data.repository.MediaUnavailable
import com.construct.messenger.media.VideoNotePlayback
import com.construct.messenger.media.VoicePlayer
import com.construct.messenger.media.VoiceRecorder
import com.construct.messenger.data.model.ReplyRef
import com.construct.messenger.data.model.Contact
import com.construct.messenger.data.model.ContactTrustAlert
import com.construct.messenger.data.model.KtStatus
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class ChatUiState(
    val contactId: String,
    val title: String,
    val messages: List<Message> = emptyList(),
    val draft: String = "",
    /** The message the composer is quoting. Null when the next send is not a reply. */
    val replyingTo: ReplyRef? = null,
    /** Text of the message being edited, shown in the bar. Null when the next send is a new message. */
    val editingOriginal: String? = null,
    /** Their unacknowledged security event; the banner shows while it is not NONE. */
    /** What to warn about: their security event, or a failed KT proof. */
    val trustAlert: ContactTrustAlert? = null,
    /** The key server's proof for their key verified; the nav bar marks it. */
    val ktVerified: Boolean = false,
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
    private val videoNotes: VideoNotePlayback,
) : ViewModel() {
    /** The voice note being recorded or waiting to be sent; the composer shows its bar. */
    val recording: StateFlow<VoiceRecorder.State> = recorder.state

    /** The voice note playing, anywhere in this chat. */
    val playing: StateFlow<VoicePlayer.Playing?> = player.state

    /** The video note expanded in place and playing with sound, if any. */
    val videoNote: StateFlow<VideoNotePlayback.State> = videoNotes.state

    private val _voiceLoading = MutableStateFlow<Set<String>>(emptySet())
    val voiceLoading: StateFlow<Set<String>> = _voiceLoading.asStateFlow()

    private val _voiceUnavailable = MutableStateFlow<Set<String>>(emptySet())
    val voiceUnavailable: StateFlow<Set<String>> = _voiceUnavailable.asStateFlow()

    val contactId: String = requireNotNull(savedStateHandle.get<String>("contactId"))

    private val draft = MutableStateFlow("")
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
        combine(replying, editing, attachments, files) { reply, edit, photos, picked -> Composer(reply, edit, photos, picked) },
    ) { messages, contacts, draftText, composer ->
        val (reply, edit, photos, picked) = composer
        val contact = contacts.find { it.userId == contactId }
        val name = chatPeerName(contact, contactId)
        ChatUiState(
            contactId = contactId,
            title = name.uppercase(),
            messages = messages,
            draft = draftText,
            replyingTo = reply,
            editingOriginal = edit?.original,
            trustAlert = contact?.trustAlert,
            ktVerified = contact?.ktStatus == KtStatus.VERIFIED,
            contactName = name,
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
    fun onHidden() {
        messagesRepository.chatHidden(contactId)
        // Leaving the chat folds a note playing in place (iOS `handleViewDisappear`).
        videoNotes.collapse()
    }

    override fun onCleared() {
        messagesRepository.chatHidden(contactId)
        player.stop()
        videoNotes.collapse()
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
        // iOS quotes media by its kind (`buildQuoted`): a sticker, a voice note, an album by its
        // first item. A video note is a video on the wire and a note here.
        val media = message.media
        replying.value = ReplyRef.of(
            message.id,
            quote?.takeIf { it.isNotBlank() } ?: message.body,
            ReplyRef.mediaTypeOf(media),
            videoNote = (media as? MessageMedia.Album)?.videoNote != null,
        )
    }

    /** A sticker from the picker, sent at once (iOS `sendSticker`); the composer is left as it is. */
    fun sendSticker(ref: com.construct.messenger.stickers.StickerReference) {
        viewModelScope.launch { report(messagesRepository.sendSticker(contactId, ref)) }
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

    private val _galleryResult = kotlinx.coroutines.flow.MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    /** A save to the gallery finished: true when the file is there. */
    val galleryResult: kotlinx.coroutines.flow.SharedFlow<Boolean> = _galleryResult

    /** The viewer's Save: [item] into the phone's gallery. */
    fun saveToGallery(item: com.construct.messenger.data.model.MediaItem) {
        viewModelScope.launch {
            val saved = try {
                messagesRepository.saveToGallery(item)
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            _galleryResult.tryEmit(saved)
        }
    }

    private val _shareFile = kotlinx.coroutines.flow.MutableSharedFlow<OpenFile>(extraBufferCapacity = 1)
    /** A photo or video ready to hand to the share sheet (iOS viewer's "…"). */
    val shareFile: kotlinx.coroutines.flow.SharedFlow<OpenFile> = _shareFile

    fun share(item: com.construct.messenger.data.model.MediaItem) {
        viewModelScope.launch {
            try {
                val name = item.filename?.takeIf { it.isNotBlank() } ?: if (item.isVideo) "video.mp4" else "photo.jpg"
                _shareFile.tryEmit(OpenFile(messagesRepository.openable(item, name), item.mimeType))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _galleryResult.tryEmit(false)
            }
        }
    }

    /** The decrypted bytes of [item], for a video about to play. */
    /** A tap on a video note whose bytes are here: expand it with sound, or pause / resume it. */
    fun tapVideoNote(key: VideoNotePlayback.Key, data: ByteArray) = videoNotes.tap(key, data)

    fun cycleVideoNoteRate() = videoNotes.cycleRate()

    /** The note left the screen (scrolled away) or opens full screen. */
    fun collapseVideoNote(key: VideoNotePlayback.Key? = null) =
        if (key == null) videoNotes.collapse() else videoNotes.collapse(ifShowing = key)

    fun attachVideoNote(view: android.view.TextureView) = videoNotes.attach(view)

    fun detachVideoNote(view: android.view.TextureView) = videoNotes.detach(view)

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
        videoNotes.collapse()
        return recorder.start()
    }

    fun stopRecording() = recorder.stop()

    fun cancelRecording() = recorder.cancel()

    /** A recorded video note, sent in the background like a voice note; the file goes with it. */
    fun sendVideoNote(take: com.construct.messenger.media.VideoNoteTake) {
        viewModelScope.launch { messagesRepository.sendVideoNote(contactId, take) }
    }

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

    /**
     * The composer is free at once, as on iOS (`ChatView.onSend` clears before the send ends): the
     * bubble, SENDING then SENT or FAILED with its retry, is the send's progress. Waiting for the
     * server with the text locked in the field read as a send that hung (testers, 2026-10-03) —
     * over VEIL or a slow network the answer takes seconds.
     */
    fun send() {
        val typed = draft.value
        val text = typed.trim()
        val photos = attachments.value
        val picked = files.value
        if (text.isEmpty() && photos.isEmpty() && picked.isEmpty()) return
        val reply = replying.value
        val edit = editing.value
        draft.value = ""
        replying.value = null
        editing.value = null
        if ((photos.isNotEmpty() || picked.isNotEmpty()) && edit == null) {
            // Photos and files go as two messages, the text with the first. Each started now, on
            // its own: the files must not wait for a video to encode and upload — leaving the chat
            // meanwhile cancelled this coroutine, and with it the files, which were never sent
            // (stand, 2026-09-30).
            attachments.value = emptyList()
            files.value = emptyList()
            if (photos.isNotEmpty()) viewModelScope.launch { report(messagesRepository.sendPhotos(contactId, photos, text, reply)) }
            if (picked.isNotEmpty()) {
                val caption = if (photos.isEmpty()) text else ""
                viewModelScope.launch { report(messagesRepository.sendFiles(contactId, picked.map { it.uri }, caption)) }
            }
            return
        }
        viewModelScope.launch {
            val outcome = try {
                if (edit != null) messagesRepository.edit(contactId, edit.messageId, text)
                else messagesRepository.send(contactId, text, reply)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SendOutcome.Failed("", e.message ?: "send failed")
            }
            // A failed send has its FAILED bubble to retry from. A failed edit, or a send refused
            // before it wrote a row, has nothing on screen: the text goes back into the composer,
            // unless the user has started on something else meanwhile.
            val lost = outcome is SendOutcome.Failed && (edit != null || outcome.messageId.isEmpty())
            if (lost && draft.value.isEmpty() && replying.value == null && editing.value == null) {
                draft.value = typed
                replying.value = reply
                editing.value = edit
            }
        }
    }

    private companion object {
        const val MAX_ATTACHMENTS = 99
    }
}

/**
 * The peer's name in the chat header and its security banner: the name the user gave them, the
 * name they shared, their username, then the generated name — [Contact.displayName] already holds
 * that order (`resolvedName`). The header sets it in capitals. **Canon:** iOS `ChatView`'s
 * `ObservedPeerName` → `resolvedDisplayName`.
 *
 * Until 2026-10-07 a username won over the shared name here, as `@username`: Alice, who shared
 * her name, was "@ALICE" on Android and "ALICE" on iOS.
 */
internal fun chatPeerName(contact: Contact?, contactId: String): String =
    contact?.displayName ?: DisplayNameGenerator.generate(contactId)
