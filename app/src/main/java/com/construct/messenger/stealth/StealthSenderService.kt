package com.construct.messenger.stealth

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.invite.AccountAddress
import com.construct.messenger.invite.AccountAddressBook
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

/**
 * ConstructSEALED — sealed sender (hides sender identity from the server).
 * Mirrors iOS `StealthSenderService`, with all crypto delegated to
 * construct-core FFI (Phase 5: one implementation, one test suite):
 *
 *  - send: [buildSealedInner] — seal our SenderCertificate to the recipient's
 *    X25519 identity key, attach the real content type and (per policy) a
 *    Privacy Pass token sealed to the server key;
 *  - receive: [resolveSender] — unseal and hand the certificate on, unchecked. The core checks
 *    the server signature where it matters — when the certificate opens a session — and the
 *    ratchet authenticates every message on a session that already exists.
 */
@Singleton
class StealthSenderService @Inject constructor(
    @ApplicationContext context: Context,
    private val grpcClient: GrpcClient,
    private val cryptoManager: CryptoManager,
    private val serverKeys: ServerKeysProvider,
    private val policy: StealthPolicy,
    private val wallet: TokenWalletService,
    private val addressBook: AccountAddressBook,
    private val intake: IntakeCredentials,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    /** Resolved sender identity + the real content type and E2EE payload carried
     * inside SealedInner (the outer envelope's payload is empty for sealed sends).
     * [senderCertificate] is what a first message opens its session from. */
    data class ResolvedSender(
        val senderId: String,
        val contentType: ContentType,
        val encryptedPayload: ByteArray,
        val senderCertificate: uniffi.construct_core.SenderCertificate? = null,
    ) {
        /** The device that wrote the message, as its certificate names it; empty without one. */
        val senderDeviceId: String get() = senderCertificate?.deviceId.orEmpty()
    }

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
     * Builds SealedInner proto bytes for a sealed send. SealedInner is plaintext to the relay, so
     * [contentType] is limited to [SealedEnvelopeType]; the real type rides in KNST byte 5.
     * Caller checks [StealthPolicy.shouldUseSealedSender] first.
     */
    suspend fun buildSealedInner(
        recipientUserId: String,
        recipientIdentityKey: ByteArray,
        encryptedPayload: ByteArray,
        contentType: SealedEnvelopeType,
        /** The server refused this envelope's credential once: pay this time (see [SealedSend]). */
        afterCredentialRejection: Boolean = false,
    ): ByteArray {
        val certBytes = getSenderCertificate()
        val sealedCert = sealedSealSenderCert(
            certBytes,
            recipientIdentityKey,
        )

        val deliveryTag = ByteArray(32).also(random::nextBytes)
        val builder = SealedInner.newBuilder()
            // By their address when this device knows it, by the server's id otherwise. Only this
            // field: the token below stays keyed by the account id, because the server resolves
            // the address to that id before it checks anything else.
            .setRecipientUserId(
                AccountAddress.recipientField(recipientUserId, addressBook.of(recipientUserId)),
            )
            .setSenderCertCiphertext(ByteString.copyFrom(sealedCert))
            .setEncryptedPayload(ByteString.copyFrom(encryptedPayload))
            .setContentType(contentType.proto)
            .setDeliveryTag(ByteString.copyFrom(deliveryTag))

        // A credential the recipient issued, instead of a token. Keyed by the account id, not
        // the address above: the server resolves the address to that id before it checks it.
        if (afterCredentialRejection) intake.noteRejected(recipientUserId)
        val intakeTag = if (afterCredentialRejection) null else intake.sealedTag(recipientUserId)
        val payment = EnvelopePayment.choose(
            credential = intakeTag != null,
            afterCredentialRejection = afterCredentialRejection,
            policyWantsToken = policy.shouldConsumeToken(recipientUserId),
        )
        if (payment == EnvelopePayment.CREDENTIAL && intakeTag != null) {
            builder.setIntakeTagSealed(ByteString.copyFrom(intakeTag))
        } else if (payment == EnvelopePayment.TOKEN) {
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
            ppSealTokenBytes(token, serverKey)
        }.getOrElse {
            Log.w(TAG, "token seal failed — plaintext fallback", it)
            token
        }
    }

    // ── Receive path ────────────────────────────────────────────────────────

    /**
     * Unseals SealedInner bytes and returns the sender, the real content type and the
     * certificate, or null when the box does not open (caller drops the message with a log).
     *
     * Nothing here refuses a certificate. Until 2026-09-27 this dropped every message whose
     * certificate had expired — issued for 24 h, while the mailbox keeps a message for 7 days, so
     * anything that waited more than a day was lost — and every message it could not verify. iOS
     * never gated delivery on either. The one decision a certificate carries, whether it may open
     * a session, is the core's (`SenderCertificate::identity_for_opening`), with one rule on both
     * platforms: `decisions/first-message-opens-without-the-server.md`.
     */
    fun resolveSender(sealedInnerBytes: ByteArray): ResolvedSender? {
        return try {
            val inner = SealedInner.parseFrom(sealedInnerBytes)
            if (inner.senderCertCiphertext.isEmpty) return null

            val certBytes = cryptoManager.openSealedToDevice(inner.senderCertCiphertext.toByteArray())
            val cert = SenderCertificate.parseFrom(certBytes)

            ResolvedSender(
                senderId = cert.senderUserId,
                contentType = inner.contentType,
                encryptedPayload = inner.encryptedPayload.toByteArray(),
                senderCertificate = cert.toCore(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "unseal failed", e)
            null
        }
    }

    /** The server keys a sender certificate is checked against — handed to the core before each
     * open, since the fetched key can arrive or rotate while the app runs. */
    fun trustedServerKeys(): List<ByteArray> = listOfNotNull(serverKeys.bundleVerificationKey())

    private companion object {
        const val TAG = "StealthSender"
        const val PREFS_FILE_NAME = "stealth_sender_prefs"
        const val KEY_CERT = "sender_cert"
        const val KEY_CERT_EXPIRY = "sender_cert_expiry"
        const val CERT_EXPIRY_LEEWAY_S = 300L
    }
}
