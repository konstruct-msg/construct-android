package com.construct.messenger.stealth

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.data.local.PeerDeviceRegistry
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
 *  - send: [buildSealedInner] — on an established session, the core's session envelope
 *    ([sessionEnvelope]); a first flight sealed whole with our certificate ([firstFlight]);
 *    otherwise seal our SenderCertificate to the recipient's X25519 identity key. Then the real content type and (per policy) a Privacy Pass token sealed to
 *    the server key;
 *  - receive: [resolveSender] — a session envelope names its writer by the session pair its tag
 *    matches; otherwise unseal and hand the certificate on, unchecked. The core checks the server
 *    signature where it matters — when the certificate opens a session — and the ratchet
 *    authenticates every message on a session that already exists.
 *
 * The session envelope: `decisions/sealed-envelope-keyed-by-the-session.md` (core 0.26.0). The
 * first flight: `decisions/first-flight-sealed-whole.md` (core 0.27).
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
    private val peerDevices: PeerDeviceRegistry,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    /** Resolved sender identity + the real content type and E2EE payload carried
     * inside SealedInner (the outer envelope's payload is empty for sealed sends).
     * [senderCertificate] is what a first message opens its session from — out of the certificate
     * box, or out of a first flight sealed whole with its wire payload. A session envelope
     * carries none: [envelopeSession] is the session whose tag matched, and [envelopeDevice] the
     * device it is with — the writer. */
    data class ResolvedSender(
        val senderId: String,
        val contentType: ContentType,
        val encryptedPayload: ByteArray,
        val senderCertificate: uniffi.construct_core.SenderCertificate? = null,
        val envelopeSession: String? = null,
        val envelopeDevice: String? = null,
    ) {
        /** The device that wrote the message, as its certificate or its session names it. */
        val senderDeviceId: String get() = senderCertificate?.deviceId ?: envelopeDevice.orEmpty()
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
     *
     * With [sessionEnvelope] the inner carries it and nothing else of the message: no certificate
     * box, no `encrypted_payload` — the wire payload is inside it, and the session names the
     * writer. No certificate is fetched for it. [firstFlight] replaces both the same way: the
     * certificate and the wire payload are inside it.
     */
    suspend fun buildSealedInner(
        recipientUserId: String,
        recipientIdentityKey: ByteArray,
        encryptedPayload: ByteArray,
        contentType: SealedEnvelopeType,
        /** The server refused this envelope's credential once: pay this time (see [SealedSend]). */
        afterCredentialRejection: Boolean = false,
        /** A session envelope the core sealed ([sessionEnvelope], or a DECRYPTION_ERROR it built). */
        sessionEnvelope: ByteArray? = null,
        /** A first flight the core sealed whole ([firstFlight]). */
        firstFlight: ByteArray? = null,
    ): ByteArray {
        val deliveryTag = ByteArray(32).also(random::nextBytes)
        val builder = SealedInner.newBuilder()
            // By their address when this device knows it, by the server's id otherwise. Only this
            // field: the token below stays keyed by the account id, because the server resolves
            // the address to that id before it checks anything else.
            .setRecipientUserId(
                AccountAddress.recipientField(recipientUserId, addressBook.of(recipientUserId)),
            )
            .setContentType(contentType.proto)
            .setDeliveryTag(ByteString.copyFrom(deliveryTag))
        if (sessionEnvelope != null) {
            builder.setSessionEnvelope(ByteString.copyFrom(sessionEnvelope))
        } else if (firstFlight != null) {
            builder.setFirstFlight(ByteString.copyFrom(firstFlight))
        } else {
            builder.setSenderCertCiphertext(ByteString.copyFrom(sealedSealSenderCert(getSenderCertificate(), recipientIdentityKey)))
            builder.setEncryptedPayload(ByteString.copyFrom(encryptedPayload))
        }

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

    /**
     * [wirePayload], a ratchet message to the device [recipientIdentityKey] names, as a session
     * envelope — `null` when it goes with a certificate (a first flight, or a session older than
     * the envelope). **Canon:** iOS `StealthSenderService.buildSealedInner` (the static bridge).
     */
    fun sessionEnvelope(recipientIdentityKey: ByteArray, wirePayload: ByteArray): ByteArray? {
        val device = runCatching { cryptoManager.deriveDeviceIdFromIdentity(recipientIdentityKey) }.getOrNull()
            ?: return null
        return runCatching { cryptoManager.sealEnvelope(device, wirePayload) }
            .onFailure { Log.w(TAG, "session envelope for ${device.take(8)}… not sealed — certificate path", it) }
            .getOrNull()
    }

    /**
     * [wirePayload] as a first flight sealed whole with our certificate, to the device
     * [recipientIdentityKey] names — `null` when it is not a first flight and goes with a
     * certificate. Its PQXDH header carries our ML-KEM identity key, the same on every first
     * flight, and beside a certificate box it let the server tie that key to the account (FF-1).
     * Throws for a first flight the core cannot seal: that one is not sent at all.
     * **Canon:** iOS `StealthSenderService.buildSealedInner` (the static bridge).
     */
    suspend fun firstFlight(recipientIdentityKey: ByteArray, wirePayload: ByteArray): ByteArray? {
        val device = cryptoManager.deriveDeviceIdFromIdentity(recipientIdentityKey)
        return cryptoManager.sealFirstFlight(device, recipientIdentityKey, wirePayload, getSenderCertificate())
            ?.also { Log.d(TAG, "first flight to ${device.take(8)}… sealed whole (${it.size}B)") }
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
    suspend fun resolveSender(sealedInnerBytes: ByteArray): ResolvedSender? {
        return try {
            val inner = SealedInner.parseFrom(sealedInnerBytes)
            if (!inner.sessionEnvelope.isEmpty) return resolveEnvelopeSender(inner.sessionEnvelope.toByteArray())
            val certBytes: ByteArray
            val payload: ByteArray
            if (!inner.firstFlight.isEmpty) {
                // A first flight sealed whole: certificate and wire payload come out together.
                val opened = cryptoManager.openFirstFlight(inner.firstFlight.toByteArray())
                certBytes = opened.certificate
                payload = opened.wirePayload
                Log.d(TAG, "first flight opened (${payload.size}B)")
            } else {
                if (inner.senderCertCiphertext.isEmpty) return null
                certBytes = cryptoManager.openSealedToDevice(inner.senderCertCiphertext.toByteArray())
                payload = inner.encryptedPayload.toByteArray()
            }
            val cert = SenderCertificate.parseFrom(certBytes)

            ResolvedSender(
                senderId = cert.senderUserId,
                contentType = inner.contentType,
                encryptedPayload = payload,
                senderCertificate = cert.toCore(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "unseal failed", e)
            null
        }
    }

    /**
     * A session envelope names its writer by the session pair its tag matches, which the core
     * finds. The kind byte inside says whether the body is a wire payload or a DECRYPTION_ERROR;
     * the outer content type is generic for both, so the server cannot tell them apart.
     * **Canon:** iOS `StealthSenderService.resolveEnvelopeSender`.
     */
    private suspend fun resolveEnvelopeSender(envelope: ByteArray): ResolvedSender? {
        val opened = cryptoManager.openEnvelope(envelope) ?: run {
            // Not ours, or from a session this device never held — the same drop as a box that
            // does not open.
            Log.e(TAG, "no session envelope pair matched")
            return null
        }
        // A peer's device, or one of our own (SENDER_SYNC): both are in the device registry,
        // recorded when the session with it was opened.
        val account = peerDevices.accountIdForDevice(opened.contactId) ?: run {
            Log.e(TAG, "envelope writer ${opened.contactId.take(8)}… is in no known account")
            return null
        }
        if (opened.retired) {
            Log.i(TAG, "envelope from ${opened.contactId.take(8)}… on a session no longer held — the core answers it")
        } else {
            Log.d(TAG, "envelope from ${opened.contactId.take(8)}… kind=${opened.kind}")
        }
        return ResolvedSender(
            senderId = account,
            contentType = if (opened.kind == ENVELOPE_KIND_DECRYPTION_ERROR) {
                ContentType.CONTENT_TYPE_DECRYPTION_ERROR
            } else {
                ContentType.CONTENT_TYPE_UNSPECIFIED
            },
            encryptedPayload = opened.body,
            envelopeSession = opened.sessionId,
            envelopeDevice = opened.contactId,
        )
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
        /** `EnvelopeOpened.kind`: 1 = ratchet wire payload, 2 = DECRYPTION_ERROR. */
        val ENVELOPE_KIND_DECRYPTION_ERROR: UByte = 2u
    }
}
