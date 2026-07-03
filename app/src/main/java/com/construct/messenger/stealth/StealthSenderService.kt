package com.construct.messenger.stealth

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import com.google.protobuf.ByteString
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import shared.proto.core.v1.EnvelopeOuterClass.ContentType
import shared.proto.core.v1.EnvelopeOuterClass.SealedInner
import shared.proto.core.v1.EnvelopeOuterClass.SenderCertificate
import shared.proto.services.v1.AuthServiceOuterClass.GetSenderCertificateRequest
import uniffi.construct_core.ppSealTokenBytes
import uniffi.construct_core.sealedSealSenderCert
import uniffi.construct_core.sealedUnsealSenderCert
import uniffi.construct_core.sealedVerifySenderCert

/**
 * ConstructSEALED — sealed sender (hides sender identity from the server).
 * Mirrors iOS `StealthSenderService`, with all crypto delegated to
 * construct-core FFI (Phase 5: one implementation, one test suite):
 *
 *  - send: [buildSealedInner] — seal our SenderCertificate to the recipient's
 *    X25519 identity key, attach the real content type and (per policy) a
 *    Privacy Pass token sealed to the server key;
 *  - receive: [resolveSender] — unseal, reject expired certs, verify the
 *    server signature against the bundle key from well-known.
 */
@Singleton
class StealthSenderService @Inject constructor(
    @ApplicationContext context: Context,
    private val grpcClient: GrpcClient,
    private val cryptoManager: CryptoManager,
    private val serverKeys: ServerKeysProvider,
    private val policy: StealthPolicy,
    private val wallet: TokenWalletService,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    /** Resolved sender identity + the real content type and E2EE payload carried
     * inside SealedInner (the outer envelope's payload is empty for sealed sends). */
    data class ResolvedSender(
        val senderId: String,
        val contentType: ContentType,
        val encryptedPayload: ByteArray,
    )

    // ── Sender certificate (from identity-service, 24h TTL) ────────────────

    /** Returns cached cert bytes, or fetches a fresh one (5-min expiry leeway). */
    suspend fun getSenderCertificate(): ByteArray {
        loadCachedCert()?.let { return it }
        val response = grpcClient.auth.getSenderCertificate(
            GetSenderCertificateRequest.getDefaultInstance(),
        )
        val cert = response.certificate.toByteArray()
        prefs.edit()
            .putString(KEY_CERT, Base64.encodeToString(cert, Base64.NO_WRAP))
            .putLong(KEY_CERT_EXPIRY, response.expiresAt)
            .apply()
        return cert
    }

    /** Call on logout / identity change. */
    fun clearCertCache() {
        prefs.edit().remove(KEY_CERT).remove(KEY_CERT_EXPIRY).apply()
    }

    private fun loadCachedCert(): ByteArray? {
        val expiry = prefs.getLong(KEY_CERT_EXPIRY, 0L)
        if (System.currentTimeMillis() / 1000 >= expiry - CERT_EXPIRY_LEEWAY_S) return null
        return prefs.getString(KEY_CERT, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
    }

    // ── Send path ───────────────────────────────────────────────────────────

    /**
     * Builds SealedInner proto bytes for a sealed send. The real [contentType]
     * travels inside SealedInner (the outer envelope stays generic — Phase 3).
     * Caller checks [StealthPolicy.shouldUseSealedSender] first.
     */
    suspend fun buildSealedInner(
        recipientUserId: String,
        recipientIdentityKey: ByteArray,
        encryptedPayload: ByteArray,
        contentType: ContentType,
    ): ByteArray {
        val certBytes = getSenderCertificate()
        val sealedCert = sealedSealSenderCert(
            certBytes.toUByteList(),
            recipientIdentityKey.toUByteList(),
        ).toByteArray()

        val deliveryTag = ByteArray(32).also(random::nextBytes)
        val builder = SealedInner.newBuilder()
            .setRecipientUserId(recipientUserId)
            .setSenderCertCiphertext(ByteString.copyFrom(sealedCert))
            .setEncryptedPayload(ByteString.copyFrom(encryptedPayload))
            .setContentType(contentType)
            .setDeliveryTag(ByteString.copyFrom(deliveryTag))

        if (policy.shouldConsumeToken(recipientUserId)) {
            wallet.consumeToken()?.let { token ->
                policy.recordTokenConsumed(recipientUserId)
                builder.setTokenNonce(ByteString.copyFrom(token.nonce))
                builder.setTokenBytes(ByteString.copyFrom(sealTokenBytes(token.token)))
            }
        }

        return builder.build().toByteArray()
    }

    /** Seals token bytes to the server key; plaintext fallback if the key isn't
     * cached yet (graceful degradation, same as iOS — relay can see the token,
     * which only weakens relay-privacy, not the anti-abuse property). */
    private fun sealTokenBytes(token: ByteArray): ByteArray {
        val serverKey = serverKeys.tokenEncryptionKey() ?: return token
        return runCatching {
            ppSealTokenBytes(token.toUByteList(), serverKey.toUByteList()).toByteArray()
        }.getOrElse {
            Log.w(TAG, "token seal failed — plaintext fallback", it)
            token
        }
    }

    // ── Receive path ────────────────────────────────────────────────────────

    /**
     * Unseals SealedInner bytes and returns the verified sender + real content
     * type, or null on any failure (caller drops the message with a log —
     * unseal failures are non-fatal by design).
     */
    fun resolveSender(sealedInnerBytes: ByteArray): ResolvedSender? {
        return try {
            val inner = SealedInner.parseFrom(sealedInnerBytes)
            if (inner.senderCertCiphertext.isEmpty) return null

            val identityPriv = cryptoManager.identityKeyBytes()
            val certBytes = sealedUnsealSenderCert(
                inner.senderCertCiphertext.toByteArray().toUByteList(),
                identityPriv.toUByteList(),
            ).toByteArray()
            val cert = SenderCertificate.parseFrom(certBytes)

            val now = System.currentTimeMillis() / 1000
            if (cert.expiresAt <= now) {
                Log.i(TAG, "expired sender cert (expired ${now - cert.expiresAt}s ago)")
                return null
            }

            val bundleKey = serverKeys.bundleVerificationKey() ?: run {
                Log.e(TAG, "no bundle verification key cached — cannot verify sender cert")
                return null
            }
            val valid = sealedVerifySenderCert(
                cert.senderUserId,
                cert.senderDomain,
                cert.senderIdentityKey.toByteArray().toUByteList(),
                cert.senderDeviceId,
                cert.issuedAt,
                cert.expiresAt,
                cert.serverSignature.toByteArray().toUByteList(),
                bundleKey.toUByteList(),
            )
            if (!valid) {
                Log.e(TAG, "sender cert signature invalid for ${cert.senderUserId.take(8)}…")
                return null
            }

            ResolvedSender(cert.senderUserId, inner.contentType, inner.encryptedPayload.toByteArray())
        } catch (e: Exception) {
            Log.e(TAG, "unseal failed", e)
            null
        }
    }

    private companion object {
        const val TAG = "StealthSender"
        const val PREFS_FILE_NAME = "stealth_sender_prefs"
        const val KEY_CERT = "sender_cert"
        const val KEY_CERT_EXPIRY = "sender_cert_expiry"
        const val CERT_EXPIRY_LEEWAY_S = 300L
    }
}
