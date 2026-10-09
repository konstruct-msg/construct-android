package com.construct.messenger.service

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.local.AckStore
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.SessionStateStore
import com.construct.messenger.data.local.db.ChatDao
import com.construct.messenger.data.local.db.ChatEntity
import com.construct.messenger.data.local.db.MessageDao
import com.construct.messenger.data.local.db.MessageEntity
import com.construct.messenger.data.local.db.UserDao
import com.construct.messenger.data.local.db.UserEntity
import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.util.ConversationId
import com.construct.messenger.util.EditWire
import com.construct.messenger.util.KnstFrame
import com.construct.messenger.util.TextWire
import com.construct.messenger.domain.usecase.ReceivingOpenUseCase
import com.construct.messenger.domain.usecase.SessionControlUseCase
import com.construct.messenger.util.SenderSyncRouting
import shared.proto.messaging.v1.Content.DeleteMessage
import shared.proto.messaging.v1.Content.DeleteScope
import uniffi.construct_core.CfeAction
import uniffi.construct_core.CfeIncomingEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.construct.messenger.util.ProfileShare
import shared.proto.core.v1.EnvelopeOuterClass.ContentType
import org.junit.Test
import java.util.UUID
import shared.proto.messaging.v1.Content.MessageContent
import shared.proto.messaging.v1.Content.TextMessage
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.wheneverBlocking
import org.mockito.kotlin.whenever

class ProcessorEffectsImplTest {

    private val myId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val peer = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    private val alerts = FakeAlerts()

    /** Just enough to receive text messages: Room fakes and a known local account. */
    private class Inbox(alerts: IncomingAlerts, myId: String) {
        val messages = FakeMessageDao()
        val chats = FakeChatDao()
        val users = FakeUserDao()
        val acks = FakeAckStore()
        val pendingChunks = FakePendingChunkDao()
        val reactions = mock<com.construct.messenger.data.local.ReactionStore>()
        val callSignals = com.construct.messenger.calls.CallSignalInbox()
        /** What auto-download was told arrived: the media kind of each stored message. */
        val arrived = mutableListOf<String?>()
        val avatars = RecordingAvatars()
        val sessionManager = mock<SessionManager>()
        val sendReceipt = mock<com.construct.messenger.domain.usecase.SendReceiptUseCase>()
        val effects = ProcessorEffectsImpl(
            cryptoManager = mock<CryptoManager>(),
            keystoreManager = mock<KeystoreManager>().also { whenever(it.getUserId()).thenReturn(myId) },
            messageDao = messages,
            chatDao = chats,
            userDao = users,
            ackStore = acks,
            sessionStateStore = mock(),
            sessionManager = sessionManager,
            sessionControl = mock(),
            sendReceiptUseCase = sendReceipt,
            sendContactCard = mock(),
            addressBook = mock(),
            intake = mock(),
            receivingOpen = mock(),
            actionExecutor = { mock<CfeTimerBridge>() },
            pendingResends = mock(),
            held = HeldEnvelopes(),
            alerts = alerts,
            chunks = ChunkReassembler(pendingChunks),
            mediaPreview = { if (it.caption.isNotBlank()) it.caption else "Photo" },
            contactAvatars = avatars,
            reactions = reactions,
            callSignals = callSignals,
            mediaArrivals = { kind, _ -> arrived += kind },
        )
    }

    /**
     * A contact card from a contact files their address on their row; the card is not a bubble.
     * Mutation: drop the card branch in `onControlFrame` — this reddens.
     */
    @Test
    fun `a contact card pins the sender's address`() = runTest {
        val users = FakeUserDao().also { it.rows[peer] = UserEntity(id = peer, isContact = true) }
        val keystore = mock<KeystoreManager>().also { whenever(it.getUserId()).thenReturn(myId) }
        val messages = FakeMessageDao()
        val effects = ProcessorEffectsImpl(
            cryptoManager = mock<CryptoManager>(),
            keystoreManager = keystore,
            messageDao = messages,
            chatDao = FakeChatDao(),
            userDao = users,
            ackStore = FakeAckStore(),
            sessionStateStore = mock(),
            sessionManager = mock(),
            sessionControl = mock(),
            sendReceiptUseCase = mock(),
            sendContactCard = mock(),
            addressBook = com.construct.messenger.invite.AccountAddressBook(
                users,
                keystore,
                com.construct.messenger.security.SecurityNotices(users, com.construct.messenger.data.local.ChatPresence()),
            ),
            intake = mock(),
            receivingOpen = mock(),
            actionExecutor = { mock<CfeTimerBridge>() },
            pendingResends = mock(),
            held = HeldEnvelopes(),
            alerts = alerts,
            chunks = ChunkReassembler(FakePendingChunkDao()),
            mediaPreview = { if (it.caption.isNotBlank()) it.caption else "Photo" },
            contactAvatars = RecordingAvatars(),
            reactions = mock(),
            callSignals = com.construct.messenger.calls.CallSignalInbox(),
        )
        val address = ByteArray(32) { 0x5A }
        val card = com.construct.messenger.invite.ContactCardPayload(accountAddress = address).encoded()
        effects.onControlFrame(peer, "card-1", shared.proto.core.v1.EnvelopeOuterClass.ContentType.CONTENT_TYPE_CONTACT_CARD_VALUE, card)
        org.junit.Assert.assertArrayEquals(address, users.rows[peer]?.accountAddress)
        org.junit.Assert.assertTrue("a card is not a bubble", messages.rows.isEmpty())
    }

