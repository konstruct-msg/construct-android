package com.construct.messenger.domain.usecase

import android.util.Log
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.crypto.KyberPrekeyService
import com.construct.messenger.data.api.GrpcClient
import com.google.protobuf.ByteString
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
 *
 * Every upload carries as many one-time Kyber keys (ML-KEM-1024) as classic ones, once the hybrid
 * identity is published ([KyberPrekeyService.oneTimeKeysForUpload]): the server serves one of each
 * together and expires both pools together, so the classic count is the count for both.
 */
@Singleton
class UploadPreKeysUseCase @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val grpcClient: GrpcClient,
    private val kyberPrekeys: KyberPrekeyService,
) {
    /**
     * Upload [count] freshly-generated one-time pre-keys unconditionally.
     *
     * @param deviceId The device id to upload keys for.
     * @param count Number of OTPKs to generate and upload.
     * @param replaceExisting If true, atomically replace all existing OTPKs (both pools) on the server.
     * @return [UploadPreKeysResult.Uploaded] on success, [UploadPreKeysResult.Failed] on error.
     */
    suspend operator fun invoke(
        deviceId: String,
        count: Int = DEFAULT_BATCH_SIZE,
        replaceExisting: Boolean = false,
    ): UploadPreKeysResult {
        return try {
            val otpks = cryptoManager.generateOneTimePrekeys(count)
            // Persisted by the service before they are returned, like the classic ones must be.
            val kyber = kyberPrekeys.oneTimeKeysForUpload(deviceId, count)
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
                .addAllKyberPreKeys(kyber)
                .setReplaceExisting(replaceExisting)
                .build()
            grpcClient.key.uploadPreKeys(request)
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
            if (countResponse.count >= minThreshold) {
                UploadPreKeysResult.Skipped
            } else {
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
    }
}

private fun List<UByte>.toByteString(): ByteString = ByteString.copyFrom(ByteArray(size) { this[it].toByte() })
