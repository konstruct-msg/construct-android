package com.construct.messenger.data.repository

import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.MessageRecord
import com.construct.messenger.data.local.MessageStore
import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.service.FanoutRetries
import com.construct.messenger.service.FanoutRetryQueue
import com.construct.messenger.util.ConversationId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * A queued message keeps its place in its lane (todo 321, the owner 2026-11-21): the next send to
 * the same person waits until it has left or failed. As Signal: a send's retry is the same job, and
 * its recipient's queue runs nothing else meanwhile (`FastJobStorage.placeJobInEligibleList`).
 *
 * Queued means no device of the person took it yet — the path cut it off, or its session is being
 * opened — and the retry queue ([FanoutRetryQueue]) sends it again. A message one device took holds
 * too while another of the person's devices is owed a copy that is late rather than refused
 * ([FanoutRetries.owesLateCopy]): Signal's job is done only when every device has it. A refused copy
 * does not hold — a person's dead tablet would hold every message for the retry's quarter hour. The
 * hold lasts while the entry lives: the copy leaves, its attempts run out (FAILED), or it expires. A
 * QUEUED row with no entry holds nothing, or it would hold the chat for good. Every change to the
 * queue wakes the waiting send; a row deleted meanwhile is seen at the queue's next change, the
 * retry's next pass at the latest.
 *
 * One client makes this decision today; iOS does not hold. It goes into the core's `fanout_retry`
 * before a second client copies it (rule 2).
 */
@Singleton
class QueuedAhead @Inject constructor(
    private val retries: FanoutRetryQueue,
    private val lateCopies: FanoutRetries,
    private val messages: MessageStore,
    private val keystore: KeystoreManager,
) : ChatSendLanes.Hold {

    override suspend fun awaitClear(contactId: String, lane: ChatSendLanes.Lane) {
        var waitingOn: String? = null
        while (true) {
            val seen = retries.changes.value
            val ahead = ahead(contactId, lane)
            if (ahead == null) {
                if (waitingOn != null) Log.i(TAG, "send to ${contactId.take(8)}… goes: the queued one ahead left")
                return
            }
            if (ahead.id != waitingOn) {
                Log.i(TAG, "send to ${contactId.take(8)}… waits behind a queued one ${ahead.id.take(8)}…")
                waitingOn = ahead.id
            }
            retries.changes.first { it != seen }
        }
    }

    private suspend fun ahead(contactId: String, lane: ChatSendLanes.Lane): MessageRecord? {
        val myId = keystore.getUserId() ?: return null
        val ahead = retries.all()
            .filter { it.recipientUserId.equals(contactId, ignoreCase = true) }
            .mapNotNull { entry -> messages.get(entry.baseMessageId)?.let { Ahead(it, lateCopies.owesLateCopy(entry)) } }
        return holding(ahead, ConversationId.direct(myId, contactId), lane)
    }

    /** A message the retry queue owes the person, and whether a copy it owes is late rather than refused. */
    internal data class Ahead(val row: MessageRecord, val owesLateCopy: Boolean)

    internal companion object {
        const val TAG = "SendLane"

        /** Of the messages the retry queue owes [chatId]'s person, the one that holds [lane]; null when none does. */
        fun holding(ahead: List<Ahead>, chatId: String, lane: ChatSendLanes.Lane): MessageRecord? =
            ahead.firstOrNull { (row, late) ->
                row.isSentByMe && row.chatId == chatId && ChatSendLanes.Lane.of(row.mediaType) == lane &&
                    (row.deliveryStatus == DeliveryStatus.QUEUED || late)
            }?.row
    }
}
