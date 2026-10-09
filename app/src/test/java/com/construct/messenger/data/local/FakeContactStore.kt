package com.construct.messenger.data.local

import com.construct.messenger.util.DisplayNameGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** [ContactStore] in a map, for tests; [ContactStoreContract] holds it to Room's behaviour. */
internal class FakeContactStore : ContactStore {
    val rows = linkedMapOf<String, ContactRecord>()

    private fun byName(keep: (ContactRecord) -> Boolean) = rows.values.filter(keep).sortedBy { it.displayName }

    private fun change(id: String, f: (ContactRecord) -> ContactRecord) {
        rows[id]?.let { rows[id] = f(it) }
    }

    override fun observeContacts(): Flow<List<ContactRecord>> = flow { emit(byName { it.isContact && !it.isBlocked }) }
    override fun observeBlocked(): Flow<List<ContactRecord>> = flow { emit(byName { it.isBlocked }) }
    override fun observeAll(): Flow<List<ContactRecord>> = flow { emit(rows.values.toList()) }
    override fun observe(id: String): Flow<ContactRecord?> = flow { emit(rows[id]) }
    override suspend fun get(id: String): ContactRecord? = rows[id]

    override suspend fun ensure(id: String) {
        rows.getOrPut(id) { ContactRecord(id = id, displayName = DisplayNameGenerator.generate(id), isContact = true) }
    }

    override suspend fun upsert(record: ContactRecord) { rows[record.id] = record }
    override suspend fun delete(id: String) { rows.remove(id) }
    override suspend fun setBlocked(id: String, blocked: Boolean) = change(id) { it.copy(isBlocked = blocked) }
    override suspend fun setAlias(id: String, alias: String?) =
        change(id) { it.copy(localAlias = alias?.trim()?.takeIf { a -> a.isNotEmpty() }) }
    override suspend fun setSharingWith(id: String, sharing: Boolean) = change(id) { it.copy(amSharingWith = sharing) }
    override suspend fun sharingWith(): List<String> = rows.values.filter { it.amSharingWith && !it.isBlocked }.map { it.id }

    override suspend fun rememberIdentity(id: String, identityPublic: ByteArray) {
        ensure(id)
        change(id) { it.copy(identityPublic = identityPublic) }
    }

    override suspend fun setAccountAddress(id: String, address: ByteArray?) = change(id) { it.copy(accountAddress = address) }
    override suspend fun setKtStatus(id: String, status: Int) = change(id) { it.copy(ktStatus = status) }
    override suspend fun setSecurityNotice(id: String, notice: Int) = change(id) { it.copy(securityNotice = notice) }
    override suspend fun pendingAvatarIds(): List<String> = rows.values.filter { it.pendingAvatarRef != null }.map { it.id }

    override suspend fun clearPendingAvatar(id: String, stored: ByteArray): Boolean {
        val row = rows[id]?.takeIf { it.pendingAvatarRef.contentEquals(stored) } ?: return false
        rows[id] = row.copy(pendingAvatarRef = null, pendingAvatarSinceMs = null)
        return true
    }

    override suspend fun completePendingAvatar(id: String, stored: ByteArray, avatar: ByteArray): Boolean {
        val row = rows[id]?.takeIf { it.pendingAvatarRef.contentEquals(stored) } ?: return false
        rows[id] = row.copy(avatarData = avatar, pendingAvatarRef = null, pendingAvatarSinceMs = null)
        return true
    }
}
