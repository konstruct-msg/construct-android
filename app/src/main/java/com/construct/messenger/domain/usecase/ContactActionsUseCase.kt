package com.construct.messenger.domain.usecase

import com.construct.messenger.diagnostics.Log
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.PeerDeviceRegistry
import com.construct.messenger.data.local.SessionStateStore
import com.construct.messenger.data.local.db.ChatDao
import com.construct.messenger.data.local.db.MessageDao
import com.construct.messenger.data.local.db.UserDao
import com.construct.messenger.util.ConversationId
import javax.inject.Inject
import javax.inject.Singleton
import shared.proto.sentinel.v1.SentinelServiceOuterClass.ReportSpamRequest
import shared.proto.sentinel.v1.SentinelServiceOuterClass.SpamCategory
import shared.proto.services.v1.UserServiceOuterClass.BlockUserRequest
import shared.proto.services.v1.UserServiceOuterClass.UnblockUserRequest
import uniffi.construct_core.CfeSecureStoreSlot

/**
 * What the user can do to a contact: remove, block, report. **Canon:** iOS `UserProfileView`
 * danger section, `ChatsViewModel.pruneContact`.
 */
@Singleton
class ContactActionsUseCase @Inject constructor(
    private val keystoreManager: KeystoreManager,
    private val userDao: UserDao,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val registry: PeerDeviceRegistry,
    private val cryptoManager: CryptoManager,
    private val sessionStateStore: SessionStateStore,
    private val grpcClient: GrpcClient,
) {
    /**
     * Remove the contact, its chat and its messages from this device, and forget every session
     * with their devices. Local only: nothing is sent.
     *
     * Their next message is answered by the core with a DECRYPTION_ERROR (it no longer holds the
     * state), they open a new one, and its handshake brings the contact back
     * (`decisions/sessions-renew-by-sending.md`). A redelivered message already processed is
     * dropped by the ack store before it could. Refusing them is [setBlocked]'s job, which the
     * server enforces before delivery.
     */
    suspend fun delete(userId: String) {
        // Devices first: resolving can read the row about to go.
        val devices = devicesOf(userId)
        keystoreManager.getUserId()?.let { myId ->
            val chatId = ConversationId.direct(myId, userId)
            messageDao.deleteChat(chatId)
            chatDao.delete(chatId)
        }
        userDao.delete(userId)
        for (device in devices) {
            runCatching { cryptoManager.forgetContactState(device) }
                .onFailure { Log.w(TAG, "forget ${device.take(8)}… failed", it) }
            // The core keeps no record of it now; the persisted blob would bring it back on the
            // next launch.
            sessionStateStore.saveSecureStore(CfeSecureStoreSlot.Session(device), ByteArray(0))
        }
        Log.i(TAG, "contact ${userId.take(8)}… removed — forgot ${devices.size} device session(s)")
    }

    /**
     * Block or unblock. The local flag stands whatever the server says: it is what this device
     * shows, and the server row is what refuses delivery — a failed sync is logged, not undone.
     */
    suspend fun setBlocked(userId: String, blocked: Boolean) {
        userDao.getById(userId)?.let { userDao.upsert(it.copy(isBlocked = blocked)) }
        val myId = keystoreManager.getUserId().orEmpty()
        runCatching {
            if (blocked) {
                grpcClient.user.blockUser(
                    BlockUserRequest.newBuilder().setBlockerUserId(myId).setUserId(userId).build(),
                ).success
            } else {
                grpcClient.user.unblockUser(
                    UnblockUserRequest.newBuilder().setBlockerUserId(myId).setUserId(userId).build(),
                ).success
            }
        }.onFailure { Log.w(TAG, "block sync (${if (blocked) "block" else "unblock"}) failed", it) }
    }

    /**
     * Report each of their devices this device knows, then block. Report-and-block is the safe
     * default: nobody should keep receiving from someone they reported. The report names a device
     * and a category — never a message. Returns whether any report was accepted.
     */
    suspend fun reportSpam(userId: String): Boolean {
        var accepted = false
        for (device in devicesOf(userId)) {
            runCatching {
                grpcClient.sentinel.reportSpam(
                    ReportSpamRequest.newBuilder()
                        .setReportedDeviceId(device)
                        .setCategory(SpamCategory.SPAM_CATEGORY_UNWANTED)
                        .build(),
                ).accepted
            }.onSuccess { accepted = accepted || it }
                .onFailure { Log.w(TAG, "report ${device.take(8)}… failed", it) }
        }
        setBlocked(userId, blocked = true)
        return accepted
    }

    private suspend fun devicesOf(userId: String): List<String> {
        val known = registry.knownDevices(userId).map { it.deviceId }
        return known.ifEmpty { listOfNotNull(registry.resolveDeviceId(userId)) }
    }

    private companion object {
        const val TAG = "ContactActions"
    }
}
