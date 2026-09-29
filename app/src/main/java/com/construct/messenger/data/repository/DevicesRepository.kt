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
import shared.proto.services.v1.AuthServiceOuterClass.DeviceInfo
import shared.proto.services.v1.AuthServiceOuterClass.DeviceMetadata
import shared.proto.services.v1.AuthServiceOuterClass.ListDevicesRequest
import shared.proto.services.v1.AuthServiceOuterClass.RevokeDeviceRequest
import shared.proto.services.v1.AuthServiceOuterClass.SealedDeviceMetadata

/** The account's devices: list and revoke. Linking is not offered (iOS: debug builds only). */
interface DevicesRepository {
    /** Current device first. Throws on a network or server error. */
    suspend fun list(): List<LinkedDevice>

    /** Throws on refusal — the primary device cannot be revoked. */
    suspend fun revoke(deviceId: String)
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
    }
}
