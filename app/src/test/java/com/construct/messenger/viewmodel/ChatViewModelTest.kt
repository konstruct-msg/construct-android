package com.construct.messenger.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.construct.messenger.data.model.Contact
import com.construct.messenger.data.model.Message
import com.construct.messenger.data.model.ReplyRef
import com.construct.messenger.data.repository.AcceptInviteResult
import com.construct.messenger.data.repository.ContactsRepository
import com.construct.messenger.data.repository.FindUserResult
import com.construct.messenger.data.repository.IncomingContactRequest
import com.construct.messenger.data.repository.MessagesRepository
import com.construct.messenger.domain.usecase.SendOutcome
import com.construct.messenger.invite.MintedInvite
import com.construct.messenger.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val qualities = org.mockito.kotlin.mock<com.construct.messenger.media.VideoQualityStore>().also {
        org.mockito.kotlin.whenever(it.state)
            .thenReturn(kotlinx.coroutines.flow.MutableStateFlow(com.construct.messenger.media.VideoQuality.P720))
    }

    /** Tracks that played to their end; a test emits one to stand for the player. */
    private val finished = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val player = org.mockito.kotlin.mock<com.construct.messenger.media.VoicePlayer>().also {
        org.mockito.kotlin.whenever(it.finished).thenReturn(finished)
        org.mockito.kotlin.whenever(it.state).thenReturn(MutableStateFlow(null))
    }
    private val continuous = MutableStateFlow(false)
    private val voiceSettings = org.mockito.kotlin.mock<com.construct.messenger.media.VoicePlaybackSettings>().also {
        org.mockito.kotlin.whenever(it.continuous).thenReturn(continuous)
    }

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val drafts = com.construct.messenger.data.repository.ForwardDrafts()

    private fun chat(id: String, messages: FakeMessagesRepository): ChatViewModel {
        val handle = SavedStateHandle()
        handle["contactId"] = id
        return ChatViewModel(handle, messages, FakeContactsRepository(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), player, org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), drafts, org.mockito.kotlin.mock(), qualities, voiceSettings)
    }

    private val voiceNote = Message(
        id = "v1", chatId = "peer-1", body = "", isOutgoing = false,
        media = com.construct.messenger.data.model.MessageMedia.Voice(
            com.construct.messenger.data.model.MediaItem("store-1", ByteArray(32), ByteArray(32), 10, "audio/m4a"), emptyList(),
        ),
    )

    private fun reading(outcome: com.construct.messenger.transcription.Transcriber.Outcome, auto: Boolean = false): com.construct.messenger.transcription.NoteTranscription =
        org.mockito.kotlin.mock<com.construct.messenger.transcription.NoteTranscription>().also {
            org.mockito.kotlin.whenever(it.available).thenReturn(true)
            org.mockito.kotlin.whenever(it.autoTranscribe).thenReturn(auto)
            kotlinx.coroutines.runBlocking { org.mockito.kotlin.whenever(it.read(org.mockito.kotlin.any())).thenReturn(outcome) }
        }

    private fun chatReading(messages: FakeMessagesRepository, transcription: com.construct.messenger.transcription.NoteTranscription): ChatViewModel {
        val handle = SavedStateHandle()
        handle["contactId"] = "peer-1"
        return ChatViewModel(handle, messages, FakeContactsRepository(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), player, org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), drafts, transcription, qualities, voiceSettings)
    }

    private fun voice(id: String, store: String) = voiceNote.copy(
        id = id,
        media = com.construct.messenger.data.model.MessageMedia.Voice(
            com.construct.messenger.data.model.MediaItem(store, ByteArray(32), ByteArray(32), 10, "audio/m4a"), emptyList(),
        ),
    )

    /** The next voice note, older to newer, skipping what is not one; none after the last (TODO 149). */
    @Test
    fun nextVoiceIsTheFollowingVoiceNote() {
        val text = Message(id = "t", chatId = "peer-1", body = "hi", isOutgoing = false)
        val list = listOf(voice("a", "s-a"), text, voice("b", "s-b"), voice("c", "s-c"))
        assertEquals("b", nextVoice(list, "s-a")?.id)
        assertEquals("c", nextVoice(list, "s-b")?.id)
        assertNull(nextVoice(list, "s-c"))
        assertNull(nextVoice(list, "unknown"))
    }

    /** Continuous on and the chat on screen: a note that ended by itself starts the next one. */
    @Test
    fun aFinishedNotePlaysTheNextWhenContinuous() = runTest {
        val messages = FakeMessagesRepository()
        messages.flow.value = listOf(voice("a", "s-a"), voice("b", "s-b"))
        continuous.value = true
        val vm = chat("peer-1", messages)
        vm.onShown()
        advanceUntilIdle()
        finished.emit("s-a")
        advanceUntilIdle()
        org.mockito.kotlin.verify(player).toggle(org.mockito.kotlin.eq("s-b"), org.mockito.kotlin.any(), org.mockito.kotlin.eq(false))
    }

    /** Off — the default — or the chat not on screen: the next note waits for a tap. */
    @Test
    fun aFinishedNoteStopsThereUnlessContinuousAndShown() = runTest {
        val messages = FakeMessagesRepository()
        messages.flow.value = listOf(voice("a", "s-a"), voice("b", "s-b"))
        val vm = chat("peer-1", messages)
        vm.onShown()
        advanceUntilIdle()
        finished.emit("s-a")
        advanceUntilIdle()
        continuous.value = true
        vm.onHidden()
        finished.emit("s-a")
        advanceUntilIdle()
        org.mockito.kotlin.verify(player, org.mockito.kotlin.never()).toggle(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    /** What a voice note says, read on the device, is kept with it (TODO 116). */
    @Test
    fun aTranscribedNoteKeepsWhatItSays() = runTest {
        val messages = FakeMessagesRepository()
        val vm = chatReading(messages, reading(com.construct.messenger.transcription.Transcriber.Outcome.Done(com.construct.messenger.transcription.Transcriber.Transcript("hello", "en"))))
        vm.transcribe(voiceNote)
        advanceUntilIdle()
        assertEquals(listOf("v1" to "hello"), messages.transcripts)
        assertTrue(vm.transcribing.value.isEmpty())
    }

    /** No model yet: the person is told where to get one; nothing is kept. */
    @Test
    fun noModelSaysSo() = runTest {
        val messages = FakeMessagesRepository()
        val vm = chatReading(messages, reading(com.construct.messenger.transcription.Transcriber.Outcome.NoModel))
        val problems = mutableListOf<Int>()
        val job = launch { vm.transcriptionProblem.collect { problems += it } }
        // Subscribed before the read: the flow keeps nothing for a late collector.
        runCurrent()
        vm.transcribe(voiceNote)
        advanceUntilIdle()
        assertEquals(listOf(com.construct.messenger.R.string.stt_error_no_model), problems)
        assertTrue(messages.transcripts.isEmpty())
        job.cancel()
    }

    /** Automatic reading is off unless the person turned it on (iOS default). */
    @Test
    fun automaticReadingWaitsForTheSetting() = runTest {
        val messages = FakeMessagesRepository()
        val off = chatReading(messages, reading(com.construct.messenger.transcription.Transcriber.Outcome.Done(com.construct.messenger.transcription.Transcriber.Transcript("x", null)), auto = false))
        off.autoTranscribe(voiceNote)
        advanceUntilIdle()
        assertTrue(messages.transcripts.isEmpty())
        val on = chatReading(messages, reading(com.construct.messenger.transcription.Transcriber.Outcome.Done(com.construct.messenger.transcription.Transcriber.Transcript("x", null)), auto = true))
        on.autoTranscribe(voiceNote)
        on.autoTranscribe(voiceNote)
        advanceUntilIdle()
        assertEquals("read once", 1, messages.transcripts.size)
    }

    /**
     * A forward goes to the picked chat as content only, in transcript order, and that chat sends
     * it when it opens — not the chat it was picked in. iOS `ForwardDrafts`.
     */
    @Test
    fun aForwardIsSentByTheChatItGoesTo() = runTest {
        val here = FakeMessagesRepository()
        val source = chat("peer-1", here)
        val replyAndQuote = Message(id = "m1", chatId = "peer-1", body = " hello ", isOutgoing = false, replyToId = "m0", replyPreview = "earlier")
        val empty = Message(id = "m2", chatId = "peer-1", body = "  ", isOutgoing = true)
        assertTrue(source.forward(listOf(replyAndQuote, empty), "peer-2"))
        advanceUntilIdle()
        assertTrue("not sent by the chat it was picked in", here.forwarded.isEmpty())

        val there = FakeMessagesRepository()
        chat("peer-2", there)
        advanceUntilIdle()
        assertEquals(listOf("peer-2" to listOf<com.construct.messenger.data.model.ForwardItem>(com.construct.messenger.data.model.ForwardItem.Text("hello"))), there.forwarded)
        assertTrue("taken once", drafts.pending.value.isEmpty())
    }

    @Test
    fun aForwardToTheChatOnScreenGoesAtOnce() = runTest {
        val here = FakeMessagesRepository()
        val vm = chat("peer-1", here)
        advanceUntilIdle()
        vm.forward(listOf(Message(id = "m1", chatId = "peer-1", body = "again", isOutgoing = true)), "peer-1")
        advanceUntilIdle()
        assertEquals(1, here.forwarded.size)
    }

    @Test
    fun sendClearsDraftAndRecordsOutgoing() = runTest {
        val messages = FakeMessagesRepository()
        val handle = SavedStateHandle()
        handle["contactId"] = "peer-1"
        val viewModel = ChatViewModel(
            handle,
            messages,
            FakeContactsRepository(),
            org.mockito.kotlin.mock(),
            org.mockito.kotlin.mock(),
            player,
            org.mockito.kotlin.mock(),
            org.mockito.kotlin.mock(),
            drafts,
            org.mockito.kotlin.mock(),
            qualities,
            voiceSettings,
        )
        viewModel.onDraftChange("hello")
        viewModel.send()
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.draft)
        assertEquals(1, messages.sent.size)
        assertEquals("hello", messages.sent.single().text)
        assertNull(messages.sent.single().reply)
        assertTrue(viewModel.uiState.value.messages.single().isOutgoing)
    }

    /**
     * The field is empty while the send is still on the wire (testers, 2026-10-03: the text sat in
     * a locked field until the server answered). Mutation: clear only on Sent — this reddens.
     */
    @Test
    fun `the composer is free before the send ends`() = runTest {
        val messages = FakeMessagesRepository().also { it.gate = kotlinx.coroutines.CompletableDeferred() }
        val handle = SavedStateHandle().apply { set("contactId", "peer-1") }
        val viewModel = ChatViewModel(handle, messages, FakeContactsRepository(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), player, org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), drafts, org.mockito.kotlin.mock(), qualities, voiceSettings)
        viewModel.startReply(Message(id = "q", chatId = "peer-1", body = "quoted", isOutgoing = false))
        viewModel.onDraftChange("hello")
        viewModel.send()
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.draft)
        assertNull(viewModel.uiState.value.replyingTo)
        assertEquals("hello", messages.sent.single().text)
        viewModel.onDraftChange("next")
        messages.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("next", viewModel.uiState.value.draft)
    }

    /**
     * The header names the peer as iOS does: the shared name, in capitals — not `@username`.
     * Mutation: put the username branch back ahead of the name — this reddens.
     */
    @Test
    fun `the header shows the shared name, not the username`() = runTest {
        val contacts = FakeContactsRepository()
        contacts.contacts.value = listOf(Contact(userId = "peer-1", displayName = "Alice", username = "alice"))
        val handle = SavedStateHandle().apply { set("contactId", "peer-1") }
        val viewModel = ChatViewModel(handle, FakeMessagesRepository(), contacts, org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), player, org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), drafts, org.mockito.kotlin.mock(), qualities, voiceSettings)
        advanceUntilIdle()

        assertEquals("ALICE", viewModel.uiState.value.title)
        assertEquals("Alice", viewModel.uiState.value.contactName)
    }

    /** Refused before a row was written, nothing on screen holds the text: it comes back. */
    @Test
    fun `a send refused before any bubble gives the text back`() = runTest {
        val messages = FakeMessagesRepository().also { it.refuseSend = true }
        val handle = SavedStateHandle().apply { set("contactId", "peer-1") }
        val viewModel = ChatViewModel(handle, messages, FakeContactsRepository(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), player, org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), drafts, org.mockito.kotlin.mock(), qualities, voiceSettings)
        viewModel.onDraftChange("hello ")
        viewModel.send()
        advanceUntilIdle()

        assertEquals("hello ", viewModel.uiState.value.draft)
    }

    @Test
    fun sendCarriesTheReplyAndClearsTheBar() = runTest {
        val messages = FakeMessagesRepository()
        val handle = SavedStateHandle()
        handle["contactId"] = "peer-1"
        val viewModel = ChatViewModel(handle, messages, FakeContactsRepository(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), player, org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), drafts, org.mockito.kotlin.mock(), qualities, voiceSettings)

        viewModel.startReply(
            Message(id = "ABC", chatId = "peer-1", body = "  original  ", isOutgoing = false),
        )
        advanceUntilIdle()
        assertEquals("abc", viewModel.uiState.value.replyingTo?.messageId)
        assertEquals("original", viewModel.uiState.value.replyingTo?.preview)

        viewModel.onDraftChange("answer")
        viewModel.send()
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.draft)
        assertNull(viewModel.uiState.value.replyingTo)
        assertEquals(ReplyRef.of("abc", "original"), messages.sent.single().reply)
    }

    /** A video note recorded while quoting answers that message, as a photo would (iOS: a note is an attachment). */
    @Test
    fun aVideoNoteCarriesTheReplyAndClearsTheBar() = runTest {
        val messages = FakeMessagesRepository()
        val handle = SavedStateHandle()
        handle["contactId"] = "peer-1"
        val viewModel = ChatViewModel(handle, messages, FakeContactsRepository(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), player, org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), drafts, org.mockito.kotlin.mock(), qualities, voiceSettings)
        viewModel.startReply(Message(id = "ABC", chatId = "peer-1", body = "original", isOutgoing = false))
        advanceUntilIdle()

        viewModel.sendVideoNote(com.construct.messenger.media.VideoNoteTake(emptyList()))
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.replyingTo)
        assertEquals(ReplyRef.of("abc", "original"), messages.notes.single())
    }

    /** Files alone take the reply; beside photos, the photos do — the text and the quote go with the first message. */
    @Test
    fun filesCarryTheReplyOnlyWhenTheyGoFirst() = runTest {
        val messages = FakeMessagesRepository()
        val handle = SavedStateHandle()
        handle["contactId"] = "peer-1"
        val viewModel = ChatViewModel(handle, messages, FakeContactsRepository(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), player, org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), drafts, org.mockito.kotlin.mock(), qualities, voiceSettings)
        val reply = Message(id = "ABC", chatId = "peer-1", body = "original", isOutgoing = false)

        viewModel.startReply(reply)
        viewModel.attachFiles(listOf(org.mockito.kotlin.mock()))
        viewModel.send()
        advanceUntilIdle()
        assertEquals(ReplyRef.of("abc", "original"), messages.files.single())

        viewModel.startReply(reply)
        viewModel.attach(listOf(org.mockito.kotlin.mock()))
        viewModel.attachFiles(listOf(org.mockito.kotlin.mock()))
        viewModel.send()
        advanceUntilIdle()
        assertNull(messages.files.last())
    }

    @Test
    fun sendEditsTheTargetAndClearsTheBar() = runTest {
        val messages = FakeMessagesRepository()
        val handle = SavedStateHandle()
        handle["contactId"] = "peer-1"
        val viewModel = ChatViewModel(handle, messages, FakeContactsRepository(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), player, org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), drafts, org.mockito.kotlin.mock(), qualities, voiceSettings)
        val original = Message(id = "mine", chatId = "peer-1", body = "hello", isOutgoing = true)

        viewModel.startEdit(original)
        advanceUntilIdle()
        assertEquals("hello", viewModel.uiState.value.draft)
        assertEquals("hello", viewModel.uiState.value.editingOriginal)
        assertNull(viewModel.uiState.value.replyingTo)

        viewModel.onDraftChange("hello there")
        viewModel.send()
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.draft)
        assertNull(viewModel.uiState.value.editingOriginal)
        assertTrue(messages.sent.isEmpty())
        assertEquals("mine", messages.edits.single().messageId)
        assertEquals("hello there", messages.edits.single().text)
    }

    @Test
    fun aFailedEditKeepsTheDraft() = runTest {
        val messages = FakeMessagesRepository().also { it.failEdit = true }
        val handle = SavedStateHandle()
        handle["contactId"] = "peer-1"
        val viewModel = ChatViewModel(handle, messages, FakeContactsRepository(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), player, org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), drafts, org.mockito.kotlin.mock(), qualities, voiceSettings)
        viewModel.startEdit(Message(id = "mine", chatId = "peer-1", body = "hello", isOutgoing = true))
        viewModel.onDraftChange("hello there")
        viewModel.send()
        advanceUntilIdle()

        assertEquals("hello there", viewModel.uiState.value.draft)
        assertEquals("hello", viewModel.uiState.value.editingOriginal)
    }

    @Test
    fun deleteDropsTheRowAndAnOpenEditOfIt() = runTest {
        val messages = FakeMessagesRepository()
        val handle = SavedStateHandle()
        handle["contactId"] = "peer-1"
        val viewModel = ChatViewModel(handle, messages, FakeContactsRepository(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), player, org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), drafts, org.mockito.kotlin.mock(), qualities, voiceSettings)
        val original = Message(id = "mine", chatId = "peer-1", body = "hello", isOutgoing = true)
        viewModel.startEdit(original)
        viewModel.delete(original)
        advanceUntilIdle()

        assertEquals(listOf("mine"), messages.deleted)
        assertNull(viewModel.uiState.value.editingOriginal)
        assertEquals("", viewModel.uiState.value.draft)
    }
    /** Picked photos go with the typed text as their caption, and the composer is free at once. */
    @Test
    fun `picked photos are sent with the draft as caption`() = runTest {
        val handle = SavedStateHandle().apply { set("contactId", "peer") }
        val messages = FakeMessagesRepository()
        val viewModel = ChatViewModel(handle, messages, FakeContactsRepository(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), player, org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), drafts, org.mockito.kotlin.mock(), qualities, voiceSettings)
        val a = org.mockito.kotlin.mock<android.net.Uri>()
        val b = org.mockito.kotlin.mock<android.net.Uri>()
        viewModel.attach(listOf(a, b, a))
        viewModel.onDraftChange("sea")
        viewModel.send()
        advanceUntilIdle()

        assertEquals(listOf(listOf(a, b) to "sea"), messages.photos)
        // The quality last chosen goes with them (TODO 148).
        assertEquals(listOf(com.construct.messenger.media.VideoQuality.P720), messages.qualities)
        assertTrue(messages.sent.isEmpty())
        assertEquals(emptyList<android.net.Uri>(), viewModel.uiState.value.attachments)
        assertEquals("", viewModel.uiState.value.draft)
    }

    /** iOS Quote & Reply: the selected part is the quote, the message is still the one replied to. */
    @Test
    fun `a selected quote replaces the preview of the reply`() = runTest {
        val handle = SavedStateHandle()
        handle["contactId"] = "peer-1"
        val viewModel = ChatViewModel(handle, FakeMessagesRepository(), FakeContactsRepository(), org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), player, org.mockito.kotlin.mock(), org.mockito.kotlin.mock(), drafts, org.mockito.kotlin.mock(), qualities, voiceSettings)
        val message = Message(id = "M-1", chatId = "c", body = "one two three", isOutgoing = false)
        viewModel.startReply(message, "two")
        advanceUntilIdle()
        assertEquals("m-1", viewModel.uiState.value.replyingTo?.messageId)
        assertEquals("two", viewModel.uiState.value.replyingTo?.preview)
        viewModel.startReply(message)
        advanceUntilIdle()
        assertEquals("one two three", viewModel.uiState.value.replyingTo?.preview)
    }
}

