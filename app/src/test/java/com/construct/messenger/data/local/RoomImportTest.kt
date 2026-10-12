package com.construct.messenger.data.local

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.construct.messenger.calls.CallRecordStatus
import com.construct.messenger.data.local.db.ChatEntity
import com.construct.messenger.data.local.db.ConstructDatabase
import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.util.MediaWire
import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import shared.proto.messaging.v1.Content.MediaAlbumMessage
import shared.proto.messaging.v1.Content.MediaMessage
import shared.proto.messaging.v1.Content.MediaType
import uniffi.construct_core.LocalStore

/**
 * Room filled through its own stores, moved by [RoomImport], then read back through the same
 * interfaces on both sides: what the app saw before the move is what it sees after.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomImportTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), ConstructDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val core = LocalStore.inMemory(ByteArray(32) { 1 })
    private val feed = LocalStoreFeed(core)
    private val import = RoomImport(db.exportDao(), feed, myUserId = ME)

    @After
    fun close() {
        db.close()
        core.close()
    }

    private val album = MediaAlbumMessage.newBuilder()
        .addItems(MediaMessage.newBuilder().setMediaType(MediaType.MEDIA_TYPE_IMAGE).setFileUrl("store-1").setEncryptionKey(ByteString.copyFrom(ByteArray(32) { 7 })))
        .setCaption("caption")
        .build()
        .toByteArray()

    private suspend fun fill() {
        val contacts = RoomContactStore(db.userDao())
        contacts.upsert(
            ContactRecord(
                id = ANN, username = "ann", displayName = "Ann", avatarData = byteArrayOf(9), isContact = true,
                identityPublic = ByteArray(32) { 2 }, localAlias = "A", amSharingWith = true, ktStatus = 1,
            ),
        )
        contacts.ensure(BOB)
        val chats = RoomChatStore(db.chatDao())
        chats.insert(ChatRecord(CHAT_ANN, ANN, "last", 30, unreadCount = 2, isPinned = true))
        chats.insert(ChatRecord(CHAT_BOB, BOB, null, null))
        val messages = RoomMessageStore(db.messageDao())
        messages.insert(MessageRecord("m1", CHAT_ANN, "hi", isSentByMe = true, timestampMs = 10, deliveryStatus = DeliveryStatus.DELIVERED, orderKey = "k1"))
        messages.insert(
            MessageRecord(
                "m2", CHAT_ANN, "re", isSentByMe = false, timestampMs = 20, deliveryStatus = DeliveryStatus.SENT,
                replyToId = "m1", replyPreview = "hi", isEdited = true, orderKey = "k2",
            ),
        )
        messages.insert(
            MessageRecord(
                "m3", CHAT_ANN, "caption", isSentByMe = true, timestampMs = 30, deliveryStatus = DeliveryStatus.FAILED,
                mediaType = MediaWire.KIND_ALBUM, mediaPayload = album, orderKey = "k3",
            ),
        )
        RoomReactionStore(db.reactionDao()).put(ReactionRecord("m1", ANN, "👍", 11, 12))
        RoomCallStore(db.callRecordDao()).put(CallRecord("c1", ANN, "Ann", incoming = true, CallRecordStatus.MISSED, 40, 40, 0))
        RoomPeerDeviceStore(db.peerDeviceDao()).record(PeerDeviceRecord("d1", ANN, ByteArray(32) { 3 }, 5))
        RoomServerMessageIdStore(db.serverMessageIdDao()).record("s1", "m1", 10)
        RoomProcessedStore(db.ackDao()).record("m2", ANN, 21)
        RoomPendingChunkStore(db.pendingChunkDao()).add(PendingChunk(ANN, "big", 0, 2, 10, 0, byteArrayOf(7), 22))
        RoomPendingResendStore(db.pendingResendDao()).queue(PendingResend("m1", "d1", ANN, 23, attempts = 1))
        RoomIssuedInviteStore(db.issuedInviteDao()).record(IssuedInviteRecord("jti", "contact", 1_700_000_000, 600, "s"))
        RoomSessionBlobStore(db.sessionStateDao()).put("session:d1", byteArrayOf(4, 5))
    }

    @Test
    fun whatRoomHeldReadsTheSameFromTheCore() = runTest {
        fill()
        val report = import.run()
        assertEquals(3, report.imported["messages"])
        assertTrue(import.isDone())

        assertEquals(
            RoomContactStore(db.userDao()).observeAll().first().sortedBy { it.id },
            CoreContactStore(feed).observeAll().first().sortedBy { it.id },
        )
        assertEquals(RoomChatStore(db.chatDao()).observeAll().first(), CoreChatStore(feed).observeAll().first())
        val coreMessages = CoreMessageStore(feed, myUserId = { ME })
        for (chat in listOf(CHAT_ANN, CHAT_BOB)) {
            assertEquals(RoomMessageStore(db.messageDao()).observeChat(chat).first(), coreMessages.observeChat(chat).first())
        }
        assertEquals(RoomReactionStore(db.reactionDao()).observeChat(CHAT_ANN).first(), CoreReactionStore(feed).observeChat(CHAT_ANN).first())
        assertEquals(RoomCallStore(db.callRecordDao()).observeRecent().first(), CoreCallStore(feed).observeRecent().first())
        assertEquals(RoomPeerDeviceStore(db.peerDeviceDao()).forAccount(ANN), CorePeerDeviceStore(core).forAccount(ANN))
        assertEquals("m1", CoreServerMessageIdStore(core).localId("s1"))
        assertEquals(listOf("m2"), CoreProcessedStore(core).ids())
        assertEquals(
            RoomPendingChunkStore(db.pendingChunkDao()).chunks(ANN, "big").map { it.chunkIndex to it.payload.toList() },
            CorePendingChunkStore(core).chunks(ANN, "big").map { it.chunkIndex to it.payload.toList() },
        )
        assertEquals(RoomPendingResendStore(db.pendingResendDao()).all(), CorePendingResendStore(core).all())
        assertEquals(RoomIssuedInviteStore(db.issuedInviteDao()).observeAll().first(), CoreIssuedInviteStore(feed).observeAll().first())
        assertArrayEquals(byteArrayOf(4, 5), CoreSessionBlobStore(core).get("session:d1"))
    }

    /** Interrupted or not, a second run finds everything in place and writes nothing new. */
    @Test
    fun aSecondRunChangesNothing() = runTest {
        fill()
        import.run()
        import.run()
        assertEquals(3L, core.messageCount().toLong())
        assertEquals(2, core.chats().size)
    }

    /** Room shows no message whose chat it does not hold; neither does the core. */
    @Test
    fun aMessageWithNoChatIsLeftOut() = runTest {
        fill()
        RoomMessageStore(db.messageDao()).insert(MessageRecord("lost", "chat-gone", "x", isSentByMe = true, timestampMs = 1, deliveryStatus = DeliveryStatus.SENT))
        val report = import.run()
        assertEquals(1, report.skipped["messages"])
        assertTrue(import.isDone())
    }

    /** A count that does not match marks nothing done: Room stays the store. */
    @Test
    fun aMismatchLeavesTheImportUndone() = runTest {
        fill()
        // A second chat with Ann — the core holds one chat per peer, so it cannot take this one.
        db.chatDao().insertIfAbsent(ChatEntity("chat-ann-2", ANN))
        try {
            import.run()
            fail("the import must not pass")
        } catch (e: RoomImport.Mismatch) {
            assertEquals("chats", e.table)
        }
        assertFalse(import.isDone())
    }

    @Test
    fun anEmptyRoomImportsNothingAndIsDone() = runTest {
        import.run()
        assertTrue(import.isDone())
        assertEquals(0L, core.messageCount().toLong())
    }

    private companion object {
        const val ME = "5f0c1a2b-0000-4000-8000-000000000001"
        const val ANN = "0b6e9f6a-3c1d-4f7e-9a2b-5d8c7e6f1a2b"
        const val BOB = "1c7f0a7b-4d2e-4a8f-8b3c-6e9d8f7a2b3c"
        const val CHAT_ANN = "direct:ann"
        const val CHAT_BOB = "direct:bob"
    }
}
