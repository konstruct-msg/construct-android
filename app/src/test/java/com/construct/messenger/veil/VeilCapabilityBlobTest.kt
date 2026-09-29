package com.construct.messenger.veil

import com.construct.messenger.veil.VeilCapabilityBlob.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The blobs below were produced by construct-veil-protocol itself (`Capability::sign` /
 * `CapabilityV2::sign`, issuer seed `11`×32, scope "ru-front"), so they check this parser against
 * the encoder the relay decodes with — not against a layout written from the same reading.
 */
class VeilCapabilityBlobTest {

    @Test
    fun `a bearer capability from the protocol crate parses and verifies`() {
        val cap = VeilCapabilityBlob.parseBearer(hex(V1), ISSUER, NOW)
        assertEquals(1_700_000_000L, cap.notBefore)
        assertEquals(4_000_000_000L, cap.notAfter)
        assertEquals("ru-front", cap.scope)
    }

    @Test
    fun `a key-bound capability from the protocol crate parses and verifies`() {
        val cap = VeilCapabilityBlob.parseKeyBound(hex(V2), ISSUER, NOW)
        assertArrayEquals(ByteArray(32) { 0xD4.toByte() }, cap.veilPk)
        assertEquals(0, cap.role)
        assertEquals(1_700_000_000L, cap.notBefore)
        assertEquals(4_000_000_000L, cap.notAfter)
        assertEquals("ru-front", cap.scope)
    }

    /** Mutation: skip the signature check — every case here passes. */
    @Test
    fun `a changed byte anywhere in the signed part fails the signature`() {
        for (at in listOf(0, 20, 50, 60, 70)) {
            val blob = hex(V2).also { it[at] = (it[at].toInt() xor 1).toByte() }
            assertThrows(VeilCapabilityBlob.Invalid::class.java) { VeilCapabilityBlob.parseKeyBound(blob, ISSUER, NOW) }
        }
    }

    @Test
    fun `another issuer's key does not verify`() {
        val other = hex("8a0ee71cd95f86a9f6877211accefaff6bb97f3051b3b2141f1c71690b9a2dcf")
        assertThrows(VeilCapabilityBlob.Invalid::class.java) { VeilCapabilityBlob.parseBearer(hex(V1), other, NOW) }
    }

    /** The domains differ, so one layout never verifies as the other. */
    @Test
    fun `a bearer blob is not a key-bound one, nor the reverse`() {
        assertThrows(VeilCapabilityBlob.Invalid::class.java) { VeilCapabilityBlob.parseKeyBound(hex(V1), ISSUER, NOW) }
        assertThrows(VeilCapabilityBlob.Invalid::class.java) { VeilCapabilityBlob.parseBearer(hex(V2), ISSUER, NOW) }
    }

    @Test
    fun `an expired capability is refused`() {
        assertThrows(VeilCapabilityBlob.Invalid::class.java) { VeilCapabilityBlob.parseKeyBound(hex(V2), ISSUER, 4_000_000_001L) }
    }

    @Test
    fun `a truncated blob is refused, not read past`() {
        val blob = hex(V2)
        for (size in listOf(0, 10, 67, blob.size - 1)) {
            assertThrows(VeilCapabilityBlob.Invalid::class.java) {
                VeilCapabilityBlob.parseKeyBound(blob.copyOf(size), ISSUER, NOW)
            }
        }
    }

    @Test
    fun `the bundled issuer key is iOS's`() {
        assertEquals("8a0ee71cd95f86a9f6877211accefaff6bb97f3051b3b2141f1c71690b9a2dcf", VeilCapabilityBlob.ISSUER_KEY_HEX)
    }

    companion object {
        const val NOW = 1_800_000_000L
        val ISSUER = hex("d04ab232742bb4ab3a1368bd4615e4e6d0224ab71a016baf8520a332c9778737")
        const val V1 =
            "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2" +
                "00f153650000000000286bee00000000010872752d66726f6e74947f33345b627d88cb4de295e4434a9b952a3074cbbf0c" +
                "915d85369bfb82f775170893c727a1108706363833a73bb7dac6367b7424c48b0e4965aaf5126fd20f"
        const val V2 =
            "c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4" +
                "0000f153650000000000286bee00000000010872752d66726f6e74460d5342f4be673ba9684e7784b960a6603b71d6641e" +
                "dd9803c3701eb614385f84b779b56fc765a23d49dd05725f107296827dbcb3b1c44966b1635845f3560e"
    }
}
