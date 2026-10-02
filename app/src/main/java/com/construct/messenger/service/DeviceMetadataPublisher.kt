package com.construct.messenger.service

import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.diagnostics.Log
import com.google.protobuf.ByteString
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import shared.proto.core.v1.Identity
import shared.proto.services.v1.AuthServiceOuterClass.DeviceMetadata
import shared.proto.services.v1.AuthServiceOuterClass.SealedDeviceMetadata
import shared.proto.services.v1.AuthServiceOuterClass.SetDeviceMetadataRequest

/**
 * What this device says about itself to the other devices of its account — a name and a platform,
 * sealed to each of them, so the server stores a blob it cannot read. **Canon:** iOS
 * `DeviceMetadataService`, where the full reasoning lives: the server dropped these fields as
 * device fingerprinting (migration 013), and they come back only as ciphertext.
 *
 * Until this existed an Android device was an unnamed row in every sibling's list — "Device" on
 * Android, its id on iOS.
 */
@Singleton
class DeviceMetadataPublisher @Inject constructor(
    private val keystoreManager: KeystoreManager,
    private val sessionManager: SessionManager,
    private val cryptoManager: CryptoManager,
    private val grpcClient: GrpcClient,
) {
    /**
     * Re-seal and upload when the account's device set has moved since the last publish — the
     * only thing that invalidates a blob: a device added has no copy, a device removed can still
     * read. Best effort; a failure is retried on the next start.
     */
    suspend fun publishIfNeeded() {
        val myId = keystoreManager.getUserId() ?: return
        val bundles = try {
            sessionManager.discoverOwnDeviceBundles(myId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "own device set unknown — not publishing", e)
            return
        }
        // Not "the account has no devices": the answer was empty. Publishing now would replace a
        // good blob with nothing.
        if (bundles.isEmpty()) return
        val keys = LinkedHashMap<String, ByteArray>()
        bundles.forEach { keys[it.deviceId] = it.identityPublic }
        // Ours included on purpose: this device reads its own row back through the same path.
        val ourId = cryptoManager.currentDeviceId()
        val ourKey = cryptoManager.currentIdentityPublic()
        if (ourId != null && ourKey != null) keys.putIfAbsent(ourId, ourKey)

        if (!DeviceMetadataSeal.needsPublish(keys.keys, keystoreManager.deviceMetadataPublishedFor())) return
        val blob = DeviceMetadataSeal.seal(
            DeviceMetadataSeal.selfDescription(),
            keys.values.toList(),
            cryptoManager::sealToDeviceKey,
        ) ?: return
        try {
            grpcClient.device.setDeviceMetadata(
                SetDeviceMetadataRequest.newBuilder().setSealedMetadata(ByteString.copyFrom(blob)).build(),
            )
            // Recorded only once the server took it: a marker written on an attempt would make a
            // failed publish look done and skip every retry.
            keystoreManager.saveDeviceMetadataPublishedFor(keys.keys)
            Log.i(TAG, "published ${blob.size}B for ${keys.size} device(s)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "publish failed — retried on the next start", e)
        }
    }

    private companion object {
        const val TAG = "DeviceMetadata"
    }
}

/** The parts of [DeviceMetadataPublisher] that need neither the core nor the network. */
internal object DeviceMetadataSeal {
    /** The largest blob the server takes (`SetDeviceMetadataRequest`); larger is refused whole. */
    const val MAX_BLOB_BYTES = 4096

    /**
     * The platform, not the model: "Pixel 8" is the fingerprint 013 removed, and a person who wants
     * another name should choose it rather than have the model volunteered (iOS says "iPhone").
     */
    fun selfDescription(): DeviceMetadata = DeviceMetadata.newBuilder()
        .setDeviceName("Android")
        .setPlatform(Identity.DevicePlatform.DEVICE_PLATFORM_ANDROID)
        .build()

    /** An empty current set is an unknown one, never a reason to publish. */
    fun needsPublish(current: Set<String>, lastPublishedFor: Set<String>): Boolean =
        current.isNotEmpty() && current != lastPublishedFor

    /**
     * One copy per key, unlabelled — a reader finds its own by trying each. A key that cannot be
     * sealed to costs only its own copy. `null` when no copy was made or the blob is over the
     * limit: a refused upload would leave the previous blob standing, silently out of date.
     */
    fun seal(
        metadata: DeviceMetadata,
        identityKeys: List<ByteArray>,
        sealTo: (plaintext: ByteArray, identityPublic: ByteArray) -> ByteArray,
    ): ByteArray? {
        val plaintext = metadata.toByteArray()
        val sealed = SealedDeviceMetadata.newBuilder()
        for (key in identityKeys) {
            val copy = try {
                sealTo(plaintext, key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("DeviceMetadata", "could not seal to a device key — skipping that copy")
                continue
            }
            sealed.addCopies(ByteString.copyFrom(copy))
        }
        if (sealed.copiesCount == 0) return null
        val blob = sealed.build().toByteArray()
        if (blob.size > MAX_BLOB_BYTES) {
            Log.w("DeviceMetadata", "blob is ${blob.size}B for ${sealed.copiesCount} device(s) — over the limit, not publishing")
            return null
        }
        return blob
    }
}
