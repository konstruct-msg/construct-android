package com.construct.messenger.crypto

import android.content.Context
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.KeystoreManager
import com.google.protobuf.ByteString
import dagger.hilt.android.qualifiers.ApplicationContext
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import shared.proto.services.v1.KeyServiceOuterClass.KyberOneTimePreKey
import shared.proto.services.v1.KeyServiceOuterClass.KyberSignedPreKeyUpload
import shared.proto.services.v1.KeyServiceOuterClass.UploadPreKeysRequest
import uniffi.construct_core.KyberPrekeyUpload

/**
 * The device's Kyber prekeys (ML-KEM-1024, PQXDH v2) on this side of the FFI: persisting the
 * core's store, publishing the signed prekey, and carrying one-time keys to the server.
 *
 * **Canon:** iOS `KyberPrekeyService`. The core generates, signs and holds every Kyber key; the
 * seeds never leave it, and the responder init decapsulates inside it. What is left here is what
 * only the app can do — storage and network — and the order they must happen in:
 *
 * - A Kyber key reaches the server with two signatures over `created_at || public key`: Ed25519
 *   and hybrid (Ed25519 + ML-DSA-65). The server checks the hybrid one against the hybrid identity
 *   key already on the device's row, and an initiator refuses a Kyber key without it. So the first
 *   Kyber SPK is published in the same `UploadPreKeys` as the hybrid identity, and one-time Kyber
 *   keys are only sent once that identity is published ([isHybridIdentityPublished]).
 * - The server serves a classic and a Kyber one-time key together and expires both pools together
 *   on a replace-all. One-time Kyber keys therefore ride on the classic uploads, the same count
 *   each time ([oneTimeKeysForUpload], used by `UploadPreKeysUseCase`), and the two pools stay in
 *   step without a second count RPC.
 * - Every change to the store is persisted before the public half leaves the device: a key the
 *   server may serve must never exist only in memory.
 */
