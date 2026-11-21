package com.construct.messenger.data.repository

import com.construct.messenger.domain.usecase.SendOutcome
import com.construct.messenger.domain.usecase.SendTurn
import com.construct.messenger.util.MediaWire
import kotlinx.coroutines.CompletableDeferred

/**
 * Sends to a person leave one at a time, in the order they were written (todo 321).
 *
 * The server orders a chat by arrival. Each send used to run on its own, so one held up on the key
 * server or a dead connection arrived after the ones written later — the tester's «they stick, then
 * go in another order». Signal sends to a recipient through one job queue
 * (`IndividualSendJobV2` → `recipient.id.toQueueKey(hasMedia)`), media in a queue of its own beside
 * it so a text does not wait on an upload; this is that queue.
 *
 * A send takes its place when it is called and leaves on its [SendTurn]: the row and the upload go
 * first, so a bubble appears at once and two photos upload side by side, but the second does not
 * leave before the first. A place is given back when its send has left, or when it ends without
 * leaving (an upload failed) — and never before the place ahead of it, so nothing skips a send
 * still on its way.
 *
 * Once its turn comes a send also waits for [Hold]: a message ahead of it that the path cut off
 * is QUEUED, and the retry queue sends it again later. Signal's retry is the same job in the same
 * queue and keeps its place (`FastJobStorage.placeJobInEligibleList`); here the retry runs apart
 * from the lane, so the lane asks it ([QueuedAhead]).
 */
class ChatSendLanes(private val hold: Hold = Hold.NONE) {

    enum class Lane {
        TEXT, MEDIA;

        companion object {
            /** A sticker is a reference inside the text path, with no upload: the text lane. */
            fun of(mediaType: String?): Lane =
                if (mediaType == null || mediaType == MediaWire.KIND_STICKER) TEXT else MEDIA
        }
    }

    /** Whether a send to [contactId] in [lane] has to wait for one ahead of it that is not in the lane. */
    fun interface Hold {
        suspend fun awaitClear(contactId: String, lane: Lane)

        companion object {
            val NONE = Hold { _, _ -> }
        }
    }

    private val tails = HashMap<Pair<String, Lane>, CompletableDeferred<Unit>>()

    /** Takes a place in [contactId]'s [lane] and sends on it — the ordinary send. */
    suspend fun inOrder(contactId: String, lane: Lane, send: suspend (SendTurn) -> SendOutcome): SendOutcome =
        place(contactId, lane).run(send)

    /**
     * The next place in [contactId]'s [lane], taken now without waiting — how sends found together
     * (the outbox a new process finds) keep their order while they run side by side. A place must
     * be [Place.run]: one left unrun holds its lane.
     */
    fun place(contactId: String, lane: Lane): Place {
        val key = contactId.lowercase() to lane
        val done = CompletableDeferred<Unit>()
        val ahead = synchronized(tails) { tails.put(key, done) }
        return Place(key, ahead, done)
    }

    inner class Place internal constructor(
        private val key: Pair<String, Lane>,
        private val ahead: CompletableDeferred<Unit>?,
        private val done: CompletableDeferred<Unit>,
    ) {
        private fun release() {
            if (done.isCompleted) return
            val free = {
                done.complete(Unit)
                synchronized(tails) { if (tails[key] === done) tails.remove(key) }
            }
            if (ahead == null || ahead.isCompleted) free() else ahead.invokeOnCompletion { free() }
        }

        suspend fun run(send: suspend (SendTurn) -> SendOutcome): SendOutcome {
            val turn = SendTurn { leave ->
                try {
                    ahead?.await()
                    hold.awaitClear(key.first, key.second)
                    leave()
                } finally {
                    release()
                }
            }
            return try {
                send(turn)
            } finally {
                release()
            }
        }
    }
}
