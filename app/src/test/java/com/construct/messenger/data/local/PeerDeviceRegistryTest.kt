package com.construct.messenger.data.local

import com.construct.messenger.crypto.CryptoManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock

/** The registry's own rules, above whichever [PeerDeviceStore] holds the rows. */
class PeerDeviceRegistryTest {
    private val ann = "0b6e9f6a-3c1d-4f7e-9a2b-5d8c7e6f1a2b"
    private val bob = "1c7f0a7b-4d2e-4a8f-8b3c-6e9d8f7a2b3c"
    private fun key(c: Char) = ByteArray(32) { c.code.toByte() }
    private fun id(c: Char) = c.toString().repeat(32)

    // A device id is the hash of its key; here, the key's first byte repeated.
    private val crypto = mock<CryptoManager> {
        on { deriveDeviceIdFromIdentity(any()) } doAnswer { (it.arguments[0] as ByteArray)[0].toInt().toChar().toString().repeat(32) }
    }
    private val store = FakePeerDeviceStore()
    private val registry = PeerDeviceRegistry(store, FakeContactStore(), crypto)

    /**
     * A server answer naming Ann's device under Bob changes nothing. Two locks hold it: the
     * registry's check (which logs the attempt) and the store's rule that a known id keeps its
     * first row — dropping either alone leaves this green.
     */
    @Test
    fun aDeviceIsNotMovedToAnotherAccount() = runTest {
        registry.record(ann, id('a'), key('a'))
        registry.record(bob, id('a'), key('a'))
        assertEquals(ann, registry.accountIdForDevice(id('a')))
        assertEquals(emptyList<PeerDeviceRecord>(), registry.knownDevices(bob))
    }

    @Test
    fun aKeyThatIsNotTheDevicesIsRefused() = runTest {
        registry.record(ann, id('a'), key('b'))
        assertNull(registry.identityForDevice(id('a')))
    }

    @Test
    fun theActiveListForgetsTheRest() = runTest {
        registry.recordAll(ann, listOf(PeerDeviceRegistry.PeerDevice(id('a'), key('a')), PeerDeviceRegistry.PeerDevice(id('b'), key('b'))))
        registry.recordAll(ann, listOf(PeerDeviceRegistry.PeerDevice(id('b'), key('b'))), activeDeviceIds = listOf(id('b')))
        assertEquals(listOf(id('b')), registry.knownDevices(ann).map { it.deviceId })
    }
}
