package com.construct.messenger.data.repository

import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.db.IssuedInviteDao
import com.construct.messenger.data.local.db.IssuedInviteEntity
import com.construct.messenger.data.local.db.UserDao
import com.construct.messenger.data.local.db.UserEntity
import com.construct.messenger.data.local.db.localName
import com.construct.messenger.data.local.db.resolvedName
import com.construct.messenger.data.model.Contact
import com.construct.messenger.data.model.SecurityNotice
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.invite.AccountAddressBook
import com.construct.messenger.invite.AccountAddressSource
import com.construct.messenger.invite.InviteConfig
import com.construct.messenger.invite.InviteException
import com.construct.messenger.invite.InviteGenerator
import com.construct.messenger.invite.InviteObject
import com.construct.messenger.invite.InviteVerifier
import com.construct.messenger.invite.MintedInvite
import com.construct.messenger.util.DisplayNameGenerator
import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusRuntimeException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import shared.proto.services.v1.InviteServiceOuterClass.AcceptInviteRequest
import shared.proto.services.v1.InviteServiceOuterClass.InviteToken
import shared.proto.services.v1.InviteServiceOuterClass.RevokeInviteRequest
import shared.proto.services.v1.UserServiceOuterClass.CheckUsernameAvailabilityRequest
import shared.proto.services.v1.UserServiceOuterClass.ContactRequestAction
import shared.proto.services.v1.UserServiceOuterClass.FindUserRequest
import shared.proto.services.v1.UserServiceOuterClass.GetContactRequestsRequest
import shared.proto.services.v1.UserServiceOuterClass.GetUserProfileRequest
import shared.proto.services.v1.UserServiceOuterClass.RespondToContactRequestRequest
import shared.proto.services.v1.UserServiceOuterClass.SendContactRequestRequest
import shared.proto.services.v1.UserServiceOuterClass.SetDiscoverableRequest