    @Test
    fun `a contact card hands over the sender's intake key`() = runTest {
        val users = FakeUserDao().also { it.rows[peer] = UserEntity(id = peer, isContact = true) }
        val keystore = mock<KeystoreManager>().also { whenever(it.getUserId()).thenReturn(myId) }
        val messages = FakeMessageDao()
        val intake = mock<com.construct.messenger.stealth.IntakeCredentials>()
        val effects = ProcessorEffectsImpl(
            cryptoManager = mock<CryptoManager>(),
            keystoreManager = keystore,
            messageDao = messages,
            chatDao = FakeChatDao(),
            userDao = users,
            ackStore = FakeAckStore(),
            sessionStateStore = mock(),
            sessionManager = mock(),
            sessionControl = mock(),
            sendReceiptUseCase = mock(),
            sendContactCard = mock(),
            addressBook = com.construct.messenger.invite.AccountAddressBook(
                users,
                keystore,
                com.construct.messenger.security.SecurityNotices(users, com.construct.messenger.data.local.ChatPresence()),
            ),
            intake = intake,
            receivingOpen = mock(),
            actionExecutor = { mock<CfeTimerBridge>() },
            pendingResends = mock(),
            held = HeldEnvelopes(),
            alerts = alerts,
            chunks = ChunkReassembler(FakePendingChunkDao()),
            mediaPreview = { if (it.caption.isNotBlank()) it.caption else "Photo" },
            contactAvatars = RecordingAvatars(),
            reactions = mock(),
            callSignals = com.construct.messenger.calls.CallSignalInbox(),
        )
        val address = ByteArray(32) { 0x5A }
        val card = com.construct.messenger.invite.ContactCardPayload(intakeKey = ByteArray(32) { 7 }, accountAddress = address).encoded()
        effects.onControlFrame(peer, "card-1", shared.proto.core.v1.EnvelopeOuterClass.ContentType.CONTENT_TYPE_CONTACT_CARD_VALUE, card)
        org.mockito.kotlin.verify(intake).recordPeerKey(org.mockito.kotlin.eq(peer), org.mockito.kotlin.argThat { size == 32 && all { it == 7.toByte() } })
    }

    @Test
    fun `an unseen message counts as unread and raises one notification`() = runTest {
        val inbox = Inbox(alerts, myId)
        inbox.effects.onDecrypted(peer, "m1", "hi".toByteArray())
        inbox.effects.onDecrypted(peer, "m2", "again".toByteArray())

        assertEquals(2, inbox.chats.rows[ConversationId.direct(myId, peer)]?.unreadCount)
        assertEquals(listOf(peer, peer), alerts.raised)
    }

    @Test
    fun `a redelivered message is neither counted nor announced twice`() = runTest {
        val inbox = Inbox(alerts, myId)
        inbox.effects.onDecrypted(peer, "m1", "hi".toByteArray())
        inbox.effects.onDecrypted(peer, "m1", "hi".toByteArray())

        assertEquals(1, inbox.chats.rows[ConversationId.direct(myId, peer)]?.unreadCount)
        assertEquals(1, alerts.raised.size)
    }

    @Test
    fun `a message into the chat on screen is read as it lands`() = runTest {
        alerts.visible = peer
        val inbox = Inbox(alerts, myId)
        inbox.effects.onDecrypted(peer, "m1", "hi".toByteArray())

        assertEquals("hi", inbox.messages.rows["m1"]?.text)
        assertEquals(0, inbox.chats.rows[ConversationId.direct(myId, peer)]?.unreadCount)
        assertTrue(alerts.raised.isEmpty())
    }

    @Test
    fun `onDecrypted persists message chat and contact`() = runTest {
        val messages = FakeMessageDao()
        val chats = FakeChatDao()
        val users = FakeUserDao()
        val acks = FakeAckStore()
        val keystore: KeystoreManager = mock()
        whenever(keystore.getUserId()).thenReturn(myId)

        val effects = ProcessorEffectsImpl(
            cryptoManager = mock<CryptoManager>(),
            keystoreManager = keystore,
            messageDao = messages,
            chatDao = chats,
            userDao = users,
            ackStore = acks,
            sessionStateStore = mock(),
            sessionManager = mock(),
            sessionControl = mock(),
            sendReceiptUseCase = mock(),
            sendContactCard = mock(),
            addressBook = mock(),
            intake = mock(),
            receivingOpen = mock(),
            actionExecutor = { mock<CfeTimerBridge>() },
            pendingResends = mock(),
            held = HeldEnvelopes(),
            alerts = alerts,
            chunks = ChunkReassembler(FakePendingChunkDao()),
            mediaPreview = { if (it.caption.isNotBlank()) it.caption else "Photo" },
            contactAvatars = RecordingAvatars(),
            reactions = mock(),
            callSignals = com.construct.messenger.calls.CallSignalInbox(),
        )

        effects.onDecrypted(peer, "msg-1", "hello".toByteArray())

        val chatId = ConversationId.direct(myId, peer)
        assertEquals("hello", messages.rows["msg-1"]?.text)
        assertEquals(chatId, messages.rows["msg-1"]?.chatId)
        assertEquals(peer, chats.rows[chatId]?.otherUserId)
        assertEquals(1, chats.rows[chatId]?.unreadCount)
        assertEquals(peer, users.rows[peer]?.id)
        assertTrue(acks.isProcessed("msg-1"))
    }

    @Test
    fun `onSenderSync strips SSR1 and persists sent copy under base id`() = runTest {
        val messages = FakeMessageDao()
        val chats = FakeChatDao()
        val users = FakeUserDao()
        val acks = FakeAckStore()
        val keystore: KeystoreManager = mock()
        whenever(keystore.getUserId()).thenReturn(myId)
        val effects = ProcessorEffectsImpl(
            cryptoManager = mock<CryptoManager>(),
            keystoreManager = keystore,
            messageDao = messages,
            chatDao = chats,
            userDao = users,
            ackStore = acks,
            sessionStateStore = mock(),
            sessionManager = mock(),
            sessionControl = mock(),
            sendReceiptUseCase = mock(),
            sendContactCard = mock(),
            addressBook = mock(),
            intake = mock(),
            receivingOpen = mock(),
            actionExecutor = { mock<CfeTimerBridge>() },
            pendingResends = mock(),
            held = HeldEnvelopes(),
            alerts = alerts,
            chunks = ChunkReassembler(FakePendingChunkDao()),
            mediaPreview = { if (it.caption.isNotBlank()) it.caption else "Photo" },
            contactAvatars = RecordingAvatars(),
            reactions = mock(),
            callSignals = com.construct.messenger.calls.CallSignalInbox(),
        )
        val baseId = "550e8400-e29b-41d4-a716-446655440000"
        val content = MessageContent.newBuilder()
            .setText(TextMessage.newBuilder().setText("mirrored"))
            .build()
            .toByteArray()
        val knst = KnstFrame.pack(content, KnstFrame.TYPE_E2EE_SIGNAL, UUID.fromString(baseId))
        val routed = SenderSyncRouting.encode(peer, knst)

        effects.onSenderSync(
            contactId = "source-device-opaque",
            messageId = "$baseId-ss-0123456789abcdef",
            plaintext = routed,
            timestampMs = 42L,
        )

        val chatId = ConversationId.direct(myId, peer)
        assertEquals("mirrored", messages.rows[baseId]?.text)
        assertEquals(true, messages.rows[baseId]?.isSentByMe)
        assertEquals(42L, messages.rows[baseId]?.timestamp)
        assertEquals(chatId, messages.rows[baseId]?.chatId)
        assertEquals(0, chats.rows[chatId]?.unreadCount)
        assertTrue(acks.isProcessed("$baseId-ss-0123456789abcdef"))
    }

