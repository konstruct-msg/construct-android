package com.construct.messenger.invite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared link is the iOS one: `https://konstruct.cc/add?invite=…`, and both forms are ours. */
class InviteLinkTest {

    @Test fun `the shared link is https to the invite host`() =
        assertEquals("https://konstruct.cc/add?invite=Q0l2", InviteConfig.shareLink("Q0l2"))

    @Test fun `both link forms are recognised, nothing else is`() {
        assertTrue(InviteConfig.isInviteLink("konstruct://add?invite=Q0l2"))
        assertTrue(InviteConfig.isInviteLink("https://konstruct.cc/add?invite=Q0l2"))
        assertTrue(InviteConfig.isInviteLink("https://web.konstruct.cc/add/Q0l2"))
        assertFalse(InviteConfig.isInviteLink("https://evil.example/add?invite=Q0l2"))
        assertFalse(InviteConfig.isInviteLink("http://konstruct.cc/add?invite=Q0l2"))
        assertFalse(InviteConfig.isInviteLink("https://konstruct.cc/faq"))
    }

    @Test fun `the payload comes out of every form`() {
        assertEquals("Q0l2", InviteObject.extractInviteParam("https://konstruct.cc/add?invite=Q0l2"))
        assertEquals("Q0l2", InviteObject.extractInviteParam("konstruct://add?invite=Q0l2&x=1"))
        assertEquals("Q0l2", InviteObject.extractInviteParam("https://konstruct.cc/add/Q0l2"))
    }
}
