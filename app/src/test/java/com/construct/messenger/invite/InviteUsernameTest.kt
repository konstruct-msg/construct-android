package com.construct.messenger.invite

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.local.KeystoreManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Which invites carry our username (construct-docs TODO 95, 98): the QR and a `konstruct://` link
 * do, so the person adding us sees who they added; an HTTPS link never does — it passes through
 * other messengers, whose previews and scanners keep it in their logs. Same rule and test as iOS
 * `InviteUsernameTests`.
 */
class InviteUsernameTest {
    private val crypto = mock<CryptoManager>().also {
        whenever(it.signInvite(any())).thenReturn(ByteArray(InviteConfig.SIGNATURE_LENGTH) { 1 })
        whenever(it.verifyingKey()).thenReturn(ByteArray(32) { 2 })
        whenever(it.verifyInvite(any(), any(), any())).thenReturn(true)
    }
    private val keystore = mock<KeystoreManager>().also {
        whenever(it.getOwnAccountAddress()).thenReturn(ByteArray(AccountAddress.LENGTH) { 3 })
    }
    private val generator = InviteGenerator(crypto, keystore)
    private val user = "11111111-1111-4111-8111-111111111111"
    private val device = "0123456789abcdef0123456789abcdef"

    @Test
    fun `the rule — an HTTPS link never, an app link and the QR yes`() {
        assertNull(InviteGenerator.linkUsername("alice", useHttps = true))
        assertEquals("alice", InviteGenerator.linkUsername("alice", useHttps = false))
    }

    /** Mutation: let the HTTPS link through — this reddens. */
    @Test
    fun `the shared HTTPS link signs no username even when one is given`() {
        val minted = generator.mintLink(user, device, username = "alice")
        assertEquals(true, minted.deepLink.startsWith("https://"))
        assertNull(InviteObject.fromRaw(minted.deepLink).un)
    }

    @Test
    fun `the QR carries it, and so does its konstruct link`() {
        val minted = generator.mintQr(user, device, username = "alice")
        assertEquals("alice", InviteObject.fromRaw(minted.payload).un)
        assertEquals(true, minted.deepLink.startsWith(InviteConfig.DEEP_LINK_SCHEME))
        assertEquals("alice", InviteObject.fromRaw(minted.deepLink).un)
    }
}