    /**
     * B8: a contact sharing their profile renames them here and marks them sharing; no bubble,
     * and a local name the user gave still wins where names are shown. Mutation: drop the profile
     * branch — this reddens (the frame falls through as non-visible and nothing changes).
     */
    @Test
    fun `a shared profile renames the contact and adds no bubble`() = runTest {
        val inbox = Inbox(alerts, myId)
        inbox.users.rows[peer] = UserEntity(id = peer, displayName = "quick hotfix", isContact = true, localAlias = "Kostya")
        val frame = KnstFrame.pack(
            com.construct.messenger.util.LegacyProfileShare("Konstantin", timestampSec = 1).encode(),
            KnstFrame.TYPE_E2EE_SIGNAL,
            UUID.randomUUID(),
        )

        inbox.effects.onDecrypted(peer, "p1", frame)

        val row = inbox.users.rows[peer]!!
        assertEquals("Konstantin", row.displayName)
        assertTrue(row.isSharingWithMe)
        assertEquals("Kostya", row.localAlias)
        assertTrue(inbox.messages.rows.isEmpty())
    }

    /** A profile as the core names it (core 0.30): `ControlFrameDecrypted`, body without the header. */
    private suspend fun Inbox.profile(messageId: String, name: String, editedAtMs: Long, avatar: ProfileShare.Avatar) =
        effects.onControlFrame(peer, messageId, ContentType.CONTENT_TYPE_PROFILE_VALUE, ProfileShare(name, editedAtMs, avatar).encoded())

    private val avatarRef = ProfileShare.AvatarRef("m-1", "https://media.example/m-1", ByteArray(32) { 3 }, "image/jpeg")

    /**
     * Type 29: applied by version, the avatar by its state, no bubble. Mutation: compare `>=` —
     * the replayed profile renames again and this reddens.
     */
    @Test
    fun `a typed profile applies only when newer, and names its avatar to fetch`() = runTest {
        val inbox = Inbox(alerts, myId)
        inbox.users.rows[peer] = UserEntity(id = peer, displayName = "quick hotfix", isContact = true)

        inbox.profile("p1", "Konstantin", 20, ProfileShare.Avatar.Set(avatarRef))
        var row = inbox.users.rows[peer]!!
        assertEquals("Konstantin", row.displayName)
        assertTrue(row.isSharingWithMe)
        assertEquals(20L, row.profileEditedAtMs)
        assertEquals(avatarRef, ProfileShare.AvatarRef.fromStored(row.pendingAvatarRef!!))
        assertEquals(listOf(peer), inbox.avatars.fetched)

        // The same version again, then an older one: neither changes anything.
        inbox.profile("p2", "Old Name", 20, ProfileShare.Avatar.Removed)
        inbox.profile("p3", "Older Name", 19, ProfileShare.Avatar.Removed)
        row = inbox.users.rows[peer]!!
        assertEquals("Konstantin", row.displayName)
        assertTrue(row.pendingAvatarRef != null)

        assertTrue(inbox.messages.rows.isEmpty())
        assertTrue(listOf("p1", "p2", "p3").all { inbox.acks.isProcessed(it) })
    }

    /** `removed` clears the avatar and anything still pending — the hole TODO 92 found. */
    @Test
    fun `a typed profile with the avatar removed clears it`() = runTest {
        val inbox = Inbox(alerts, myId)
        inbox.users.rows[peer] = UserEntity(
            id = peer, isContact = true, avatarData = byteArrayOf(1), profileEditedAtMs = 5,
            pendingAvatarRef = avatarRef.stored(), pendingAvatarSinceMs = 1,
        )
        inbox.profile("p1", "K", 6, ProfileShare.Avatar.Removed)
        val row = inbox.users.rows[peer]!!
        assertNull(row.avatarData)
        assertNull(row.pendingAvatarRef)
        assertTrue(inbox.avatars.fetched.isEmpty())
    }

    @Test
    fun `a typed profile from someone with no row adds no one`() = runTest {
        val inbox = Inbox(alerts, myId)
        inbox.profile("p1", "Stranger", 1, ProfileShare.Avatar.Unchanged)
        assertTrue(inbox.users.rows.isEmpty())
        assertTrue(inbox.acks.isProcessed("p1"))
    }

    /** The v1 layout carries no version: once a typed profile is held it could put an older name back. */
    @Test
    fun `an untyped profile is ignored once a typed one is held`() = runTest {
        val inbox = Inbox(alerts, myId)
        inbox.users.rows[peer] = UserEntity(id = peer, displayName = "Konstantin", isContact = true, profileEditedAtMs = 20)
        val frame = KnstFrame.pack(
            com.construct.messenger.util.LegacyProfileShare("quick hotfix", timestampSec = 99).encode(),
            KnstFrame.TYPE_E2EE_SIGNAL,
            UUID.randomUUID(),
        )
        inbox.effects.onDecrypted(peer, "p1", frame)
        assertEquals("Konstantin", inbox.users.rows[peer]!!.displayName)
        assertTrue(inbox.messages.rows.isEmpty())
    }

    @Test
    fun `incoming text is stored under the knst id, not the envelope id`() = runTest {
        val inbox = Inbox(alerts, myId)
        val id = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val frame = KnstFrame.pack(TextWire.encode("hi"), KnstFrame.TYPE_E2EE_SIGNAL, id)
        inbox.effects.onDecrypted(peer, "envelope-rewritten", frame)

        assertEquals("hi", inbox.messages.rows[id.toString()]?.text)
        assertNull(inbox.messages.rows["envelope-rewritten"])
    }