@Singleton
class ContactsRepositoryImpl @Inject constructor(
    private val userDao: UserDao,
    private val keystoreManager: KeystoreManager,
    private val generator: InviteGenerator,
    private val verifier: InviteVerifier,
    private val grpcClient: GrpcClient,
    private val issuedInviteDao: IssuedInviteDao,
    private val addressBook: AccountAddressBook,
    private val accountRepository: AccountRepository,
) : ContactsRepository {

    private val incoming = MutableStateFlow<List<IncomingContactRequest>>(emptyList())
    override val incomingRequests: Flow<List<IncomingContactRequest>> = incoming.asStateFlow()

    override val contacts: Flow<List<Contact>> = userDao.observeContacts().map { rows ->
        rows.map {
            Contact(
                userId = it.id,
                displayName = it.resolvedName(it.id),
                username = it.username,
                securityNotice = SecurityNotice.of(it.securityNotice),
                ktStatus = com.construct.messenger.data.model.KtStatus.of(it.ktStatus),
                localName = it.localName,
                avatar = it.avatarData,
            )
        }
    }

    override val blocked: Flow<List<Contact>> = userDao.observeBlocked().map { rows ->
        rows.map {
            Contact(
                userId = it.id,
                displayName = it.resolvedName(it.id),
                username = it.username,
                localName = it.localName,
            )
        }
    }

    override suspend fun mintLink(): MintedInvite = journal("link", sitting = null) { userId, deviceId ->
        generator.mintLink(userId = userId, deviceId = deviceId, ttlSeconds = InviteConfig.TTL_SECONDS.toInt())
    }

    override suspend fun mintQr(sitting: String): MintedInvite = journal("qr", sitting) { userId, deviceId ->
        generator.mintQr(userId = userId, deviceId = deviceId, username = accountRepository.cachedUsername())
    }

    /** Every invite is journalled, so it can be listed and revoked by its jti. */
    private suspend fun journal(kind: String, sitting: String?, mint: (String, String) -> MintedInvite): MintedInvite {
        val userId = keystoreManager.getUserId() ?: error("not authenticated")
        val deviceId = keystoreManager.getDeviceId() ?: error("no device id")
        val minted = mint(userId, deviceId)
        issuedInviteDao.upsert(
            IssuedInviteEntity(
                jti = minted.jti,
                kind = kind,
                issuedAtEpochSec = minted.issuedAtEpochSec,
                ttlSeconds = minted.ttlSeconds,
                sitting = sitting,
            ),
        )
        return minted
    }

    override suspend fun accept(raw: String): AcceptInviteResult {
        return try {
            // Contacts are made only by an account whose address this device knows — the gate on
            // the scan and link surfaces says why; this is the backstop for a link opened directly.
            if (keystoreManager.getOwnAccountAddress() == null) throw InviteException.NoAccountAddress
            val invite = verifier.decode(raw)
            val verified = verifier.verify(invite)
            val request = AcceptInviteRequest.newBuilder()
                .setInvite(invite.toProto())
                .build()
            val response = grpcClient.invite.acceptInvite(request)
            val userId = response.userId.ifEmpty { invite.uuid }
            persistContact(userId, invite, verified.identityPublic)
            AcceptInviteResult.Ok(
                Contact(
                    userId = userId,
                    displayName = invite.un?.takeIf { it.isNotBlank() }
                        ?: DisplayNameGenerator.generate(userId),
                    username = invite.un.orEmpty(),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: InviteException) {
            Log.w(TAG, "accept invite: ${e.message}")
            AcceptInviteResult.Failed(e.message ?: "invite failed")
        } catch (e: Exception) {
            Log.w(TAG, "accept invite rpc failed", e)
            AcceptInviteResult.Failed(e.message ?: "accept failed")
        }
    }

    override suspend fun revoke(jti: String): InviteRevocation {
        return try {
            val response = grpcClient.invite.revokeInvite(
                RevokeInviteRequest.newBuilder().setJti(jti).build(),
            )
            // Either answer is final: burned now, or already redeemed / unknown. Only a missing
            // answer keeps the row, since the invite may still be good.
            issuedInviteDao.delete(jti)
            if (response.success) InviteRevocation.REVOKED else InviteRevocation.ALREADY_USED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "revoke $jti failed", e)
            InviteRevocation.UNCONFIRMED
        }
    }

    override val issuedInvites: Flow<List<IssuedInvite>> = issuedInviteDao.observeAll().map { rows ->
        rows.map { IssuedInvite(it.jti, it.kind, it.issuedAtEpochSec, it.ttlSeconds, it.sitting) }
    }

    override suspend fun findByUsername(username: String): FindUserResult {
        val needle = username.trim().removePrefix("@").lowercase()
        if (needle.isEmpty()) return FindUserResult.Failed("empty")
        return try {
            val response = grpcClient.user.findUser(
                FindUserRequest.newBuilder().setUsername(needle).build(),
            )
            val id = response.userId
            if (id.isEmpty()) FindUserResult.NotFound else FindUserResult.Found(id)
        } catch (e: StatusRuntimeException) {
            if (e.status.code == Status.Code.NOT_FOUND) FindUserResult.NotFound
            else FindUserResult.Failed(e.status.code.name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FindUserResult.Failed(e.message ?: "find failed")
        }
    }

    override suspend fun sendContactRequest(userId: String): Boolean {
        return try {
            grpcClient.user.sendContactRequest(
                SendContactRequestRequest.newBuilder().setToUserId(userId).build(),
            )
            true
        } catch (e: StatusRuntimeException) {
            Log.w(TAG, "sendContactRequest ${e.status.code}")
            e.status.code == Status.Code.ALREADY_EXISTS
        } catch (e: Exception) {
            Log.w(TAG, "sendContactRequest failed", e)
            false
        }
    }

    override suspend fun refreshRequests() {
        try {
            val response = grpcClient.user.getContactRequests(
                GetContactRequestsRequest.getDefaultInstance(),
            )
            incoming.value = response.incomingList.map {
                IncomingContactRequest(
                    requestId = it.requestId,
                    fromUserId = it.fromUserId,
                    displayName = it.fromDisplayName.ifBlank { DisplayNameGenerator.generate(it.fromUserId) },
                    username = it.fromUsername,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "getContactRequests failed", e)
        }
    }

    override suspend fun acceptRequest(requestId: String, fromUserId: String): Boolean {
        return try {
            grpcClient.user.respondToContactRequest(
                RespondToContactRequestRequest.newBuilder()
                    .setRequestId(requestId)
                    .setAction(ContactRequestAction.CONTACT_REQUEST_ACTION_ACCEPT)
                    .build(),
            )
            val existing = userDao.getById(fromUserId)
            userDao.upsert(
                (existing ?: UserEntity(id = fromUserId)).copy(
                    displayName = existing?.displayName?.ifBlank { null }
                        ?: DisplayNameGenerator.generate(fromUserId),
                    isContact = true,
                ),
            )
            incoming.value = incoming.value.filterNot { it.requestId == requestId }
            getProfile(fromUserId)?.let { profile ->
                val row = userDao.getById(fromUserId) ?: return@let
                userDao.upsert(
                    row.copy(
                        displayName = ContactNames.offered(row, profile.displayName) ?: row.displayName,
                        username = profile.username.ifBlank { row.username },
                    ),
                )
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "acceptRequest failed", e)
            false
        }
    }

    override suspend fun checkUsername(username: String): UsernameAvailability {
        val needle = username.trim().removePrefix("@")
        if (needle.isEmpty()) return UsernameAvailability(false, "invalid_format")
        return try {
            val response = grpcClient.user.checkUsernameAvailability(
                CheckUsernameAvailabilityRequest.newBuilder().setUsername(needle).build(),
            )
            UsernameAvailability(response.available, if (response.hasReason()) response.reason else null)
        } catch (e: Exception) {
            UsernameAvailability(false, e.message, checkFailed = true)
        }
    }

    override suspend fun setDiscoverable(enabled: Boolean): Boolean {
        return try {
            grpcClient.user.setDiscoverable(
                SetDiscoverableRequest.newBuilder().setDiscoverable(enabled).build(),
            ).discoverable
        } catch (e: Exception) {
            Log.w(TAG, "setDiscoverable failed", e)
            false
        }
    }

    override suspend fun getProfile(userId: String): UserProfile? {
        return try {
            val profile = grpcClient.user.getUserProfile(
                GetUserProfileRequest.newBuilder().setUserId(userId).build(),
            ).profile
            UserProfile(
                userId = profile.userId.ifEmpty { userId },
                displayName = if (profile.hasDisplayName()) profile.displayName else "",
                username = if (profile.hasUsername()) profile.username else "",
            )
        } catch (e: Exception) {
            Log.w(TAG, "getProfile failed", e)
            null
        }
    }

    private suspend fun persistContact(userId: String, invite: InviteObject, identityPublic: ByteArray) {
        val existing = userDao.getById(userId)
        val display = ContactNames.offered(existing, invite.un)
            ?: existing?.displayName?.takeIf { it.isNotBlank() }
            ?: DisplayNameGenerator.generate(userId)
        userDao.upsert(
            (existing ?: UserEntity(id = userId)).copy(
                username = invite.un.orEmpty().ifEmpty { existing?.username.orEmpty() },
                displayName = display,
                isContact = true,
                identityPublic = identityPublic,
            ),
        )
        // From the signed invite, already checked by the server against the account's recovery
        // key: it outranks a card, and a different pinned one is a security event.
        addressBook.pin(userId, invite.addr, AccountAddressSource.INVITE)
    }

    private companion object {
        const val TAG = "ContactsRepository"
    }
}

/**
 * The decoded invite as the wire message `AcceptInvite` carries. The server rebuilds the
 * canonical string from exactly these fields, so a field dropped here reads as a bad signature on
 * the server for an invite that verified here — `internal` so a test can reach the mapping.
 */
internal fun InviteObject.toProto(): InviteToken {
    val b = InviteToken.newBuilder()
        .setV(v)
        .setJti(jti)
        .setUuid(uuid)
        .setServer(server)
        .setTs(ts)
        .setSig(sig)
        .setDeviceId(deviceId)
        .setTtl(ttl)
        // The last field of the canonical string, and the one the server checks against the
        // account's recovery key. `eph_pub` (v1–v3) stays empty: the server refuses a v5 with one.
        .setAddr(ByteString.copyFrom(addr))
    if (!un.isNullOrEmpty()) b.un = un
    return b.build()
}

/**
 * The name a contact goes by, when something other than the contact offers one — the server's
 * profile on accepting a request, the username in an invite. **Canon:** iOS
 * `User+DisplayName.applyServerUsername`, which leaves the name alone while `isSharingWithMe`.
 *
 * A contact who shares their profile named themselves end to end; a server's or an invite's name
 * would quietly replace that until their next profile came. Null: keep what the row has.
 */
internal object ContactNames {
    fun offered(existing: UserEntity?, name: String?): String? {
        if (existing?.isSharingWithMe == true) return null
        return name?.trim()?.takeIf { it.isNotEmpty() }
    }
}
