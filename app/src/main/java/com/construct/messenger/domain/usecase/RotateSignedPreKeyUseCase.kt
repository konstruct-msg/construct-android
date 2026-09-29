package com.construct.messenger.domain.usecase

import android.content.Context
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.crypto.KyberPrekeyService
import com.construct.messenger.crypto.toSignedProto
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.KeystoreManager
import com.google.protobuf.ByteString
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import shared.proto.services.v1.KeyServiceOuterClass.RotateSignedPreKeyRequest
import shared.proto.services.v1.KeyServiceOuterClass.SignedPreKeyRotationReason
import shared.proto.services.v1.KeyServiceOuterClass.SignedPreKeyUpload

/**
 * Weekly SPK rotation: the classic SPK and the Kyber SPK (ML-KEM-1024) together, in one
 * `RotateSignedPreKey`, each with its hybrid signature so the server stores them atomically with
 * the keys — never a rotated key without the signature initiators require.
 *
 * **Canon:** iOS `PreKeyRotationService.performAtomicRotation` (7-day interval).
 *
 * Waits for the first Kyber publish ([KyberPrekeyService.publishIfNeeded]): until the hybrid
 * identity is on the server it cannot verify the hybrid signatures, and a rotation without them
 * would clear the ones the bundle needs.
 *
 * On RPC failure the in-memory core is reloaded from Keystore, so a mutated classic SPK never
 * desyncs from what the server still serves (the key service stores both keys in one transaction,
 * so a failed RPC stored neither). The pending Kyber key is kept — the server may have stored it
 * before the answer was lost, and the next attempt sends the same key — unless the server said it
 * refused that key: then it stored nothing and would refuse it again, so it is rolled back.
 */
@Singleton
class RotateSignedPreKeyUseCase @Inject constructor(
    @ApplicationContext context: Context,
    private val cryptoManager: CryptoManager,
    private val grpcClient: GrpcClient,
    private val keystoreManager: KeystoreManager,
    private val kyberPrekeys: KyberPrekeyService,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    suspend fun rotateIfNeeded() {
        val deviceId = keystoreManager.getDeviceId() ?: return
        if (!cryptoManager.isMessagingReady) return
        val last = prefs.getLong(KEY_LAST, 0L)
        if (System.currentTimeMillis() - last < INTERVAL_MS) return
        if (!kyberPrekeys.isHybridIdentityPublished(deviceId)) {
            Log.i(TAG, "SPK rotation waits for the first Kyber publish")
            return
        }
        val saved = keystoreManager.getPrivateKeys()
        val userId = keystoreManager.getUserId()
        try {
            // Kyber first: begin is idempotent while a key is pending, and persisting it before
            // the upload means a key the server may hold is never only in memory.
            val kyber = cryptoManager.beginKyberSpkRotation()
            if (!kyberPrekeys.persist()) error("Kyber SPK not persisted — rotation deferred")
            val rotated = cryptoManager.rotateSignedPrekey()
            val classicPublic = rotated.publicKey
            val request = RotateSignedPreKeyRequest.newBuilder()
                .setDeviceId(deviceId)
                .setNewSignedPreKey(
                    SignedPreKeyUpload.newBuilder()
                        .setKeyId(rotated.keyId.toInt())
                        .setPublicKey(ByteString.copyFrom(classicPublic))
                        .setSignature(ByteString.copyFrom(rotated.signature)),
                )
                .setNewKyberSignedPreKey(kyber.toSignedProto())
                .setSignedPreKeyHybridSignature(ByteString.copyFrom(cryptoManager.signClassicSpkHybrid(classicPublic)))
                .setKyberSignedPreKeyHybridSignature(
                    ByteString.copyFrom(kyber.hybridSignature),
                )
                .setReason(SignedPreKeyRotationReason.SIGNED_PRE_KEY_ROTATION_REASON_SCHEDULED)
                .build()
            val response = grpcClient.key.rotateSignedPreKey(request)
            if (!response.success) error("server rejected SPK rotation")
            cryptoManager.commitKyberSpkRotation()
            if (!kyberPrekeys.persist()) {
                // The commit is in memory only; a restart comes back with the key still pending,
                // which decapsulates as well and is committed by the next rotation. Not fatal.
                Log.e(TAG, "Kyber commit not persisted — the key stays pending until the next rotation")
            }
            keystoreManager.savePrivateKeys(cryptoManager.exportPrivateKeys())
            kyberPrekeys.recordPublished(deviceId, kyber.keyId)
            prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
            Log.i(TAG, "SPK rotated classic keyId=${rotated.keyId} kyber keyId=${kyber.keyId}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "SPK rotation failed — rolling core back from Keystore", e)
            if (saved != null && userId != null) {
                runCatching {
                    cryptoManager.close()
                    cryptoManager.loadOrCreate(saved)
                    // The persisted Kyber store still holds the pending key.
                    cryptoManager.setLocalUserId(userId, keystoreManager.getKyberPrekeys())
                }.onFailure { Log.e(TAG, "core reload after a failed rotation failed", it) }
            }
            if (KyberPrekeyService.isKyberSpkRejection(e) && cryptoManager.isMessagingReady) {
                runCatching {
                    cryptoManager.rollbackKyberSpkRotation()
                    kyberPrekeys.persist()
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
