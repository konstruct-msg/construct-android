package com.construct.messenger.data.local

import uniffi.construct_core.LocalContact
import uniffi.construct_core.LocalStore
import uniffi.construct_core.LocalStoreTable
import com.construct.messenger.util.DisplayNameGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [ContactStore] on the core's encrypted `LocalStore` (TODO 136, step 3) — held to the same
 * [ContactStoreContract] as [RoomContactStore]. Not wired into the app yet: Room stays the store
 * until the import moves its rows here.
 *
 * Every write runs under one lock. Most are single statements in the core already; the lock is
 * for the few that read first — [ensure], [upsert] (it keeps `added_at` and `shared_with_me_at`,
 * which [ContactRecord] does not carry), and the pending-avatar guard, which the core has no
 * conditional write for. Every contact write on the device goes through this one instance, so the
 * lock makes those read-then-writes atomic against each other.
 */
class CoreContactStore(
    private val feed: LocalStoreFeed,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : ContactStore {
    private val store: LocalStore get() = feed.store
    private val writes = Mutex()

    private suspend fun <T> write(block: (LocalStore) -> T): T =
        writes.withLock { withContext(Dispatchers.IO) { block(store) } }

    private suspend fun <T> read(block: (LocalStore) -> T): T = withContext(Dispatchers.IO) { block(store) }

    // The core's `contacts()` keeps blocked people in; ours lists them apart.
    override fun observeContacts(): Flow<List<ContactRecord>> =
        feed.watch(LocalStoreTable.CONTACTS) { s -> s.contacts().filterNot { it.isBlocked }.map { it.record() } }

    override fun observeBlocked(): Flow<List<ContactRecord>> =
        feed.watch(LocalStoreTable.CONTACTS) { s -> s.everyContact().filter { it.isBlocked }.map { it.record() } }

    override fun observeAll(): Flow<List<ContactRecord>> =
        feed.watch(LocalStoreTable.CONTACTS) { s -> s.everyContact().map { it.record() } }

    override fun observe(id: String): Flow<ContactRecord?> =
        feed.watch(LocalStoreTable.CONTACTS) { s -> s.contact(id)?.record() }

    override suspend fun get(id: String): ContactRecord? = read { it.contact(id)?.record() }

    override suspend fun ensure(id: String) = write { ensureIn(it, id) }

    private fun ensureIn(s: LocalStore, id: String) {
        if (s.contact(id) != null) return
        s.upsertContact(ContactRecord(id = id, displayName = DisplayNameGenerator.generate(id), isContact = true).local(null))
    }

    override suspend fun upsert(record: ContactRecord) = write { it.upsertContact(record.local(it.contact(record.id))) }

    override suspend fun delete(id: String) = write { it.deleteContact(id) }

    override suspend fun setBlocked(id: String, blocked: Boolean) {
        write { it.setContactBlocked(id, blocked) }
    }

    override suspend fun setAlias(id: String, alias: String?) {
        write { it.setContactAlias(id, alias?.trim()?.takeIf { a -> a.isNotEmpty() }) }
    }

    override suspend fun setSharingWith(id: String, sharing: Boolean) {
        write { it.setSharingWith(id, sharing) }
    }

    // The core's `sharing_with()` keeps blocked people in.
    override suspend fun sharingWith(): List<String> = read { s ->
        val blocked = s.everyContact().filter { it.isBlocked }.mapTo(HashSet()) { it.id }
        s.sharingWith().filterNot { it in blocked }
    }

    override suspend fun rememberIdentity(id: String, identityPublic: ByteArray) {
        write {
            ensureIn(it, id)
            it.setIdentityKey(id, identityPublic)
        }
    }

    override suspend fun setAccountAddress(id: String, address: ByteArray?) {
        write { it.setAccountAddress(id, address) }
    }

    override suspend fun setKtStatus(id: String, status: Int) {
        write { it.setKtStatus(id, status.toShort()) }
    }

    override suspend fun setSecurityNotice(id: String, notice: Int) {
        write { it.setSecurityNotice(id, notice.toShort()) }
    }

    override suspend fun pendingAvatarIds(): List<String> = read { s -> s.contactsWithPendingAvatar().map { it.id } }

    override suspend fun clearPendingAvatar(id: String, stored: ByteArray): Boolean = write { s ->
        val row = s.contact(id)?.takeIf { it.pendingAvatarRef.contentEquals(stored) } ?: return@write false
        s.setContactAvatar(id, row.avatar, null, null)
    }

    override suspend fun completePendingAvatar(id: String, stored: ByteArray, avatar: ByteArray): Boolean = write { s ->
        s.contact(id)?.takeIf { it.pendingAvatarRef.contentEquals(stored) } ?: return@write false
        s.setContactAvatar(id, avatar, null, null)
    }

    /** The core's row for [this]; the two times [ContactRecord] does not carry come from [held]. */
    private fun ContactRecord.local(held: LocalContact?) = LocalContact(
        id = id,
        username = username,
        displayName = displayName,
        localAlias = localAlias,
        avatar = avatarData,
        knownIdentityKey = identityPublic,
        accountAddress = accountAddress,
        isContact = isContact,
        isBlocked = isBlocked,
        isSharingWithMe = isSharingWithMe,
        amISharingWith = amSharingWith,
        sharedWithMeAt = held?.sharedWithMeAt ?: nowMs().takeIf { isSharingWithMe },
        addedAt = held?.addedAt ?: nowMs().takeIf { isContact },
        ktStatus = ktStatus.toShort(),
        securityNotice = securityNotice.toShort(),
        profileEditedAtMs = profileEditedAtMs,
        pendingAvatarRef = pendingAvatarRef,
        pendingAvatarSince = pendingAvatarSinceMs,
    )
}

private fun LocalContact.record() = ContactRecord(
    id = id,
    username = username,
    displayName = displayName,
    avatarData = avatar,
    isContact = isContact,
    isBlocked = isBlocked,
    isSharingWithMe = isSharingWithMe,
    identityPublic = knownIdentityKey,
    accountAddress = accountAddress,
    securityNotice = securityNotice.toInt(),
    localAlias = localAlias,
    amSharingWith = amISharingWith,
    profileEditedAtMs = profileEditedAtMs,
    pendingAvatarRef = pendingAvatarRef,
    pendingAvatarSinceMs = pendingAvatarSince,
    ktStatus = ktStatus.toInt(),
)
