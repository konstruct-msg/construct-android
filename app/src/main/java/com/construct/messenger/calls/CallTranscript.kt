package com.construct.messenger.calls

import androidx.annotation.StringRes
import com.construct.messenger.R
import com.construct.messenger.data.local.CONTENT_TYPE_CALL
import com.construct.messenger.data.local.CallRecord
import com.construct.messenger.data.local.ChatStore
import com.construct.messenger.data.local.ContactStore
import com.construct.messenger.data.local.MessageRecord
import com.construct.messenger.data.local.MessageStore
import com.construct.messenger.data.local.ensure
import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.util.ConversationId

/**
 * A call shows in the conversation it was with, between the messages, at the time it started:
 * "Missed call", "Outgoing call · 3:05", and a tap calls back. Until 2026-11-26 a call was only a
 * row of the Calls tab, and that tab is going (owner; vault todo 351). **Canon:** iOS
 * `CallTranscript` — one row per call, named by it (`call-<call id>`), never sent; the call's facts
 * are read from the call log.
 *
 * One difference, on purpose: the row's text is the call's words ("Missed call"), because this app
 * keeps the chat list's line as text and reads it back from the newest row
 * ([com.construct.messenger.data.local.refreshChatPreview]). A call's record is written once, when it
 * ends, so the words never disagree with it.
 */
object CallTranscript {
    private const val ID_PREFIX = "call-"

    /** The row of a call: one per call, so a second write of the same call adds nothing. */
    fun messageId(callId: String): String = ID_PREFIX + callId.lowercase()

    /** The call a row names; null for an ordinary message. */
    fun callId(messageId: String): String? {
        val id = messageId.lowercase()
        return if (id.startsWith(ID_PREFIX) && id.length > ID_PREFIX.length) id.substring(ID_PREFIX.length) else null
    }

    /** When the call stands in the transcript: when it started; one with no start, when it ended. */
    fun time(call: CallRecord): Long = call.startedAtMs.takeIf { it > 0 } ?: call.endedAtMs

    /** Whether the call counts as unread in its chat: one that rang here and nobody answered. */
    fun raisesUnread(call: CallRecord): Boolean = call.incoming && call.status == CallRecordStatus.MISSED

    /** What the row and the chat list say, without the length. */
    @StringRes
    fun labelRes(incoming: Boolean, status: CallRecordStatus): Int = when (status) {
        CallRecordStatus.MISSED -> if (incoming) R.string.call_row_missed else R.string.call_row_unanswered
        CallRecordStatus.DECLINED -> R.string.call_row_declined
        CallRecordStatus.FAILED -> R.string.call_row_failed
        CallRecordStatus.COMPLETED -> if (incoming) R.string.call_row_incoming else R.string.call_row_outgoing
    }

    /** The row for [call] in [chatId], saying [label]. Reply, edit, media are left empty on purpose. */
    fun row(call: CallRecord, chatId: String, label: String): MessageRecord = MessageRecord(
        id = messageId(call.id),
        chatId = chatId,
        text = label,
        isSentByMe = !call.incoming,
        timestampMs = time(call),
        deliveryStatus = DeliveryStatus.DELIVERED,
        contentType = CONTENT_TYPE_CALL,
    )

    /**
     * Writes the call's row into the chat with its peer, made if missing. [announce]: the call just
     * ended here — the chat list shows it and a missed one counts as unread. Returns whether the row
     * was added.
     */
    suspend fun write(
        call: CallRecord,
        announce: Boolean,
        myId: String,
        label: String,
        messages: MessageStore,
        chats: ChatStore,
        contacts: ContactStore,
    ): Boolean {
        val chatId = ConversationId.direct(myId, call.peerUserId)
        // The core holds a message to its chat and the chat to its contact: both first.
        contacts.ensure(call.peerUserId)
        chats.ensure(chatId, call.peerUserId)
        val added = messages.insert(row(call, chatId, label))
        if (!added || !announce) return added
        chats.setPreview(chatId, label, time(call))
        if (raisesUnread(call)) chats.incrementUnread(chatId)
        return added
    }
}
