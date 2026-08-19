package com.construct.messenger.domain.usecase

import android.content.Context
import android.util.Log
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.KeystoreManager
import com.google.protobuf.ByteString
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import shared.proto.services.v1.KeyServiceOuterClass.RotateSignedPreKeyRequest
import shared.proto.services.v1.KeyServiceOuterClass.SignedPreKeyRotationReason
import shared.proto.services.v1.KeyServiceOuterClass.SignedPreKeyUpload

/**
 * Weekly classic SPK rotation. Kyber SPK is not rotated here yet (no Android PQC
 * key manager) — classic-only matches the 1:1 path we ship.
 *
 * **Canon:** iOS `PreKeyRotationService.rotateIfNeeded` (7-day interval).
 * On RPC failure the in-memory core is reloaded from Keystore so a mutated SPK
 * never desyncs from what the server still serves.
 */
@Singleton
class RotateSignedPreKeyUseCase @Inject constructor(
    @ApplicationContext context: Context,
    private val cryptoManager: CryptoManager,
    private val grpcClient: GrpcClient,
    private val keystoreManager: KeystoreManager,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    suspend fun rotateIfNeeded() {
        val deviceId = keystoreManager.getDeviceId() ?: return
        if (!cryptoManager.isMessagingReady) return
        val last = prefs.getLong(KEY_LAST, 0L)
        if (System.currentTimeMillis() - last < INTERVAL_MS) return
        val saved = keystoreManager.getPrivateKeys()
        val userId = keystoreManager.getUserId()
        try {
            val rotated = cryptoManager.rotateSignedPrekey()
            val request = RotateSignedPreKeyRequest.newBuilder()
                .setDeviceId(deviceId)
                .setNewSignedPreKey(
                    SignedPreKeyUpload.newBuilder()
                        .setKeyId(rotated.keyId.toInt())
                        .setPublicKey(ByteString.copyFrom(rotated.publicKey.map { it.toByte() }.toByteArray()))
                        .setSignature(ByteString.copyFrom(rotated.signature.map { it.toByte() }.toByteArray())),
                )
                .setReason(SignedPreKeyRotationReason.SIGNED_PRE_KEY_ROTATION_REASON_SCHEDULED)
                .build()
            val response = grpcClient.key.rotateSignedPreKey(request)
            if (!response.success) error("server rejected SPK rotation")
            keystoreManager.savePrivateKeys(cryptoManager.exportPrivateKeys())
            prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
            Log.i(TAG, "SPK rotated keyId=${rotated.keyId}")
        } catch (e: Exception) {
            Log.w(TAG, "SPK rotation failed — rolling core back from Keystore", e)
            if (saved != null && userId != null) {
                runCatching {
                    cryptoManager.close()
                    cryptoManager.loadOrCreate(saved)
                    cryptoManager.setLocalUserId(userId)
                }
            }
        }
    }

    private companion object {
        const val TAG = "RotateSpk"
        const val PREFS = "construct_spk"
        const val KEY_LAST = "last_rotation_ms"
        const val INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
