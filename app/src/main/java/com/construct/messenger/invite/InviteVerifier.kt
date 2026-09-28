package com.construct.messenger.invite

import android.util.Log
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import shared.proto.services.v1.KeyServiceOuterClass.GetPreKeyBundleRequest

data class VerifiedInvite(
    val invite: InviteObject,
    val identityPublic: ByteArray,
    val verifyingKey: ByteArray,
)

@Singleton
class InviteVerifier @Inject constructor(
    private val grpcClient: GrpcClient,
    private val cryptoManager: CryptoManager,
) {
    private val usedJtis = Collections.synchronizedSet(mutableSetOf<String>())

    fun decode(raw: String): InviteObject = InviteObject.fromRaw(raw).also { it.validate() }

    suspend fun verify(invite: InviteObject): VerifiedInvite {
        invite.validate()
        if (invite.isExpired()) throw InviteException.Expired
        if (!usedJtis.add(invite.jti)) throw InviteException.AlreadyUsed

        val response = try {
            val req = GetPreKeyBundleRequest.newBuilder()
                .setUserId(invite.uuid)
                .setConsumeOneTimePrekey(false)
                .setDeviceId(invite.deviceId)
            grpcClient.key.getPreKeyBundle(req.build())
        } catch (e: Exception) {
            usedJtis.remove(invite.jti)
            Log.w(TAG, "public key fetch failed for ${invite.uuid.take(8)}…", e)
            throw InviteException.PublicKeyFetchFailed
        }

        val verifyingKey = response.verifyingKey.toByteArray()
        val identityPublic = response.bundle.identityKey.toByteArray()
        if (verifyingKey.isEmpty() || identityPublic.isEmpty()) {
            usedJtis.remove(invite.jti)
            throw InviteException.PublicKeyFetchFailed
        }

        val expected = cryptoManager.deriveDeviceIdFromIdentity(identityPublic)
        if (invite.deviceId.lowercase() != expected.lowercase()) {
            usedJtis.remove(invite.jti)
            throw InviteException.DeviceIdMismatch
        }

        // The canonical string covers `addr`: an address changed in transit fails here.
        val sig = b64decode(invite.sig)
        val ok = cryptoManager.verifyInvite(invite.canonicalString(), sig, verifyingKey)
        if (!ok) {
            usedJtis.remove(invite.jti)
            throw InviteException.InvalidSignature
        }
        return VerifiedInvite(invite, identityPublic, verifyingKey)
    }

    private companion object {
        const val TAG = "InviteVerifier"
    }
}