private class FakeMessagesRepository : MessagesRepository {
    val sent = mutableListOf<Sent>()
    val flow = MutableStateFlow<List<Message>>(emptyList())
    override fun observeContact(contactId: String): Flow<List<Message>> = flow.asStateFlow()
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var refuseSend = false
    override suspend fun send(contactId: String, text: String, reply: ReplyRef?): SendOutcome {
        sent += Sent(contactId, text, reply)
        if (refuseSend) return SendOutcome.Failed("", "not authenticated")
        gate?.await()
        flow.value = flow.value + Message(
            id = "m-${sent.size}",
            chatId = contactId,
            body = text,
            isOutgoing = true,
            replyToId = reply?.messageId,
            replyPreview = reply?.preview,
        )
        return SendOutcome.Sent(flow.value.last().id)
    }

    data class Sent(val contactId: String, val text: String, val reply: ReplyRef?)
    val photos = mutableListOf<Pair<List<android.net.Uri>, String>>()
    val qualities = mutableListOf<com.construct.messenger.media.VideoQuality>()
    val files = mutableListOf<ReplyRef?>()
    override suspend fun sendFiles(contactId: String, uris: List<android.net.Uri>, caption: String, reply: ReplyRef?): SendOutcome {
        files += reply
        return SendOutcome.Sent("f")
    }
    override suspend fun openable(item: com.construct.messenger.data.model.MediaItem, name: String): android.net.Uri = org.mockito.kotlin.mock()
    override fun describe(uri: android.net.Uri) = "a.pdf" to 10L
    override suspend fun sendVoice(contactId: String, recording: java.io.File, durationMs: Long, waveform: List<Float>) = SendOutcome.Sent("v")
    val notes = mutableListOf<ReplyRef?>()
    override suspend fun sendVideoNote(contactId: String, take: com.construct.messenger.media.VideoNoteTake, reply: ReplyRef?): SendOutcome {
        notes += reply
        return SendOutcome.Sent("n")
    }
    override suspend fun mediaBytes(item: com.construct.messenger.data.model.MediaItem) = ByteArray(0)
    val transcripts = mutableListOf<Pair<String, String>>()
    override suspend fun setTranscript(messageId: String, text: String, language: String?) {
        transcripts += messageId to text
    }
    override suspend fun saveToGallery(item: com.construct.messenger.data.model.MediaItem) = Unit
    override suspend fun sendPhotos(
        contactId: String,
        uris: List<android.net.Uri>,
        caption: String,
        reply: ReplyRef?,
        quality: com.construct.messenger.media.VideoQuality,
    ): SendOutcome {
        photos += uris to caption
        qualities += quality
        return SendOutcome.Sent("p-${photos.size}")
    }
    val edits = mutableListOf<Edit>()
    val deleted = mutableListOf<String>()
    var failEdit = false
    data class Edit(val messageId: String, val text: String)
    override suspend fun edit(contactId: String, messageId: String, newText: String): SendOutcome {
        edits += Edit(messageId, newText)
        if (failEdit) return SendOutcome.Failed(messageId, "no")
        flow.value = flow.value.map { row ->
            if (row.id == messageId) row.copy(body = newText, isEdited = true) else row
        }
        return SendOutcome.Sent(messageId)
    }
    override suspend fun delete(contactId: String, messageId: String) {
        deleted += messageId
        flow.value = flow.value.filter { it.id != messageId }
    }
    override suspend fun sendSticker(contactId: String, ref: com.construct.messenger.stickers.StickerReference) = SendOutcome.Sent("s")
    val forwarded = mutableListOf<Pair<String, List<com.construct.messenger.data.model.ForwardItem>>>()
    override suspend fun forward(contactId: String, items: List<com.construct.messenger.data.model.ForwardItem>): Int {
        forwarded += contactId to items
        return 0
    }
    val retried = mutableListOf<String>()
    override suspend fun retry(contactId: String, messageId: String): SendOutcome {
        retried += messageId
        return SendOutcome.Sent(messageId)
    }
    val reacted = mutableListOf<Pair<String, String>>()
    override suspend fun react(contactId: String, messageId: String, emoji: String): Boolean {
        reacted += messageId to emoji
        return true
    }
    override suspend fun chatShown(contactId: String) = Unit
    override fun chatHidden(contactId: String) = Unit
}

