package com.construct.messenger.security

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.local.ChatPresence
import com.construct.messenger.data.local.ContactRecord
import com.construct.messenger.data.local.ContactStore
import com.construct.messenger.data.local.FakeContactStore
import com.construct.messenger.data.local.FakeJudgedDeviceStore
import com.construct.messenger.data.local.FakePeerDeviceStore
import com.construct.messenger.data.local.PeerDeviceRecord
import com.construct.messenger.data.local.PeerDeviceRegistry
import com.construct.messenger.data.model.SecurityNotice
import com.construct.messenger.service.SessionManager.PeerBundle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import uniffi.construct_core.PeerDeviceVerdict
import uniffi.construct_core.deriveDeviceId

/**
 * `todo/268`: a device seen is not a device judged. Real core, real keys, and the order
 * `SessionManager.discoverPeerBundles` uses (`SessionManagerTest` holds it to that order). Each way
 * a device can reach the registry without a verdict must still raise the alarm on the next answer.
 * **Canon:** iOS `DeviceCertificateLinkTests`.
 */
class PeerDeviceJudgementTest {
    private val me = CoreTestDevice.create()
    private val phone = CoreTestDevice.create()
    private val mac = CoreTestDevice.create()
    private val rogue = CoreTestDevice.create()
    private val contacts = FakeContactStore().also { it.rows[PEER] = ContactRecord(id = PEER, isContact = true) }
    private val rows = FakePeerDeviceStore()
    private val judged = FakeJudgedDeviceStore()
    private var coreUp = true
    private val crypto: CryptoManager = mock<CryptoManager>().also {
        whenever(it.deriveDeviceIdFromIdentity(any())).thenAnswer { c -> deriveDeviceId(c.getArgument(0)) }
        whenever(it.judgePeerDevices(any(), any())).thenAnswer { c ->
            if (coreUp) me.core.judgePeerDevices(c.getArgument(0), c.getArgument(1)) else null
        }
    }
    private val registry = PeerDeviceRegistry(rows, contacts, crypto, judged)
    private val vetting = vettingOn(contacts)

    private fun vettingOn(store: ContactStore) =
        PeerDeviceVetting(crypto, store, SecurityNotices(store, ChatPresence()), registry)

    /** What `discoverPeerBundles` does with a full answer. */
    private suspend fun answer(vararg bundles: PeerBundle, active: List<String> = emptyList(), with: PeerDeviceVetting = vetting) {
        val trustedBefore = registry.trusted(PEER)
        registry.recordAll(PEER, bundles.map { PeerDeviceRegistry.PeerDevice(it.deviceId, it.identityPublic) }, active)
        bundles.forEach { contacts.rememberIdentity(PEER, it.identityPublic) }
        if (trustedBefore != null) with.vet(PEER, bundles.toList(), trustedBefore)
    }

    private fun notice() = SecurityNotice.of(contacts.rows.getValue(PEER).securityNotice)

    private fun assertAlarm() = assertEquals(SecurityNotice.UNVOUCHED_DEVICE, notice())

    private fun assertNoAlarm() = assertEquals(SecurityNotice.NONE, notice())

    private fun CoreTestDevice.withoutHybridKey() = served().copy(bundle = bundle.copy(hybridIdentityKey = null))

    /** The control: the path that always worked. */
    @Test
    fun aDeviceFirstSeenInAFullAnswerRaisesTheAlarm() = runTest {
        answer(phone.served())
        answer(phone.served(), rogue.served())
        assertAlarm()
    }

    /** Seen on the 263 stand. Mutation: `trusted` reads every row again — it is pinned, no alarm. */
    @Test
    fun aDeviceFirstServedWithoutItsHybridKeyRaisesTheAlarmOnceItHasOne() = runTest {
        answer(phone.served())
        answer(phone.served(), rogue.withoutHybridKey())
        assertNoAlarm()
        answer(phone.served(), rogue.served())
        assertAlarm()
    }

