package com.construct.messenger.security

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.local.ChatPresence
import com.construct.messenger.data.local.ContactRecord
import com.construct.messenger.data.local.FakeContactStore
import com.construct.messenger.data.model.SecurityNotice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import uniffi.construct_core.OfferedPeerDevice
import uniffi.construct_core.PeerDeviceVerdict

/**
 * The receiving side of device certificates (`decisions/devices-of-an-account-sign-each-other.md`),
 * through the real core: which devices of a contact's account raise the alarm. The verdicts are the
 * core's and tested there; what can go wrong here is the mapping and the selection — a field taken
 * from the wrong place, or the wrong verdicts kept, and the alarm is silently never raised.
 * **Canon:** iOS `DeviceCertificateLinkTests`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerDeviceVettingTest {

    private fun device() = CoreTestDevice.create()

    private fun vouch(signer: CoreTestDevice, subject: CoreTestDevice) = signer.vouch(subject)

    private val me = device()
    private val contacts = FakeContactStore().also { it.rows[PEER] = ContactRecord(id = PEER, isContact = true) }
    private val crypto: CryptoManager = mock<CryptoManager>().also {
        whenever(it.judgePeerDevices(any(), any())).thenAnswer { call ->
            me.core.judgePeerDevices(call.getArgument(0), call.getArgument(1))
        }
    }
    private val notices = SecurityNotices(contacts, ChatPresence())
    private val vetting = PeerDeviceVetting(crypto, contacts, notices)

    private fun notice() = SecurityNotice.of(contacts.rows.getValue(PEER).securityNotice)

    /**
     * A contact with a trusted phone links a Mac (vouched) and the server adds a device of its own:
     * the alarm names the server's device and only it. Mutation: keep `Vouched` too in `unvouched`
     * — the Mac is named.
     */
    @Test
    fun onlyTheDeviceNobodyVouchedForRaisesTheAlarm() = runTest {
        val (phone, mac, rogue) = Triple(device(), device(), device())
        val bundles = listOf(phone.served(), mac.served(vouch(phone, mac)), rogue.served())
        val offered = PeerDeviceVetting.offered(bundles)

        val verdicts = me.core.judgePeerDevices(listOf(phone.id), offered)

        assertEquals(listOf(PeerDeviceVerdict.Pinned, PeerDeviceVerdict.Vouched(phone.id), PeerDeviceVerdict.Unvouched), verdicts)
        assertEquals(listOf(rogue.id), PeerDeviceVetting.unvouched(listOf(phone.id), offered, verdicts))
        vetting.vet(PEER, bundles, trustedBefore = listOf(phone.id))
        assertEquals(SecurityNotice.UNVOUCHED_DEVICE, notice())
    }

    /** An honest link is silent. Mutation: drop the certificate in `offered` — the Mac alarms. */
    @Test
    fun aDeviceLinkedByATrustedOneIsSilent() = runTest {
        val (phone, mac) = device() to device()
        vetting.vet(PEER, listOf(phone.served(), mac.served(vouch(phone, mac))), trustedBefore = listOf(phone.id))
        assertEquals(SecurityNotice.NONE, notice())
    }

    /** Nothing of theirs trusted yet: trusted on first use, no alarm. */
    @Test
    fun anAccountSeenForTheFirstTimeIsSilent() = runTest {
        vetting.vet(PEER, listOf(device().served(), device().served()), trustedBefore = emptyList())
        assertEquals(SecurityNotice.NONE, notice())
    }

    /**
     * A device the user was already warned about and kept is trusted by the next answer: no second
     * alarm. Mutation: drop `!in known` — it alarms again on every fetch.
     */
    @Test
    fun aDeviceAlreadyTrustedIsNotAlarmedAgain() {
        val d = device()
        val offered = PeerDeviceVetting.offered(listOf(d.served()))
        assertEquals(emptyList<String>(), PeerDeviceVetting.unvouched(listOf(d.id), offered, listOf(PeerDeviceVerdict.Unvouched)))
        assertEquals(listOf(d.id), PeerDeviceVetting.unvouched(emptyList(), offered, listOf(PeerDeviceVerdict.Unvouched)))
    }

    /**
     * A warning exists to protect a conversation: no row, no alarm (our own account has none).
     * Mutation: drop the row check — the app-wide notice is announced for nobody.
     */
    @Test
    fun noContactRowNoAlarm() = runTest {
        val heard = mutableListOf<SecurityNotices.Announcement>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { notices.announced.toList(heard) }
        contacts.rows.clear()
        val (phone, rogue) = device() to device()
        vetting.vet(PEER, listOf(phone.served(), rogue.served()), trustedBefore = listOf(phone.id))
        assertEquals(emptyList<SecurityNotices.Announcement>(), heard)
        assertTrue(contacts.rows.isEmpty())
    }

    /** A bundle without the hybrid key or its binding is not offered; the certificate is carried as served. */
    @Test
    fun whatTheCoreIsHanded() {
        val d = device()
        assertTrue(PeerDeviceVetting.offered(listOf(d.served().copy(bundle = d.bundle.copy(hybridIdentitySignature = null)))).isEmpty())
        assertTrue(PeerDeviceVetting.offered(listOf(d.served().copy(bundle = d.bundle.copy(hybridIdentityKey = ByteArray(0))))).isEmpty())
        val offered: OfferedPeerDevice = PeerDeviceVetting.offered(listOf(d.served(byteArrayOf(1, 2)))).single()
        assertEquals(d.id, offered.deviceId)
        assertArrayEquals(byteArrayOf(1, 2), offered.certificate)
        assertArrayEquals(d.bundle.verifyingKey, offered.keys.verifyingKey)
        assertArrayEquals(d.bundle.identityPublic, offered.keys.identityPublic)
    }

    private companion object {
        const val PEER = CoreTestDevice.ACCOUNT
    }
}
