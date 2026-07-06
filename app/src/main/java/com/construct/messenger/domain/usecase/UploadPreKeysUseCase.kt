package com.construct.messenger.domain.usecase

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import com.google.protobuf.ByteString
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import shared.proto.services.v1.KeyServiceOuterClass.GetPreKeyCountRequest
import shared.proto.services.v1.KeyServiceOuterClass.OneTimePreKey
import shared.proto.services.v1.KeyServiceOuterClass.UploadPreKeysRequest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a one-time pre-key upload attempt.
 */
sealed interface UploadPreKeysResult {
    data object Skipped : UploadPreKeysResult
    data class Uploaded(val count: Int) : UploadPreKeysResult
    data class Failed(val error: Exception) : UploadPreKeysResult
}

/**
 * Generates and uploads one-time pre-keys (OTPK) to the server.
 *
 * **Canon:** iOS `OtpkReplenishmentService.generateAndUpload`. Two entry points:
 * - [invoke] — upload a fixed batch unconditionally.
 * - [replenishIfNeeded] — check current server count first; only upload if below [minThreshold].
 *
 * OTPK upload is **never fatal** — failures are logged and returned in [UploadPreKeysResult],
 * never thrown. The caller decides whether to retry or swallow.
 */
@Singleton
class UploadPreKeysUseCase @Inject constructor(
    @ApplicationContext context: Context,
    private val cryptoManager: CryptoManager,
    private val grpcClient: GrpcClient,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
    /**
     * Upload [count] freshly-generated one-time pre-keys unconditionally.
     *
     * @param deviceId The device id to upload keys for.
     * @param count Number of OTPKs to generate and upload.
     * @param replaceExisting If true, atomically replace all existing OTPKs on the server.
     * @param supportsPqRatchet Whether to declare PQ_RATCHET capability.
     * @return [UploadPreKeysResult.Uploaded] on success, [UploadPreKeysResult.Failed] on error.
     */
    suspend operator fun invoke(
        deviceId: String,
        count: Int = DEFAULT_BATCH_SIZE,
        replaceExisting: Boolean = false,
    ): UploadPreKeysResult {
        return try {
            val otpks = cryptoManager.generateOneTimePrekeys(count)
            val supportsPqRatchet = cryptoManager.supportsPqRatchet()
            val request = UploadPreKeysRequest.newBuilder()
                .setDeviceId(deviceId)
                .addAllPreKeys(
                    otpks.map { otpk ->
                        OneTimePreKey.newBuilder()
                            .setKeyId(otpk.keyId.toInt())
                            .setPublicKey(otpk.publicKey.toByteString())
                            .build()
                    },
                )
                .setReplaceExisting(replaceExisting)
                .setSupportsPqRatchet(supportsPqRatchet)
                .build()
            grpcClient.key.uploadPreKeys(request)
            // Remember what capability the server now holds — replenishIfNeeded
            // compares against this to force a re-upload when a build flips
            // supportsPqRatchet() while the server OTPK count is healthy.
            prefs.edit().putBoolean(KEY_ADVERTISED_PQ_RATCHET, supportsPqRatchet).apply()
            UploadPreKeysResult.Uploaded(count)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "OTPK upload failed", error)
            UploadPreKeysResult.Failed(error)
        }
    }

    /**
     * Checks the current OTPK count on the server. If below [minThreshold], uploads a
     * fresh batch of [batchSize] keys to replenish the pool.
     *
     * @return [UploadPreKeysResult.Skipped] if the server count is sufficient,
     *         [UploadPreKeysResult.Uploaded] if replenishment was performed,
     *         [UploadPreKeysResult.Failed] if the count check or upload failed.
     */
    suspend fun replenishIfNeeded(
        deviceId: String,
        minThreshold: Int = RECOMMENDED_MINIMUM,
        batchSize: Int = DEFAULT_BATCH_SIZE,
    ): UploadPreKeysResult {
        return try {
            val countResponse = grpcClient.key.getPreKeyCount(
                GetPreKeyCountRequest.newBuilder().setDeviceId(deviceId).build(),
            )
            // Capability re-advertisement: supports_pq_ratchet reaches the server
            // ONLY inside uploadPreKeys. When a build flips the capability (suite-3
            // rollout), a device with a healthy server count would otherwise never
            // re-upload — peers keep fetching the stale flag and negotiate classic.
            val advertised = cryptoManager.supportsPqRatchet()
            val lastAdvertised = if (prefs.contains(KEY_ADVERTISED_PQ_RATCHET)) {
                prefs.getBoolean(KEY_ADVERTISED_PQ_RATCHET, false)
            } else {
                null
            }
            if (countResponse.count >= minThreshold && lastAdvertised == advertised) {
                UploadPreKeysResult.Skipped
            } else {
                if (lastAdvertised != advertised) {
                    Log.i(TAG, "supportsPqRatchet changed ($lastAdvertised → $advertised) — forcing upload to re-advertise")
                }
                invoke(deviceId, batchSize, replaceExisting = false)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "OTPK replenishment check failed", error)
            UploadPreKeysResult.Failed(error)
        }
    }

    private companion object {
        const val TAG = "UploadPreKeysUseCase"
        /** Default batch size for periodic replenishment. Matches iOS `OtpkReplenishmentService`. */
        const val DEFAULT_BATCH_SIZE = 100
        /** Server's recommended minimum OTPK count (from `GetPreKeyCountResponse`). */
        const val RECOMMENDED_MINIMUM = 20
        const val PREFS_FILE_NAME = "prekey_upload_state"
        /** Last supports_pq_ratchet value successfully uploaded (absent = never/unknown). */
        const val KEY_ADVERTISED_PQ_RATCHET = "advertised_pq_ratchet"
    }
}

private fun List<UByte>.toByteString(): ByteString = ByteString.copyFrom(ByteArray(size) { this[it].toByte() })