    @Test
    fun `an edit replaces the authors text and adds no bubble`() = runTest {
        val inbox = Inbox(alerts, myId)
        val id = UUID.fromString("22222222-2222-4222-8222-222222222222")
        inbox.effects.onDecrypted(
            peer,
            "env",
            KnstFrame.pack(TextWire.encode("before"), KnstFrame.TYPE_E2EE_SIGNAL, id),
        )
        val chatId = ConversationId.direct(myId, peer)
        inbox.effects.onDecrypted(
            peer,
            "env-edit",
            KnstFrame.pack(EditWire.encode(id.toString(), "after"), KnstFrame.TYPE_E2EE_SIGNAL, UUID.randomUUID()),
        )

        assertEquals("after", inbox.messages.rows[id.toString()]?.text)
        assertTrue(inbox.messages.rows[id.toString()]!!.isEdited)
        assertEquals(1, inbox.messages.rows.size)
        assertEquals(1, inbox.chats.rows[chatId]?.unreadCount)
        assertEquals("after", inbox.chats.rows[chatId]?.lastMessageText)
    }

    /**
     * A call signal the core opened reaches the call machine when a contact sent it, and is
     * dropped when a stranger did; acknowledged either way. Mutation: deliver without the contact
     * check — the stranger's ring gets through.
     */
    @Test
    fun `a call signal from a contact is delivered, from a stranger dropped`() = runTest {
        val inbox = Inbox(alerts, myId)
        inbox.users.rows[peer] = UserEntity(id = peer, isContact = true)
        val got = mutableListOf<com.construct.messenger.calls.CallSignalInbox.Incoming>()
        val job = backgroundScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) { inbox.callSignals.signals.collect { got += it } }
        val signal = com.construct.messenger.calls.CallSignalWire.ringing("call-1", "dev", 1)

        inbox.effects.onCallSignal(peer, "sig-1", signal.toByteArray())
        val stranger = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        inbox.effects.onCallSignal(stranger, "sig-2", signal.toByteArray())