@Singleton
class KyberPrekeyService @Inject constructor(
    @ApplicationContext context: Context,
    private val cryptoManager: CryptoManager,
    private val keystoreManager: KeystoreManager,
    private val grpcClient: GrpcClient,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val publishMutex = Mutex()

    // ── Persistence ─────────────────────────────────────────────────────────

    /** Persist the core's Kyber prekeys. Call after anything that changed them: generating,
     * a rotation step, a responder init that burned a one-time key. */
    fun persist(): Boolean = try {
        val blob = cryptoManager.exportKyberPrekeys()
        keystoreManager.saveKyberPrekeys(blob).also { ok ->
            if (!ok) Log.e(TAG, "PERSIST-FAIL Kyber prekeys (${blob.size}B)")
        }
    } catch (e: Exception) {
        Log.e(TAG, "Kyber prekeys export failed", e)
        false
    }

    /** Persist the blob a responder init handed back (`SessionInitResult.kyberPrekeys`, set when
     * the init burned a one-time key). Same bytes [persist] would export. */
    fun persist(blob: ByteArray) {
        if (!keystoreManager.saveKyberPrekeys(blob)) {
            Log.e(TAG, "PERSIST-FAIL Kyber prekeys after a responder init (${blob.size}B) — the burned key comes back on restart")
        }
    }

    // ── One-time keys alongside the classic ones ────────────────────────────

    /**
     * One-time Kyber keys to send in the same `UploadPreKeys` as [count] classic ones, already
     * persisted. Empty when the server could not verify them yet (hybrid identity not published
     * for [deviceId]) or the core cannot sign them — the classic upload goes ahead either way, and
     * an initiator then uses the Kyber SPK.
     */
    fun oneTimeKeysForUpload(deviceId: String, count: Int): List<KyberOneTimePreKey> {
        if (count <= 0 || !isHybridIdentityPublished(deviceId)) return emptyList()
        return try {
            val keys = cryptoManager.generateKyberOneTimePrekeys(count)
            if (!persist()) return emptyList()
            keys.map { it.toOneTimeProto() }
        } catch (e: Exception) {
            Log.e(TAG, "Kyber one-time keys not generated (classic upload continues)", e)
            emptyList()
        }
    }

    // ── Signed prekey ───────────────────────────────────────────────────────

    /**
     * Publish the Kyber SPK if the server does not have it confirmed yet — on every messaging
     * start, before the one-time key replenishment (which only carries Kyber keys once this has
     * published the hybrid identity). Concurrent callers share one run.
     */
    suspend fun publishIfNeeded(deviceId: String) = publishMutex.withLock {
        if (!cryptoManager.isMessagingReady || deviceId.isEmpty()) return@withLock
        try {
            publish(deviceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Kyber SPK publish failed (next start retries)", e)
        }
    }

    /** Whether this device's hybrid identity is on the server — recorded by the publish that
     * carried it. One-time Kyber keys and the rotation's hybrid signatures wait for it. */
    fun isHybridIdentityPublished(deviceId: String): Boolean =
        prefs.getString(KEY_PUBLISHED, null)?.substringBefore(':') == deviceId

    /** Record the Kyber SPK the server now holds with its hybrid signature. */
    fun recordPublished(deviceId: String, keyId: UInt) {
        prefs.edit().putString(KEY_PUBLISHED, "$deviceId:$keyId").apply()
    }

    private fun published(deviceId: String, keyId: UInt): Boolean =
        prefs.getString(KEY_PUBLISHED, null) == "$deviceId:$keyId"

    private suspend fun publish(deviceId: String) {
        val lost = cryptoManager.kyberPrekeysLost
        val current = cryptoManager.currentKyberSpkUpload()
        if (current != null && !lost && published(deviceId, current.keyId)) return

        // The hybrid identity signs every Kyber key and must exist before the first one. It lives
        // in the private-key record, which is saved before anything it signed leaves the device.
        val hybridPublic = cryptoManager.ensureHybridIdentityPublicKey()
        if (!keystoreManager.savePrivateKeys(cryptoManager.exportPrivateKeys())) {
            Log.e(TAG, "hybrid identity not persisted — publish deferred")
            return
        }
        val binding = cryptoManager.signHybridIdentityBinding(hybridPublic)

        // A device with a committed SPK republishes it (the server lost it, or it was rotated
        // without its hybrid signature). One without starts a rotation: the pending key is
        // persisted, so a retry — this start or the next — sends the same key.
        val spk: KyberPrekeyUpload = current ?: cryptoManager.beginKyberSpkRotation()
        val classicSpk = cryptoManager.currentSignedPrekeyPublic()
        val classicSpkHybrid = runCatching { cryptoManager.signClassicSpkHybrid(classicSpk) }.getOrNull()
        // The first one-time keys go with the first SPK — and all of them again after the store
        // was lost, with the server's old ones expired (their secrets are gone). A republished SPK
        // brings none: from then on they ride on the classic uploads.
        val oneTime = if (current == null || lost) cryptoManager.generateKyberOneTimePrekeys(INITIAL_ONE_TIME_BATCH) else emptyList()
        if (!persist()) return

        val request = UploadPreKeysRequest.newBuilder()
            .setDeviceId(deviceId)
            .setKyberSignedPreKey(spk.toSignedProto())
            .addAllKyberPreKeys(oneTime.map { it.toOneTimeProto() })
            .setHybridIdentityKey(ByteString.copyFrom(hybridPublic))
            .setHybridIdentitySignature(ByteString.copyFrom(binding))
            .setKyberSignedPreKeyHybridSignature(spk.hybridSignature.toByteString())
            // Replace-all expires the classic pool too; the next replenishment refills it.
            .setReplaceExisting(lost)
        classicSpkHybrid?.let { request.setSignedPreKeyHybridSignature(ByteString.copyFrom(it)) }

        try {
            uploadWithRetry { grpcClient.key.uploadPreKeys(request.build()) }
        } catch (e: Exception) {
            // A pending key the server refused outright (not a transport failure) would be refused
            // again on every start — one that waited past the 30-day age limit, say. Forget it; the
            // next start begins from a fresh key.
            if (current == null && !isTransient(e)) {
                cryptoManager.rollbackKyberSpkRotation()
                persist()
            }
            throw e
        }

        if (current == null) {
            cryptoManager.commitKyberSpkRotation()
            persist()
        }
        cryptoManager.kyberPrekeysLost = false
        recordPublished(deviceId, spk.keyId)
        Log.i(TAG, "Kyber SPK published (keyId=${spk.keyId}, ${oneTime.size} one-time keys, replaceAll=$lost)")
    }

    private suspend fun uploadWithRetry(upload: suspend () -> Unit) {
        var attempt = 0
        while (true) {
            try {
                upload()
                return
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (grpcCode(e) != Status.Code.UNAVAILABLE || attempt >= TRANSIENT_RETRIES) throw e
                attempt++
                delay(attempt * 2_000L)
            }
        }
    }

    companion object {
        private const val TAG = "KyberPrekeys"
        private const val PREFS = "construct_kyber"

        /** The Kyber SPK (by device and key id) the server last confirmed with its hybrid
         * signature. Keyed by both so a new identity or a rotated key publishes again. */
        private const val KEY_PUBLISHED = "published_spk"

        /** One-time Kyber keys sent with the first Kyber SPK. Later batches follow the classic
         * replenishment count. */
        private const val INITIAL_ONE_TIME_BATCH = 50

        /** Retries of the SPK publish on UNAVAILABLE within one run (startup transport churn). */
        private const val TRANSIENT_RETRIES = 2

        fun grpcCode(e: Throwable): Status.Code? = when (e) {
            is StatusException -> e.status.code
            is StatusRuntimeException -> e.status.code
            else -> null
        }

        /** A failure that says nothing about whether the server stored the upload. */
        fun isTransient(e: Throwable): Boolean = when (grpcCode(e)) {
            null, Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED,
            Status.Code.CANCELLED, Status.Code.UNKNOWN,
            -> true
            else -> false
        }

        /** The server refused the Kyber SPK in a rotation (it stored nothing). The key service
         * names it in the status message — `Kyber SPK rotation failed: …`. */
        fun isKyberSpkRejection(e: Throwable): Boolean =
            e.message.orEmpty().contains("Kyber SPK rotation failed")
    }
}

internal fun KyberPrekeyUpload.toOneTimeProto(): KyberOneTimePreKey = KyberOneTimePreKey.newBuilder()
    .setKeyId(keyId.toInt())
    .setPublicKey(publicKey.toByteString())
    .setSignature(signature.toByteString())
    .setCreatedAt(createdAt.toLong())
    .setHybridSignature(hybridSignature.toByteString())
    .build()

internal fun KyberPrekeyUpload.toSignedProto(): KyberSignedPreKeyUpload = KyberSignedPreKeyUpload.newBuilder()
    .setKeyId(keyId.toInt())
    .setPublicKey(publicKey.toByteString())
    .setSignature(signature.toByteString())
    .setCreatedAt(createdAt.toLong())
    .build()

private fun List<UByte>.toByteString(): ByteString = ByteString.copyFrom(ByteArray(size) { this[it].toByte() })
