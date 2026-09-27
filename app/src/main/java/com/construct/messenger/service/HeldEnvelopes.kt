package com.construct.messenger.service

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The envelopes of messages the core holds queued for a session to open. The queue itself is the
 * core's; this keeps only what the core's answers name by id and cannot carry: the content type and
 * timestamp a drained message is routed by (a drained SENDER_SYNC is an outgoing copy, not an
 * incoming bubble). The hold behind our own unanswered SESSION_RESET_INIT, and the `ReplayHeld`
 * that sent an envelope back through processing, went with the SRI on 2026-09-27.
 *
 * In memory only. After a restart the core's queue is empty too and the server redelivers both.
 *
 * **Canon:** iOS `MessageRouter.coreQueuedEnvelopes` — and like it, not a second queue: nothing
 * here decides what waits or what drains.
 */
@Singleton
class HeldEnvelopes @Inject constructor() {
    private val held = ConcurrentHashMap<String, MessageRouter.IncomingMessage>()

    fun hold(message: MessageRouter.IncomingMessage) {
        held[message.messageId] = message
    }

    /** The envelope held under [messageId], removed — the core has answered for it. */
    fun take(messageId: String): MessageRouter.IncomingMessage? = held.remove(messageId)
}
