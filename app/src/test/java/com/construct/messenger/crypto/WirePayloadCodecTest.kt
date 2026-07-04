package com.construct.messenger.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WirePayloadCodecTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Vector produced by an independent little-endian packer (mirrors wire_payload.rs
     * / iOS WirePayloadCoder): msgNum=7, kyberOtpkId=5, suite=1, kem=8×0xAB, content=5B. */
    @Test
    fun `decode matches cross-implementation vector`() {
        val payload = hex(
            "07000000" +                                     // message_number = 7
                "000102030405060708090a0b0c0d0e0f" +
                "101112131415161718191a1b1c1d1e1f" +         // dh_public_key
                "00000000" +                                 // one_time_prekey_id = 0
                "05000000" +                                 // kyber_otpk_id = 5
                "0800" +                                     // kem_len = 8
                "00000000" +                                 // previous_chain_length = 0
                "0100" +                                     // suite_id = 1
                "abababababababab" +                         // kem_ciphertext
                "1122334455",                                 // sealed box
        )

        val decoded = WirePayloadCodec.decode(payload)
        assertEquals(7u, decoded.messageNumber)
        assertEquals(0u, decoded.oneTimePrekeyId)
        assertEquals(5u, decoded.kyberOtpkId)
        assertEquals(1.toUShort(), decoded.suiteId)
        assertArrayEquals(ByteArray(8) { 0xAB.toByte() }, decoded.kemCiphertext)
        assertArrayEquals(hex("1122334455"), decoded.content)
        assertArrayEquals(hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"), decoded.ephemeralPublicKey)
    }

    @Test
    fun `encode then decode round trips`() {
        val eph = ByteArray(32) { it.toByte() }
        val content = byteArrayOf(9, 8, 7, 6)
        val kem = ByteArray(16) { 0xCD.toByte() }

        val encoded = WirePayloadCodec.encode(
            messageNumber = 42u,
            ephemeralPublicKey = eph,
            oneTimePrekeyId = 3u,
            content = content,
            kemCiphertext = kem,
            kyberOtpkId = 9u,
            suiteId = 2u,
        )
        val decoded = WirePayloadCodec.decode(encoded)

        assertEquals(42u, decoded.messageNumber)
        assertEquals(3u, decoded.oneTimePrekeyId)
        assertEquals(9u, decoded.kyberOtpkId)
        assertEquals(2.toUShort(), decoded.suiteId)
        assertArrayEquals(eph, decoded.ephemeralPublicKey)
        assertArrayEquals(kem, decoded.kemCiphertext)
        assertArrayEquals(content, decoded.content)
    }

    @Test
    fun `no-PQC payload has null kem`() {
        val encoded = WirePayloadCodec.encode(
            messageNumber = 1u,
            ephemeralPublicKey = ByteArray(32),
            oneTimePrekeyId = 0u,
            content = byteArrayOf(1, 2, 3),
        )
        assertNull(WirePayloadCodec.decode(encoded).kemCiphertext)
    }

    @Test
    fun `too-short payload is rejected`() {
        assertThrows(WirePayloadCodec.WirePayloadException::class.java) {
            WirePayloadCodec.decode(ByteArray(WirePayloadCodec.HEADER_SIZE))
        }
    }

    @Test
    fun `wrong dh key length is rejected on encode`() {
        assertThrows(WirePayloadCodec.WirePayloadException::class.java) {
            WirePayloadCodec.encode(1u, ByteArray(31), 0u, byteArrayOf(1))
        }
    }
}
