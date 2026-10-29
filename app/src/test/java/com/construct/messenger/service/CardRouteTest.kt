package com.construct.messenger.service

import org.junit.Assert.assertEquals
import org.junit.Test

class CardRouteTest {
    private val myId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val peer = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"

    /** Mutation: route by the account id alone — a card from our own device would be dropped, or a contact's become our key. */
    @Test
    fun `a card is our own key only off a session with our own device`() {
        assertEquals(CardRoute.OWN_DEVICE, CardRoute.of(myId, myId.uppercase(), myId))
        assertEquals(CardRoute.IGNORED, CardRoute.of(myId, null, myId))
        assertEquals(CardRoute.CONTACT, CardRoute.of(myId, peer, peer))
        assertEquals(CardRoute.CONTACT, CardRoute.of(null, peer, peer))
    }
}
