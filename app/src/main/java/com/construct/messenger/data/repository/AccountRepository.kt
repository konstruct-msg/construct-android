package com.construct.messenger.data.repository

import kotlinx.coroutines.flow.StateFlow

/** This account as Settings shows it. */
data class OwnAccount(
    val userId: String,
    /** Server display name, else the name generated from [userId] — what contacts see. */
    val displayName: String,
    /** Empty = no public alias. */
    val username: String,
    val discoverable: Boolean,
    /** [com.construct.messenger.util.IdentityFingerprint]; `null` before the identity is loaded. */
    val fingerprint: String?,
    /** The picture this account goes by, a 512 px square JPEG; `null` = the identicon. */
    val avatar: ByteArray? = null,
)

sealed interface UsernameChange {
    data class Saved(val username: String) : UsernameChange
    /** Outside [AccountRepository.USERNAME_LENGTH]. */
    data object InvalidLength : UsernameChange
    /** Taken or refused by the server; [reason] is its code, if any. */
    data class Unavailable(val reason: String?) : UsernameChange
    data object Failed : UsernameChange
}

/**
 * The signed-in account's own profile: alias, discoverability, identity fingerprint.
 *
 * **Canon:** iOS `SettingsViewModel` (loadUserInfo / saveUsername / setDiscoverable).
 */
interface AccountRepository {
    /** `null` while signed out. Cached values first, then whatever [refresh] learns. */
    val account: StateFlow<OwnAccount?>

    suspend fun refresh()

    /** Trims, lowercases, checks availability, then sets it on the server. */
    suspend fun changeUsername(raw: String): UsernameChange

    /** Returns whether the change was applied. Enabling needs a username. */
    suspend fun setDiscoverable(enabled: Boolean): Boolean

    /**
     * [picture], already cropped square, becomes this account's avatar — on this device only;
     * contacts learn it when the profile is shared. False if it could not be encoded.
     */
    suspend fun setAvatar(picture: android.graphics.Bitmap): Boolean

    companion object {
        /** iOS `MessageSizeLimits.min/maxUsernameCharacters`; the server caps at 20. */
        val USERNAME_LENGTH = 3..20
    }
}
