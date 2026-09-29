package com.construct.messenger.veil

import com.construct.messenger.veil.VeilCapabilityBlob.hex
import com.google.protobuf.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import shared.proto.services.v1.VeilServiceOuterClass.IssueVeilCapabilityResponse

/**
 * The capability answer arrives over a path a censor may sit on; only the bundled front, with its
 * bundled pin, is accepted, and only a blob the issuer signed. Canon: the pin half of iOS
 * `VeilRelayTrust`, and iOS `VeilCapabilityV2Bootstrapper` for the key-bound checks.
 */
class VeilCapabilitiesTest {

    private val seed = VeilSeeds.relays.first()

    private fun answer(block: IssueVeilCapabilityResponse.Builder.() -> Unit = {}) =
        IssueVeilCapabilityResponse.newBuilder()
            .setCapability(ByteString.copyFrom(hex(VeilCapabilityBlobTest.V1)))
            .setCapabilityVersion(1)
            .setRelayAddress(seed.address)
            .setSpki(seed.spkiHex.uppercase())
            .setSni(seed.sni)
            .apply(block)
            .build()

    private fun rejection(response: IssueVeilCapabilityResponse) =
        VeilCapabilities.rejectionOf(seed, response, VeilCapabilityBlobTest.ISSUER, VeilCapabilityBlobTest.NOW)

    private val ourPk = ByteArray(32) { 0xD4.toByte() }

    private fun keyBound(block: IssueVeilCapabilityResponse.Builder.() -> Unit = {}) = answer {
        setCapability(ByteString.copyFrom(hex(VeilCapabilityBlobTest.V2)))
        setCapabilityVersion(2)
        block()
    }

    private fun keyBoundRejection(response: IssueVeilCapabilityResponse, pk: ByteArray = ourPk) =
        VeilCapabilities.keyBoundRejectionOf(seed, response, pk, VeilCapabilityBlobTest.ISSUER, VeilCapabilityBlobTest.NOW)

    @Test
    fun `the bundled front with its pin is accepted`() {
        assertNull(rejection(answer()))
        // Fields the server leaves out are not a mismatch.
        assertNull(rejection(answer { clearRelayAddress(); clearSpki(); clearSni() }))
    }

    /** Mutation: drop the pin comparison — a redirected front with its own key is stored. */
    @Test
    fun `another pin is refused`() {
        assertNotNull(rejection(answer { setSpki("00".repeat(32)) }))
    }

    @Test
    fun `another front is refused, not learned`() {
        assertNotNull(rejection(answer { setRelayAddress("evil.example:443") }))
    }

    @Test
    fun `a key-bound capability is not stored as a bearer one`() {
        assertNotNull(rejection(answer { setCapabilityVersion(2) }))
    }

    @Test
    fun `an empty capability is refused`() {
        assertNotNull(rejection(answer { setCapability(ByteString.EMPTY) }))
    }

    /** Mutation: drop the blob check from `rejectionOf` — a forged bearer blob is stored. */
    @Test
    fun `a bearer blob the issuer did not sign is refused`() {
        val forged = hex(VeilCapabilityBlobTest.V1).also { it[20] = 0 }
        assertNotNull(rejection(answer { setCapability(ByteString.copyFrom(forged)) }))
    }

    @Test
    fun `a key-bound capability bound to our key is accepted`() {
        assertNull(keyBoundRejection(keyBound()))
    }

    /** Mutation: drop the veil_pk comparison — a capability for another key is stored. */
    @Test
    fun `a key-bound capability bound to another key is refused`() {
        assertNotNull(keyBoundRejection(keyBound(), pk = ByteArray(32) { 1 }))
    }

    @Test
    fun `a bearer answer is not stored as key-bound`() {
        assertNotNull(keyBoundRejection(answer()))
        assertNotNull(keyBoundRejection(keyBound { setCapabilityVersion(1) }))
    }

    @Test
    fun `a key-bound answer for another front is refused`() {
        assertNotNull(keyBoundRejection(keyBound { setSpki("00".repeat(32)) }))
        assertNotNull(keyBoundRejection(keyBound { setRelayAddress("evil.example:443") }))
    }

    /** Set bits disable, as `MethodSet::from_bitmask` reads them; veil-front's bit stays clear. */
    @Test
    fun `the method mask leaves only veil-front`() {
        val mask = VeilMethod.DISABLED_EXCEPT_VEIL_FRONT
        assertEquals(0, mask and (1 shl VeilMethod.VEIL_FRONT.id))
        assertEquals(0b0111, mask)
    }
}
