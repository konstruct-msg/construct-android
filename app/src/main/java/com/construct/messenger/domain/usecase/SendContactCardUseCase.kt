package com.construct.messenger.domain.usecase

import com.construct.messenger.diagnostics.Log
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.api.MessagingService
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.SessionStateStore
import com.construct.messenger.invite.ContactCardPayload
import com.construct.messenger.service.OrchestratorGateway
import com.construct.messenger.service.SessionManager
import com.construct.messenger.stealth.StealthPolicy
import com.construct.messenger.stealth.SealedEnvelopeType
import com.construct.messenger.stealth.IntakeCredentials
import com.construct.messenger.stealth.SealedSend
import com.construct.messenger.util.KnstFrame
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import shared.proto.core.v1.EnvelopeOuterClass.ContentType
import uniffi.construct_core.CfeAction
import uniffi.construct_core.CfeIncomingEvent

/**
 * Our contact card (KNST type 27) to a contact's device that does not have it yet: this account's
 * address, so they name us by key rather than by server id. Android mints no intake key, so the
 * card carries the address alone.
 *
 * Sent once per device — when we first hear from it and when we first write to it, whichever
 * comes first. **Canon:** iOS `OutboundSessionService.sendContactCard`; decision
 * `contact-card-carries-the-address-back.md`.
 *
 * Sealed or not sent, like every other control here: fail-closed under stealth.
 */
@Singleton
class SendContactCardUseCase @Inject constructor(
    private val keystoreManager: KeystoreManager,
    private val sessionManager: SessionManager,
    private val orchestrator: OrchestratorGateway,
    private val cryptoManager: CryptoManager,
    private val messagingService: MessagingService,
    private val stealthPolicy: StealthPolicy,
    private val sealedSend: SealedSend,
    private val intake: IntakeCredentials,
    private val sessionStateStore: SessionStateStore,
) {
    suspend fun sendIfOwed(contactId: String) {
        // The key always, the address when this device has seen the phrase.
        val address = keystoreManager.getOwnAccountAddress()
        val target = sessionManager.resolveTarget(contactId) ?: return
        val deviceId = target.deviceId
        if (deviceId.lowercase() in keystoreManager.contactCardSentTo()) return
        if (!cryptoManager.isMessagingReady || !sessionManager.hasSession(deviceId)) return
        if (!stealthPolicy.shouldUseSealedSender()) return
        val ik = target.identityPublic
        if (ik.isEmpty()) return

        val cardId = UUID.randomUUID()
        val plaintext = KnstFrame.pack(
            ContactCardPayload(intakeKey = intake.ownKey(), accountAddress = address).encoded(),
            ContentType.CONTENT_TYPE_CONTACT_CARD_VALUE,
            cardId,
        )
        val actions = orchestrator.handleEvent(
            CfeIncomingEvent.OutgoingMessage(
                contactId = deviceId,
                messageId = cardId.toString(),
                plaintext = plaintext,
                contentType = 0u,
            ),
        )
        if (!sessionStateStore.saveCfeActions(actions)) {
            Log.w(TAG, "card encrypt without session persist — dropping")
            return
        }
        val wire = actions.filterIsInstance<CfeAction.SendEncryptedMessage>()
            .firstOrNull { it.to == deviceId }
            ?.payload
            ?: return
        try {
            val result = sealedSend.send(
                recipientUserId = target.accountId,
                recipientIdentityKey = ik,
                encryptedPayload = wire,
                contentType = SealedEnvelopeType.GENERIC,
            )
            // Only after the server took it: marking first would cost the device our address on
            // one failed RPC. A refusal returns rather than throws.
            if (!result.success) {
                Log.w(TAG, "contact card to ${deviceId.take(8)}… refused (${result.errorCode}) — next exchange retries")
                return
            }
            keystoreManager.markContactCardSent(deviceId)
            Log.i(TAG, "contact card handed to ${deviceId.take(8)}…")
        } catch (e: Exception) {
            Log.w(TAG, "contact card to ${deviceId.take(8)}… not sent — next exchange retries", e)
        }
    }

    private companion object {
        const val TAG = "SendContactCard"
    }
}
