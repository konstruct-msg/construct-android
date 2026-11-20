package com.construct.messenger.service

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.InMemoryAtRiskSessionStore
import com.construct.messenger.data.local.PeerDeviceRegistry
import com.google.protobuf.ByteString
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import shared.proto.services.v1.KeyServiceGrpcKt.KeyServiceCoroutineStub
import shared.proto.services.v1.KeyServiceOuterClass.GetPreKeyBundleResponse
import shared.proto.services.v1.KeyServiceOuterClass.PreKeyBundle
import uniffi.construct_core.CfeAction
import uniffi.construct_core.CfeIncomingEvent

/**
 * Todo 305: two first sends to one device at once (stand 304, a share with two albums). Both saw
 * no session, both fetched a bundle — two of the peer's one-time prekeys — and the second
 * `init_session` was refused ("Session already exists"), its message FAILED.
 *
 * A send now asks the core's machine (`SessionNeeded`, 0.77): the real core here, not a stand-in.
 * Its answer is one open per device; the send that comes while it runs is told to wait.
 */
class FirstSendOpensOnceTest {

    private val grpc: GrpcClient = mock()
    private val keys: KeyServiceCoroutineStub = mock()
    private val registry: PeerDeviceRegistry = mock()

    /**
     * The barrier is the bundle fetch: it takes a while, and the second send reaches its check
     * meanwhile. The peer's bundle has no PQXDH v2 keys, so the core refuses the open — which is
     * also what shows the open is over: the next send is granted one again.
     *
     * Red before the change: two fetches. Mutations, one at a time: `WAITING` handled as `HELD`
     * (the second send goes on without a session); `needSession` without the event lock still
     * passes — the core's machine is what holds the phase.
     */
    @Test
    fun twoFirstSendsToOneDeviceFetchOneBundle() = runTest {
        val us = CryptoManager().apply { loadOrCreate(); setLocalUserId(OUR_ACCOUNT) }
        val peerKeys = CryptoManager().loadOrCreate()
        val device = us.deriveDeviceIdFromIdentity(peerKeys.identityPublic)
        whenever(grpc.sealedKey).thenReturn(keys)
        whenever(registry.accountIdForDevice(device)).thenReturn(PEER)
        whenever(registry.identityForDevice(device)).thenReturn(peerKeys.identityPublic)
        whenever(keys.getPreKeyBundle(any(), any())).doSuspendableAnswer {
            delay(100)
            GetPreKeyBundleResponse.newBuilder()
                .setDeviceId(device)
                .setBundle(
                    PreKeyBundle.newBuilder()
                        .setIdentityKey(ByteString.copyFrom(peerKeys.identityPublic))
                        .setSignedPreKey(ByteString.copyFrom(peerKeys.signedPrekeyPublic))
                        .setSignedPreKeySignature(ByteString.copyFrom(peerKeys.signature))
                        .setCryptoSuiteValue(10),
                )
                .setVerifyingKey(ByteString.copyFrom(peerKeys.verifyingKey))
                .build()
        }
        lateinit var bridge: CfeTimerBridge
        val manager = SessionManager(us, grpc, mock(), registry, mock(), mock(), InMemoryAtRiskSessionStore(), dagger.Lazy { bridge })
        bridge = CfeTimerBridge(us, mock(), HeldEnvelopes(), manager)

        val first = async { runCatching { manager.ensureSessionForDevice(PEER, device) } }
        val second = async { runCatching { manager.ensureSessionForDevice(PEER, device) } }

        assertTrue(second.await().exceptionOrNull() is SessionManager.OpenInFlight)
        assertFalse(first.await().exceptionOrNull() is SessionManager.OpenInFlight)
        verify(keys, times(1)).getPreKeyBundle(any(), any())

        // The refused open is over: the next send is granted one, and fetches again.
        assertFalse(runCatching { manager.ensureSessionForDevice(PEER, device) }.exceptionOrNull() is SessionManager.OpenInFlight)
        verify(keys, times(2)).getPreKeyBundle(any(), any())
    }

    /**
     * What waited for an open goes once it opened, and only then: after a refused one it keeps its
     * place in the retry, or a peer that never opens would be asked again at once, forever.
     * Mutations: call `openAnswered` on every answer (the refusal case reddens); drop it (the
     * first case reddens).
     */
    @Test
    fun theCopiesThatWaitedGoOnceTheOpenOpened() = runTest {
        val opened = mutableListOf<String>()
        val waiting = WaitingCopies { opened += it }
        val sessionManager: SessionManager = mock()
        whenever(sessionManager.bundleForOpenSession(DEVICE)).thenReturn(
            PreKeyBundle.getDefaultInstance().toBinaryKeyBundle(ByteArray(0)),
        )
        val accepted = Gateway(listOf(CfeAction.NotifySessionCreated(DEVICE)))
        assertTrue(CfeTimerBridge(accepted, mock(), HeldEnvelopes(), sessionManager, waiting).answerOpenSession(DEVICE))
        assertEquals(listOf(DEVICE), opened)

        opened.clear()
        val refused = Gateway(listOf(CfeAction.NotifyError("OPEN_SESSION_REFUSED", "PqRequired")))
        assertFalse(CfeTimerBridge(refused, mock(), HeldEnvelopes(), sessionManager, waiting).answerOpenSession(DEVICE))
        assertEquals(emptyList<String>(), opened)
    }

    /**
     * A stale bundle the core opened anyway is at risk, and a clean open is not: the core's
     * `SessionOpenedAtRisk` decides which (0.77; until then Android judged the refusal itself).
     * Mutation: call `openedCleanly` whatever the answer — the at-risk open is cleared.
     */
    @Test
    fun anOpenAtRiskIsMarkedAndNotCleared() = runTest {
        val sessionManager: SessionManager = mock()
        whenever(sessionManager.bundleForOpenSession(DEVICE)).thenReturn(
            PreKeyBundle.getDefaultInstance().toBinaryKeyBundle(ByteArray(0)),
        )
        val gateway = Gateway(listOf(CfeAction.SessionOpenedAtRisk(DEVICE)))

        CfeTimerBridge(gateway, mock(), HeldEnvelopes(), sessionManager).answerOpenSession(DEVICE)

        verify(sessionManager).openedAtRisk(DEVICE)
        verify(sessionManager, org.mockito.kotlin.never()).openedCleanly(DEVICE)
    }

    private class Gateway(private val answer: List<CfeAction>) : OrchestratorGateway {
        override fun handleEvent(event: CfeIncomingEvent): List<CfeAction> = answer
    }

    private companion object {
        const val OUR_ACCOUNT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val PEER = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val DEVICE = "11111111111111111111111111111111"
    }
}
