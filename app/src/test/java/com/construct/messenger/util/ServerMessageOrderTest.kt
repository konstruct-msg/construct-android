package com.construct.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same strings iOS `ServerMessageOrder` writes — one store, two clients (TODO 136). */
class ServerMessageOrderTest {
    @Test
    fun aKeyIsTwoFixedWidthNumbers() {
        assertEquals("00000001760000000000-00000000000000000042", ServerMessageOrder.key(1_760_000_000_000, 42))
        assertNull("no server time, no key", ServerMessageOrder.key(0, 5))
    }

    @Test
    fun aCursorIsReadAsMillisecondsAndSequence() {
        assertEquals("00000001760000000000-00000000000000000007", ServerMessageOrder.key("1760000000000-7"))
        assertEquals("00000001760000000000-00000000000000000000", ServerMessageOrder.key("1760000000000"))
        assertNull(ServerMessageOrder.key("abc-1"))
    }

    /** Metadata gives the time; the cursor's sequence, when there is one, breaks the tie. */
    @Test
    fun metadataAndCursorCombineAsOnIos() {
        assertEquals(ServerMessageOrder.key(1_000, 9), ServerMessageOrder.key(1_000, 3, "2000-9"))
        assertEquals(ServerMessageOrder.key(2_000, 9), ServerMessageOrder.key(0, 3, "2000-9"))
        assertEquals(ServerMessageOrder.key(1_000, 3), ServerMessageOrder.key(1_000, 3, null))
        assertNull(ServerMessageOrder.key(0, 3, null))
    }

    @Test
    fun pendingSitsAfterEveryRealKeyAndLocalAtItsTime() {
        val pending = ServerMessageOrder.pending("AbC")
        assertEquals("99999999999999999999-99999999999999999999-abc", pending)
        assertTrue(pending > ServerMessageOrder.key(Long.MAX_VALUE / 2, Long.MAX_VALUE / 2)!!)
        assertEquals("00000000000000001200-00000000000000000000-xyz", ServerMessageOrder.local(1_200, "XYZ"))
        assertEquals("clamped to 1 ms", "00000000000000000001-00000000000000000000-a", ServerMessageOrder.local(0, "a"))
    }

    /** The string sort is the numeric one — what the store's index sorts by. */
    @Test
    fun stringOrderIsNumericOrder() {
        val keys = listOf(ServerMessageOrder.key(10, 2)!!, ServerMessageOrder.key(9, 99)!!, ServerMessageOrder.key(10, 10)!!)
        assertEquals(listOf(keys[1], keys[0], keys[2]), keys.sorted())
    }
}
