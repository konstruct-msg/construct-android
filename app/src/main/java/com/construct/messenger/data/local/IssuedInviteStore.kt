package com.construct.messenger.data.local

import com.construct.messenger.data.local.db.IssuedInviteDao
import com.construct.messenger.data.local.db.IssuedInviteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import uniffi.construct_core.LocalIssuedInvite
import uniffi.construct_core.LocalStoreTable

/** An invite capability this device minted; [sitting] groups the codes of one QR showing. */
data class IssuedInviteRecord(
    val jti: String,
    val kind: String,
    val issuedAtEpochSec: Long,
    val ttlSeconds: Int,
    val sitting: String? = null,
)

/**
 * The invites this device minted, until revoked (TODO 136). [IssuedInviteStoreContract] holds
 * Room's and the core's to one behaviour.
 */
interface IssuedInviteStore {
    /** Newest first; equal times by [IssuedInviteRecord.jti]. */
    fun observeAll(): Flow<List<IssuedInviteRecord>>

    /** Writes [invite] over the row of its jti. */
    suspend fun record(invite: IssuedInviteRecord)

    suspend fun delete(jti: String)
}

/** Built by `LocalStores`; nothing provides a `IssuedInviteDao`. */
class RoomIssuedInviteStore(private val dao: IssuedInviteDao) : IssuedInviteStore {
    override fun observeAll(): Flow<List<IssuedInviteRecord>> = dao.observeAll().map { rows ->
        rows.map { IssuedInviteRecord(it.jti, it.kind, it.issuedAtEpochSec, it.ttlSeconds, it.sitting) }
    }

    override suspend fun record(invite: IssuedInviteRecord) = with(invite) {
        dao.upsert(IssuedInviteEntity(jti, kind, issuedAtEpochSec, ttlSeconds, sitting))
    }

    override suspend fun delete(jti: String) = dao.delete(jti)
}

/**
 * On the core's `LocalStore`, which keeps the same table. The core's times are ms, like every time
 * in its store; Android's are the token's seconds.
 */
class CoreIssuedInviteStore(private val feed: LocalStoreFeed) : IssuedInviteStore {
    override fun observeAll(): Flow<List<IssuedInviteRecord>> = feed.watch(LocalStoreTable.ISSUED_INVITES) { s ->
        s.issuedInvites().map { IssuedInviteRecord(it.jti, it.kind, it.issuedAt / 1000, it.ttlSeconds.toInt(), it.sitting) }
    }

    override suspend fun record(invite: IssuedInviteRecord) {
        withContext(Dispatchers.IO) {
            with(invite) { feed.store.recordIssuedInvite(LocalIssuedInvite(jti, kind, issuedAtEpochSec * 1000, ttlSeconds.toUInt(), sitting)) }
        }
    }

    override suspend fun delete(jti: String) {
        withContext(Dispatchers.IO) { feed.store.deleteIssuedInvites(listOf(jti)) }
    }
}
