package com.construct.messenger.veil

import com.google.crypto.tink.subtle.Ed25519Sign
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A front the server offers is learned only with coordinates signed by the relay-config key, and
 * a front already held is never re-pointed by a signature. The signed bytes are the canonical JSON
 * already checked against construct-veil's own signer ([VeilConfigLinkTest]); the capability is
 * the one that signer issued.
 */
class VeilEntryPointsTest {
    private val signer = Ed25519Sign(ByteArray(32) { 1 })
    private val key = VeilConfigLinkTest.TEST_KEY
    private val now = VeilConfigLinkTest.NOW
    private val capability = Base64.getDecoder().decode(
        VeilConfigLink.parseAndVerify(VeilConfigLink.blobOf(VeilConfigLinkTest.LINK)!!, key, now).capabilityB64,
    )
    private val pin = "ab".repeat(32)

    private fun sign(relay: String, sni: String, spki: String, exp: Long): String {
        val tuple = VeilConfigLink.canonical(JSONObject().put("exp", exp).put("relay", relay).put("sni", sni).put("spki", spki))!!
        return "ed25519:" + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign(tuple.toByteArray()))
    }

    private fun offer(relay: String = "front.example:443", sni: String = "cdn/front.example", spki: String = pin, exp: Long = now + 3600) =
        OfferedFront(relay, sni, spki, exp, sign(relay, sni, spki, exp), capability, 1)

    @Test
    fun `the signed tuple is the server's canonical JSON`() {
        val tuple = JSONObject().put("spki", "deadbeef").put("sni", "cdn/front.example").put("relay", "front.example:443").put("exp", 1_770_000_000L)
        assertEquals(
            """{"exp":1770000000,"relay":"front.example:443","sni":"cdn/front.example","spki":"deadbeef"}""",
            VeilConfigLink.canonical(tuple),
        )
    }

    @Test
    fun `signed coordinates teach the front and its capability`() {
        val (front, notAfter) = VeilEntryPoints.accept(offer(), known = null, keyHex = key, nowSeconds = now).getOrThrow()
        assertEquals(VeilRelay("front.example:443", "cdn/front.example", pin), front.relay)
        assertEquals(VeilConfigLinkTest.EXP, notAfter)
    }

    /** Mutation: skip the tuple signature — the server alone names a front; this reddens. */
    @Test
    fun `a pin the signature does not cover is refused`() {
        val forged = offer().copy(spki = "cd".repeat(32))
        assertTrue(VeilEntryPoints.accept(forged, null, key, now).isFailure)
        assertTrue(VeilEntryPoints.accept(offer().copy(signature = ""), null, key, now).isFailure)
        // Signed, but by another key: the real one.
        assertTrue(VeilEntryPoints.accept(offer(), null, nowSeconds = now).isFailure)
    }

    @Test
    fun `expired coordinates are refused`() {
        assertTrue(VeilEntryPoints.accept(offer(exp = now - 1), null, key, now).isFailure)
    }

    /** Mutation: let a signature win over a held pin — the server could move a known front. */
    @Test
    fun `a held front is not re-pointed by a signature`() {
        val held = VeilFrontRules.normalize("front.example:443", "cdn/front.example", "cd".repeat(32), 1)!!
        assertTrue(VeilEntryPoints.accept(offer(), held, key, now).isFailure)
        val same = VeilFrontRules.normalize("front.example:443", "cdn/front.example", pin, 1)!!
        assertEquals(same, VeilEntryPoints.accept(offer().copy(signature = ""), same, key, now).getOrThrow().first)
    }

    @Test
    fun `only a valid bearer capability comes with it`() {
        assertTrue(VeilEntryPoints.accept(offer().copy(capabilityVersion = 2), null, key, now).isFailure)
        assertTrue(VeilEntryPoints.accept(offer().copy(capability = capability.copyOf().also { it[0] = (it[0] + 1).toByte() }), null, key, now).isFailure)
    }
}
