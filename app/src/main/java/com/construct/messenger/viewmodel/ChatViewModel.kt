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
import com.construct.messenger.data.repository.ReactionQuickSetRepository
import com.construct.messenger.domain.usecase.SendOutcome
import com.construct.messenger.security.SecurityNotices
import com.construct.messenger.util.DisplayNameGenerator
import com.construct.messenger.util.ReactionRules
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import com.construct.messenger.R
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
    private val quickReactions: ReactionQuickSetRepository,
    private val forwardDrafts: com.construct.messenger.data.repository.ForwardDrafts,
    private val transcription: com.construct.messenger.transcription.NoteTranscription,
    private val videoQualities: com.construct.messenger.media.VideoQualityStore,
    private val voiceSettings: com.construct.messenger.media.VoicePlaybackSettings,
    private val chatOnScreen: com.construct.messenger.media.ChatOnScreen,
) : ViewModel() {
    /** How picked videos are sent; the composer offers it while a video is attached (TODO 148). */
    val videoQuality: StateFlow<com.construct.messenger.media.VideoQuality> get() = videoQualities.state

    fun setVideoQuality(quality: com.construct.messenger.media.VideoQuality) = videoQualities.save(quality)

    /** The voice note being recorded or waiting to be sent; the composer shows its bar. */
    val recording: StateFlow<VoiceRecorder.State> = recorder.state

    /** The voice note playing, anywhere in this chat. */
    val playing: StateFlow<VoicePlayer.Playing?> = player.state

    /** The voice notes' speed; the bubble playing shows it (TODO 149). */
    val voiceRate: StateFlow<Float> get() = player.rate

    private val _voiceFollow = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** A message whose voice note started by itself after the one before it: the list follows it. */
    val voiceFollow: kotlinx.coroutines.flow.SharedFlow<String> = _voiceFollow

    /** ON_START to ON_STOP: only a chat on screen plays its next note (iOS: the active chat). */
    private var shown = false

    /** The emoji heading the message menu, learned from this device's reactions (TODO 117). */
    val reactionRow: StateFlow<List<String>> get() = quickReactions.slots

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
        shown = true
        chatOnScreen.appeared(contactId)
        viewModelScope.launch { messagesRepository.chatShown(contactId) }
    }

    /** ON_STOP — navigated away or the app went to the background. */
    fun onHidden() {
        shown = false
        chatOnScreen.disappeared(contactId)
        messagesRepository.chatHidden(contactId)
        // Leaving the chat folds a note playing in place (iOS `handleViewDisappear`).
        videoNotes.collapse()
    }

    override fun onCleared() {
        messagesRepository.chatHidden(contactId)
        // A track playing outlives its chat; the now-playing banner controls it (iOS `ed0b6266`).
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

    /** Notes being read on this device now, by message id (TODO 116). */
    private val _transcribing = MutableStateFlow<Set<String>>(emptySet())
    val transcribing: StateFlow<Set<String>> = _transcribing.asStateFlow()

    /** Why a note the person asked to read was not: a string resource. */
    private val _transcriptionProblem = kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val transcriptionProblem: kotlinx.coroutines.flow.SharedFlow<Int> = _transcriptionProblem

    val canTranscribe: Boolean get() = transcription.available

    /** Notes already read automatically this time, so a failure is not tried on every frame. */
    private val autoTried = mutableSetOf<String>()

    /** The audio a voice note or a video note carries; null for anything else. */
    private fun noteAudio(message: Message): com.construct.messenger.data.model.MediaItem? = when (val m = message.media) {
        is MessageMedia.Voice -> m.audio
        is MessageMedia.Album -> m.videoNote
        else -> null
    }?.takeUnless { it.mediaId.startsWith(com.construct.messenger.util.MediaWire.LOCAL_PREFIX) }

    /** Read [message] on this device and keep what it says. **Canon:** iOS `ChatViewModel.transcribe`. */
    fun transcribe(message: Message) = read(message, quiet = false)

    /** A voice note on screen, when the person turned automatic reading on. Quiet: no complaint. */
    fun autoTranscribe(message: Message) {
        if (!transcription.available || !transcription.autoTranscribe || message.transcript != null) return
        if (message.media !is MessageMedia.Voice || !autoTried.add(message.id)) return
        read(message, quiet = true)
    }

    private fun read(message: Message, quiet: Boolean) {
        val item = noteAudio(message) ?: return
        if (message.id in _transcribing.value) return
        _transcribing.value = _transcribing.value + message.id
        viewModelScope.launch {
            val problem = try {
                when (val outcome = transcription.read(messagesRepository.mediaBytes(item))) {
                    is com.construct.messenger.transcription.Transcriber.Outcome.Done -> {
                        messagesRepository.setTranscript(message.id, outcome.transcript.text, outcome.transcript.language)
                        null
                    }
                    com.construct.messenger.transcription.Transcriber.Outcome.NoModel -> R.string.stt_error_no_model
                    com.construct.messenger.transcription.Transcriber.Outcome.Unavailable -> R.string.stt_error_unavailable
                    com.construct.messenger.transcription.Transcriber.Outcome.Failed -> R.string.stt_error_failed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                R.string.stt_error_failed
            } finally {
                _transcribing.value = _transcribing.value - message.id
            }
            if (problem != null && !quiet) _transcriptionProblem.tryEmit(problem)
        }
    }

    private val _forwardFailed = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Part of a forward sent here was not: its bytes are on neither this device nor the server. */
    val forwardFailed: kotlinx.coroutines.flow.SharedFlow<Unit> = _forwardFailed

    /**
     * Forward [messages] — in the order given, the transcript's — to [toContactId]: their content
     * only (`ForwardItem`), handed to that chat, which sends it when it opens. True when anything
     * goes. **Canon:** iOS `ChatView.finishForward`.
     */
    fun forward(messages: List<Message>, toContactId: String): Boolean {
        val items = com.construct.messenger.data.model.ForwardItem.of(messages)
        forwardDrafts.hand(items, toContactId)
        return items.isNotEmpty()
    }

    init {
        // What was forwarded to this chat: taken once, sent through this chat's own path.
        viewModelScope.launch {
            forwardDrafts.pending.collect { pending ->
                if (contactId !in pending) return@collect
                val items = forwardDrafts.take(contactId)
                if (items.isEmpty()) return@collect
                launch { if (messagesRepository.forward(contactId, items) > 0) _forwardFailed.tryEmit(Unit) }
            }
        }
        viewModelScope.launch { player.finished.collect(::playNextVoice) }
    }

    private val _reactionFailed = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** A reaction no device took; the badge has already gone back to what it was. */
    val reactionFailed: kotlinx.coroutines.flow.SharedFlow<Unit> = _reactionFailed

    /**
     * React to [message] with [emoji] — or take it off, when it is the one already there. The
     * badge shows at once (iOS `sendReaction`).
     */
    fun react(message: Message, emoji: String) {
        // What the menu row learns from. Taking a reaction off is not a use of it.
        if (ReactionRules.localToggle(message.myReaction, emoji) is ReactionRules.Incoming.Add) {
            quickReactions.record(emoji)
        }
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

    /**
     * A recorded video note, sent in the background like a voice note; the file goes with it. It
     * answers the message being quoted, as on iOS, where a note is an attachment of the composer.
     */
    fun sendVideoNote(take: com.construct.messenger.media.VideoNoteTake) {
        val reply = replying.value
        replying.value = null
        viewModelScope.launch { messagesRepository.sendVideoNote(contactId, take, reply) }
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
    fun toggleVoice(voice: MessageMedia.Voice, messageId: String) =
        play(voice.audio, music = false, com.construct.messenger.media.PlaybackSource(contactId, messageId))

    /** Play [voice] from [fraction] of it; the one already playing moves there (iOS `seek`). */
    fun seekVoice(voice: MessageMedia.Voice, messageId: String, fraction: Float) =
        seek(voice.audio, music = false, com.construct.messenger.media.PlaybackSource(contactId, messageId), fraction)

    /** The message whose track to bring into view: the chat was opened from the banner. */
    fun takeFocus(): String? = chatOnScreen.takeFocus(contactId)

    private fun seek(item: com.construct.messenger.data.model.MediaItem, music: Boolean, source: com.construct.messenger.media.PlaybackSource, fraction: Float) {
        val id = item.mediaId
        if (player.state.value?.mediaId == id) {
            player.seek(id, ByteArray(0), fraction, music, source)
            return
        }
        load(item) { player.seek(id, it, fraction, music, source) }
    }

    fun cycleVoiceRate() = player.cycleRate()

    /**
     * Settings → Transcription → continuous: a note that ended by itself starts the next voice
     * note in this chat, older to newer, and the list follows it; after the last, silence.
     * **Canon:** iOS `ChatViewModel.playNextVoiceIfContinuous`.
     */
    private fun playNextVoice(finished: String) {
        if (!shown || !voiceSettings.continuous.value) return
        val next = nextVoice(uiState.value.messages, finished) ?: return
        val voice = next.media as MessageMedia.Voice
        _voiceFollow.tryEmit(next.id)
        load(voice.audio) { player.toggle(voice.audio.mediaId, it, source = com.construct.messenger.media.PlaybackSource(contactId, next.id)) }
    }

    /**
     * Play or pause a music file in place, in the voice notes' player — one sound at a time —
     * at its own speed. **Canon:** iOS `FileAttachmentBubbleView` (2026-10-13). Fetched on the
     * first tap, as a voice note is.
     */
    fun toggleTrack(item: com.construct.messenger.data.model.MediaItem, messageId: String) =
        play(item, music = true, trackSource(item, messageId))

    /** Move a music file to [fraction] — its slider let go (TODO 151, iOS `AudioFileRow`). */
    fun seekTrack(item: com.construct.messenger.data.model.MediaItem, messageId: String, fraction: Float) =
        seek(item, music = true, trackSource(item, messageId), fraction)

    private fun trackSource(item: com.construct.messenger.data.model.MediaItem, messageId: String) =
        com.construct.messenger.media.PlaybackSource(contactId, messageId, fileName = item.filename ?: "")

    private fun play(item: com.construct.messenger.data.model.MediaItem, music: Boolean, source: com.construct.messenger.media.PlaybackSource) {
        val id = item.mediaId
        if (player.state.value?.mediaId == id) {
            player.toggle(id, ByteArray(0))
            return
        }
        load(item) { player.toggle(id, it, music, source) }
    }

    /** Fetch [item]'s audio, then hand it to [start]; a spinner on its button meanwhile. */
    private fun load(item: com.construct.messenger.data.model.MediaItem, start: (ByteArray) -> Unit) {
        val id = item.mediaId
        if (id in _voiceLoading.value) return
        _voiceLoading.value = _voiceLoading.value + id
        viewModelScope.launch {
            try {
                start(messagesRepository.mediaBytes(item))
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
            // Photos and files go as two messages, the text and the reply with the first. Each started now, on
            // its own: the files must not wait for a video to encode and upload — leaving the chat
            // meanwhile cancelled this coroutine, and with it the files, which were never sent
            // (stand, 2026-09-30).
            attachments.value = emptyList()
            files.value = emptyList()
            if (photos.isNotEmpty()) {
                val quality = videoQualities.state.value
                viewModelScope.launch { report(messagesRepository.sendPhotos(contactId, photos, text, reply, quality)) }
            }
            if (picked.isNotEmpty()) {
                val first = photos.isEmpty()
                viewModelScope.launch {
                    report(messagesRepository.sendFiles(contactId, picked.map { it.uri }, if (first) text else "", if (first) reply else null))
                }
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

/** The first voice note after the one whose audio is [finished], older to newer; null after the last. */
internal fun nextVoice(messages: List<Message>, finished: String): Message? {
    val i = messages.indexOfFirst { (it.media as? MessageMedia.Voice)?.audio?.mediaId == finished }
    if (i < 0) return null
    return messages.drop(i + 1).firstOrNull { it.media is MessageMedia.Voice }
}