        assertEquals(1, got.size)
        assertEquals(peer, got[0].accountId)
        assertEquals("call-1", got[0].signal.callId)
        assertTrue(inbox.acks.isProcessed("sig-1"))
        assertTrue(inbox.acks.isProcessed("sig-2"))
        assertTrue(inbox.messages.rows.isEmpty())
        job.cancel()
    }

    /**
     * Since core 0.29/0.30 the core names every silent control frame (`CallSignalDecrypted`,
     * `ControlFrameDecrypted`), so one arriving as a decrypted message means the core and the app
     * disagree about the frame: dropped and acknowledged — never rung, never applied, never a
     * bubble. Mutation: handle a type-12 or type-29 frame on the message path again, as before
     * 0.29/0.30 — it rings or renames, and this reddens.
     */
    @Test
    fun `a control frame in a decrypted message is dropped, not handled`() = runTest {
        val inbox = Inbox(alerts, myId)
        inbox.users.rows[peer] = UserEntity(id = peer, displayName = "before", isContact = true)
        val got = mutableListOf<com.construct.messenger.calls.CallSignalInbox.Incoming>()
        val job = backgroundScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) { inbox.callSignals.signals.collect { got += it } }
        val signal = com.construct.messenger.calls.CallSignalWire.frame(
            com.construct.messenger.calls.CallSignalWire.ringing("call-1", "dev", 1),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
        )
        val profile = KnstFrame.pack(
            ProfileShare("after", 9, ProfileShare.Avatar.Removed).encoded(), ContentType.CONTENT_TYPE_PROFILE_VALUE, UUID.randomUUID(),
        )

        inbox.effects.onDecrypted(peer, "env-1", signal)
        inbox.effects.onDecrypted(peer, "env-2", profile)

        assertTrue(got.isEmpty())
        assertEquals("before", inbox.users.rows[peer]?.displayName)
        assertTrue(inbox.acks.isProcessed("env-1"))
        assertTrue(inbox.acks.isProcessed("env-2"))
        assertTrue(inbox.messages.rows.isEmpty())
        job.cancel()
    }

    /**
     * The core names the peer of a control frame by the device whose session opened it; the
     * profile lands on the account. Mutation: file it under the action's contact id — no row
     * matches the device, nothing is renamed, this reddens (iOS lost every call signal this way
     * with 0.29, construct-ios #49).
     */
    @Test
    fun `a control frame named by a device lands on its account`() = runTest {
        val inbox = Inbox(alerts, myId)
        inbox.users.rows[peer] = UserEntity(id = peer, displayName = "before", isContact = true)
        val device = "dddddddd-dddd-dddd-dddd-dddddddddddd"
        org.mockito.kotlin.wheneverBlocking { inbox.sessionManager.accountIdForDevice(device) }.thenReturn(peer)

        inbox.effects.onControlFrame(device, "p1", ContentType.CONTENT_TYPE_PROFILE_VALUE, ProfileShare("after", 9, ProfileShare.Avatar.Removed).encoded())

        assertEquals("after", inbox.users.rows[peer]?.displayName)
        assertTrue(inbox.acks.isProcessed("p1"))
    }

    /**
     * A blocked contact reaches nothing: its control frames are not applied and its messages not
     * stored or receipted, both acknowledged so the queue drains. Mutation: drop either block
     * check — the profile renames or the bubble appears, and this reddens.
     */
    @Test
    fun `a blocked contact's frames and messages are dropped`() = runTest {
        val inbox = Inbox(alerts, myId)
        inbox.users.rows[peer] = UserEntity(id = peer, displayName = "before", isContact = true, isBlocked = true)

        inbox.effects.onControlFrame(peer, "p1", ContentType.CONTENT_TYPE_PROFILE_VALUE, ProfileShare("after", 9, ProfileShare.Avatar.Removed).encoded())
        inbox.effects.onDecrypted(peer, "m1", "hi".toByteArray())

        assertEquals("before", inbox.users.rows[peer]?.displayName)
        assertTrue(inbox.messages.rows.isEmpty())
        assertTrue(inbox.acks.isProcessed("p1"))
        assertTrue(inbox.acks.isProcessed("m1"))
        org.mockito.kotlin.verifyNoInteractions(inbox.sendReceipt)
    }

    /** A receipt the core named marks our message delivered. */
    @Test
    fun `a receipt frame marks our message delivered`() = runTest {
        val inbox = Inbox(alerts, myId)
        inbox.messages.rows["ours-1"] = com.construct.messenger.data.local.db.MessageEntity(
            id = "ours-1", chatId = "c", text = "x", isSentByMe = true, timestamp = 1,
            deliveryStatus = com.construct.messenger.data.model.DeliveryStatus.SENT.name,
        )
        val receipt = shared.proto.signaling.v1.Presence.DeliveryReceipt.newBuilder()
            .setDirect(shared.proto.signaling.v1.Presence.DirectReceipt.newBuilder().addMessageIds("ours-1"))
            .build().toByteArray()

        inbox.effects.onControlFrame(peer, "r1", ContentType.CONTENT_TYPE_DELIVERY_RECEIPT_VALUE, receipt)

        assertEquals(com.construct.messenger.data.model.DeliveryStatus.DELIVERED.name, inbox.messages.rows["ours-1"]?.deliveryStatus)
        assertTrue(inbox.acks.isProcessed("r1"))
    }

    /** Mutation: drop the `isSentByMe` check — the peer's receipt rewrites the status of a message they sent us. */
    @Test
    fun `a receipt naming a message we received changes nothing`() = runTest {
        val inbox = Inbox(alerts, myId)
        inbox.messages.rows["theirs-1"] = com.construct.messenger.data.local.db.MessageEntity(
            id = "theirs-1", chatId = "c", text = "x", isSentByMe = false, timestamp = 1,
            deliveryStatus = com.construct.messenger.data.model.DeliveryStatus.SENT.name,
        )
        val receipt = shared.proto.signaling.v1.Presence.DeliveryReceipt.newBuilder()
            .setDirect(shared.proto.signaling.v1.Presence.DirectReceipt.newBuilder().addMessageIds("theirs-1"))
            .build().toByteArray()

        inbox.effects.onControlFrame(peer, "r2", ContentType.CONTENT_TYPE_DELIVERY_RECEIPT_VALUE, receipt)

        assertEquals(com.construct.messenger.data.model.DeliveryStatus.SENT.name, inbox.messages.rows["theirs-1"]?.deliveryStatus)
        assertTrue(inbox.acks.isProcessed("r2"))
    }

    /** iOS matches receipt ids case-insensitively; an upper-case id still finds our row. */
    @Test
    fun `a receipt id in another case still marks our message`() = runTest {
        val inbox = Inbox(alerts, myId)
        inbox.messages.rows["ours-2"] = com.construct.messenger.data.local.db.MessageEntity(
            id = "ours-2", chatId = "c", text = "x", isSentByMe = true, timestamp = 1,
            deliveryStatus = com.construct.messenger.data.model.DeliveryStatus.SENT.name,
        )
        val receipt = shared.proto.signaling.v1.Presence.DeliveryReceipt.newBuilder()
            .setDirect(shared.proto.signaling.v1.Presence.DirectReceipt.newBuilder().addMessageIds("OURS-2"))
            .build().toByteArray()

        inbox.effects.onControlFrame(peer, "r3", ContentType.CONTENT_TYPE_DELIVERY_RECEIPT_VALUE, receipt)

        assertEquals(com.construct.messenger.data.model.DeliveryStatus.DELIVERED.name, inbox.messages.rows["ours-2"]?.deliveryStatus)
    }

    /**
     * A reaction is metadata on its target: applied under the peer's account, never a row. From a
     * sibling device it is ours. Mutation: drop either reaction branch — its message turns into
     * nothing at all and the store is never called.
     */
    @Test
    fun `a reaction is applied under its reactor and adds no bubble`() = runTest {
        val inbox = Inbox(alerts, myId)
        val target = "22222222-2222-4222-8222-222222222222"
        val reaction = com.construct.messenger.util.ReactionWire.encode(
            target, com.construct.messenger.util.ReactionRules.Incoming.Add("😂"), 77,
        )
        inbox.effects.onDecrypted(peer, "env-r", KnstFrame.pack(reaction, KnstFrame.TYPE_E2EE_SIGNAL, UUID.randomUUID()))
        verifyBlocking(inbox.reactions) {
            applyIncoming(org.mockito.kotlin.eq(target), org.mockito.kotlin.eq(peer), org.mockito.kotlin.eq(1),
                org.mockito.kotlin.eq("😂"), org.mockito.kotlin.eq(77L), any(), any(), any())
        }
        assertTrue(inbox.messages.rows.isEmpty())
        assertTrue(inbox.acks.isProcessed("env-r"))

        val id = UUID.randomUUID()
        inbox.effects.onSenderSync(
            "sibling", "$id-ss-0123456789abcdef",
            KnstFrame.pack(SenderSyncRouting.encode(peer, reaction), KnstFrame.TYPE_SENDER_SYNC, id), 42L,
        )
        verifyBlocking(inbox.reactions) {
            applyIncoming(org.mockito.kotlin.eq(target), org.mockito.kotlin.eq(myId), org.mockito.kotlin.eq(1),
                org.mockito.kotlin.eq("😂"), org.mockito.kotlin.eq(77L), org.mockito.kotlin.eq(42L), any(), any())
        }
        assertTrue(inbox.messages.rows.isEmpty())
    }

    /**
     * A text too long for one frame arrives as several; it is one bubble, and only once the last
     * frame is in. Each frame's envelope is done with as soon as its bytes are held. Mutation:
     * return [ChunkReassembler.Assembly.Ready] for every chunk — the first frame becomes nothing
     * and the message never appears.
     */
    @Test
    fun `a message in several frames is one bubble once the last arrives`() = runTest {
        val inbox = Inbox(alerts, myId)
        val id = UUID.fromString("66666666-6666-4666-8666-666666666666")
        val long = "ж".repeat(3000)
        val frames = KnstFrame.chunks(TextWire.encode(long), KnstFrame.TYPE_E2EE_SIGNAL, id)
        assertEquals(2, frames.size)

        inbox.effects.onDecrypted(peer, "env-c1", frames[1])
        assertTrue(inbox.messages.rows.isEmpty())
        assertTrue(inbox.acks.isProcessed("env-c1"))

        inbox.effects.onDecrypted(peer, "env-c0", frames[0])
        assertEquals(long, inbox.messages.rows[id.toString()]?.text)
        assertEquals(1, inbox.messages.rows.size)
        assertTrue(inbox.pendingChunks.rows.isEmpty())
    }

    /**
     * iOS frames its copy to our own device as type 23 around `SSR1 ‖ content`, cut into chunks
     * when long (`MultiDeviceSendCoordinator`). Android read only `SSR1 ‖ frame` and dropped every
     * iOS sibling's copy as unroutable. Mutation: remove the in-frame branch of `routeSenderSync`.
     */
    @Test
    fun `sender sync in the iOS layout lands in the partner's chat`() = runTest {
        val inbox = Inbox(alerts, myId)
        val id = UUID.fromString("77777777-7777-4777-8777-777777777777")
        val long = "q".repeat(5000)
        val frames = KnstFrame.chunks(
            SenderSyncRouting.encode(peer, TextWire.encode(long)),
            KnstFrame.TYPE_SENDER_SYNC,
            id,
        )
        assertEquals(2, frames.size)

        inbox.effects.onSenderSync("sibling", "$id-ss-0123456789abcdef-c0", frames[0], 42L)
        inbox.effects.onSenderSync("sibling", "$id-ss-0123456789abcdef-c1", frames[1], 42L)

        val row = inbox.messages.rows[id.toString()]
        assertEquals(long, row?.text)
        assertEquals(true, row?.isSentByMe)
        assertEquals(ConversationId.direct(myId, peer), row?.chatId)
    }

    /**
     * A photo from iOS is a row: its caption as the text, the album kept whole for the bubble,
     * and the chat list says what it is. Until C3 it was acknowledged and dropped.
     */
    @Test
    fun `a photo album is kept with the message`() = runTest {
        val inbox = Inbox(alerts, myId)
        val id = UUID.fromString("88888888-8888-4888-8888-888888888888")
        val album = shared.proto.messaging.v1.Content.MediaAlbumMessage.newBuilder().addItems(
            shared.proto.messaging.v1.Content.MediaMessage.newBuilder()
                .setMediaId("m1")
                .setEncryptionKey(com.google.protobuf.ByteString.copyFrom(ByteArray(32)))
                .setMimeType("image/jpeg"),
        )
        val content = MessageContent.newBuilder().setMediaAlbum(album).build().toByteArray()
        inbox.effects.onDecrypted(peer, "env-photo", KnstFrame.pack(content, KnstFrame.TYPE_E2EE_SIGNAL, id))

        val row = inbox.messages.rows[id.toString()]!!
        assertEquals("", row.text)
        assertEquals(com.construct.messenger.util.MediaWire.KIND_ALBUM, row.mediaType)
        assertEquals(album.build().toByteArray().toList(), row.mediaPayload?.toList())
        assertEquals("Photo", inbox.chats.rows[ConversationId.direct(myId, peer)]?.lastMessageText)
    }

    /**
     * Auto-download hears of an album once, as it is stored — not again when the same message is
     * delivered a second time (a lost ACK, a replayed queue), and not for a text.
     * Mutation that reddens it: drop `firstSight &&` from the call in `persistIncoming`.
     */
    @Test
    fun `an arriving album is offered to auto-download once`() = runTest {
        val inbox = Inbox(alerts, myId)
        val id = UUID.fromString("99999999-9999-4999-8999-999999999999")
        val album = shared.proto.messaging.v1.Content.MediaAlbumMessage.newBuilder().addItems(
            shared.proto.messaging.v1.Content.MediaMessage.newBuilder()
                .setMediaId("m2")
                .setEncryptionKey(com.google.protobuf.ByteString.copyFrom(ByteArray(32)))
                .setMimeType("image/jpeg"),
        )
        val content = MessageContent.newBuilder().setMediaAlbum(album).build().toByteArray()
        inbox.effects.onDecrypted(peer, "env-a", KnstFrame.pack(content, KnstFrame.TYPE_E2EE_SIGNAL, id))
        inbox.effects.onDecrypted(peer, "env-b", KnstFrame.pack(content, KnstFrame.TYPE_E2EE_SIGNAL, id))
        val text = MessageContent.newBuilder().setText(
            shared.proto.messaging.v1.Content.TextMessage.newBuilder().setText("hi"),
        ).build().toByteArray()
        inbox.effects.onDecrypted(peer, "env-c", KnstFrame.pack(text, KnstFrame.TYPE_E2EE_SIGNAL, UUID.randomUUID()))

        assertEquals(listOf<String?>(com.construct.messenger.util.MediaWire.KIND_ALBUM), inbox.arrived)
    }

    @Test
    fun `a peer cannot edit a message we sent`() = runTest {
        val inbox = Inbox(alerts, myId)
        val id = "33333333-3333-4333-8333-333333333333"
        val chatId = ConversationId.direct(myId, peer)
        inbox.messages.rows[id] = MessageEntity(
            id = id,
            chatId = chatId,
            text = "mine",
            isSentByMe = true,
            timestamp = 1L,
            deliveryStatus = DeliveryStatus.SENT.name,
        )
        inbox.effects.onDecrypted(
            peer,
            "env-edit",
            KnstFrame.pack(EditWire.encode(id, "hijack"), KnstFrame.TYPE_E2EE_SIGNAL, UUID.randomUUID()),
        )

        assertEquals("mine", inbox.messages.rows[id]?.text)
        assertFalse(inbox.messages.rows[id]!!.isEdited)
    }

    @Test
    fun `sender sync applies our own edit`() = runTest {
        val inbox = Inbox(alerts, myId)
        val id = "44444444-4444-4444-8444-444444444444"
        val chatId = ConversationId.direct(myId, peer)
        inbox.messages.rows[id] = MessageEntity(
            id = id,
            chatId = chatId,
            text = "before",
            isSentByMe = true,
            timestamp = 1L,
            deliveryStatus = DeliveryStatus.SENT.name,
        )
        inbox.chats.rows[chatId] = ChatEntity(id = chatId, otherUserId = peer, lastMessageText = "before", lastMessageTime = 1L)
        val edit = KnstFrame.pack(EditWire.encode(id, "after"), KnstFrame.TYPE_E2EE_SIGNAL, UUID.randomUUID())
        inbox.effects.onSenderSync(peer, "edit-ss-0123456789abcdef", SenderSyncRouting.encode(peer, edit), 50L)

        assertEquals("after", inbox.messages.rows[id]?.text)
        assertTrue(inbox.messages.rows[id]!!.isEdited)
        assertEquals(1, inbox.messages.rows.size)
        assertEquals("after", inbox.chats.rows[chatId]?.lastMessageText)
    }

    @Test
    fun `delete for everyone removes the authors row`() = runTest {
        val inbox = Inbox(alerts, myId)
        val id = UUID.fromString("55555555-5555-4555-8555-555555555555")
        inbox.effects.onDecrypted(
            peer,
            "env",
            KnstFrame.pack(TextWire.encode("gone"), KnstFrame.TYPE_E2EE_SIGNAL, id),
        )
        val deletion = shared.proto.messaging.v1.Content.MessageContent.newBuilder()
            .setDelete(
                DeleteMessage.newBuilder()
                    .setTargetMessageId(id.toString())
                    .setScope(DeleteScope.DELETE_SCOPE_EVERYONE),
            )
            .build()
            .toByteArray()
        inbox.effects.onDecrypted(
            peer,
            "env-del",
            KnstFrame.pack(deletion, KnstFrame.TYPE_E2EE_SIGNAL, UUID.randomUUID()),
        )

        assertNull(inbox.messages.rows[id.toString()])
        val chatId = ConversationId.direct(myId, peer)
        assertNull(inbox.chats.rows[chatId]?.lastMessageText)
    }

    // ── openReceiving: what an open leaves behind ─────────────────────────

    private val incoming = MessageRouter.IncomingMessage(
        messageId = "init-1",
        senderId = peer,
        contentType = shared.proto.core.v1.EnvelopeOuterClass.ContentType.CONTENT_TYPE_E2EE_SIGNAL,
        encryptedPayload = ByteArray(0),
        timestampMs = 0L,
        viaSealedSender = true,
    )

    private val control: SessionControlUseCase = mock()

    private fun effectsFor(
        outcome: ReceivingOpenUseCase.Outcome,
        crypto: CryptoManager,
        bridge: CfeTimerBridge,
        acks: FakeAckStore,
        opener: ReceivingOpenUseCase = mock(),
    ): ProcessorEffectsImpl {
        val keystore: KeystoreManager = mock()
        whenever(keystore.getUserId()).thenReturn(myId)
        wheneverBlocking { opener.open(any(), anyOrNull()) }.thenReturn(outcome)
        return ProcessorEffectsImpl(
            cryptoManager = crypto,
            keystoreManager = keystore,
            messageDao = FakeMessageDao(),
            chatDao = FakeChatDao(),
            userDao = FakeUserDao(),
            ackStore = acks,
            sessionStateStore = mock(),
            sessionManager = mock(),
            sessionControl = control,
            sendReceiptUseCase = mock(),
            sendContactCard = mock(),
            addressBook = mock(),
            intake = mock(),
            receivingOpen = opener,
            actionExecutor = { bridge },
            pendingResends = mock(),
            held = HeldEnvelopes(),
            alerts = alerts,
            chunks = ChunkReassembler(FakePendingChunkDao()),
            mediaPreview = { if (it.caption.isNotBlank()) it.caption else "Photo" },
            contactAvatars = RecordingAvatars(),
            reactions = mock(),
            callSignals = com.construct.messenger.calls.CallSignalInbox(),
        )
    }

    /** The open's actions carry the opener's decrypt and everything that waited behind it;
     * unexecuted, those messages are lost. Nothing is announced after them: the peer learns the
     * session opened from our next message. Mutation that reddens it: skip `execute`. */
    @Test
    fun `an open executes what it produced`() = runTest {
        val actions = listOf<CfeAction>(CfeAction.NotifySessionCreated(contactId = "dev"))
        val bridge: CfeTimerBridge = mock()
        val opener: ReceivingOpenUseCase = mock()
        val acks = FakeAckStore().apply { markProcessed("init-1", peer) }

        val outcome = effectsFor(ReceivingOpenUseCase.Outcome.Opened("dev", "init-1", actions), mock(), bridge, acks, opener)
            .openReceiving("dev", incoming)

        assertEquals(ProcessingOutcome.Processed, outcome)
        verifyBlocking(bridge) { execute(actions) }
    }

    /** A failed open is let go, and its writer is told by the core: the decryption errors are
     * among the open's own actions, executed here. Nothing further is asked — until 2026-09-27
     * this asked the core for a teardown. Mutation that reddens it: skip `execute` on failure. */
    @Test
    fun `a failed open is let go and its actions tell the writer`() = runTest {
        val crypto: CryptoManager = mock()
        val bridge: CfeTimerBridge = mock()
        val acks = FakeAckStore()
        val errors = listOf<CfeAction>(
            CfeAction.SendDecryptionError(contactId = "dev", messageId = "q-1", payload = byteArrayOf(1), enveloped = false),
        )
        val failed = ReceivingOpenUseCase.Outcome.Failed(listOf("q-1"), listOf("q-2"), "AEAD", errors)

        val outcome = effectsFor(failed, crypto, bridge, acks).openReceiving("dev", incoming)

        assertEquals(ProcessingOutcome.Acked, outcome)
        assertTrue(acks.isProcessed("init-1"))
        assertTrue(acks.isProcessed("q-1"))
        assertTrue(acks.isProcessed("q-2"))
        verifyBlocking(bridge) { execute(errors) }
        verify(crypto, never()).handleEvent(any())
    }

    /** A refused certificate says nothing about who sent the message; nothing here asks the core
     * anything more about it (the core itself sends no error to a writer it could not vouch for). */
    @Test
    fun `a refused certificate asks nothing more`() = runTest {
        val crypto: CryptoManager = mock()
        val acks = FakeAckStore()
        val refused = ReceivingOpenUseCase.Outcome.Failed(listOf("init-1"), emptyList(), "SENDER_CERTIFICATE_REFUSED: BadSignature", emptyList())

        effectsFor(refused, crypto, mock(), acks).openReceiving("dev", incoming)

        assertTrue(acks.isProcessed("init-1"))
        verify(crypto, never()).handleEvent(any())
    }

    @Test
    fun `an unreachable open leaves the carrier for a later delivery`() = runTest {
        val acks = FakeAckStore()

        val outcome = effectsFor(ReceivingOpenUseCase.Outcome.Unreachable, mock(), mock(), acks)
            .openReceiving("dev", incoming)

        assertEquals(ProcessingOutcome.Deferred, outcome)
        assertFalse(acks.isProcessed("init-1"))
    }
}

