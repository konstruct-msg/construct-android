package com.construct.messenger.data.repository

import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.FakeContactStore
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.PeerDeviceRegistry
import com.construct.messenger.invite.InviteObject
import com.construct.messenger.invite.InviteVerifier
import com.construct.messenger.invite.VerifiedInvite
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import shared.proto.services.v1.InviteServiceGrpcKt.InviteServiceCoroutineStub
import shared.proto.services.v1.InviteServiceOuterClass.AcceptInviteResponse

/**
 * A verified invite's device is the root the rest of the account is judged against (`todo/268`).
 * Mutation: drop `pinInvited` from `persistContact` — the first answer reads as a first meeting and
 * accepts whatever the server offers next to it.
 */
class AcceptedInvitePinsItsDeviceTest {
    @Test
    fun anAcceptedInvitePinsItsDevice() = runTest {
        val identity = ByteArray(32) { 7 }
        val invite = InviteObject(
            v = 5, jti = "jti", uuid = ACCOUNT, deviceId = "a".repeat(32), server = "s", ts = 1,
            sig = "sig", un = null, ttl = 300, addr = ByteArray(32),
        )
        val verifier = mock<InviteVerifier>()
        whenever(verifier.decode(any())).thenReturn(invite)
        whenever(verifier.verify(any())).thenReturn(VerifiedInvite(invite, identity, ByteArray(32)))
        val keystore = mock<KeystoreManager>()
        whenever(keystore.getOwnAccountAddress()).thenReturn(ByteArray(32))
        val stub = mock<InviteServiceCoroutineStub>()
        whenever(stub.acceptInvite(any(), any())).thenReturn(AcceptInviteResponse.newBuilder().setUserId(ACCOUNT).build())
        val grpc = mock<GrpcClient>()
        whenever(grpc.invite).thenReturn(stub)
        val devices = mock<PeerDeviceRegistry>()
        val repo = ContactsRepositoryImpl(
            FakeContactStore(), keystore, mock(), verifier, grpc, mock(), mock(), mock(), mock(), mock(), devices, com.construct.messenger.data.local.InMemoryAtRiskSessionStore(),
        )

        assertTrue(repo.accept("raw") is AcceptInviteResult.Ok)

        verify(devices).pinInvited(eq(ACCOUNT), eq(identity))
    }

    private companion object {
        const val ACCOUNT = "e7a4e3d2-0000-4000-8000-000000000001"
    }
}
