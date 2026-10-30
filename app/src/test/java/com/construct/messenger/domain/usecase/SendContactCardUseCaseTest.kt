package com.construct.messenger.domain.usecase

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.local.ContactRecord
import com.construct.messenger.data.local.FakeContactStore
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.service.OrchestratorGateway
import com.construct.messenger.service.SessionManager
import com.construct.messenger.stealth.IntakeCredentials
import com.construct.messenger.stealth.StealthPolicy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import uniffi.construct_core.IntakeKeyState

class SendContactCardUseCaseTest {
    private val peer = "00000000-0000-0000-0000-000000000002"
    private val device = "a".repeat(32)
    private val keystore = mock<KeystoreManager> { on { contactCardSentTo() } doReturn emptySet() }
    private val sessions = mock<SessionManager>()
    private val orchestrator = mock<OrchestratorGateway>()
    private val crypto = mock<CryptoManager> { on { isMessagingReady } doReturn true }
    private val policy = mock<StealthPolicy> { on { shouldUseSealedSender() } doReturn true }
    private val intake = mock<IntakeCredentials> { on { currentOwnKey() } doReturn IntakeKeyState(2u, ByteArray(32)) }
    private val contacts = FakeContactStore()

    private val useCase = SendContactCardUseCase(
        keystore, sessions, orchestrator, crypto, mock(), policy, mock(), intake, mock(), mock(), contacts,
    )

    /**
     * A rotation took our key from a blocked contact; nothing hands it back — not the hour's
     * sweep, not their next message. Mutation: drop the blocked check — this reddens.
     */
    @Test
    fun `a blocked contact is never handed the key`() = runTest {
        whenever(sessions.resolveTarget(peer)).thenReturn(SessionManager.SessionPeer(peer, device, ByteArray(32) { 1 }))
        whenever(sessions.hasSession(device)).thenReturn(true)
        contacts.rows[peer] = ContactRecord(id = peer, isContact = true, isBlocked = true)

        useCase.sendIfOwed(peer)

        verify(orchestrator, never()).handleEvent(any())
    }
}