private class FakeMessageDao : MessageDao {
    val rows = linkedMapOf<String, MessageEntity>()
    override fun observeChat(chatId: String) = MutableStateFlow(rows.values.filter { it.chatId == chatId })
    override suspend fun getById(messageId: String) = rows[messageId]
    override suspend fun getByIdIgnoreCase(messageId: String) =
        rows.entries.firstOrNull { it.key.equals(messageId, ignoreCase = true) }?.value
    override suspend fun insert(message: MessageEntity) { rows[message.id] = message }
    override suspend fun updateDeliveryStatus(messageId: String, status: String) {
        rows[messageId]?.let { rows[messageId] = it.copy(deliveryStatus = status) }
    }
    override suspend fun markEditedMedia(id: String, text: String, mediaPayload: ByteArray) {
        rows[id]?.let { rows[id] = it.copy(text = text, mediaPayload = mediaPayload, isEdited = true) }
    }
    override suspend fun markEdited(id: String, text: String) {
        rows[id]?.let { rows[id] = it.copy(text = text, isEdited = true) }
    }
    override suspend fun deleteById(id: String) { rows.remove(id) }
    override suspend fun latestVisible(chatId: String) =
        rows.values.filter { it.chatId == chatId && it.contentType == 0 }.maxByOrNull { it.timestamp }
    override suspend fun deleteChat(chatId: String) { rows.values.removeAll { it.chatId == chatId } }
}

