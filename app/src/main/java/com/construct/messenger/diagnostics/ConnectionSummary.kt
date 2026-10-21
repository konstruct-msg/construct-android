package com.construct.messenger.diagnostics

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One `CONN` line an hour in the log: how the message stream lived — how often it opened and on
 * which route, why each connection ended, how many heartbeats and frames it heard, how much of the
 * hour it was up, and the bytes the app moved. The phone's battery screen says how much the app
 * cost, not why; this says why (TODO 204).
 *
 * The reason it exists: a OnePlus tester's log of 2026-10-18..20 held 137 reconnects in 45 hours,
 * 88 of them a VEIL stream that went silent for a minute, and each reconnect is a new tunnel with
 * its handshake and its chaff. That took a script over the whole log to see.
 *
 * Counted, not timed: nothing here wakes the phone. [flushIfDue] is called from a loop that runs
 * anyway, so an hour the phone slept through ends at the next wake, and the line says how long its
 * window really was.
 */
@Singleton
class ConnectionSummary internal constructor(
    private val now: () -> Long,
    private val trafficBytes: () -> Pair<Long, Long>?,
    private val write: (String) -> Unit,
) {
    @Inject constructor() : this(SystemClock::elapsedRealtime, ::appTraffic, { Log.i(TAG, it) })

    /** Why a connection that had opened ended. */
    enum class End(val label: String) {
        /** The watchdog: no frame for a minute, heartbeats included. */
        SILENT("silent"),

        /** The same, noticed well past the minute: the phone slept, and nothing woke it sooner. */
        SILENT_ASLEEP("asleep"),

        /** The server ended the call. */
        CLOSED("closed"),

        /** A transport error: reset, end of stream, unavailable. */
        NETWORK("network"),

        /** Our own route switch or a new client closed it. */
        ROUTE_SWITCH("route"),
        OTHER("other"),
    }

    private var windowStart = now()
    private var bytesAtStart = trafficBytes()
    private var opened = IntArray(2)
    private var failedOpens = IntArray(2)
    private val ended = Array(2) { IntArray(End.entries.size) }
    private var heartbeats = 0
    private var frames = 0
    private var upMs = 0L
    private var upSince: Long? = null

    @Synchronized
    fun opened(viaVeil: Boolean) {
        opened[route(viaVeil)]++
        upSince = now()
    }

    /** A connection that never heard a frame. */
    @Synchronized
    fun failedToOpen(viaVeil: Boolean) {
        failedOpens[route(viaVeil)]++
    }

    @Synchronized
    fun ended(end: End, viaVeil: Boolean) {
        ended[route(viaVeil)][end.ordinal]++
        closeUp()
    }

    @Synchronized
    fun heartbeat() {
        heartbeats++
    }

    @Synchronized
    fun frame() {
        frames++
    }

    /** Writes the line and starts a new window once an hour has passed; returns it, or null. */
    @Synchronized
    fun flushIfDue(): String? {
        val at = now()
        if (at - windowStart < WINDOW_MS) return null
        val running = upSince
        if (running != null) {
            upMs += at - running
            upSince = at
        }
        val bytes = trafficBytes()
        val line = line(at - windowStart, bytes)
        write(line)
        windowStart = at
        bytesAtStart = bytes
        opened = IntArray(2)
        failedOpens = IntArray(2)
        ended.forEach { it.fill(0) }
        heartbeats = 0
        frames = 0
        upMs = 0
        return line
    }

    private fun line(windowMs: Long, bytes: Pair<Long, Long>?): String {
        val start = bytesAtStart
        val traffic = if (bytes != null && start != null) {
            " rx=${kb(bytes.first - start.first)} tx=${kb(bytes.second - start.second)}"
        } else {
            ""
        }
        val ends = End.entries.joinToString(" ") { "${it.label}=${pair { r -> ended[r][it.ordinal] }}" }
        return "CONN ${windowMs / 60_000}min opened=${pair { opened[it] }} failed_open=${pair { failedOpens[it] }} " +
            "ended[$ends] heartbeats=$heartbeats frames=$frames up=${upMs * 100 / windowMs.coerceAtLeast(1)}%$traffic"
    }

    private fun closeUp() {
        val since = upSince ?: return
        upMs += now() - since
        upSince = null
    }

    companion object {
        private const val TAG = "Runtime"
        const val WINDOW_MS = 60 * 60 * 1000L
        private const val VEIL = 0
        private const val DIRECT = 1

        private fun route(viaVeil: Boolean) = if (viaVeil) VEIL else DIRECT

        /** `veil+direct`, so a line reads which route the counts came from. */
        private fun pair(count: (Int) -> Int) = "${count(VEIL)}v+${count(DIRECT)}d"

        private fun kb(bytes: Long) = "${bytes / 1024}KB"

        private fun appTraffic(): Pair<Long, Long>? {
            val uid = Process.myUid()
            val rx = TrafficStats.getUidRxBytes(uid)
            val tx = TrafficStats.getUidTxBytes(uid)
            return if (rx < 0 || tx < 0) null else rx to tx
        }
    }
}
