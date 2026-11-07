package com.construct.messenger.data.local

import com.construct.messenger.diagnostics.Log
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.model.IdentityIds
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Durable account -> device translation above the crypto seam.
 *
 * The registry is a server answer cache, not a trust root. A device is accepted only when its
 * advertised id equals deriveDeviceId(identityPublic); the core still receives device ids only.
 *
 * A device the account did not have is recorded and nothing more. Whether it warrants an alarm is
 * decided from its certificate by `PeerDeviceVetting`, which reads [trusted] before the answer is
 * recorded here (`decisions/devices-of-an-account-sign-each-other.md`). A row says a device was
 * seen; only [trusted] says the core gave it a verdict (`todo/268`).
 */
@Singleton
class PeerDeviceRegistry @Inject constructor(
    private val store: PeerDeviceStore,
    private val contacts: ContactStore,
    private val cryptoManager: CryptoManager,
    private val judged: JudgedDeviceStore,
) {
    private val seedLock = Mutex()

    suspend fun record(
        accountId: String,
        deviceId: String,
        identityPublic: ByteArray,
        activeDeviceIds: List<String>? = null,
    ) {
        if (!accepts(accountId, deviceId, identityPublic)) return
        seedBeforeWriting()
        val existing = store.forDevice(deviceId)
        if (existing != null && existing.accountId != accountId) {
            Log.e(TAG, "refusing to rehome device ${deviceId.take(8)}…")
            return
        }
        // A known device keeps its row: its id is the hash of its key, so nothing on it can change.
        store.record(PeerDeviceRecord(deviceId, accountId, identityPublic.copyOf(), System.currentTimeMillis()))
        retain(accountId, activeDeviceIds.orEmpty())
    }

    suspend fun recordAll(
        accountId: String,
        devices: List<PeerDevice>,
        activeDeviceIds: List<String> = emptyList(),
    ) {
        devices.forEach { record(accountId, it.deviceId, it.identityPublic) }
        retain(accountId, activeDeviceIds)
    }

    private suspend fun retain(accountId: String, activeDeviceIds: List<String>) {
        val active = activeDeviceIds.filter(IdentityIds::isCryptoDeviceId)
        if (active.isEmpty()) return
        val retired = store.forAccount(accountId).map { it.deviceId } - active.toSet()
        store.retain(accountId, active)
        quietly("retire") { judged.retire(accountId, retired) }
    }

    /**
     * The devices of [accountId] the core may treat as trusted, or null when that cannot be read.
     *
     * **Null is not an empty list.** The core reads no trusted device as a first meeting and accepts
     * everything offered, so a failed read must judge nothing rather than pass a rogue device.
     *
     * **For the same reason this is empty only for an account that never had a verdict.** With
     * every judged device gone from the active set, what is left is unjudged, and a server could
     * use a first meeting to pass it. The devices that left go to the core instead: nothing offered
     * matches them, and a certificate from one cannot be checked once it is gone, so the alarm fires.
     */
    suspend fun trusted(accountId: String): List<String>? = try {
        seedIfDue()
        // An account with no rows keeps the answer it always had: the device its pinned key derives to.
        resolveDeviceId(accountId)
        val marked = judged.all()
        store.forAccount(accountId).map { it.deviceId }.filter { it in marked }
            .ifEmpty { judged.formerlyTrusted(accountId).toList() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "cannot read the judged devices of ${accountId.take(8)}…: ${e.message}")
        null
    }

    suspend fun markJudged(deviceIds: List<String>) {
        quietly("mark") { judged.add(deviceIds) }
    }

    /** The device of a verified invite: its key is the root the rest of the account is judged against. */
    suspend fun pinInvited(accountId: String, identityPublic: ByteArray) {
        val deviceId = cryptoManager.deriveDeviceIdFromIdentity(identityPublic)
        record(accountId, deviceId, identityPublic)
        if (store.forDevice(deviceId)?.accountId == accountId) markJudged(listOf(deviceId))
    }

    /**
     * A mark is never worth a message or a contact. Not marked is judged again on the next answer,
     * the safe direction; a retired device that keeps its mark reads as pinned if it comes back.
     */
    private suspend fun quietly(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "judged devices: $what failed: ${e.message}")
        }
    }

    /**
     * Once per store: the rows from before this distinction count as judged. Left unmarked, an
     * account would read as a first meeting after the update and accept anything offered with it.
     * Runs before the first read and the first write, so no new row can be taken for an old one.
     */
    private suspend fun seedIfDue() = seedLock.withLock {
        if (judged.isSeeded()) return@withLock
        judged.seed(store.all().map { it.deviceId })
    }

    /** A row still has to be written: delivery needs it. [trusted] retries the seed, and refuses if it fails. */
    private suspend fun seedBeforeWriting() = quietly("seed") { seedIfDue() }

    suspend fun knownDevices(accountId: String): List<PeerDeviceRecord> =
        store.forAccount(accountId)

    suspend fun resolveDeviceId(accountOrDeviceId: String): String? {
        if (IdentityIds.isCryptoDeviceId(accountOrDeviceId)) return accountOrDeviceId
        store.forAccount(accountOrDeviceId).firstOrNull()?.let { return it.deviceId }
        val legacyIdentity = contacts.get(accountOrDeviceId)?.identityPublic ?: return null
        if (legacyIdentity.isEmpty()) return null
        val derived = cryptoManager.deriveDeviceIdFromIdentity(legacyIdentity)
        if (!IdentityIds.isCryptoDeviceId(derived)) return null
        record(accountOrDeviceId, derived, legacyIdentity)
        // No row and nothing judged before: nothing else has written this key, it is the one an
        // invite pinned. With judged devices gone, the key may be one the last answer brought.
        quietly("mark") {
            if (judged.formerlyTrusted(accountOrDeviceId).isEmpty()) judged.add(listOf(derived))
        }
        return derived
    }

    suspend fun accountIdForDevice(deviceId: String): String? =
        store.forDevice(deviceId)?.accountId

    suspend fun identityForDevice(deviceId: String): ByteArray? =
        store.forDevice(deviceId)?.identityPublic

    private fun accepts(accountId: String, deviceId: String, identityPublic: ByteArray): Boolean {
        if (accountId.isEmpty() || !IdentityIds.isCryptoDeviceId(deviceId) || identityPublic.isEmpty()) {
            return false
        }
        val derived = cryptoManager.deriveDeviceIdFromIdentity(identityPublic)
        if (derived != deviceId) {
            Log.e(TAG, "rejecting mismatched device/key pair ${deviceId.take(8)}…")
            return false
        }
        return true
    }

    data class PeerDevice(
        val deviceId: String,
        val identityPublic: ByteArray,
    )

    private companion object {
        const val TAG = "PeerDeviceRegistry"
    }
}
