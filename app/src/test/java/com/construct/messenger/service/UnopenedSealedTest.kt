package com.construct.messenger.service

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.StreamCursorStore
import com.construct.messenger.data.api.StreamCursorTracker
import com.construct.messenger.data.local.AckStore
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.PeerDeviceRecord
import com.construct.messenger.data.local.PeerDeviceRegistry
import com.construct.messenger.service.UnopenedSealed.Disposition.DeferOnce
import com.construct.messenger.service.UnopenedSealed.Disposition.Drop
import com.construct.messenger.stealth.StealthSenderService
import com.google.protobuf.ByteString
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import shared.proto.core.v1.EnvelopeOuterClass.Envelope
import shared.proto.core.v1.EnvelopeOuterClass.SealedInner
import shared.proto.core.v1.EnvelopeOuterClass.SealedSenderEnvelope

/** TODO 160: a sealed message that does not open is held once, then the cursor goes past it. */
class UnopenedSealedTest {

    private val ours = "aa".repeat(16)
    private val sibling = "bb".repeat(16)

    private fun inner(recipientDevice: String = ""): ByteArray =
        SealedInner.newBuilder().setRecipientDevice(recipientDevice).build().toByteArray()

    // --- decision table (iOS `unsealFailureDisposition`) ---

    @Test
    fun `a first failure on one device is held for one redelivery`() {
        assertEquals(DeferOnce, UnopenedSealed.disposition(namesNoRecipientDevice = false, ownDeviceCount = 1, alreadyDeferred = false))
        assertEquals(DeferOnce, UnopenedSealed.disposition(namesNoRecipientDevice = true, ownDeviceCount = 1, alreadyDeferred = false))
        assertEquals(DeferOnce, UnopenedSealed.disposition(namesNoRecipientDevice = false, ownDeviceCount = 3, alreadyDeferred = false))
    }

    @Test
    fun `a second failure drops it`() {
        for (unnamed in listOf(false, true)) for (count in listOf(1, 2)) {
            assertEquals(Drop, UnopenedSealed.disposition(unnamed, count, alreadyDeferred = true))
        }
    }

    @Test
    fun `an unnamed copy on a multi-device account is a sibling's and drops at once`() {
        assertEquals(Drop, UnopenedSealed.disposition(namesNoRecipientDevice = true, ownDeviceCount = 2, alreadyDeferred = false))
    }

    @Test
    fun `only a copy named for another device is addressed elsewhere`() {
        assertEquals(sibling, UnopenedSealed.otherDeviceAddressed(inner(sibling), ours))
        assertNull(UnopenedSealed.otherDeviceAddressed(inner(ours), ours))
        assertNull(UnopenedSealed.otherDeviceAddressed(inner(""), ours))
        assertNull("our own id unknown yet", UnopenedSealed.otherDeviceAddressed(inner(sibling), null))
        assertNull("corruption is the open's to report", UnopenedSealed.otherDeviceAddressed(byteArrayOf(-1, -1, -1), ours))
    }

    @Test
    fun `unnamed means an empty recipient device, not a parse failure`() {
        assertTrue(UnopenedSealed.namesNoRecipientDevice(inner("")))
        assertFalse(UnopenedSealed.namesNoRecipientDevice(inner(sibling)))
        assertFalse(UnopenedSealed.namesNoRecipientDevice(byteArrayOf(-1, -1, -1)))
    }

    // --- through the router: what the cursor does ---

    private val ackStore = object : AckStore {
        override suspend fun hydrate() = Unit
        override fun isProcessed(messageId: String) = false
        override suspend fun markProcessed(messageId: String, senderId: String) = Unit
        override suspend fun prune(olderThanMs: Long) = 0
    }
    private val tracker = StreamCursorTracker(mock<StreamCursorStore>(), ackStore)
    private val stealth: StealthSenderService = mock() // resolveSender → null: nothing opens
    private val crypto: CryptoManager = mock { on { currentDeviceId() } doReturn ours }
    private val keystore: KeystoreManager = mock { on { getUserId() } doReturn "me" }
    private val registry: PeerDeviceRegistry = mock()
    private val router = MessageRouter(mock(), stealth, ServerOrderBook(), tracker, crypto, keystore, registry)

    private fun sealed(id: String, inner: ByteArray) = Envelope.newBuilder()
        .setMessageId(id)
        .setSealedSender(SealedSenderEnvelope.newBuilder().setSealedInner(ByteString.copyFrom(inner)))
        .build()

    private suspend fun ownDevices(vararg ids: String) {
        whenever(registry.knownDevices("me")).thenReturn(ids.map { PeerDeviceRecord(it, "me", ByteArray(32), 0) })
    }

    /** Mutation: report nothing from `unopened` — the cursor stays where it was. */
    @Test
    fun `a message that does not open twice lets the cursor past it`() = runBlocking<Unit> {
        ownDevices(ours)
        tracker.track("m1", "c1")
        router.ingest(sealed("m1", inner(ours)), null)
        assertNull("held after the first failure", tracker.committedCursor())

        // The reconnect replays from the committed cursor; the dedup must let the redelivery through.
        tracker.reset()
        tracker.track("m1", "c1")
        router.ingest(sealed("m1", inner(ours)), null)
        assertEquals("c1", tracker.committedCursor())
    }

    @Test
    fun `a sibling's unnamed copy lets the cursor past it at once`() = runBlocking<Unit> {
        ownDevices(ours, sibling)
        tracker.track("m1", "c1")
        router.ingest(sealed("m1", inner("")), null)
        assertEquals("c1", tracker.committedCursor())
    }

    /** Mutation: drop the `otherDeviceAddressed` check — the open is tried and the cursor held. */
    @Test
    fun `a copy for another device moves the cursor without trying to open it`() = runBlocking<Unit> {
        ownDevices(ours)
        tracker.track("m1", "c1")
        router.ingest(sealed("m1", inner(sibling)), null)
        assertEquals("c1", tracker.committedCursor())
        verify(stealth, never()).resolveSender(any())
    }
}
