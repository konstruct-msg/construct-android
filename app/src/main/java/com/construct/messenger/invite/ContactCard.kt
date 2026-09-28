package com.construct.messenger.invite

import com.google.protobuf.ByteString
import shared.proto.core.v1.EnvelopeOuterClass.ContactCard

/**
 * The payload of content type 27: what an account hands each contact about itself.
 *
 * A `ContactCard` proto, or — at exactly 32 bytes — a bare intake key from a build before the
 * card; any card with a field is longer. A field of the wrong length is dropped rather than
 * failing the card. **Canon:** iOS `ContactCardPayload`; vectors `knst_contact_card.json`;
 * decision `contact-card-carries-the-address-back.md`.
 *
 * Android mints no intake key yet, so what it sends carries the address alone.
 */
data class ContactCardPayload(
    val intakeKey: ByteArray? = null,
    val accountAddress: ByteArray? = null,
) {
    fun encoded(): ByteArray {
        val b = ContactCard.newBuilder()
        intakeKey?.let { b.intakeKey = ByteString.copyFrom(it) }
        accountAddress?.let { b.accountAddress = ByteString.copyFrom(it) }
        return b.build().toByteArray()
    }

    override fun equals(other: Any?): Boolean =
        other is ContactCardPayload &&
            intakeKey.contentEquals(other.intakeKey) &&
            accountAddress.contentEquals(other.accountAddress)

    override fun hashCode(): Int = intakeKey.contentHashCode() * 31 + accountAddress.contentHashCode()

    companion object {
        const val LEGACY_INTAKE_KEY_LENGTH = 32

        fun read(payload: ByteArray): ContactCardPayload? {
            if (payload.size == LEGACY_INTAKE_KEY_LENGTH) return ContactCardPayload(intakeKey = payload)
            val card = runCatching { ContactCard.parseFrom(payload) }.getOrNull() ?: return null
            return ContactCardPayload(
                intakeKey = card.intakeKey.toByteArray().takeIf { it.size == LEGACY_INTAKE_KEY_LENGTH },
                accountAddress = card.accountAddress.toByteArray().takeIf { it.size == AccountAddress.LENGTH },
            )
        }
    }
}

/** Where a contact's address came from — see [AccountAddressPin.decide]. */
enum class AccountAddressSource { INVITE, CARD }

enum class AccountAddressPin {
    PINNED, UNCHANGED, CONFLICT_KEPT, CONFLICT_REPLACED;

    val isSecurityEvent: Boolean get() = this == CONFLICT_KEPT || this == CONFLICT_REPLACED

    companion object {
        /**
         * An account's address never changes, so a different one is never an update. The invite
         * outranks the card: the server checked it against the account's recovery key.
         * **Canon:** iOS `AccountAddressPin.decide`.
         */
        fun decide(existing: ByteArray?, incoming: ByteArray, source: AccountAddressSource): AccountAddressPin =
            when {
                existing == null -> PINNED
                existing.contentEquals(incoming) -> UNCHANGED
                source == AccountAddressSource.INVITE -> CONFLICT_REPLACED
                else -> CONFLICT_KEPT
            }
    }
}
