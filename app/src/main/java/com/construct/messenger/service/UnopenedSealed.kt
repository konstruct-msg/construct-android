package com.construct.messenger.service

import shared.proto.core.v1.EnvelopeOuterClass.SealedInner

/**
 * What becomes of a sealed message that does not reach the processor (TODO 160). Before this it
 * was dropped with a log line and never reported to the cursor, so the cursor stopped at it and
 * the server delivered it — and everything after it — again on every connect, for 30 days.
 *
 * Every outcome but one moves the cursor past the message. Nothing is sent: the server sees the
 * resume cursor it would have seen had the message opened.
 *
 * **Canon:** iOS `MessageRouter.routeIncomingMessage` (the unseal branch),
 * `StealthSenderService.otherDeviceAddressed` / `namesNoRecipientDevice` / `unsealFailureDisposition`.
 */
internal object UnopenedSealed {

    enum class Disposition {
        /** Held for one redelivery — a box that fails right after an identity-key rotation deserves a second chance. */
        DeferOnce,
        /** Final: the cursor goes past it. */
        Drop,
    }

    /**
     * The device a copy is sealed to when that is not [ourDeviceId] — checked before trying to
     * open it, because it never can open here. Null when the copy names no device, names ours,
     * does not parse (corruption — the open reports it), or our own id is not known yet.
     */
    fun otherDeviceAddressed(sealedInner: ByteArray, ourDeviceId: String?): String? {
        if (ourDeviceId.isNullOrEmpty()) return null
        val target = parse(sealedInner)?.recipientDevice ?: return null
        return target.takeIf { it.isNotEmpty() && it != ourDeviceId }
    }

    /**
     * Whether a copy names no recipient device. The server writes such a copy to every device of
     * the account, so on an account with more than one device a copy that does not open here is
     * most likely a sibling's (Android senders omitted the field until `04d666f`, TODO 161).
     * A parse failure is not "unnamed".
     */
    fun namesNoRecipientDevice(sealedInner: ByteArray): Boolean =
        parse(sealedInner)?.recipientDevice?.isEmpty() ?: false

    /** What a failed open does with the message: one redelivery, unless that is already spent or cannot help. */
    fun disposition(namesNoRecipientDevice: Boolean, ownDeviceCount: Int, alreadyDeferred: Boolean): Disposition = when {
        alreadyDeferred -> Disposition.Drop
        namesNoRecipientDevice && ownDeviceCount > 1 -> Disposition.Drop
        else -> Disposition.DeferOnce
    }

    private fun parse(bytes: ByteArray): SealedInner? = runCatching { SealedInner.parseFrom(bytes) }.getOrNull()
}
