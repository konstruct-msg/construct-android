package com.construct.messenger.data.repository

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One send at a time to a person, in the order they were written (todo 321).
 *
 * The server orders a chat by arrival. Each send used to run on its own, so one held up on the key
 * server or a dead connection arrived after the ones written later — the tester's «they stick, then
 * go in another order». Signal sends to a recipient through one job queue
 * (`IndividualSendJob` → `recipient.id.toQueueKey(hasMedia)`), media in a queue of its own beside
 * it so a text does not wait on an upload; this is that queue for a send in progress. A [Mutex]
 * resumes its waiters first come, first served, so the order of the calls is the order of the sends.
 *
 * What it does not order: a message the path cut off is QUEUED and resent by the retry queue
 * ([com.construct.messenger.service.FanoutRetries]), which does not hold the lane meanwhile.
 */
class ChatSendLanes {

    enum class Lane { TEXT, MEDIA }

    private val lanes = ConcurrentHashMap<Pair<String, Lane>, Mutex>()

    suspend fun <T> inOrder(contactId: String, lane: Lane, send: suspend () -> T): T =
        lanes.getOrPut(contactId.lowercase() to lane) { Mutex() }.withLock { send() }
}
