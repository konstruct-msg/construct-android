package com.construct.messenger.data.local.db

import com.construct.messenger.data.model.SecurityNotice
import com.construct.messenger.util.DisplayNameGenerator
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * User / contact row.
 *
 * **Canon:** `docs/ANDROID_ONBOARDING.md` §8.3 —
 * `User(id /* ServerUserId UUID */, username = "", displayName = "", avatarData?,
 * isContact = false, isBlocked = false, isSharingWithMe = false)`.
 *
 * [id] is the **ServerUserId** (36-char UUID) — never the 32-hex CryptoDeviceId
 * (§8.1: the two id types must never be mixed).
 */
@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val username: String = "",
    val displayName: String = "",
    val avatarData: ByteArray? = null,
    val isContact: Boolean = false,
    val isBlocked: Boolean = false,
    val isSharingWithMe: Boolean = false,
    /** Peer's X25519 identity public key — sealed-sender input. Remembered at session init. */
    val identityPublic: ByteArray? = null,
    /** Their account address (Ed25519 recovery public key), from their signed invite. Sealed
     * sends name the recipient by it; `null` for a contact added before invites carried it. */
    val accountAddress: ByteArray? = null,
    /** An unacknowledged security event for this contact ([SecurityNotice]), or 0. Cleared only by
     * the user, from the chat banner. */
    val securityNotice: Int = SecurityNotice.NONE.code,
    /** A name the user gave them here. Never leaves the device; outranks every other name. */
    val localAlias: String? = null,
    /** This device sent them our profile and has not stopped sharing (iOS `amISharingWith`). */
    val amSharingWith: Boolean = false,
) {
    // ByteArray field: structural equality must be explicit.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UserEntity) return false
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
            amSharingWith == other.amSharingWith
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
        return result
    }
}

/** The user's own name for them, when they gave one. */
val UserEntity.localName: String?
    get() = localAlias?.trim()?.takeIf { it.isNotEmpty() }

/**
 * The name to show for [userId]: the one the user gave them, the name they shared, their
 * username, then the name generated from the id. **Canon:** iOS `User.resolvedDisplayName`.
 */
fun UserEntity?.resolvedName(userId: String): String =
    this?.localName
        ?: this?.displayName?.takeIf { it.isNotBlank() }
        ?: this?.username?.takeIf { it.isNotBlank() }
        ?: DisplayNameGenerator.generate(userId)

@Dao
interface UserDao {

    @Query("SELECT * FROM users WHERE isContact = 1 AND isBlocked = 0 ORDER BY displayName ASC")
    fun observeContacts(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users")
    fun observeAll(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE isBlocked = 1 ORDER BY displayName ASC")
    fun observeBlocked(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE id = :userId")
    fun observeById(userId: String): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE id = :userId")
    suspend fun getById(userId: String): UserEntity?

    @Upsert
    suspend fun upsert(user: UserEntity)

    @Query("UPDATE users SET securityNotice = :code WHERE id = :userId")
    suspend fun setSecurityNotice(userId: String, code: Int)

    /** Its own statement, so no upsert of a row read earlier can carry an older alias back. */
    @Query("UPDATE users SET localAlias = :alias WHERE id = :userId")
    suspend fun setLocalAlias(userId: String, alias: String?)

    @Query("UPDATE users SET amSharingWith = :sharing WHERE id = :userId")
    suspend fun setAmSharingWith(userId: String, sharing: Boolean)

    @Query("DELETE FROM users WHERE id = :userId")
    suspend fun delete(userId: String)
}
