package com.construct.messenger.veil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FerrySessionStatsTest {

    private var now = 1_000_000L
    private fun stats() = FerrySessionStats { now }

    @Test
    fun `the line says age, bytes both ways and how long the relay was quiet`() {
        val s = stats()
        s.sent(300)
        s.sent(40)
        now += 5_000
        s.received(1_200)
        now += 61_500

        assertEquals(
            "VEIL native-TLS: session ended reason=local-closed age=66s up=340B down=1200B quiet=61s",
            s.end(FerrySessionEnd.LOCAL_CLOSED),
        )
    }

    @Test
    fun `a relay that never sent a byte is quiet since never`() {
        val s = stats()
        s.sent(500)
        now += 20_000

        assertEquals(
            "VEIL native-TLS: session ended reason=relay-closed age=20s up=500B down=0B quiet=never",
            s.end(FerrySessionEnd.RELAY_CLOSED),
        )
    }

    @Test
    fun `quiet counts from the last byte, not the first`() {
        val s = stats()
        s.received(10)
        now += 30_000
        s.received(10)
        now += 4_000

        assertEquals(
            "VEIL native-TLS: session ended reason=stopped age=34s up=0B down=20B quiet=4s",
            s.end(FerrySessionEnd.STOPPED),
        )
    }

    @Test
    fun `the first end names the reason and later ends say nothing`() {
        val s = stats()
        val first = s.end(FerrySessionEnd.RELAY_ERROR)

        assertEquals(
            "VEIL native-TLS: session ended reason=relay-error age=0s up=0B down=0B quiet=never",
            first,
        )
        assertNull(s.end(FerrySessionEnd.SEND_FAILED))
        assertNull(s.end(FerrySessionEnd.LOCAL_CLOSED))
    }

    @Test
    fun `reason names are the ones iOS writes`() {
        assertEquals(
            listOf("relay-closed", "relay-error", "send-failed", "local-closed", "stopped", "ferry-failed"),
            FerrySessionEnd.entries.map { it.wire },
        )
    }
}
