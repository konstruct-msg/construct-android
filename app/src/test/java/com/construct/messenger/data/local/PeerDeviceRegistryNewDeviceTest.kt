package com.construct.messenger.data.local

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.local.db.PeerDeviceDao
import com.construct.messenger.data.local.db.PeerDeviceEntity
import com.construct.messenger.data.local.db.UserDao
import com.construct.messenger.data.model.SecurityNotice
import com.construct.messenger.security.SecurityNotices
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** `decisions/a-new-device-is-the-security-event.md` */
class PeerDeviceRegistryNewDeviceTest {

    private val me = "00000000-0000-0000-0000-00000000000a"
    private val peer = "00000000-0000-0000-0000-00000000000b"

    @Test
    fun `the rule - only a device outside an already listed set of someone else`() {
        assertTrue(PeerDeviceRegistry.isNewDeviceEvent(peer, me, listedBefore = true, alreadyKnown = false))
        assertFalse(PeerDeviceRegistry.isNewDeviceEvent(peer, me, listedBefore = false, alreadyKnown = false))
        assertFalse(PeerDeviceRegistry.isNewDeviceEvent(peer, me, listedBefore = true, alreadyKnown = true))
        assertFalse(PeerDeviceRegistry.isNewDeviceEvent(me, me, listedBefore = true, alreadyKnown = false))
    }

    // ── the registry wired to it ────────────────────────────────────────────

    private val dao = Devices()
    private val listed = mutableSetOf<String>()
    private val keystore = mock<KeystoreManager>().also { ks ->
        whenever(ks.getUserId()).thenReturn(me)
        whenever(ks.deviceSetsListed()).thenAnswer { listed.toSet() }
        whenever(ks.markDeviceSetListed(any())).thenAnswer { listed += (it.arguments[0] as String).lowercase(); Unit }
    }
    private val crypto = mock<CryptoManager>().also { c ->
        whenever(c.deriveDeviceIdFromIdentity(any())).thenAnswer { (it.arguments[0] as ByteArray)[0].toInt().toString(16).padStart(2, '0').repeat(16) }
    }
    private val notices = mock<SecurityNotices>()
    private val registry = PeerDeviceRegistry(dao, mock<UserDao>(), crypto, keystore, notices)

    private fun key(n: Int) = ByteArray(32) { n.toByte() }
    private fun device(n: Int) = PeerDeviceRegistry.PeerDevice(n.toString(16).padStart(2, '0').repeat(16), key(n))

    /**
     * First contact: the invite records one device, then the fan-out lists the rest. Neither is an
     * event. Mutation: raise on any unknown device — this reddens.
     */
    @Test
    fun `devices seen before and in the first full list are first sight`() = runTest {
        registry.record(peer, device(1).deviceId, key(1))
        registry.recordAll(peer, listOf(device(1), device(2)))

        verify(notices, never()).raise(any(), any())
    }

    /** Mutation: never mark the set listed — the ghost device goes unnoticed. */
    @Test
    fun `a device the listed set did not name is raised`() = runTest {
        registry.recordAll(peer, listOf(device(1)))
        registry.recordAll(peer, listOf(device(1), device(3)))

        verify(notices).raise(peer, SecurityNotice.NEW_DEVICE)
    }

    @Test
    fun `a device first heard through a certificate after the listing is raised too`() = runTest {
        registry.recordAll(peer, listOf(device(1)))
        registry.record(peer, device(4).deviceId, key(4))

        verify(notices).raise(peer, SecurityNotice.NEW_DEVICE)
    }

    private class Devices : PeerDeviceDao {
        val rows = mutableListOf<PeerDeviceEntity>()
        override suspend fun forAccount(accountId: String) = rows.filter { it.accountId == accountId }
        override suspend fun forDevice(deviceId: String) = rows.firstOrNull { it.deviceId == deviceId }
        override suspend fun upsert(device: PeerDeviceEntity) {
            rows.removeAll { it.deviceId == device.deviceId }
            rows += device
        }
        override suspend fun upsertAll(devices: List<PeerDeviceEntity>) = devices.forEach { upsert(it) }
        override suspend fun deleteNotActive(accountId: String, activeDeviceIds: List<String>) {
            rows.removeAll { it.accountId == accountId && it.deviceId !in activeDeviceIds }
        }
    }
}
