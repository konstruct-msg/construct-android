package com.construct.messenger.data.repository

import com.construct.messenger.data.model.Contact
import com.construct.messenger.invite.MintedInvite
import kotlinx.coroutines.flow.Flow

sealed interface AcceptInviteResult {
    data class Ok(val contact: Contact) : AcceptInviteResult
    data class Failed(val reason: String) : AcceptInviteResult
}

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
}
