package com.construct.messenger.data.local

import com.construct.messenger.data.model.SecurityNotice
import com.construct.messenger.util.DisplayNameGenerator
import kotlinx.coroutines.flow.Flow

/**
 * A person's row: a contact, a blocked one, or someone a message or session brought in.
 *
 * Our own type — not Room's `UserEntity`, not the core's `LocalContact` — so the store under
 * [ContactStore] can change without its callers (TODO 136, the seam). [id] is the **ServerUserId**,
 * never a CryptoDeviceId.
 */
data class ContactRecord(
    val id: String,
    val username: String = "",
    val displayName: String = "",
    val avatarData: ByteArray? = null,
    val isContact: Boolean = false,
    val isBlocked: Boolean = false,
    val isSharingWithMe: Boolean = false,
    /** Their X25519 identity public key — sealed-sender input, remembered at session init. */
    val identityPublic: ByteArray? = null,
    /** Their account address (Ed25519 recovery public key), from their signed invite. */
    val accountAddress: ByteArray? = null,
    /** An unacknowledged security event ([SecurityNotice]), or 0. */
    val securityNotice: Int = SecurityNotice.NONE.code,
    /** A name the user gave them here. Never leaves the device; outranks every other name. */
    val localAlias: String? = null,
    /** This device sent them our profile and has not stopped sharing (iOS `amISharingWith`). */
    val amSharingWith: Boolean = false,
    /** `edited_at_ms` of the last typed profile applied from them; 0 = none. */
    val profileEditedAtMs: Long = 0,
    /** The avatar their profile named that has not arrived yet: `AvatarRef` bytes, or null. */
    val pendingAvatarRef: ByteArray? = null,
    /** When [pendingAvatarRef] was set. */
    val pendingAvatarSinceMs: Long? = null,
    /** The last Key Transparency verdict on their bundle ([com.construct.messenger.data.model.KtStatus]). */
    val ktStatus: Int = 0,
) {
    /** The user's own name for them, when they gave one. */
    val localName: String?
        get() = localAlias?.trim()?.takeIf { it.isNotEmpty() }

    // ByteArray fields: structural equality must be explicit.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContactRecord) return false
        return id == other.id &&
            username == other.username &&
            displayName == other.displayName &&
            avatarData.contentEquals(other.avatarData) &&
            isContact == other.isContact &&
            isBlocked == other.isBlocked &&
            isSharingWithMe == other.isSharingWithMe &&
            identityPublic.contentEquals(other.identityPublic) &&
            accountAddress.contentEquals(other.accountAddress) &&
            securityNotice == other.securityNotice &&
            localAlias == other.localAlias &&
            amSharingWith == other.amSharingWith &&
            profileEditedAtMs == other.profileEditedAtMs &&
            pendingAvatarRef.contentEquals(other.pendingAvatarRef) &&
            pendingAvatarSinceMs == other.pendingAvatarSinceMs &&
            ktStatus == other.ktStatus
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + username.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + avatarData.contentHashCode()
        result = 31 * result + isContact.hashCode()
        result = 31 * result + isBlocked.hashCode()
        result = 31 * result + isSharingWithMe.hashCode()
        result = 31 * result + identityPublic.contentHashCode()
        result = 31 * result + accountAddress.contentHashCode()
        result = 31 * result + securityNotice
        result = 31 * result + (localAlias?.hashCode() ?: 0)
        result = 31 * result + amSharingWith.hashCode()
        result = 31 * result + profileEditedAtMs.hashCode()
        result = 31 * result + pendingAvatarRef.contentHashCode()
        result = 31 * result + (pendingAvatarSinceMs?.hashCode() ?: 0)
        result = 31 * result + ktStatus
        return result
    }
}

/**
 * The name to show for [userId]: the one the user gave them, the name they shared, their
 * username, then the name generated from the id. **Canon:** iOS `User.resolvedDisplayName`.
 *
 * A generated name held in `displayName` is skipped, not shown: it stands for "no name", and
 * showing it hid a username the contact did have. Rows are created with it, and until 2026-10-02 a
 * profile carrying it overwrote the username from the invite — this repairs both.
 */
fun ContactRecord?.resolvedName(userId: String): String =
    this?.localName
        ?: this?.displayName?.takeIf { it.isNotBlank() && !DisplayNameGenerator.isGenerated(it, userId) }
        ?: this?.username?.takeIf { it.isNotBlank() }
        ?: DisplayNameGenerator.generate(userId)

/**
 * The people this device knows — the only way in to their rows (TODO 136, step 1). Reads are by
 * id or by list, observed as `Flow`; writes change their named fields of one row, as
 * `LocalStore`'s do in the core (`set_contact_blocked`, `set_kt_status`, …), so two writers of
 * different fields never carry an older value of the other's back. A write to a row that does not
 * exist changes nothing.
 *
 * Today on Room ([RoomContactStore]); the implementation on the core's `LocalStore` replaces it
 * without its callers changing. [ContactStoreContract] in the tests holds both to the same cases.
 */
interface ContactStore {
    /** Contacts not blocked, by name. */
    fun observeContacts(): Flow<List<ContactRecord>>

    /** Blocked people, by name — out of [observeContacts], listed apart so they can be unblocked. */
    fun observeBlocked(): Flow<List<ContactRecord>>

    /** Every row, contacts or not. */
    fun observeAll(): Flow<List<ContactRecord>>

    fun observe(id: String): Flow<ContactRecord?>

    suspend fun get(id: String): ContactRecord?

    /**
     * The row for someone a message or a session brought in: created as a contact under the name
     * generated from the id when there is none; one that exists is left as it is.
     */
    suspend fun ensure(id: String)

    /**
     * The whole row. Left for what still decides a row as a whole — a shared profile, an
     * accepted invite or request; each goes to field writes as the core's operations for it land.
     */
    suspend fun upsert(record: ContactRecord)

    /** The row only. Their chat and messages are the chats domain's (the core takes them too). */
    suspend fun delete(id: String)

    suspend fun setBlocked(id: String, blocked: Boolean)

    /** Blank or null clears it. */
    suspend fun setAlias(id: String, alias: String?)

    suspend fun setSharingWith(id: String, sharing: Boolean)

    /** Ids we share our profile with, blocked ones left out. */
    suspend fun sharingWith(): List<String>

    /** Their identity key, creating the row as [ensure] does when there is none. */
    suspend fun rememberIdentity(id: String, identityPublic: ByteArray)

    suspend fun setAccountAddress(id: String, address: ByteArray?)

    suspend fun setKtStatus(id: String, status: Int)

    suspend fun setSecurityNotice(id: String, notice: Int)

    /** Rows whose profile named an avatar that has not arrived. */
    suspend fun pendingAvatarIds(): List<String>

    /**
     * Drops the pending avatar reference — only while it is still [stored], since a newer profile
     * may have named another while this one downloaded. False: it was not.
     */
    suspend fun clearPendingAvatar(id: String, stored: ByteArray): Boolean

    /** The avatar and the cleared reference together, under the same guard as [clearPendingAvatar]. */
    suspend fun completePendingAvatar(id: String, stored: ByteArray, avatar: ByteArray): Boolean
}
