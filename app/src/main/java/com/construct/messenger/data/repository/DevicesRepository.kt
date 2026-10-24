package com.construct.messenger.data.repository

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.model.DevicePlatform
import com.construct.messenger.data.model.LinkedDevice
import com.construct.messenger.diagnostics.Log
import com.google.protobuf.ByteString
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import shared.proto.core.v1.Identity
import shared.proto.services.v1.AuthServiceOuterClass.ApproveJoinRequestRequest
import shared.proto.services.v1.AuthServiceOuterClass.DeviceInfo
import shared.proto.services.v1.AuthServiceOuterClass.GetDeviceLinkResultRequest
import shared.proto.services.v1.AuthServiceOuterClass.GetDeviceLinkResultResponse
import shared.proto.services.v1.AuthServiceOuterClass.InitiateDeviceLinkRequest
import shared.proto.services.v1.AuthServiceOuterClass.PublishDeviceCertificateRequest
import shared.proto.services.v1.AuthServiceOuterClass.DeviceMetadata
import shared.proto.services.v1.AuthServiceOuterClass.ListDevicesRequest
import shared.proto.services.v1.AuthServiceOuterClass.RevokeDeviceRequest
import shared.proto.services.v1.AuthServiceOuterClass.SealedDeviceMetadata

/**
 * The account's devices: list and revoke; and, in debug builds only (as iOS), the account side of
 * linking a new one (`devicelink/`).
 */
interface DevicesRepository {
    /** Current device first. Throws on a network or server error. */
    suspend fun list(): List<LinkedDevice>

    /** Throws on refusal — the primary device cannot be revoked. */
    suspend fun revoke(deviceId: String)

    /** Flow A: a link token for this account's QR, and when it expires (unix seconds). */
    suspend fun initiateLink(): Pair<String, Long>

    /** Flow A: what became of a token this device issued. */
    suspend fun linkResult(token: String): LinkResult

    /** Flow B: approve the join request of the device whose QR was scanned. */
    suspend fun approveJoinRequest(pendingDeviceId: String)

    /** Publish the certificate by which this device vouches for [subjectDeviceId]. */
    suspend fun publishCertificate(subjectDeviceId: String, certificate: ByteArray)

    sealed interface LinkResult {
        data object Pending : LinkResult
        /** [proof] is empty from a new device that predates device certificates. */
        class Confirmed(val deviceId: String, val proof: ByteArray) : LinkResult
        data object Expired : LinkResult
    }
}

@Singleton
class DevicesRepositoryImpl @Inject constructor(
    private val grpcClient: GrpcClient,
    private val cryptoManager: CryptoManager,
    private val keystoreManager: KeystoreManager,
) : DevicesRepository {

    override suspend fun list(): List<LinkedDevice> {
        val ownId = keystoreManager.getDeviceId()
        // A deadline, as on iOS: a server stream that never closes would otherwise leave the
        // screen spinning with nothing to report.
        return grpcClient.device.withDeadlineAfter(LIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .listDevices(ListDevicesRequest.getDefaultInstance())
            .map { it.device.toLinkedDevice(ownId) }
            .toList()
            .sortedByDescending { it.isCurrent }
    }

    override suspend fun revoke(deviceId: String) {
        grpcClient.device.revokeDevice(RevokeDeviceRequest.newBuilder().setDeviceId(deviceId).build())
    }

    override suspend fun initiateLink(): Pair<String, Long> =
        grpcClient.device.initiateDeviceLink(InitiateDeviceLinkRequest.getDefaultInstance())
            .let { it.linkToken to it.expiresAt }

    override suspend fun linkResult(token: String): DevicesRepository.LinkResult {
        val r = grpcClient.device.getDeviceLinkResult(GetDeviceLinkResultRequest.newBuilder().setLinkToken(token).build())
        return when (r.status) {
            GetDeviceLinkResultResponse.Status.STATUS_CONFIRMED ->
                DevicesRepository.LinkResult.Confirmed(r.deviceId, r.linkKeyProof.toByteArray())
            GetDeviceLinkResultResponse.Status.STATUS_PENDING -> DevicesRepository.LinkResult.Pending
            else -> DevicesRepository.LinkResult.Expired
        }
    }

    override suspend fun approveJoinRequest(pendingDeviceId: String) {
        grpcClient.auth.approveJoinRequest(
            ApproveJoinRequestRequest.newBuilder()
                .setPendingDeviceId(pendingDeviceId)
                .setCryptoSuite(CRYPTO_SUITE)
                .build(),
        )
    }

    override suspend fun publishCertificate(subjectDeviceId: String, certificate: ByteArray) {
        grpcClient.device.publishDeviceCertificate(
            PublishDeviceCertificateRequest.newBuilder()
                .setSubjectDeviceId(subjectDeviceId)
                .setCertificate(ByteString.copyFrom(certificate))
                .build(),
        )
    }

    private fun DeviceInfo.toLinkedDevice(ownId: String?): LinkedDevice {
        val id = device.deviceId
        val described = open(sealedMetadata)
        return LinkedDevice(
            id = id,
            name = described?.deviceName?.takeIf { it.isNotEmpty() },
            // This device is known to be Android even before it has published a description.
            platform = (described?.platform ?: platform).toModel()
                ?: if (isCurrent || id == ownId) DevicePlatform.ANDROID else null,
            createdAt = createdAt,
            isCurrent = isCurrent || id == ownId,
            isPrimary = isPrimary,
        )
    }

    /**
     * Our copy of the device's description, or null. A copy carries no recipient label, so each is
     * tried with this device's identity key inside the core; a copy sealed to a sibling fails to
     * open, which is how ours is found (iOS `DeviceMetadataService.open`).
     */
    private fun open(blob: ByteString): DeviceMetadata? {
        if (blob.isEmpty) return null
        val sealed = runCatching { SealedDeviceMetadata.parseFrom(blob) }.getOrNull() ?: return null
        for (copy in sealed.copiesList) {
            val plaintext = try {
                cryptoManager.openSealedToDevice(copy.toByteArray())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            return runCatching { DeviceMetadata.parseFrom(plaintext) }
                .onFailure { Log.w(TAG, "device metadata opened but did not parse", it) }
                .getOrNull()
        }
        return null
    }

    private fun Identity.DevicePlatform.toModel(): DevicePlatform? = when (this) {
        Identity.DevicePlatform.DEVICE_PLATFORM_IOS -> DevicePlatform.IOS
        Identity.DevicePlatform.DEVICE_PLATFORM_ANDROID -> DevicePlatform.ANDROID
        Identity.DevicePlatform.DEVICE_PLATFORM_DESKTOP -> DevicePlatform.DESKTOP
        Identity.DevicePlatform.DEVICE_PLATFORM_UNSPECIFIED -> null
        else -> DevicePlatform.OTHER
    }

    private companion object {
        const val TAG = "DevicesRepository"
        const val LIST_TIMEOUT_SECONDS = 20L
        /** iOS sends the same string; the server reads it as the suite of the account's keys. */
        const val CRYPTO_SUITE = "Curve25519+Ed25519"
    }
}
