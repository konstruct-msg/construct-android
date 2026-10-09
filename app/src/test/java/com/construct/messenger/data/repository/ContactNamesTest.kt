package com.construct.messenger.data.repository

import com.construct.messenger.data.local.ContactRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactNamesTest {

    /**
     * A contact who shares their profile keeps the name they sent: an invite's username or the
     * server's profile does not replace it. Mutation: drop the isSharingWithMe check — this reddens.
     */
    @Test
    fun `a shared name is not replaced by the server or an invite`() {
        val sharing = ContactRecord(id = "u", displayName = "Alice", isContact = true, isSharingWithMe = true)
        assertNull(ContactNames.offered(sharing, "alice_server"))
    }

    @Test
    fun `without a shared profile the offered name is taken, trimmed, and a blank one is not`() {
        val plain = ContactRecord(id = "u", displayName = "mystic parrot", isContact = true)
        assertEquals("alice", ContactNames.offered(plain, " alice "))
        assertNull(ContactNames.offered(plain, "  "))
        assertEquals("alice", ContactNames.offered(null, "alice"))
    }
}
