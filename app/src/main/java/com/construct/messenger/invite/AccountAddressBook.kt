package com.construct.messenger.invite

import android.util.Log
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.db.UserDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The address this device holds for an account: ours from [KeystoreManager], a contact's from
 * their row ([com.construct.messenger.data.local.db.UserEntity.accountAddress]). `null` when
 * none is known, and the account id is used instead ([AccountAddress.recipientField]).
 */
@Singleton
class AccountAddressBook @Inject constructor(
    private val userDao: UserDao,
    private val keystoreManager: KeystoreManager,
) {
    suspend fun of(accountId: String): ByteArray? {
        if (accountId == keystoreManager.getUserId()) return keystoreManager.getOwnAccountAddress()
        return userDao.getById(accountId)?.accountAddress?.takeIf { it.size == AccountAddress.LENGTH }
    }

    /**
     * Applies a contact's address by [AccountAddressPin.decide], onto a row that already exists: a
     * sender must not be able to put a contact in our store by sending to us.
     *
     * A conflict is a security event. Android has no surface for one yet — a key change is not
     * shown either — so it is logged and the pinned address stands; the surface is its own task.
     */
    suspend fun pin(accountId: String, address: ByteArray, source: AccountAddressSource): AccountAddressPin {
        if (address.size != AccountAddress.LENGTH) return AccountAddressPin.UNCHANGED
        val row = userDao.getById(accountId) ?: return AccountAddressPin.UNCHANGED
        val outcome = AccountAddressPin.decide(row.accountAddress, address, source)
        if (outcome == AccountAddressPin.PINNED || outcome == AccountAddressPin.CONFLICT_REPLACED) {
            userDao.upsert(row.copy(accountAddress = address))
        }
        if (outcome.isSecurityEvent) {
            Log.w(TAG, "ADDRESS: ${accountId.take(8)}… named a different account address ($source) — $outcome")
        }
        return outcome
    }

    private companion object {
        const val TAG = "AccountAddressBook"
    }
}
