package com.construct.messenger.veil

import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.transport.TransportEvents
import com.construct.messenger.transport.TransportRoute
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * A front reaches the device only as a link signed by the relay-config key (TODO 103). [LINK] was
 * written by construct-veil's own signer, `make-config-link`, with the test seed `01`×32 — so this
 * checks Android's canonical JSON against the bytes the server signs, not against itself. Its
 * capability contains `/`, which the signer does not escape.
 */
class VeilConfigLinkTest {

    @Test
    fun `the signer's link verifies and gives its front and capability`() {
        val config = VeilConfigLink.parseAndVerify(VeilConfigLink.blobOf(LINK)!!, TEST_KEY, NOW)
        assertEquals("front.example.test:443", config.front.address)
        assertEquals("front.example.test", config.front.sni)
        assertEquals("00112233445566778899aabbccddeeff".repeat(2), config.front.spki)
        assertEquals(EXP, config.capabilityNotAfter)
        assertTrue(config.capabilityB64.contains('/'))
    }

    /** Mutation: skip the blob signature — a front named by anyone is pinned; this reddens. */
    @Test
    fun `a changed field breaks the signature`() {
        val forged = blob().put("relay", "evil.example:443")
        assertRefused(VeilConfigLink.Refusal.BAD_SIGNATURE) { VeilConfigLink.parseAndVerify(encode(forged), TEST_KEY, NOW) }
    }

    /** The real key did not sign the test link: a link is not trusted for being well-formed. */
    @Test
    fun `another key's link is refused`() {
        assertRefused(VeilConfigLink.Refusal.BAD_SIGNATURE) { VeilConfigLink.parseAndVerify(VeilConfigLink.blobOf(LINK)!!, nowSeconds = NOW) }
        assertRefused(VeilConfigLink.Refusal.BAD_SIGNATURE) {
            VeilConfigLink.parseAndVerify(encode(blob().apply { remove("signature") }), TEST_KEY, NOW)
        }
    }

    /** Mutation: drop the `exp` check — an expired link still imports; this reddens. */
    @Test
    fun `an expired link is refused`() {
        assertRefused(VeilConfigLink.Refusal.EXPIRED) { VeilConfigLink.parseAndVerify(VeilConfigLink.blobOf(LINK)!!, TEST_KEY, EXP + 1) }
    }

    @Test
    fun `only a veil-config link is one`() {
        val d = VeilConfigLink.blobOf(LINK)!!
        assertEquals(d, VeilConfigLink.blobOf("  konstruct:///veil-config?d=$d "))
        assertEquals(d, VeilConfigLink.blobOf("KONSTRUCT://Veil-Config?x=1&d=$d"))
        assertNull(VeilConfigLink.blobOf("konstruct://add?invite=$d"))
        assertNull(VeilConfigLink.blobOf("konstruct://veil-config?x=1"))
        assertNull(VeilConfigLink.blobOf("https://konstruct.cc/veil-config?d=$d"))
        assertNull(VeilConfigLink.blobOf(d))
    }

    @Test
    fun `a pasted blob is taken with or without its link`() {
        val d = VeilConfigLink.blobOf(LINK)!!
        assertEquals(d, VeilConfigLink.blobOfPasted(LINK))
        assertEquals(d, VeilConfigLink.blobOfPasted("\n $d \n"))
        // Base64url that is not a JSON object, or not base64url at all, is not a blob.
        assertNull(VeilConfigLink.blobOfPasted(Base64.getUrlEncoder().encodeToString("not json".toByteArray())))
        assertNull(VeilConfigLink.blobOfPasted("hello"))
        assertNull(VeilConfigLink.blobOfPasted("konstruct://add?invite=$d"))
    }

    @Test
    fun `canonical JSON is the signer's`() {
        val obj = JSONObject("""{"b":"a/b","a":4944562082,"c":[true,null,"x\"y\n"]}""")
        assertEquals("""{"a":4944562082,"b":"a/b","c":[true,null,"x\"y\n"]}""", VeilConfigLink.canonical(obj))
        // A fraction is not something the signer writes, and its form cannot be reproduced exactly.
        assertNull(VeilConfigLink.canonical(JSONObject("""{"a":1.5}""")))
    }

    // — the importer —

    private val saved = mutableMapOf<String, String>()
    private val keystore = mock<KeystoreManager>().also { k ->
        whenever(k.veilLearnedFronts()).doAnswer { saved["fronts"] }
        whenever(k.saveVeilLearnedFronts(any())).doAnswer { saved["fronts"] = it.getArgument(0); Unit }
    }
    private val events = mock<TransportEvents>()
    private val fronts = VeilFrontStore(keystore)
    private val importer = VeilConfigImporter(fronts, keystore, events)

