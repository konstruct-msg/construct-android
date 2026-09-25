package com.construct.messenger.data.repository

import android.util.Log
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.db.IssuedInviteDao
import com.construct.messenger.data.local.db.IssuedInviteEntity
import com.construct.messenger.data.local.db.UserDao
import com.construct.messenger.data.local.db.UserEntity
import com.construct.messenger.data.model.Contact
import com.construct.messenger.invite.InviteConfig
import com.construct.messenger.invite.InviteException
import com.construct.messenger.invite.InviteGenerator
import com.construct.messenger.invite.InviteObject
import com.construct.messenger.invite.InviteVerifier
import com.construct.messenger.invite.MintedInvite
import com.construct.messenger.util.DisplayNameGenerator
import javax.inject.Inject
import javax.inject.Singleton
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import shared.proto.services.v1.InviteServiceOuterClass.AcceptInviteRequest
import shared.proto.services.v1.InviteServiceOuterClass.InviteToken
import shared.proto.services.v1.InviteServiceOuterClass.RevokeInviteRequest
import shared.proto.services.v1.UserServiceOuterClass.ContactRequestAction
import shared.proto.services.v1.UserServiceOuterClass.FindUserRequest
import shared.proto.services.v1.UserServiceOuterClass.GetContactRequestsRequest
import shared.proto.services.v1.UserServiceOuterClass.RespondToContactRequestRequest
import shared.proto.services.v1.UserServiceOuterClass.CheckUsernameAvailabilityRequest
import shared.proto.services.v1.UserServiceOuterClass.GetUserProfileRequest
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
) : ContactsRepository {

    private val incoming = MutableStateFlow<List<IncomingContactRequest>>(emptyList())
    override val incomingRequests: Flow<List<IncomingContactRequest>> = incoming.asStateFlow()

    override val contacts: Flow<List<Contact>> = userDao.observeContacts().map { rows ->
        rows.map {
            Contact(
                userId = it.id,
                displayName = it.displayName.ifBlank { DisplayNameGenerator.generate(it.id) },
                username = it.username,
            )
        }
    }

    override suspend fun mintLink(includeUsername: Boolean): MintedInvite =
        mint(kind = "link", ttlSeconds = InviteConfig.TTL_SECONDS.toInt())

    override suspend fun mintQr(): MintedInvite =
        mint(kind = "qr", ttlSeconds = InviteConfig.QR_TTL_SECONDS)

    /** Every invite is journalled, so it can be listed and revoked by its jti. */
    private suspend fun mint(kind: String, ttlSeconds: Int): MintedInvite {
        val userId = keystoreManager.getUserId() ?: error("not authenticated")
        val deviceId = keystoreManager.getDeviceId() ?: error("no device id")
        val minted = generator.mintLink(
            userId = userId,
            deviceId = deviceId,
            username = null,
            ttlSeconds = ttlSeconds,
        )
        issuedInviteDao.upsert(
            IssuedInviteEntity(
                jti = minted.jti,
                kind = kind,
                issuedAtEpochSec = minted.issuedAtEpochSec,
                ttlSeconds = minted.ttlSeconds,
            ),
        )
        return minted
    }

    override suspend fun accept(raw: String): AcceptInviteResult {
        return try {
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

    override suspend fun revoke(jti: String): Boolean {
        return try {
            val response = grpcClient.invite.revokeInvite(
                RevokeInviteRequest.newBuilder().setJti(jti).build(),
            )
            if (response.success) issuedInviteDao.delete(jti)
            response.success
        } catch (e: Exception) {
            Log.w(TAG, "revoke $jti failed", e)
            false
        }
    }

    override val issuedInvites: Flow<List<IssuedInvite>> = issuedInviteDao.observeAll().map { rows ->
        rows.map { IssuedInvite(it.jti, it.kind, it.issuedAtEpochSec, it.ttlSeconds) }
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
                        displayName = profile.displayName.ifBlank { row.displayName },
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
            UsernameAvailability(false, e.message)
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
        val display = invite.un?.takeIf { it.isNotBlank() }
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
    }

    private companion object {
        const val TAG = "ContactsRepository"
    }
}

private fun InviteObject.toProto(): InviteToken {
    val b = InviteToken.newBuilder()
        .setV(v)
        .setJti(jti)
        .setUuid(uuid)
        .setServer(server)
        .setTs(ts)
        .setEphPub(ephKey)
        .setSig(sig)
    if (deviceId.isNotEmpty()) b.deviceId = deviceId
    if (!un.isNullOrEmpty()) b.un = un
    ttl?.let { b.setTtl(it) }
    return b.build()
}
