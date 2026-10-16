package com.construct.messenger.readsync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per partner (ServerUserId, lowercased): how far this account has read the chat, from this
 * device or another of its own, and the newest incoming message seen, which is what this device
 * marks as read when the chat is open. **Canon:** iOS `ReadBoundaries` (key-value store of the
 * account). On this device only; a local wipe clears it with everything else.
 */
@Singleton
class ReadBoundaries @Inject constructor(@ApplicationContext context: Context) {
    private val read = context.getSharedPreferences("read_boundaries", Context.MODE_PRIVATE)
    private val newest = context.getSharedPreferences("read_boundaries_newest", Context.MODE_PRIVATE)

    fun boundary(partner: String): ReadSync.Boundary? = read.getString(partner.lowercase(), null)?.let(::decode)

    /** Move [partner]'s boundary to [to] if it is later; true when it moved. */
    @Synchronized
    fun advance(partner: String, to: ReadSync.Boundary): Boolean {
        val held = boundary(partner)
        if (held != null && !ReadSync.isLater(to, held)) return false
        read.edit().putString(partner.lowercase(), encode(to)).apply()
        return true
    }

    /** [message] from [partner] is at or before what this account has read. */
    fun covers(partner: String, message: ReadSync.Boundary): Boolean =
        boundary(partner)?.let { ReadSync.covers(it, message) } ?: false

    /** An incoming message from [partner] arrived; the newest one is what reading the chat reads. */
    @Synchronized
    fun noteIncoming(partner: String, message: ReadSync.Boundary) {
        val held = newestIncoming(partner)
        if (held == null || ReadSync.isLater(message, held)) newest.edit().putString(partner.lowercase(), encode(message)).apply()
    }

    fun newestIncoming(partner: String): ReadSync.Boundary? = newest.getString(partner.lowercase(), null)?.let(::decode)

    internal companion object {
        /** `ms:id` — the id last, as it may hold anything. */
        fun encode(b: ReadSync.Boundary): String = "${b.sentAtMs}:${b.messageId}"

        fun decode(s: String): ReadSync.Boundary? {
            val colon = s.indexOf(':')
            if (colon <= 0) return null
            val ms = s.substring(0, colon).toLongOrNull() ?: return null
            return ReadSync.Boundary(ms, s.substring(colon + 1))
        }
    }
}
