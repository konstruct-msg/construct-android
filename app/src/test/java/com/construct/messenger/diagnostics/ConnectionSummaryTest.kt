package com.construct.messenger.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionSummaryTest {
    private var clock = 0L
    private var traffic = 0L to 0L
    private val written = mutableListOf<String>()
    private val summary = ConnectionSummary({ clock }, { traffic }, { written += it })

    private fun minutes(m: Long) {
        clock += m * 60_000
    }

    /**
     * An hour of a stream on VEIL that went silent twice and once the phone slept through: the
     * line says which route, why each connection ended, and how much of the hour it was up.
     * Mutation: `ended` counting into the opened route's other slot — the `v`/`d` split reddens.
     */
    @Test
    fun anHourReadsRoutesEndsAndUptime() {
        summary.opened(viaVeil = true)
        repeat(3) { summary.heartbeat() }
        summary.frame()
        minutes(10)
        summary.ended(ConnectionSummary.End.SILENT, viaVeil = true)
        minutes(5)
        summary.failedToOpen(viaVeil = true)
        summary.opened(viaVeil = true)
        minutes(15)
        summary.ended(ConnectionSummary.End.SILENT_ASLEEP, viaVeil = true)
        summary.opened(viaVeil = false)
        traffic = 300L * 1024 to 100L * 1024
        assertNull("not yet an hour", summary.flushIfDue())
        minutes(30)

        val line = summary.flushIfDue()

        assertEquals(
            "CONN 60min opened=2v+1d failed_open=1v+0d " +
                "ended[silent=1v+0d asleep=1v+0d closed=0v+0d network=0v+0d route=0v+0d other=0v+0d] " +
                "heartbeats=3 frames=1 up=91% rx=300KB tx=100KB",
            line,
        )
        assertEquals(listOf(line), written)
    }

    /** A new window starts from nothing, and an hour the phone slept through says how long it was. */
    @Test
    fun theNextWindowStartsCleanAndShowsItsRealLength() {
        summary.opened(viaVeil = false)
        summary.heartbeat()
        minutes(60)
        traffic = 10L * 1024 to 4L * 1024
        summary.flushIfDue()

        minutes(180)
        traffic = 15L * 1024 to 6L * 1024
        val line = summary.flushIfDue()!!

        assertTrue(line, line.startsWith("CONN 180min opened=0v+0d"))
        assertTrue(line, line.endsWith("heartbeats=0 frames=0 up=100% rx=5KB tx=2KB"))
    }
}
