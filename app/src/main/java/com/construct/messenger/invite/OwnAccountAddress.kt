package com.construct.messenger.invite

import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.recovery.RecoveryRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * Our address as it may leave the device, judged by `GetRecoveryStatus`' fingerprint
 * ([AccountAddress.verdict]).
 *
 * A device can hold the address of an account it used to be. Handed to a contact in a card, that
 * key is pinned there (the first card wins), and every later message to us is addressed to the old
 * account, accepted by the server and written to its mailbox (iOS 2026-10-03: a Mac that had been
 * its own account before it joined another one). A foreign key is deleted here.
 *
 * **Canon:** iOS `AccountAddress.confirmedOwn` (construct-messenger `029dc156`); construct-docs
 * TODO 109.
 */
@Singleton
class OwnAccountAddress @Inject constructor(
    private val keystoreManager: KeystoreManager,
    private val recovery: RecoveryRepository,
) {
    /** The key the server last confirmed. The stored one changes only with the phrase. */
    @Volatile private var confirmed: ByteArray? = null

    /**
     * For a contact card: only a confirmed address. An unreachable server or one with no key sends
     * none, and the contact keeps writing to our account id, which is never wrong.
     */
    suspend fun forCard(): ByteArray? = judge().takeIf { it.second == AccountAddress.OwnVerdict.CONFIRMED }?.first

    /**
     * Before an invite: a foreign address is deleted, so [InviteGenerator] refuses. An unconfirmed
     * one is kept — the redeemer's `AcceptInvite` is judged by the server against the account's
     * key, and a QR must not need our server to be reachable to show.
     */
    suspend fun checkBeforeInvite() {
        judge()
    }

    private suspend fun judge(): Pair<ByteArray?, AccountAddress.OwnVerdict> {
        val stored = keystoreManager.getOwnAccountAddress() ?: return null to AccountAddress.OwnVerdict.ABSENT
        confirmed?.let { if (it.contentEquals(stored)) return stored to AccountAddress.OwnVerdict.CONFIRMED }
        val fingerprint = try {
            recovery.status().fingerprint
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "own address not confirmed (${e.javaClass.simpleName}) — left out")
            return stored to AccountAddress.OwnVerdict.UNCONFIRMED
        }
        val verdict = AccountAddress.verdict(stored, fingerprint)
        when (verdict) {
            AccountAddress.OwnVerdict.CONFIRMED -> confirmed = stored
            AccountAddress.OwnVerdict.FOREIGN -> {
                Log.e(TAG, "ADDRESS: the stored own address is not this account's — deleted")
                keystoreManager.deleteOwnAccountAddress()
            }
            AccountAddress.OwnVerdict.ABSENT, AccountAddress.OwnVerdict.UNCONFIRMED -> Unit
        }
        return stored to verdict
    }

    private companion object {
        const val TAG = "OwnAccountAddress"
    }
}
