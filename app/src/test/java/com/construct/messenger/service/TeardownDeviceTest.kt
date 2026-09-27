package com.construct.messenger.service

import com.construct.messenger.domain.usecase.SessionControlUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import shared.proto.core.v1.EnvelopeOuterClass.ContentType
import uniffi.construct_core.CfeIncomingEvent
import uniffi.construct_core.SenderCertificate

/**
 * An inbound END_SESSION tears down one ratchet, named by a device. Until 2026-09-27 it was filed
 * under the envelope's account, `removeSession(account)` removed nothing, and the dead ratchet
 * turned the peer's next re-init into a heal and an END_SESSION back (Android↔iOS stand).
 */
class TeardownDeviceTest {

    private val device = "22222222222222222222222222222222"

    private fun endSession(certificate: SenderCertificate?) = MessageRouter.IncomingMessage(
        messageId = "end-1",
        senderId = "alice-account",
        contentType = ContentType.CONTENT_TYPE_SESSION_RESET,
        encryptedPayload = ByteArray(0),
        timestampMs = 0L,
        viaSealedSender = certificate != null,
        senderCertificate = certificate,
    )

    private val certificate = SenderCertificate(
        userId = "alice-account",
        domain = "konstruct.cc",
        identityKey = ByteArray(32),
        deviceId = device,
        issuedAt = 0L,
        expiresAt = 0L,
        signature = ByteArray(64),
    )

    /** Mutation that reddens it: pass `message.senderId`. */
    @Test
    fun `a sealed teardown names the device its certificate names`() = runTest {
        assertEquals(device, teardownDevice(endSession(certificate)) { "pinned" })
    }

    @Test
    fun `an unsealed teardown falls back to the pinned device, never the account`() = runTest {
        assertEquals("pinned-device", teardownDevice(endSession(null)) { "pinned-device" })
        assertEquals(null, teardownDevice(endSession(null)) { null })
    }

    /** The core keeps the quiet after a peer's teardown; without the report, our own heal answers
     * the peer's re-init with an END_SESSION. Mutation that reddens it: drop `PeerToreDown`. */
    @Test
    fun `the core is told the peer tore the device down`() = runTest {
        val gateway: OrchestratorGateway = mock()
        val control = SessionControlUseCase(
            keystoreManager = mock(),
            sessionManager = mock(),
            sessionStateStore = mock(),
            messagingService = mock(),
            stealthPolicy = mock(),
            stealthSender = mock(),
            orchestrator = gateway,
            cryptoManager = mock(),
        )

        control.inboundEndSession(device)

        verify(gateway).handleEvent(CfeIncomingEvent.PeerToreDown(device))
    }
}
