package com.construct.messenger.data.repository

import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.FakeContactStore
import com.construct.messenger.data.local.FakeSessionBlobStore
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.repository.BlockSync.Disposition.Drop
import com.construct.messenger.data.repository.BlockSync.Disposition.Retry
import com.construct.messenger.data.repository.BlockSync.Intent
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import shared.proto.services.v1.UserServiceGrpcKt.UserServiceCoroutineImplBase
import shared.proto.services.v1.UserServiceGrpcKt.UserServiceCoroutineStub
import shared.proto.services.v1.UserServiceOuterClass.BlockUserRequest
import shared.proto.services.v1.UserServiceOuterClass.BlockUserResponse
import shared.proto.services.v1.UserServiceOuterClass.BlockedUser
import shared.proto.services.v1.UserServiceOuterClass.GetBlockedUsersRequest
import shared.proto.services.v1.UserServiceOuterClass.GetBlockedUsersResponse
import shared.proto.services.v1.UserServiceOuterClass.UnblockUserRequest
import shared.proto.services.v1.UserServiceOuterClass.UnblockUserResponse

/** TODO 170: the block state reaches the server, and the server's list comes back. **Canon:** iOS `BlockSync`. */
class BlockSyncTest {
    private val blobs = FakeSessionBlobStore()
    private val queue = BlockSyncQueue(blobs)
    private val block = Intent(blocked = true)
    private val unblock = Intent(blocked = false)

    // --- the queue ---

    /** Mutation: keep a waiting wish (`if (userId !in all)`) — the unblock is lost behind the block. */
    @Test
    fun `the latest wish replaces one still waiting`() = runTest {
        queue.record("a", block)
        queue.record("a", unblock)
        assertEquals(mapOf("a" to unblock), queue.pending())
    }

    /** Mutation: `confirm` removes whatever is queued — an unblock made mid-flight is never sent. */
    @Test
    fun `only the wish that was sent leaves the queue`() = runTest {
        queue.record("a", block)
        val sent = queue.pending().getValue("a")
        queue.record("a", unblock) // while the block was in flight
        queue.confirm("a", sent)
        assertEquals(mapOf("a" to unblock), queue.pending())

        queue.confirm("a", unblock)
        assertEquals(emptyMap<String, Intent>(), queue.pending())
    }

    @Test
    fun `the queue outlives the process, reason included`() = runTest {
        queue.record("a", Intent(blocked = true, reason = "spam"))
        queue.record("b", unblock)
        assertEquals(mapOf("a" to Intent(true, "spam"), "b" to unblock), BlockSyncQueue(blobs).pending())
    }

    // --- retry or drop ---

    /** Mutation: UNAUTHENTICATED → Drop — a wish sent while the session renews is thrown away. */
    @Test
    fun `the path failing or a session renewing is tried again`() {
        for (code in listOf(Status.UNAVAILABLE, Status.DEADLINE_EXCEEDED, Status.CANCELLED, Status.UNAUTHENTICATED)) {
            assertEquals(code.code.name, Retry, BlockSync.disposition(code.asException()))
        }
        assertEquals(Retry, BlockSync.disposition(Status.UNAVAILABLE.asRuntimeException()))
        assertEquals("no status: the path", Retry, BlockSync.disposition(IOException("reset")))
    }

    /** Mutation: every failure → Retry — a refused wish blocks the queue forever. */
    @Test
    fun `a server refusal is dropped`() {
        for (code in listOf(Status.NOT_FOUND, Status.INVALID_ARGUMENT, Status.PERMISSION_DENIED, Status.FAILED_PRECONDITION)) {
            assertEquals(code.code.name, Drop, BlockSync.disposition(code.asException()))
        }
    }

    // --- what the read-back adopts ---

    /** Mutation: drop either filter — a queued unblock is overridden, or a shown block counted again. */
    @Test
    fun `only a server row not shown here and not queued is adopted`() {
        val adopt = BlockSync.toAdopt(listOf("a", "b", "c", "a", ""), pending = setOf("b")) { it == "c" }
        assertEquals(listOf("a"), adopt)
    }

    // --- the pass, over a real stub ---

    private val name = InProcessServerBuilder.generateName()
    private val calls = Collections.synchronizedList(mutableListOf<String>())
    private var serverList = listOf<String>()
    private var unavailable = 0
    private var refuse = false
    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private val contacts = FakeContactStore()
    private val grpc: GrpcClient = mock()
    private val keystore: KeystoreManager = mock { on { getUserId() } doReturn "me" }
    private val pauses = mutableListOf<Long>()
    private lateinit var sync: BlockSyncService

    @Before
    fun start() {
        server = InProcessServerBuilder.forName(name).directExecutor().addService(object : UserServiceCoroutineImplBase() {
            override suspend fun blockUser(request: BlockUserRequest): BlockUserResponse {
                calls += "block ${request.userId}"
                fail()
                return BlockUserResponse.newBuilder().setSuccess(true).build()
            }

            override suspend fun unblockUser(request: UnblockUserRequest): UnblockUserResponse {
                calls += "unblock ${request.userId}"
                fail()
                return UnblockUserResponse.newBuilder().setSuccess(true).build()
            }

            override suspend fun getBlockedUsers(request: GetBlockedUsersRequest): GetBlockedUsersResponse {
                calls += "list"
                return GetBlockedUsersResponse.newBuilder()
                    .addAllBlockedUsers(serverList.map { BlockedUser.newBuilder().setUserId(it).build() }).build()
            }
        }).build().start()
        channel = InProcessChannelBuilder.forName(name).directExecutor().build()
        whenever(grpc.user).thenReturn(UserServiceCoroutineStub(channel))
        sync = BlockSyncService(queue, grpc, keystore, contacts, CoroutineScope(Dispatchers.Unconfined)) { pauses += it }
    }

    private fun fail() {
        if (refuse) throw Status.NOT_FOUND.asException()
        if (unavailable > 0) {
            unavailable--
            throw Status.UNAVAILABLE.asException()
        }
    }

    @After
    fun stop() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    /** The defect itself: an unblock the path lost the first time reaches the server after all. */
    @Test
    fun `a lost unblock is sent again until the server answers, then the list is read`() = runTest {
        unavailable = 2
        queue.record("a", unblock)
        sync.pass()
        assertEquals(listOf("unblock a", "unblock a", "unblock a", "list"), calls)
        assertEquals(listOf(2_000L, 10_000L), pauses)
        assertTrue(queue.pending().isEmpty())
    }

    /** Mutation: read back with the queue still waiting — the stale server row would be adopted over the queued unblock. */
    @Test
    fun `the list is not read while the queue cannot drain`() = runTest {
        unavailable = Int.MAX_VALUE
        serverList = listOf("a")
        contacts.ensure("a")
        queue.record("a", unblock)
        sync.pass()
        assertFalse("list" in calls)
        assertEquals(mapOf("a" to unblock), queue.pending())
        assertFalse(contacts.get("a")!!.isBlocked)
    }

    @Test
    fun `a refused wish leaves the queue`() = runTest {
        refuse = true
        queue.record("a", block)
        sync.pass()
        assertTrue(queue.pending().isEmpty())
        assertEquals(listOf("block a", "list"), calls)
    }

    /** Mutation: skip the read-back — the row that refuses calls stays invisible here. */
    @Test
    fun `a server row this device does not show is shown`() = runTest {
        serverList = listOf("a", "stranger")
        contacts.ensure("a")
        sync.pass()
        assertTrue(contacts.get("a")!!.isBlocked)
        assertEquals(null, contacts.get("stranger"))
    }
}
