package com.construct.messenger.stealth

import shared.proto.core.v1.EnvelopeOuterClass.ContentType

/**
 * What a sealed envelope may declare in `SealedInner.content_type` — a plaintext field the relay
 * reads. Every real type rides in KNST byte 5 inside the ciphertext; this is the closed list of
 * those that must be recognised before decryption (`docs/WIRE_FORMAT_RULES.md` §2). A type
 * added to the proto later cannot reach the sealed wire by being passed through: it has no case
 * here. **Canon:** iOS `SealedEnvelopeType`.
 */
enum class SealedEnvelopeType(val proto: ContentType) {
    /** Everything else. Serialises to nothing: the field is absent. */
    GENERIC(ContentType.CONTENT_TYPE_UNSPECIFIED),

    /** 28 — answers a message the ratchet could not open, so the ratchet cannot carry it. */
    DECRYPTION_ERROR(ContentType.CONTENT_TYPE_DECRYPTION_ERROR),
}
