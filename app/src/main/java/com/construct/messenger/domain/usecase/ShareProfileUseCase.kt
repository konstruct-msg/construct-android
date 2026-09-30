package com.construct.messenger.domain.usecase

import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.db.UserDao
import com.construct.messenger.data.repository.AccountRepository
import com.construct.messenger.util.DisplayNameGenerator
import com.construct.messenger.util.ProfileShare
import javax.inject.Inject

/**
 * "Share my profile" / "Stop sharing profile" on a contact's card. **Canon:** iOS
 * `UserProfileView.handleShareToggle`.
 *
 * Sharing sends the name this account goes by, end to end, to every device of theirs; it is
 * marked shared only once a device took it. Stopping sends nothing — it is the mark alone, as on
 * iOS: what they already received stays theirs. Android has no avatar to send yet.
 */
class ShareProfileUseCase @Inject constructor(
    private val keystoreManager: KeystoreManager,
    private val account: AccountRepository,
    private val sendMessage: SendMessageUseCase,
    private val userDao: UserDao,
) {
    suspend fun share(contactId: String): Boolean {
        val myId = keystoreManager.getUserId() ?: return false
        val name = account.account.value?.displayName?.takeIf { it.isNotBlank() }
            ?: DisplayNameGenerator.generate(myId)
        val sent = sendMessage.shareProfile(
            contactId,
            ProfileShare(displayName = name, timestampSec = System.currentTimeMillis() / 1000),
        )
        if (sent) userDao.setAmSharingWith(contactId, true)
        return sent
    }

    suspend fun stop(contactId: String) {
        userDao.setAmSharingWith(contactId, false)
    }
}
