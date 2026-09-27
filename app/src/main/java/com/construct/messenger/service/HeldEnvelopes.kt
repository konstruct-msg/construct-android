package com.construct.messenger.service

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The envelopes of messages the core holds — queued for a session to open, or held behind our own
 * unanswered SESSION_RESET_INIT. The queue itself is the core's; this keeps only what the core's
 * answers name by id and cannot carry: the content type and timestamp a drained message is routed
 * by (a drained SENDER_SYNC is an outgoing copy, not an incoming bubble), and the envelope a
 * `ReplayHeld` sends back through processing.
 *
 * In memory only. After a restart the core's queue is empty too and the server redelivers both.
 *
 * **Canon:** iOS `MessageRouter.coreQueuedEnvelopes` — and like it, not a second queue: nothing
 * here decides what waits or what drains.
 */
@Singleton
class HeldEnvelopes @Inject constructor() {
    private val held = ConcurrentHashMap<String, MessageRouter.IncomingMessage>()

    private val _replays = MutableSharedFlow<MessageRouter.IncomingMessage>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Envelopes the core released with `ReplayHeld`, to be processed again. */
    val replays: SharedFlow<MessageRouter.IncomingMessage> = _replays.asSharedFlow()

    fun hold(message: MessageRouter.IncomingMessage) {
        held[message.messageId] = message
    }

    /** The envelope held under [messageId], removed — the core has answered for it. */
    fun take(messageId: String): MessageRouter.IncomingMessage? = held.remove(messageId)

    /** Send the envelope held under [messageId] back through processing; false when none is. */
    fun replay(messageId: String): Boolean {
        val message = held.remove(messageId) ?: return false
        return _replays.tryEmit(message)
    }
}
