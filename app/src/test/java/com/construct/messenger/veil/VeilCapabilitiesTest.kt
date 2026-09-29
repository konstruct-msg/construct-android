package com.construct.messenger.veil

import com.google.protobuf.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import shared.proto.services.v1.VeilServiceOuterClass.IssueVeilCapabilityResponse

/**
 * The capability answer arrives over a path a censor may sit on; only the bundled front, with its
 * bundled pin, is accepted. Canon: the pin half of iOS `VeilRelayTrust`.
 */
class VeilCapabilitiesTest {

    private val seed = VeilSeeds.relays.first()

    private fun answer(block: IssueVeilCapabilityResponse.Builder.() -> Unit = {}) =
        IssueVeilCapabilityResponse.newBuilder()
            .setCapability(ByteString.copyFrom(ByteArray(120) { 7 }))
            .setCapabilityVersion(1)
            .setRelayAddress(seed.address)
            .setSpki(seed.spkiHex.uppercase())
            .setSni(seed.sni)
            .apply(block)
            .build()

    @Test
    fun `the bundled front with its pin is accepted`() {
        assertNull(VeilCapabilities.rejectionOf(seed, answer()))
        // Fields the server leaves out are not a mismatch.
        assertNull(VeilCapabilities.rejectionOf(seed, answer { clearRelayAddress(); clearSpki(); clearSni() }))
    }

    /** Mutation: drop the pin comparison — a redirected front with its own key is stored. */
    @Test
    fun `another pin is refused`() {
        assertNotNull(VeilCapabilities.rejectionOf(seed, answer { setSpki("00".repeat(32)) }))
    }

    @Test
    fun `another front is refused, not learned`() {
        assertNotNull(VeilCapabilities.rejectionOf(seed, answer { setRelayAddress("evil.example:443") }))
    }

    @Test
    fun `a key-bound capability is not stored as a bearer one`() {
        assertNotNull(VeilCapabilities.rejectionOf(seed, answer { setCapabilityVersion(2) }))
    }

    @Test
    fun `an empty capability is refused`() {
        assertNotNull(VeilCapabilities.rejectionOf(seed, answer { setCapability(ByteString.EMPTY) }))
    }

    /** Set bits disable, as `MethodSet::from_bitmask` reads them; veil-front's bit stays clear. */
    @Test
    fun `the method mask leaves only veil-front`() {
        val mask = VeilMethod.DISABLED_EXCEPT_VEIL_FRONT
        assertEquals(0, mask and (1 shl VeilMethod.VEIL_FRONT.id))
        assertEquals(0b0111, mask)
    }
}
