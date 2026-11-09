package com.construct.messenger.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import shared.proto.core.v1.EnvelopeOuterClass.ContentType
import uniffi.construct_core.SenderCertificate

/**
 * What a DECRYPTION_ERROR hands the core (vault todo 168). The core decides by the ratchet key the
 * error names, so these values cannot make a forged error act; they decide whether a genuine one
 * is heard. Canon: iOS `SealedRoutingBoundaryTests` (device from the certificate or the envelope,
 * `opened` both ways).
 */
class DecryptionErrorEventTest {

    private val writer = "22222222222222222222222222222222"
    private val payload = byteArrayOf(0x01, 0x00, 0xAB.toByte())

    private fun message(
        certificate: SenderCertificate? = null,
        envelopeSession: String? = null,
        envelopeDevice: String? = null,
    ) = MessageRouter.IncomingMessage(
        messageId = "m1",
        senderId = "bob",
        contentType = ContentType.CONTENT_TYPE_DECRYPTION_ERROR,
        encryptedPayload = payload,
        timestampMs = 0,
        viaSealedSender = true,
        senderCertificate = certificate,
        envelopeSession = envelopeSession,
        envelopeDevice = envelopeDevice,
    )

    private fun certificate(deviceId: String) = SenderCertificate(
        userId = "bob",
        domain = "konstruct.cc",
        identityKey = ByteArray(32) { 5 },
        deviceId = deviceId,
        issuedAt = 1_000L,
        expiresAt = 2_000L,
        signature = ByteArray(64),
        serverKid = ByteArray(0),
        serverSignatureHybrid = ByteArray(0),
    )

    /** Out of a session envelope the payload is the error itself; handed as a box, the core
     * fails to open it and the peer's error is lost.
     * Mutation that reddens it: `opened = false`. */
    @Test
    fun `an enveloped error is handed over opened, from the envelope's writer`() {
        val event = requireNotNull(decryptionErrorEvent(message(envelopeSession = "5e55", envelopeDevice = writer)))
        assertEquals(writer, event.contactId)
        assertEquals(true, event.opened)
        assertArrayEquals(payload, event.payload)
    }

    /** A boxed error (the message it is about came with a certificate) is a box for the core to
     * open. Mutation that reddens it: `opened = true`. */
    @Test
    fun `a certified error is handed over as a box, from the certified device`() {
        val event = requireNotNull(decryptionErrorEvent(message(certificate = certificate(writer))))
        assertEquals(writer, event.contactId)
        assertEquals(false, event.opened)
    }

    /** A record is per device: with nothing naming one there is no state to answer about.
     * Mutation that reddens it: drop the `isEmpty()` return. */
    @Test
    fun `an error that names no device is not handed over`() {
        assertNull(decryptionErrorEvent(message()))
    }
}
