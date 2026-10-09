package com.construct.messenger.data.local

import com.construct.messenger.data.local.db.UserDao
import com.construct.messenger.data.local.db.UserEntity
import com.construct.messenger.util.DisplayNameGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [ContactStore] on Room's `users` table — until the core's `LocalStore` takes it (TODO 136).
 * Built by `DatabaseModule`, which provides no `UserDao`: nothing else can reach the table.
 */
class RoomContactStore(private val dao: UserDao) : ContactStore {
    override fun observeContacts(): Flow<List<ContactRecord>> = dao.observeContacts().map { rows -> rows.map { it.record() } }

    override fun observeBlocked(): Flow<List<ContactRecord>> = dao.observeBlocked().map { rows -> rows.map { it.record() } }

    override fun observeAll(): Flow<List<ContactRecord>> = dao.observeAll().map { rows -> rows.map { it.record() } }

    override fun observe(id: String): Flow<ContactRecord?> = dao.observeById(id).map { it?.record() }

    override suspend fun get(id: String): ContactRecord? = dao.getById(id)?.record()

    override suspend fun ensure(id: String) = dao.insertIfAbsent(fresh(id))

    override suspend fun upsert(record: ContactRecord) = dao.upsert(record.entity())

    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun setBlocked(id: String, blocked: Boolean) = dao.setBlocked(id, blocked)

    override suspend fun setAlias(id: String, alias: String?) =
        dao.setLocalAlias(id, alias?.trim()?.takeIf { it.isNotEmpty() })

    override suspend fun setSharingWith(id: String, sharing: Boolean) = dao.setAmSharingWith(id, sharing)

    override suspend fun sharingWith(): List<String> = dao.sharingWithIds()

    override suspend fun rememberIdentity(id: String, identityPublic: ByteArray) {
        dao.insertIfAbsent(fresh(id))
        dao.setIdentityPublic(id, identityPublic)
    }

    override suspend fun setAccountAddress(id: String, address: ByteArray?) = dao.setAccountAddress(id, address)

    override suspend fun setKtStatus(id: String, status: Int) = dao.setKtStatus(id, status)

    override suspend fun setSecurityNotice(id: String, notice: Int) = dao.setSecurityNotice(id, notice)

    override suspend fun pendingAvatarIds(): List<String> = dao.pendingAvatarIds()

    override suspend fun clearPendingAvatar(id: String, stored: ByteArray): Boolean = dao.clearPendingAvatar(id, stored) > 0

    override suspend fun completePendingAvatar(id: String, stored: ByteArray, avatar: ByteArray): Boolean =
        dao.completePendingAvatar(id, stored, avatar) > 0

    private fun fresh(id: String) = UserEntity(id = id, displayName = DisplayNameGenerator.generate(id), isContact = true)
}

private fun UserEntity.record() = ContactRecord(
    id = id,
    username = username,
    displayName = displayName,
    avatarData = avatarData,
    isContact = isContact,
    isBlocked = isBlocked,
    isSharingWithMe = isSharingWithMe,
    identityPublic = identityPublic,
    accountAddress = accountAddress,
    securityNotice = securityNotice,
    localAlias = localAlias,
    amSharingWith = amSharingWith,
    profileEditedAtMs = profileEditedAtMs,
    pendingAvatarRef = pendingAvatarRef,
    pendingAvatarSinceMs = pendingAvatarSinceMs,
    ktStatus = ktStatus,
)

private fun ContactRecord.entity() = UserEntity(
    id = id,
    username = username,
    displayName = displayName,
    avatarData = avatarData,
    isContact = isContact,
    isBlocked = isBlocked,
    isSharingWithMe = isSharingWithMe,
    identityPublic = identityPublic,
    accountAddress = accountAddress,
    securityNotice = securityNotice,
    localAlias = localAlias,
    amSharingWith = amSharingWith,
    profileEditedAtMs = profileEditedAtMs,
    pendingAvatarRef = pendingAvatarRef,
    pendingAvatarSinceMs = pendingAvatarSinceMs,
    ktStatus = ktStatus,
)
