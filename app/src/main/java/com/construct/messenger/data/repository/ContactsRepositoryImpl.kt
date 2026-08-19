package com.construct.messenger.data.repository

import android.util.Log
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.KeystoreManager
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import shared.proto.services.v1.InviteServiceOuterClass.AcceptInviteRequest
import shared.proto.services.v1.InviteServiceOuterClass.InviteToken
import shared.proto.services.v1.InviteServiceOuterClass.RevokeInviteRequest

@Singleton
class ContactsRepositoryImpl @Inject constructor(
    private val userDao: UserDao,
    private val keystoreManager: KeystoreManager,
    private val generator: InviteGenerator,
    private val verifier: InviteVerifier,
    private val grpcClient: GrpcClient,
) : ContactsRepository {

    override val contacts: Flow<List<Contact>> = userDao.observeContacts().map { rows ->
        rows.map {
            Contact(
                userId = it.id,
                displayName = it.displayName.ifBlank { DisplayNameGenerator.generate(it.id) },
                username = it.username,
            )
        }
    }

    override suspend fun mintLink(includeUsername: Boolean): MintedInvite {
        val userId = keystoreManager.getUserId() ?: error("not authenticated")
        val deviceId = keystoreManager.getDeviceId() ?: error("no device id")
        return generator.mintLink(
            userId = userId,
            deviceId = deviceId,
            username = null,
            ttlSeconds = InviteConfig.TTL_SECONDS.toInt(),
        )
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
            response.success
        } catch (e: Exception) {
            Log.w(TAG, "revoke $jti failed", e)
            false
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
