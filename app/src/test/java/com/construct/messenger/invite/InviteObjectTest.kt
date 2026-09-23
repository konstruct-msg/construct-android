package com.construct.messenger.invite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class InviteObjectTest {

    private val sig = b64encode(ByteArray(64) { it.toByte() })
    private val now = System.currentTimeMillis() / 1000
    private val jti = UUID.randomUUID().toString()
    private val uuid = UUID.randomUUID().toString()
    private val deviceId = "0123456789abcdef0123456789abcdef"

    @Test
    fun `v5 canonical string includes ttl`() {
        val invite = sample(v = 5, ttl = 300)
        val expected = "5|${jti.lowercase()}|${uuid.lowercase()}|$deviceId|konstruct.cc|$now||300"
        assertEquals(expected, invite.canonicalString())
    }

    @Test
    fun `v4 canonical string has no ephKey and no ttl`() {
        val invite = sample(v = 4, ttl = null)
        val expected = "4|${jti.lowercase()}|${uuid.lowercase()}|$deviceId|konstruct.cc|$now|"
        assertEquals(expected, invite.canonicalString())
    }

    @Test
    fun `v5 compact binary roundtrips`() {
        val original = sample(v = 5, ttl = 300, un = "fox")
        val decoded = InviteObject.decodeBinary(original.encodeBinary())
        assertEquals(original.v, decoded.v)
        assertEquals(original.jti.lowercase(), decoded.jti)
        assertEquals(original.uuid.lowercase(), decoded.uuid)
        assertEquals(original.deviceId, decoded.deviceId)
        assertEquals(original.server, decoded.server)
        assertEquals(original.ts, decoded.ts)
        assertEquals(original.un, decoded.un)
        assertEquals(original.ttl, decoded.ttl)
        assertEquals(original.sig, decoded.sig)
        assertTrue(decoded.ephKey.isEmpty())
    }

    @Test
    fun `deep link extract then decode`() {
        val original = sample(v = 5, ttl = InviteConfig.TTL_SECONDS.toInt())
        val link = "konstruct://add?invite=${original.toBase64Url()}"
        val decoded = InviteObject.fromRaw(link)
        assertEquals(original.jti.lowercase(), decoded.jti)
        assertEquals(original.ttl, decoded.ttl)
    }

    @Test
    fun `v5 without ttl is expired-safe and validate fails`() {
        val invite = sample(v = 5, ttl = null)
        try {
            invite.validate(requireSignature = true)
            throw AssertionError("expected MissingTtl")
        } catch (e: InviteException.MissingTtl) {
            // expected
        }
    }

    @Test
    fun `effective ttl clamps to server max`() {
        assertEquals(InviteConfig.TTL_SECONDS, InviteConfig.effectiveTtl(99_999))
        assertEquals(300L, InviteConfig.effectiveTtl(300))
        assertFalse(sample(v = 5, ttl = 300).isExpired(now))
    }

    private fun sample(v: Int, ttl: Int?, un: String? = null) = InviteObject(
        v = v,
        jti = jti,
        uuid = uuid,
        deviceId = deviceId,
        server = "konstruct.cc",
        ephKey = "",
        ts = now,
        sig = sig,
        un = un,
        ttl = ttl,
    )
}
