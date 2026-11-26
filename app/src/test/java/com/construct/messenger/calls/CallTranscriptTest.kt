package com.construct.messenger.calls

import com.construct.messenger.R
import com.construct.messenger.data.local.CONTENT_TYPE_CALL
import com.construct.messenger.data.local.CallRecord
import com.construct.messenger.data.local.FakeChatStore
import com.construct.messenger.data.local.FakeContactStore
import com.construct.messenger.data.local.FakeMessageStore
import com.construct.messenger.data.local.isListedContentType
import com.construct.messenger.data.model.CallHistoryEntry
import com.construct.messenger.util.ConversationId
import com.construct.messenger.viewmodel.forPeer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A call stands in its conversation: a row named by the call, at the time it started; a missed one
 * is unread and the list says so. **Canon:** iOS `CallTranscriptTests`.
 */
class CallTranscriptTest {
    private val me = "00000000-0000-0000-0000-00000000000a"
    private val peer = "00000000-0000-0000-0000-00000000000b"
    private val started = 1_800_000_000_000L

    private fun call(
        id: String = "C1", incoming: Boolean = true, status: CallRecordStatus = CallRecordStatus.MISSED, seconds: Int = 0,
    ) = CallRecord(id, peer, "Anna", incoming, status, started, started + seconds * 1000L + 5000, seconds)

    @Test fun theRowIdNamesTheCallAndBack() {
        assertEquals("call-abc", CallTranscript.messageId("AbC"))
        assertEquals("abc", CallTranscript.callId("CALL-abc"))
        assertNull("an ordinary message names no call", CallTranscript.callId("abc"))
        assertNull(CallTranscript.callId("call-"))
    }

    @Test fun anOutgoingCallIsOursAnIncomingOneTheirs() {
        assertTrue(CallTranscript.row(call(incoming = false, status = CallRecordStatus.COMPLETED), "chat", "x").isSentByMe)
        val row = CallTranscript.row(call(), "chat", "Missed call")
        assertFalse(row.isSentByMe)
        assertEquals(CONTENT_TYPE_CALL, row.contentType)
        assertEquals("Missed call", row.text)
        assertEquals(started, row.timestampMs)
        assertEquals("", row.orderKey)
    }

    @Test fun aCallRowIsShown() {
        assertTrue(isListedContentType(CONTENT_TYPE_CALL))
        assertTrue(isListedContentType(0))
        assertFalse(isListedContentType(10))
    }

    @Test fun onlyAMissedIncomingCallIsUnread() {
        assertTrue(CallTranscript.raisesUnread(call(incoming = true, status = CallRecordStatus.MISSED)))
        assertFalse(CallTranscript.raisesUnread(call(incoming = false, status = CallRecordStatus.MISSED)))
        assertFalse(CallTranscript.raisesUnread(call(incoming = true, status = CallRecordStatus.DECLINED)))
        assertFalse(CallTranscript.raisesUnread(call(incoming = true, status = CallRecordStatus.COMPLETED)))
        assertFalse(CallTranscript.raisesUnread(call(incoming = true, status = CallRecordStatus.FAILED)))
    }

    @Test fun eachOutcomeHasItsOwnWords() {
        val cases = listOf(
            Triple(true, CallRecordStatus.MISSED, R.string.call_row_missed),
            Triple(false, CallRecordStatus.MISSED, R.string.call_row_unanswered),
            Triple(true, CallRecordStatus.DECLINED, R.string.call_row_declined),
            Triple(false, CallRecordStatus.DECLINED, R.string.call_row_declined),
            Triple(true, CallRecordStatus.FAILED, R.string.call_row_failed),
            Triple(false, CallRecordStatus.FAILED, R.string.call_row_failed),
            Triple(true, CallRecordStatus.COMPLETED, R.string.call_row_incoming),
            Triple(false, CallRecordStatus.COMPLETED, R.string.call_row_outgoing),
        )
        for ((incoming, status, res) in cases) assertEquals("$incoming $status", res, CallTranscript.labelRes(incoming, status))
    }

    @Test fun aMissedCallIsUnreadAndTheListSaysSo() = runTest {
        val messages = FakeMessageStore(); val chats = FakeChatStore(); val contacts = FakeContactStore()
        assertTrue(CallTranscript.write(call(), announce = true, myId = me, label = "Missed call", messages, chats, contacts))
        assertNotNull(messages.get("call-c1"))
        val chat = chats.get(ConversationId.direct(me, peer))!!
        assertEquals(1, chat.unreadCount)
        assertEquals("Missed call", chat.lastMessageText)
        assertEquals(started, chat.lastMessageTimeMs)
    }

    @Test fun anAnsweredCallIsInTheListButNotUnread() = runTest {
        val chats = FakeChatStore()
        CallTranscript.write(call(status = CallRecordStatus.COMPLETED, seconds = 60), true, me, "Incoming call", FakeMessageStore(), chats, FakeContactStore())
        val chat = chats.get(ConversationId.direct(me, peer))!!
        assertEquals(0, chat.unreadCount)
        assertEquals("Incoming call", chat.lastMessageText)
    }

    @Test fun anUnannouncedCallSetsNoPreviewAndNoUnread() = runTest {
        val messages = FakeMessageStore(); val chats = FakeChatStore()
        CallTranscript.write(call(), announce = false, myId = me, label = "Missed call", messages, chats, FakeContactStore())
        assertNotNull(messages.get("call-c1"))
        val chat = chats.get(ConversationId.direct(me, peer))!!
        assertEquals(0, chat.unreadCount)
        assertNull(chat.lastMessageText)
    }

    @Test fun theSameCallIsWrittenOnce() = runTest {
        val messages = FakeMessageStore(); val chats = FakeChatStore(); val contacts = FakeContactStore()
        assertTrue(CallTranscript.write(call(), true, me, "Missed call", messages, chats, contacts))
        assertFalse(CallTranscript.write(call(), true, me, "Missed call", messages, chats, contacts))
        assertEquals(1, chats.get(ConversationId.direct(me, peer))!!.unreadCount)
    }

    @Test fun aCardListsOnlyItsPersonsCalls() {
        fun e(id: String, who: String) = CallHistoryEntry(id, who, who, null, true, CallHistoryEntry.Status.COMPLETED, 1, 1)
        val all = listOf(e("a1", "anna"), e("m1", "mark"), e("a2", "anna"))
        assertEquals(listOf("a1", "a2"), forPeer(all, "anna").map { it.id })
        assertEquals(all, forPeer(all, null))
    }
}
