package com.construct.messenger.util

import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SenderSyncRoutingTest {

    @Test
    fun `SSR1 round trips raw UUID and payload`() {
        val partner = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val payload = "KNST payload".toByteArray()

        val decoded = SenderSyncRouting.decode(SenderSyncRouting.encode(partner, payload))

        assertEquals(UUID.fromString(partner).toString(), decoded?.partnerUserId)
        assertArrayEquals(payload, decoded?.payload)
    }

    @Test
    fun `invalid or truncated prefix is rejected`() {
        assertNull(SenderSyncRouting.decode(ByteArray(SenderSyncRouting.PREFIX_SIZE)))
        assertNull(SenderSyncRouting.decode("SSR0".toByteArray() + ByteArray(16)))
        assertNull(SenderSyncRouting.decode("SSR1".toByteArray() + ByteArray(16)))
    }
}
