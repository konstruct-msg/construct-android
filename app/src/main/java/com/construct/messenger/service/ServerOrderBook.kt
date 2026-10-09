package com.construct.messenger.service

import com.construct.messenger.util.ServerMessageOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The server's order of each arriving envelope, between the router that sees it and the effect
 * that stores the message — the core sits between them and carries only the message id. Keyed by
 * the envelope's message id; held in memory, bounded, oldest out first. A message whose key is gone
 * (a restart between the two, a chunked message stored under another id) is stored at its own time
 * instead ([ServerMessageOrder.local]), as iOS stores one with no server position.
 */
@Singleton
class ServerOrderBook @Inject constructor() {
    private val keys = object : LinkedHashMap<String, String>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CAPACITY
    }

    fun remember(envelopeMessageId: String, orderKey: String?) {
        if (envelopeMessageId.isEmpty() || orderKey == null) return
        synchronized(keys) { keys[envelopeMessageId.lowercase()] = orderKey }
    }

    /** The key [envelopeMessageId] arrived with, else one at [nowMs] for [rowId]. */
    fun keyFor(envelopeMessageId: String, rowId: String, nowMs: Long): String =
        synchronized(keys) { keys[envelopeMessageId.lowercase()] } ?: ServerMessageOrder.local(nowMs, rowId)

    private companion object {
        const val CAPACITY = 4096
    }
}