private class FakeContactsRepository : ContactsRepository {
    override val contacts = MutableStateFlow<List<Contact>>(emptyList())
    override suspend fun mintLink(): MintedInvite =
        MintedInvite("j", 0, 300, "p", "konstruct://add?invite=p")
    override suspend fun mintQr(sitting: String): MintedInvite = mintLink()
    override suspend fun accept(raw: String): AcceptInviteResult =
        AcceptInviteResult.Failed(com.construct.messenger.R.string.error_something_went_wrong)
    override suspend fun revoke(jti: String) = com.construct.messenger.data.repository.InviteRevocation.UNCONFIRMED
    override val incomingRequests = MutableStateFlow<List<IncomingContactRequest>>(emptyList())
    override suspend fun findByUsername(username: String): FindUserResult = FindUserResult.NotFound
    override suspend fun sendContactRequest(userId: String) = false
    override suspend fun refreshRequests() = Unit
    override suspend fun acceptRequest(requestId: String, fromUserId: String) = false
    override suspend fun checkUsername(username: String) =
        com.construct.messenger.data.repository.UsernameAvailability(true)
    override suspend fun setDiscoverable(enabled: Boolean) = true
    override suspend fun getProfile(userId: String) = null
    override val issuedInvites =
        MutableStateFlow<List<com.construct.messenger.data.repository.IssuedInvite>>(emptyList())
}
