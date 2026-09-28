package com.construct.messenger.invite

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
}
