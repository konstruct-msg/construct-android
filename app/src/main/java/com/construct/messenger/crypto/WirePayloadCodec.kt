package com.construct.messenger.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes/decodes the `encrypted_payload` blob carried on the wire — the Android
 * mirror of iOS `WirePayloadCoder` and of `wire_payload.rs` in construct-core.
 *
 * Wire format (little-endian), fixed 52-byte header:
 * ```
 *   [4]  message_number
 *   [32] dh_public_key            (X25519 ephemeral)
 *   [4]  one_time_prekey_id       (0 = no OTPK / 3-DH fallback)
 *   [4]  kyber_otpk_id            (0 = Kyber SPK used; >0 = Kyber OTPK id)
 *   [2]  kem_ciphertext_len       (0 = no PQC)
 *   [4]  previous_chain_length    (DR PN)
 *   [2]  suite_id
 *   [N]  kem_ciphertext           (optional)
 *   [..] nonce || ciphertext || auth_tag   (ChaCha20-Poly1305 sealed box)
 * ```
 * Suite 3 may carry an additive pq_ratchet field (u8 type + u16 len + data)
 * between the KEM ciphertext and the sealed box — skipped when extracting
 * [DecodedPayload.content]. The server forwards the whole blob opaquely.
 *
 * The receive path passes the FULL blob to `CfeIncomingEvent.MessageReceived.data`
 * and only reads [DecodedPayload.messageNumber] / [kyberOtpkId] / [kemCiphertext]
 * from the header — the Rust CFE re-parses the rest.
 */
object WirePayloadCodec {

    const val HEADER_SIZE = 52
    private const val DH_KEY_SIZE = 32

    class WirePayloadException(message: String) : Exception(message)

    data class DecodedPayload(
        val messageNumber: UInt,
        val ephemeralPublicKey: ByteArray,
        val oneTimePrekeyId: UInt,
        val kyberOtpkId: UInt,
        val previousChainLength: UInt,
        val suiteId: UShort,
        val kemCiphertext: ByteArray?,
        val content: ByteArray,
    )

    fun decode(data: ByteArray): DecodedPayload {
        if (data.size <= HEADER_SIZE) {
            throw WirePayloadException("wire payload too short: ${data.size} bytes")
        }
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        val messageNumber = buf.int.toUInt()
        val ephemeralPublicKey = ByteArray(DH_KEY_SIZE).also(buf::get)
        val oneTimePrekeyId = buf.int.toUInt()
        val kyberOtpkId = buf.int.toUInt()
        val kemLen = buf.short.toInt() and 0xFFFF
        val previousChainLength = buf.int.toUInt()
        val suiteId = buf.short.toUShort()

        val sealedBoxStart = HEADER_SIZE + kemLen
        if (data.size <= sealedBoxStart) {
            throw WirePayloadException("wire payload too short for declared KEM length $kemLen")
        }
        val kemCiphertext = if (kemLen > 0) data.copyOfRange(HEADER_SIZE, sealedBoxStart) else null

        // suite 3: skip the additive pq_ratchet field (u8 type + u16 len + data).
        var contentStart = sealedBoxStart
        if (suiteId.toInt() == 3 && data.size > contentStart + 3) {
            val pqLen = ByteBuffer.wrap(data, contentStart + 1, 2)
                .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            contentStart += 3 + pqLen
        }
        if (data.size < contentStart) {
            throw WirePayloadException("wire payload truncated before content")
        }
        val content = data.copyOfRange(contentStart, data.size)

        return DecodedPayload(
            messageNumber = messageNumber,
            ephemeralPublicKey = ephemeralPublicKey,
            oneTimePrekeyId = oneTimePrekeyId,
            kyberOtpkId = kyberOtpkId,
            previousChainLength = previousChainLength,
            suiteId = suiteId,
            kemCiphertext = kemCiphertext,
            content = content,
        )
    }

    /**
     * Pack components into the wire blob. `previousChainLength` is always 0 from
     * the app layer (the Rust orchestrator owns the real DR PN), matching iOS.
     */
    fun encode(
        messageNumber: UInt,
        ephemeralPublicKey: ByteArray,
        oneTimePrekeyId: UInt,
        content: ByteArray,
        kemCiphertext: ByteArray? = null,
        kyberOtpkId: UInt = 0u,
        suiteId: UShort = 1u,
    ): ByteArray {
        if (ephemeralPublicKey.size != DH_KEY_SIZE) {
            throw WirePayloadException("dh_public_key must be $DH_KEY_SIZE bytes, got ${ephemeralPublicKey.size}")
        }
        val kemLen = kemCiphertext?.size ?: 0
        if (kemLen > 0xFFFF) throw WirePayloadException("KEM ciphertext too large: $kemLen")

        val buf = ByteBuffer.allocate(HEADER_SIZE + kemLen + content.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(messageNumber.toInt())
        buf.put(ephemeralPublicKey)
        buf.putInt(oneTimePrekeyId.toInt())
        buf.putInt(kyberOtpkId.toInt())
        buf.putShort(kemLen.toShort())
        buf.putInt(0) // previous_chain_length
        buf.putShort(suiteId.toShort())
        kemCiphertext?.let(buf::put)
        buf.put(content)
        return buf.array()
    }
}