private class FakeChatDao : ChatDao {
    val rows = linkedMapOf<String, ChatEntity>()
    override fun observeAll() = MutableStateFlow(rows.values.toList())
    override fun observeActivity() = MutableStateFlow(emptyList<com.construct.messenger.data.model.ChatActivity>())
    override suspend fun getById(chatId: String) = rows[chatId]
    override suspend fun getAllIds() = rows.keys.toList()
    override suspend fun upsert(chat: ChatEntity) { rows[chat.id] = chat }
    override suspend fun updateLastMessage(chatId: String, text: String?, timeMs: Long) {
        rows[chatId]?.let { rows[chatId] = it.copy(lastMessageText = text, lastMessageTime = timeMs) }
    }
    override suspend fun updateUnreadCount(chatId: String, count: Int) {
        rows[chatId]?.let { rows[chatId] = it.copy(unreadCount = count) }
    }
    override suspend fun incrementUnreadCount(chatId: String) {
        rows[chatId]?.let { rows[chatId] = it.copy(unreadCount = it.unreadCount + 1) }
    }
    override suspend fun setPinned(chatId: String, pinned: Boolean) {
        rows[chatId]?.let { rows[chatId] = it.copy(isPinned = pinned) }
    }
    override suspend fun delete(chatId: String) { rows.remove(chatId) }
}

