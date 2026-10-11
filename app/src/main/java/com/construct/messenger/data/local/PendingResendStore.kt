package com.construct.messenger.data.local

import com.construct.messenger.data.local.db.PendingResendDao
import com.construct.messenger.data.local.db.PendingResendEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.construct_core.LocalPendingResend
import uniffi.construct_core.LocalStore

/**
 * A message the core asked us to send again to one device. [messageId] is the id the reader named
 * — for a sealed copy the server's; [accountId] is the device's account.
 */
data class PendingResend(
    val messageId: String,
    val deviceId: String,
    val accountId: String,
    val createdAtMs: Long,
    val attempts: Int = 0,
)

/**
 * Where [com.construct.messenger.service.PendingResends] keeps requests until they are sent
 * (TODO 136). [PendingResendStoreContract] holds Room's and the core's to one behaviour.
 */
interface PendingResendStore {
    /** A second request for the same copy keeps the first row, its age and its attempts. */
    suspend fun queue(resend: PendingResend)

    /** Oldest first. */
    suspend fun all(): List<PendingResend>

    suspend fun delete(messageId: String, deviceId: String)

    suspend fun countAttempt(messageId: String, deviceId: String)

    /** Forgets requests made before [cutoffMs]; returns how many. */
    suspend fun forgetBefore(cutoffMs: Long): Int
}

/** Built by `DatabaseModule`, which provides no `PendingResendDao`. */
class RoomPendingResendStore(private val dao: PendingResendDao) : PendingResendStore {
    override suspend fun queue(resend: PendingResend) = with(resend) {
        dao.insert(PendingResendEntity(messageId, deviceId, accountId, createdAtMs, attempts))
    }

    override suspend fun all(): List<PendingResend> =
        dao.all().map { PendingResend(it.messageId, it.deviceId, it.accountId, it.createdAtMs, it.attempts) }

    override suspend fun delete(messageId: String, deviceId: String) = dao.delete(messageId, deviceId)

    override suspend fun countAttempt(messageId: String, deviceId: String) = dao.countAttempt(messageId, deviceId)

    override suspend fun forgetBefore(cutoffMs: Long): Int = dao.pruneOlderThan(cutoffMs)
}

/** On the core's `LocalStore`, which keeps the same table. Not wired into the app yet. */
class CorePendingResendStore(private val store: LocalStore) : PendingResendStore {
    override suspend fun queue(resend: PendingResend) {
        withContext(Dispatchers.IO) {
            with(resend) { store.queueResend(LocalPendingResend(messageId, deviceId, accountId, createdAtMs, attempts.toUInt())) }
        }
    }

    override suspend fun all(): List<PendingResend> = withContext(Dispatchers.IO) {
        store.pendingResends().map { PendingResend(it.messageId, it.deviceId, it.accountId, it.createdAt, it.attempts.toInt()) }
    }

    override suspend fun delete(messageId: String, deviceId: String) {
        withContext(Dispatchers.IO) { store.deleteResend(messageId, deviceId) }
    }

    override suspend fun countAttempt(messageId: String, deviceId: String) {
        withContext(Dispatchers.IO) { store.countResendAttempt(messageId, deviceId) }
    }

    override suspend fun forgetBefore(cutoffMs: Long): Int =
        withContext(Dispatchers.IO) { store.forgetResendsBefore(cutoffMs).toInt() }
}
