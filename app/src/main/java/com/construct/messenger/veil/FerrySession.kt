package com.construct.messenger.veil

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Why a native-TLS ferry session ended — one log line per session, so a phone's export says how
 * its relay sessions die. Canon: iOS `FerrySessionEnd` (todo 315); the names and the line are the
 * same on both platforms, so one grep reads either export.
 *
 * Found on a tester's phone (todo 319): the stream went silent 19 times in five hours, every time
 * on the same tunnel, and nothing in the export said whether the relay stopped sending or the
 * server behind it did.
 */
enum class FerrySessionEnd(val wire: String) {
    /** The relay closed the stream (EOF). */
    RELAY_CLOSED("relay-closed"),

    /** A read from the relay failed. */
    RELAY_ERROR("relay-error"),

    /** A write to the relay failed: what gRPC sent never left. */
    SEND_FAILED("send-failed"),

    /** Our side closed: the gRPC connection behind the socketpair ended. */
    LOCAL_CLOSED("local-closed"),

    /** VEIL stopped. */
    STOPPED("stopped"),

    /**
     * Android only: the Rust ferry refused the connection, or setting it up threw after the
     * session existed. iOS hands Rust its fds before a session exists, so it has no such end.
     */
    FERRY_FAILED("ferry-failed"),
}

/**
 * What one ferry session carried and when it last heard from the relay. Thread-safe: the two
 * pump threads count, and whichever side ends first names the reason — [end] answers once.
 *
 * [nowMs] counts deep sleep (`SystemClock.elapsedRealtime`): a session that lived through a doze
 * is the case the line is for.
 */
class FerrySessionStats(private val nowMs: () -> Long) {
    private val startedAt = nowMs()
    private val up = AtomicLong()
    private val down = AtomicLong()
    private val lastReceivedAt = AtomicLong(NEVER)
    private val ended = AtomicBoolean(false)

    /** [n] bytes went from gRPC to the relay. */
    fun sent(n: Int) {
        up.addAndGet(n.toLong())
    }

    /** [n] bytes arrived from the relay. */
    fun received(n: Int) {
        down.addAndGet(n.toLong())
        lastReceivedAt.set(nowMs())
    }

    /** The end line for the first end, null for every later one. */
    fun end(reason: FerrySessionEnd): String? {
        if (!ended.compareAndSet(false, true)) return null
        val now = nowMs()
        val last = lastReceivedAt.get()
        val quiet = if (last == NEVER) "never" else "${(now - last) / 1000}s"
        return "VEIL native-TLS: session ended reason=${reason.wire} age=${(now - startedAt) / 1000}s " +
            "up=${up.get()}B down=${down.get()}B quiet=$quiet"
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }
}