    /** `SessionManager.recordOpenedDevice`, `fetchPeerBundleData` and `fetchIdentityKey` all do this. */
    @Test
    fun aDeviceFirstSeenOutsideAFullAnswerRaisesTheAlarm() = runTest {
        answer(phone.served())
        registry.record(PEER, rogue.id, rogue.bundle.identityPublic)
        answer(phone.served(), rogue.served())
        assertAlarm()
    }

    /** Mutation: mark before the core's answer — the device is settled with no verdict. */
    @Test
    fun aDeviceAnsweredWhileTheCoreWasDownRaisesTheAlarmOnceItIsUp() = runTest {
        answer(phone.served())
        coreUp = false
        answer(phone.served(), rogue.served())
        assertNoAlarm()
        coreUp = true
        answer(phone.served(), rogue.served())
        assertAlarm()
    }

    /** The honest version of the same: a linked device that wrote first is vouched for, and stays silent. */
    @Test
    fun aLinkedDeviceThatWroteFirstIsSilentAndJudged() = runTest {
        answer(phone.served())
        registry.record(PEER, mac.id, mac.bundle.identityPublic)
        answer(phone.served(), mac.served(phone.vouch(mac)))
        assertNoAlarm()
        // A set: the rows tie on first-seen and the ids are random, so the order is not stable.
        assertEquals(setOf(phone.id, mac.id), registry.trusted(PEER)!!.toSet())
    }

    /** The alarm is once. Mutation: `markJudged` writes nothing — it fires again after being read. */
    @Test
    fun aDeviceAlarmedAboutIsNotAlarmedAboutAgain() = runTest {
        answer(phone.served())
        answer(phone.served(), rogue.served())
        assertAlarm()
        contacts.setSecurityNotice(PEER, SecurityNotice.NONE.code)
        answer(phone.served(), rogue.served())
        assertNoAlarm()
    }

    /** An alarm that could not be raised is owed. Mutation: mark before raising — it is lost. */
    @Test
    fun anAlarmThatCouldNotBeRaisedIsRaisedAgain() = runTest {
        answer(phone.served())
        val broken = mock<ContactStore> {
            onBlocking { get(any()) } doAnswer { ContactRecord(id = PEER, isContact = true) }
            onBlocking { setSecurityNotice(any(), any()) } doAnswer { throw java.io.IOException("store") }
        }
        runCatching { answer(phone.served(), rogue.served(), with = vettingOn(broken)) }
        assertFalse(rogue.id in judged.judged)
        answer(phone.served(), rogue.served())
        assertAlarm()
    }

    /**
     * Every judged device retired, and what is left has no verdict: this must not read as a first
     * meeting, or a server passes any device by showing it once without its keys.
     * Mutation: drop `formerlyTrusted` from `trusted` — first meeting, no alarm.
     */
    @Test
    fun aDeviceLeftAfterEveryJudgedOneRetiredIsNoFirstMeeting() = runTest {
        answer(phone.served())
        answer(rogue.withoutHybridKey(), active = listOf(rogue.id))
        assertEquals(listOf(rogue.id), rows.forAccount(PEER).map { it.deviceId })
        answer(rogue.served(), active = listOf(rogue.id))
        assertAlarm()
    }

    /** The same with no row left at all, and the contact's key overwritten by the answer that emptied it. */
    @Test
    fun aKeyTheEmptyingAnswerBroughtIsNoRoot() = runTest {
        answer(phone.served())
        answer(rogue.withoutHybridKey(), active = listOf(mac.id))
        assertEquals(emptyList<PeerDeviceRecord>(), rows.forAccount(PEER))
        answer(rogue.served())
        assertAlarm()
    }

    /** Mutation: `retire` keeps the mark — it comes back pinned. */
    @Test
    fun aRetiredDeviceIsJudgedAgainIfItComesBack() = runTest {
        answer(phone.served(), mac.served(phone.vouch(mac)))
        answer(phone.served(), active = listOf(phone.id))
        answer(phone.served(), mac.served(), active = listOf(phone.id, mac.id))
        assertAlarm()
    }

