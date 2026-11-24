package com.construct.messenger.data.repository

import com.construct.messenger.R
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.FakeContactStore
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.PeerDeviceRegistry
import com.construct.messenger.invite.AccountAddressBook
import com.construct.messenger.invite.InviteObject
import com.construct.messenger.invite.InviteVerifier
import com.construct.messenger.invite.VerifiedInvite
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import shared.proto.services.v1.InviteServiceGrpcKt.InviteServiceCoroutineStub
import shared.proto.services.v1.InviteServiceOuterClass.AcceptInviteResponse

/**
 * An invite of our own adds nobody and says so: until 2026-11-24 the screen named the person
 * themselves as added, the invite was spent, and our own device was pinned as a peer's.
 */
class OwnInviteRefusedTest {
    private val stub = mock<InviteServiceCoroutineStub>()
    private val store = FakeContactStore()
    private val devices = mock<PeerDeviceRegistry>()
    private val addresses = mock<AccountAddressBook>()

    private suspend fun accept(inviter: String, me: String?): AcceptInviteResult {
        val invite = InviteObject(
            v = 5, jti = "jti", uuid = inviter, deviceId = "a".repeat(32), server = "s", ts = 1,
            sig = "sig", un = null, ttl = 300, addr = ByteArray(32),
        )
        val verifier = mock<InviteVerifier>()
        whenever(verifier.decode(any())).thenReturn(invite)
        whenever(verifier.verify(any())).thenReturn(VerifiedInvite(invite, ByteArray(32) { 7 }, ByteArray(32)))
        val keystore = mock<KeystoreManager>()
        whenever(keystore.getOwnAccountAddress()).thenReturn(ByteArray(32))
        whenever(keystore.getUserId()).thenReturn(me)
        whenever(stub.acceptInvite(any(), any())).thenReturn(AcceptInviteResponse.newBuilder().setUserId(inviter).build())
        val grpc = mock<GrpcClient>()
        whenever(grpc.invite).thenReturn(stub)
        val repo = ContactsRepositoryImpl(
            store, keystore, mock(), verifier, grpc, mock(), addresses, mock(), mock(), mock(), devices,
            com.construct.messenger.data.local.InMemoryAtRiskSessionStore(),
        )
        return repo.accept("raw")
    }

    @Test
    fun ourOwnInviteIsRefusedByName() = runTest {
        assertEquals(AcceptInviteResult.Failed(R.string.invite_error_own), accept(ME, ME))
    }

    @Test
    fun itIsTheSameAccountInAnyCase() = runTest {
        assertEquals(AcceptInviteResult.Failed(R.string.invite_error_own), accept(ME.uppercase(), ME))
    }

    @Test
    fun itIsNotSpentAndPinsNothing() = runTest {
        accept(ME, ME)
        verify(stub, never()).acceptInvite(any(), any())
        verifyNoInteractions(devices, addresses)
        assertNull(store.get(ME))
    }

    @Test
    fun anothersInviteIsAccepted() = runTest {
        assertTrue(accept(OTHER, ME) is AcceptInviteResult.Ok)
        verify(stub).acceptInvite(any(), any())
    }

    private companion object {
        const val ME = "e7a4e3d2-0000-4000-8000-000000000001"
        const val OTHER = "e7a4e3d2-0000-4000-8000-000000000002"
    }
}