internal class FakeUserDao : UserDao {
    val rows = linkedMapOf<String, UserEntity>()
    override suspend fun pendingAvatarIds(): List<String> = rows.values.filter { it.pendingAvatarRef != null }.map { it.id }
    override suspend fun clearPendingAvatar(userId: String, stored: ByteArray): Int {
        val row = rows[userId]?.takeIf { it.pendingAvatarRef.contentEquals(stored) } ?: return 0
        rows[userId] = row.copy(pendingAvatarRef = null, pendingAvatarSinceMs = null)
        return 1
    }
    override suspend fun completePendingAvatar(userId: String, stored: ByteArray, avatar: ByteArray): Int {
        val row = rows[userId]?.takeIf { it.pendingAvatarRef.contentEquals(stored) } ?: return 0
        rows[userId] = row.copy(avatarData = avatar, pendingAvatarRef = null, pendingAvatarSinceMs = null)
        return 1
    }
    override fun observeContacts(): Flow<List<UserEntity>> = MutableStateFlow(rows.values.filter { it.isContact })
    override fun observeAll(): Flow<List<UserEntity>> = MutableStateFlow(rows.values.toList())
    override fun observeBlocked(): Flow<List<UserEntity>> = MutableStateFlow(rows.values.filter { it.isBlocked })
    override fun observeById(userId: String): Flow<UserEntity?> = MutableStateFlow(rows[userId])
    override suspend fun getById(userId: String) = rows[userId]
    override suspend fun upsert(user: UserEntity) { rows[user.id] = user }
    override suspend fun delete(userId: String) { rows.remove(userId) }
    override suspend fun setKtStatus(userId: String, code: Int) = Unit
    override suspend fun setSecurityNotice(userId: String, code: Int) {
        rows[userId]?.let { rows[userId] = it.copy(securityNotice = code) }
    }
    override suspend fun setLocalAlias(userId: String, alias: String?) {
        rows[userId]?.let { rows[userId] = it.copy(localAlias = alias) }
    }
    override suspend fun setAmSharingWith(userId: String, sharing: Boolean) {
        rows[userId]?.let { rows[userId] = it.copy(amSharingWith = sharing) }
    }

    override suspend fun sharingWithIds(): List<String> = rows.values.filter { it.amSharingWith && !it.isBlocked }.map { it.id }

    override suspend fun setAvatar(userId: String, avatar: ByteArray?) {
        rows[userId]?.let { rows[userId] = it.copy(avatarData = avatar) }
    }
}

private class FakeAckStore : AckStore {
    private val ids = mutableSetOf<String>()
    override suspend fun hydrate() = Unit
    override fun isProcessed(messageId: String) = messageId in ids
    override suspend fun markProcessed(messageId: String, senderId: String) { ids += messageId }
    override suspend fun prune(olderThanMs: Long) = 0
}

private class FakeAlerts : IncomingAlerts {
    var visible: String? = null
    val raised = mutableListOf<String>()
    override fun isChatVisible(contactId: String) = contactId == visible
    override fun onUnseenMessage(contactId: String) { raised += contactId }
    override fun clear(contactId: String) = Unit
}

/** Which rows had a pending avatar fetch started. */
internal class RecordingAvatars : ContactAvatars {
    val fetched = mutableListOf<String>()
    override fun fetchPending(accountId: String) { fetched += accountId }
    override fun retryPending() = Unit
}
