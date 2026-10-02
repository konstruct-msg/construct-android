package com.construct.messenger.data.repository

import com.construct.messenger.data.model.Contact
import com.construct.messenger.invite.MintedInvite
import kotlinx.coroutines.flow.Flow

sealed interface AcceptInviteResult {
    data class Ok(val contact: Contact) : AcceptInviteResult
    data class Failed(val reason: String) : AcceptInviteResult
}

sealed interface FindUserResult {
    data class Found(val userId: String) : FindUserResult
    data object NotFound : FindUserResult
    data class Failed(val reason: String) : FindUserResult
}

data class IncomingContactRequest(
    val requestId: String,
    val fromUserId: String,
    val displayName: String,
    val username: String,
)

/**
 * Contacts + device-minted invites. UI mints a link, pastes a link, lists contacts.
 *
 * **Canon:** iOS `InviteGenerator` / `InviteVerifier` / `LinkParser.parseDynamicInvite`.
 */
interface ContactsRepository {
    val contacts: Flow<List<Contact>>

    /** Blocked people: out of [contacts], listed apart so they can be unblocked. */
    val blocked: Flow<List<Contact>> get() = kotlinx.coroutines.flow.emptyFlow()

    /** An invite for the HTTPS link a person shares — never with our username (`InviteGenerator.linkUsername`). */
    suspend fun mintLink(): MintedInvite

    /** A short-lived invite for a QR on screen: 300 s, our username in the signed body.
     * Its `payload` (base64url CIv1) is what the code carries; [sitting] names the showing it
     * belongs to. Canon: iOS `generateQRBinary` + `InviteJournal.recordQRCode`. */
    suspend fun mintQr(sitting: String): MintedInvite

    /** Accept a `konstruct://add?invite=` or `https://konstruct.cc/add?invite=` URL, or a raw base64url payload. */
    suspend fun accept(raw: String): AcceptInviteResult

    /** Pre-burn [jti] so an unused invite cannot be redeemed. */
    suspend fun revoke(jti: String): InviteRevocation

    val incomingRequests: Flow<List<IncomingContactRequest>>

    /** Exact username match. Server returns NOT_FOUND for missing *and* non-discoverable. */
    suspend fun findByUsername(username: String): FindUserResult

    suspend fun sendContactRequest(userId: String): Boolean

    suspend fun refreshRequests()

    suspend fun acceptRequest(requestId: String, fromUserId: String): Boolean

    suspend fun checkUsername(username: String): UsernameAvailability

    suspend fun setDiscoverable(enabled: Boolean): Boolean

    suspend fun getProfile(userId: String): UserProfile?

    val issuedInvites: Flow<List<IssuedInvite>>
}

/** [checkFailed]: no answer from the server — neither taken nor free. */
data class UsernameAvailability(
    val available: Boolean,
    val reason: String? = null,
    val checkFailed: Boolean = false,
)

data class UserProfile(
    val userId: String,
    val displayName: String,
    val username: String,
    val discoverable: Boolean = false,
)

/** iOS `InviteRevocation` outcomes. The journal forgets an invite only on a confirmed answer. */
enum class InviteRevocation {
    /** The server burned it; the link no longer works. */
    REVOKED,
    /** The server answered that it was already redeemed (or is unknown to it). */
    ALREADY_USED,
    /** No answer — the invite may still work, so it stays listed. */
    UNCONFIRMED,
}

data class IssuedInvite(
    val jti: String,
    val kind: String,
    val issuedAtEpochSec: Long,
    val ttlSeconds: Int,
    /** The QR showing it belongs to; `null` for a link, or a code from before showings. */
    val sitting: String? = null,
) {
    fun isLive(nowEpochSec: Long = System.currentTimeMillis() / 1000): Boolean =
        nowEpochSec < issuedAtEpochSec + ttlSeconds
}
