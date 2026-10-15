package com.construct.messenger.readsync

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.construct.messenger.data.local.ChatRecord
import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.data.local.FakeChatStore
import com.construct.messenger.data.local.FakeMessageStore
import com.construct.messenger.data.local.MessageRecord
import com.construct.messenger.util.ConversationId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A marker from another device of ours lowers the count; one from anyone else does nothing (TODO 153). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReadSyncStateTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val me = "11111111-1111-4111-8111-111111111111"
    private val kim = "22222222-2222-4222-8222-222222222222"
    private val chatId = ConversationId.direct(me, kim)
    private val chats = FakeChatStore()
    private val messages = FakeMessageStore()
    private val boundaries = ReadBoundaries(context)
    private val sent = mutableListOf<ByteArray>()
    private val state = ReadSyncState(
        boundaries,
        ReadSyncOutbox(boundaries, { sent += it }, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), { Long.MAX_VALUE }),
        chats,
        messages,
    )

    private fun incoming(i: Int) = MessageRecord(
        id = "m$i", chatId = chatId, text = "$i", isSentByMe = false,
        timestampMs = 1_000L * i, deliveryStatus = DeliveryStatus.DELIVERED, orderKey = "k$i",
    )

    private suspend fun fiveUnread() {
        chats.insert(ChatRecord(id = chatId, peerId = kim, unreadCount = 5))
        (1..5).forEach { messages.insert(incoming(it)) }
    }

    private fun marker(i: Int) = ReadSync.encode(mapOf(kim to ReadSync.Boundary.of(1_000L * i, "m$i")))

    @Test
    fun `a marker from our other device leaves only what is past it unread`() = runTest {
        fiveUnread()
        state.markers(marker(3), fromOwnAccount = true, myId = me)
        assertEquals(2, chats.get(chatId)!!.unreadCount)
    }

    /** Mutation: apply markers whoever sent them — a contact could clear my unread count. */
    @Test
    fun `a marker from a contact changes nothing`() = runTest {
        fiveUnread()
        state.markers(marker(5), fromOwnAccount = false, myId = me)
        assertEquals(5, chats.get(chatId)!!.unreadCount)
        assertNull(boundaries.boundary(kim))
    }

    @Test
    fun `an older marker does not move the boundary back`() = runTest {
        fiveUnread()
        state.markers(marker(4), fromOwnAccount = true, myId = me)
        state.markers(marker(1), fromOwnAccount = true, myId = me)
        assertEquals(1, chats.get(chatId)!!.unreadCount)
        assertEquals("m4", boundaries.boundary(kim)!!.messageId)
    }

    @Test
    fun `a message the boundary covers arrives read`() = runTest {
        boundaries.advance(kim, ReadSync.Boundary.of(5_000, "m5"))
        assertTrue(state.isRead(kim, ReadSync.Boundary.of(4_000, "m4")))
        assertFalse(state.isRead(kim, ReadSync.Boundary.of(6_000, "m6")))
    }

    @Test
    fun `without the boundary's message here, time decides and errs toward unread`() {
        val rows = (1..5).map(::incoming)
        assertEquals(2, ReadSyncState.unreadLeft(5, rows, ReadSync.Boundary.of(3_000, "absent")))
        assertEquals(0, ReadSyncState.unreadLeft(0, rows, ReadSync.Boundary.of(9_000, "absent")))
    }
}