    /** Mutation: store the capability under another address — the front could never be opened. */
    @Test
    fun `an imported link pins its front, keeps its capability and tells the route`() {
        val outcome = importer.redeem(LINK, TEST_KEY, NOW)
        assertEquals(VeilConfigImporter.Outcome.Imported, outcome)
        assertEquals(VeilRelay("front.example.test:443", "front.example.test", "00112233445566778899aabbccddeeff".repeat(2)), fronts.preferred())
        verify(keystore).saveVeilCapability(eq("front.example.test:443"), eq(VeilConfigLink.parseAndVerify(VeilConfigLink.blobOf(LINK)!!, TEST_KEY, NOW).capabilityB64), eq(EXP))
        verify(events).post(TransportRoute.Event.VeilConfigChanged)
    }

    /** The blob alone, as pasted, imports the same — and is checked the same. */
    @Test
    fun `a pasted blob imports like its link`() {
        assertEquals(VeilConfigImporter.Outcome.Imported, importer.redeem(VeilConfigLink.blobOf(LINK)!!, TEST_KEY, NOW))
        assertEquals(VeilRelay("front.example.test:443", "front.example.test", "00112233445566778899aabbccddeeff".repeat(2)), fronts.preferred())
        assertEquals(
            VeilConfigImporter.Outcome.Refused(VeilConfigLink.Refusal.BAD_SIGNATURE),
            importer.redeem(encode(blob().put("relay", "evil.example:443")), TEST_KEY, NOW),
        )
    }

    @Test
    fun `a refused link leaves nothing behind`() {
        val outcome = importer.redeem(LINK, nowSeconds = NOW) // the real key did not sign it
        assertEquals(VeilConfigImporter.Outcome.Refused(VeilConfigLink.Refusal.BAD_SIGNATURE), outcome)
        assertNull(fronts.preferred())
        verify(keystore, never()).saveVeilCapability(any(), any(), any())
        verify(events, never()).post(anyOrNull())
    }

    private fun blob() = JSONObject(String(Base64.getUrlDecoder().decode(VeilConfigLink.blobOf(LINK)!!), Charsets.UTF_8))

    private fun encode(obj: JSONObject) = Base64.getUrlEncoder().withoutPadding().encodeToString(obj.toString().toByteArray())

    private fun assertRefused(reason: VeilConfigLink.Refusal, block: () -> Unit) {
        val e = assertThrows(VeilConfigLink.Refused::class.java) { block() }
        assertEquals(reason, e.reason)
    }

    companion object {
        /** Public key of the seed `01`×32 that signed [LINK]. */
        const val TEST_KEY = "8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c"
        const val EXP = 4944562082L
        const val NOW = 1_790_000_000L

        // make-config-link --signing-key 01…01 --relay front.example.test:443
        //   --spki 00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff --days 36500
        const val LINK = "konstruct://veil-config?d=eyJjYXBhYmlsaXR5IjoiZzNvdmRpV1BHdXdoNS9xcXNjNmF5aGZBdEVDUTFCUjNlenpoUm1meE1tSVZWMnNJaHpDUWNBeExGN3NkVk41S291bS9hZ0FBQUFDaUI3Z21BUUFBQUFFQTNDc0xydE1TSWNLSzIrZlVTZElXK3p3RzZsajBQZDZZK2huSXROT0RqSSs3WTBmZHYwZUNiQzBncE5mOWNhc01wS1k1N1dtNjc5RnNidnlUaWl4M0JBPT0iLCJleHAiOjQ5NDQ1NjIwODIsInJlbGF5IjoiZnJvbnQuZXhhbXBsZS50ZXN0OjQ0MyIsInNpZ25hdHVyZSI6ImVkMjU1MTk6ell0ajRiMnpCMEYxNEtXTE5mRV9scTlxenNQTjQ4dlFBU1Q3V01od01lektJTXJfOFNFV2lvT0xGYVkwTUFiLUNsRFRCVWdlWjdfWHFnRVJ4OG8wQ0EiLCJzbmkiOiJmcm9udC5leGFtcGxlLnRlc3QiLCJzcGtpIjoiMDAxMTIyMzM0NDU1NjY3Nzg4OTlhYWJiY2NkZGVlZmYwMDExMjIzMzQ0NTU2Njc3ODg5OWFhYmJjY2RkZWVmZiJ9"
    }
}