    /** A verified invite's device is the root. Mutation: no mark in `pinInvited` — a first meeting, no alarm. */
    @Test
    fun anInvitesDeviceIsWhatTheOthersAreJudgedAgainst() = runTest {
        registry.pinInvited(PEER, phone.bundle.identityPublic)
        answer(phone.served(), rogue.served())
        assertAlarm()
    }

    /** A contact with a key and no row yet: its key's device. Mutation: drop `resolveDeviceId` from `trusted`. */
    @Test
    fun anAccountWithNoRowsKeepsItsPinnedDevice() = runTest {
        contacts.rememberIdentity(PEER, phone.bundle.identityPublic)
        assertEquals(listOf(phone.id), registry.trusted(PEER))
        answer(phone.served(), rogue.served())
        assertAlarm()
    }

    /** The same contact after a send wrote its row. Mutation: no mark in `resolveDeviceId` — a first meeting. */
    @Test
    fun aPinnedKeyResolvedBeforeTheFirstAnswerStaysTheRoot() = runTest {
        contacts.rememberIdentity(PEER, phone.bundle.identityPublic)
        registry.resolveDeviceId(PEER)
        answer(phone.served(), rogue.served())
        assertAlarm()
    }

    @Test
    fun nothingTrustedAndNothingBeforeIsAFirstMeeting() = runTest {
        answer(phone.served(), mac.served())
        assertNoAlarm()
        assertEquals(setOf(phone.id, mac.id), registry.trusted(PEER)!!.toSet())
    }

    /** Null is not empty. Mutation: return an empty list — a first meeting, and the rogue is accepted. */
    @Test
    fun aFailedReadJudgesNothing() = runTest {
        answer(phone.served())
        judged.failing = true
        assertNull(registry.trusted(PEER))
        judged.failing = false
    }

    /** Delivery does not depend on the marks. Mutation: let the seed's failure out of `record` — the row is lost. */
    @Test
    fun aDeviceIsRecordedEvenWhenTheMarksFail() = runTest {
        judged.failing = true
        registry.record(PEER, phone.id, phone.bundle.identityPublic)
        assertEquals(listOf(phone.id), rows.forAccount(PEER).map { it.deviceId })
    }

    /** An update must not turn every contact into a first meeting. Mutation: the seed marks nothing. */
    @Test
    fun theSeedMarksTheRowsThatWereThere() = runTest {
        rows.record(PeerDeviceRecord(phone.id, PEER, phone.bundle.identityPublic, 1))
        answer(phone.served(), rogue.served())
        assertAlarm()
    }

    /** Mutation: `seedIfDue` always runs — every row reads as judged on the next read. */
    @Test
    fun theSeedRunsOnce() = runTest {
        answer(phone.served())
        registry.record(PEER, rogue.id, rogue.bundle.identityPublic)
        assertEquals(listOf(phone.id), registry.trusted(PEER))
    }

    /** The first thing after the update may be a write. Mutation: no seed in `record` — the new row reads as old. */
    @Test
    fun aRowWrittenBeforeTheFirstReadIsNotTakenForAnOldOne() = runTest {
        rows.record(PeerDeviceRecord(phone.id, PEER, phone.bundle.identityPublic, 1))
        registry.record(PEER, rogue.id, rogue.bundle.identityPublic)
        assertEquals(listOf(phone.id), registry.trusted(PEER))
    }

    /** Mutation: `settled` keeps them — a device presenting another key reads as pinned from then on. */
    @Test
    fun keyChangedAndInvalidSettleNothing() {
        val offered = PeerDeviceVetting.offered(listOf(phone.served(), mac.served(), rogue.served()))
        assertEquals(
            listOf(rogue.id),
            PeerDeviceVetting.settled(offered, listOf(PeerDeviceVerdict.KeyChanged, PeerDeviceVerdict.Invalid, PeerDeviceVerdict.Unvouched)),
        )
    }

    private companion object {
        const val PEER = CoreTestDevice.ACCOUNT
    }
}
