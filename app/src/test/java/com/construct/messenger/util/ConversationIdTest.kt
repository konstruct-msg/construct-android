package com.construct.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationIdTest {

    @Test
    fun `direct sorts ids lexicographically`() {
        assertEquals(
            "direct:aaa:zzz",
            ConversationId.direct("zzz", "aaa"),
        )
        assertEquals(
            ConversationId.direct("aaa", "zzz"),
            ConversationId.direct("zzz", "aaa"),
        )
    }

    @Test
    fun `otherUserId returns the peer`() {
        val me = "11111111-1111-1111-1111-111111111111"
        val them = "22222222-2222-2222-2222-222222222222"
        val id = ConversationId.direct(me, them)
        assertEquals(them, ConversationId.otherUserId(id, me))
        assertEquals(me, ConversationId.otherUserId(id, them))
        assertNull(ConversationId.otherUserId(id, "someone-else"))
        assertNull(ConversationId.otherUserId("group:abc", me))
    }
}
