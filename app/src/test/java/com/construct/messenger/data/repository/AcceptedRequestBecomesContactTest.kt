package com.construct.messenger.data.repository

import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.FakeContactStore
import com.construct.messenger.data.local.FakeSessionBlobStore
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.util.Collections
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import shared.proto.services.v1.UserServiceGrpcKt.UserServiceCoroutineImplBase
import shared.proto.services.v1.UserServiceGrpcKt.UserServiceCoroutineStub
import shared.proto.services.v1.UserServiceOuterClass.ContactRequestStatus
import shared.proto.services.v1.UserServiceOuterClass.GetContactRequestsRequest
import shared.proto.services.v1.UserServiceOuterClass.GetContactRequestsResponse
import shared.proto.services.v1.UserServiceOuterClass.SendContactRequestRequest
import shared.proto.services.v1.UserServiceOuterClass.SendContactRequestResponse
import shared.proto.services.v1.UserServiceOuterClass.SentContactRequest

/**
 * TODO 157 over a real stub: a request of ours they accepted becomes a contact, and the server is
 * told at once so it deletes the row. **Canon:** iOS `ContactRequestService.checkAndCreateContacts`.
 */
class AcceptedRequestBecomesContactTest {
    private val name = InProcessServerBuilder.generateName()
    private val polls = Collections.synchronizedList(mutableListOf<List<String>>())
    private var sent = listOf<SentContactRequest>()
    private var failPolls = false

    private lateinit var server: Server
    private lateinit var channel: ManagedChannel

    private val contacts = FakeContactStore()
    private val ledger = ContactRequestLedger(FakeSessionBlobStore())
    private val grpc: GrpcClient = mock()
    private lateinit var repo: ContactsRepositoryImpl

    @Before
    fun start() {
        server = InProcessServerBuilder.forName(name).directExecutor().addService(object : UserServiceCoroutineImplBase() {
            override suspend fun sendContactRequest(request: SendContactRequestRequest) =
                SendContactRequestResponse.newBuilder().setRequestId("req-${request.toUserId}").build()

            override suspend fun getContactRequests(request: GetContactRequestsRequest): GetContactRequestsResponse {
                polls += request.ackAcceptedList.toList()
                if (failPolls) throw io.grpc.Status.UNAVAILABLE.asException()
                return GetContactRequestsResponse.newBuilder().addAllSent(sent).build()
            }
        }).build().start()
        channel = InProcessChannelBuilder.forName(name).directExecutor().build()
        whenever(grpc.user).thenReturn(UserServiceCoroutineStub(channel))
        repo = ContactsRepositoryImpl(contacts, mock(), mock(), mock(), grpc, mock(), mock(), mock(), mock(), ledger)
    }

    @After
    fun stop() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    private fun accepted(requestId: String) =
        SentContactRequest.newBuilder().setRequestId(requestId).setStatus(ContactRequestStatus.CONTACT_REQUEST_STATUS_ACCEPTED).build()

    /** Mutation: skip `recordSent` on send — the accepted request has no user and nothing happens. */
    @Test
    fun `an accepted request of ours becomes a contact and is acknowledged at once`() = runTest {
        repo.sendContactRequest("user-b")
        sent = listOf(accepted("req-user-b"))

        repo.refreshRequests()

        assertTrue(contacts.get("user-b")!!.isContact)
        assertNull(ledger.sentTo("req-user-b"))
        // The poll that saw it carried nothing; the one right after carried its id.
        assertEquals(listOf(emptyList(), listOf("req-user-b")), polls)
        assertEquals(emptyList<String>(), ledger.pendingAcks())
    }

    /** Mutation: `confirmAcks` before the call — a failed poll drops the ack and the row stays 30 days. */
    @Test
    fun `a failed acknowledgement is carried by the next poll`() = runTest {
        ledger.ack("req-old")
        failPolls = true
        repo.refreshRequests()
        assertEquals(listOf("req-old"), ledger.pendingAcks())

        failPolls = false
        repo.refreshRequests()
        assertEquals(listOf("req-old"), polls.last())
        assertEquals(emptyList<String>(), ledger.pendingAcks())
    }

    @Test
    fun `an accepted request this device did not send is left alone`() = runTest {
        sent = listOf(accepted("req-from-another-device"))
        repo.refreshRequests()
        assertEquals(1, polls.size)
        assertEquals(emptyList<String>(), ledger.pendingAcks())
    }
}
