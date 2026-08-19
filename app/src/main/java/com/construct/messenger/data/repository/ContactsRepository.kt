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

    suspend fun mintLink(includeUsername: Boolean = false): MintedInvite

    /** Accept a `konstruct://add?invite=` URL or a raw base64url payload. */
    suspend fun accept(raw: String): AcceptInviteResult

    /** Pre-burn [jti] so an unused invite cannot be redeemed. */
    suspend fun revoke(jti: String): Boolean

    val incomingRequests: Flow<List<IncomingContactRequest>>

    /** Exact username match. Server returns NOT_FOUND for missing *and* non-discoverable. */
    suspend fun findByUsername(username: String): FindUserResult

    suspend fun sendContactRequest(userId: String): Boolean

    suspend fun refreshRequests()

    suspend fun acceptRequest(requestId: String, fromUserId: String): Boolean
}
