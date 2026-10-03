package com.construct.messenger.stealth

import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import shared.proto.core.v1.EnvelopeOuterClass
import uniffi.construct_core.SenderCertificate

/**
 * The encrypted payload of a SENDER_SYNC: the sending device's certificate beside the core's wire
 * payload (`OwnDeviceCopy`, construct-protos `core/envelope.proto`).
 *
 * A copy to a sibling goes unsealed, so it has no `SealedInner` to bring a sender certificate in —
 * and a sibling's first message opens a session only from one
 * (`decisions/first-message-opens-without-the-server.md`). Until 2026-09-27 the copy carried the
 * bare wire payload; no compatibility with that is kept (early alpha).
 *
 * **Canon:** iOS `OwnDeviceCopy.swift`.
 */
object OwnDeviceCopy {

    /**
     * Every SENDER_SYNC goes out through this, with a certificate or without. [certificate] is the
     * serialized proto the server issued; absent or unreadable, the copy still goes — on an
     * existing session it opens without one, and a first message then fails in the core with
     * `SENDER_CERTIFICATE_MISSING` rather than never being sent.
     */
    fun wrap(certificate: ByteArray?, wirePayload: ByteArray): ByteArray {
        val builder = EnvelopeOuterClass.OwnDeviceCopy.newBuilder()
            .setWirePayload(ByteString.copyFrom(wirePayload))
        certificate?.let { bytes ->
            runCatching { EnvelopeOuterClass.SenderCertificate.parseFrom(bytes) }
                .getOrNull()
                ?.let(builder::setSenderCertificate)
        }
        return builder.build().toByteArray()
    }

    /** What a copy carries. The certificate is not verified here: the core checks the signature
     * when — and only when — it is used to open a session. */
    class Unwrapped(val certificate: SenderCertificate?, val wirePayload: ByteArray)

    /** `null` when [payload] is not a copy. */
    fun unwrap(payload: ByteArray): Unwrapped? {
        val copy = try {
            EnvelopeOuterClass.OwnDeviceCopy.parseFrom(payload)
        } catch (_: InvalidProtocolBufferException) {
            return null
        }
        if (copy.wirePayload.isEmpty) return null
        val certificate = if (copy.hasSenderCertificate()) copy.senderCertificate.toCore() else null
        return Unwrapped(certificate, copy.wirePayload.toByteArray())
    }
}

/** The server's certificate as the core takes it — one field list for both carriers, the sealed
 * inner and the own-device copy. */
fun EnvelopeOuterClass.SenderCertificate.toCore(): SenderCertificate = SenderCertificate(
    userId = senderUserId,
    domain = senderDomain,
    identityKey = senderIdentityKey.toByteArray(),
    deviceId = senderDeviceId,
    issuedAt = issuedAt,
    expiresAt = expiresAt,
    signature = serverSignature.toByteArray(),
    // Empty from a server that does not sign with a delegated key yet; then Ed25519 decides.
    serverKid = serverKid.toByteArray(),
    serverSignatureHybrid = serverSignatureHybrid.toByteArray(),
)
