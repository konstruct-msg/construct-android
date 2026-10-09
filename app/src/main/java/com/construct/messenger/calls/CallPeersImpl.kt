package com.construct.messenger.calls

import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.ContactStore
import com.construct.messenger.data.local.resolvedName
import javax.inject.Inject
import javax.inject.Singleton

/** [CallPeers] from the account and the local contact list. */
@Singleton
class CallPeersImpl @Inject constructor(
    private val keystore: KeystoreManager,
    private val contacts: ContactStore,
) : CallPeers {
    override fun myUserId(): String? = keystore.getUserId()

    override fun myDeviceId(): String? = keystore.getDeviceId()

    /** Client-side, as on iOS (`ContactPolicy.isCallableContact`): under sealed sender the server cannot tell who calls. */
    override suspend fun isCallable(userId: String): Boolean =
        contacts.get(userId).let { CallSignalInbox.admits(it?.isContact == true, it?.isBlocked == true) }

    override suspend fun name(userId: String): String = contacts.get(userId).resolvedName(userId)
}
