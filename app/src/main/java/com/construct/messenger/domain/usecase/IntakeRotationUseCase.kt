package com.construct.messenger.domain.usecase

import com.construct.messenger.data.local.ContactStore
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.stealth.IntakeCredentials
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The user blocked or removed a contact on this device: the account's intake key rotates
 * ([IntakeCredentials.rotate]), and once the server holds the new one each remaining contact is
 * handed it in an envelope of its own, at a random moment within the hour
 * ([IntakeCredentials.Rotation.cardSchedule]). Nothing is queued past the process: the "sent to"
 * list went with the old key, so a contact the hour missed gets the card with our next message to
 * them, and meanwhile pays a token. **Canon:** iOS, per construct-messenger's point-4 rules.
 */
@Singleton
class IntakeRotationUseCase internal constructor(
    private val intake: IntakeCredentials,
    private val contacts: ContactStore,
    private val keystoreManager: KeystoreManager,
    private val sendContactCard: SendContactCardUseCase,
    private val scope: CoroutineScope,
    private val random: Random,
    private val pause: suspend (Long) -> Unit,
) {
    @Inject
    constructor(
        intake: IntakeCredentials,
        contacts: ContactStore,
        keystoreManager: KeystoreManager,
        sendContactCard: SendContactCardUseCase,
    ) : this(
        intake, contacts, keystoreManager, sendContactCard,
        CoroutineScope(SupervisorJob() + Dispatchers.IO), Random.Default, { delay(it) },
    )

    /** Off the caller: the server is asked and published to, and the cards take an hour. */
    fun onContactDropped(accountId: String, reason: String) {
        scope.launch {
            val fresh = runCatching { intake.rotate("${accountId.take(8)}… $reason") }
                .onFailure { Log.w(TAG, "intake key not rotated for ${accountId.take(8)}…", it) }
                .getOrNull() ?: return@launch
            // `observeContacts` leaves blocked rows out; the one just removed is gone or excluded.
            val schedule = IntakeCredentials.Rotation.cardSchedule(
                contacts.observeContacts().first().map { it.id },
                dropped = accountId,
                ownAccount = keystoreManager.getUserId(),
                random = random,
            )
            Log.i(TAG, "generation ${fresh.generation}: cards to ${schedule.size} contact(s) within the hour")
            for ((contactId, offsetMs) in schedule) {
                launch {
                    pause(offsetMs)
                    runCatching { sendContactCard.sendIfOwed(contactId) }
                        .onFailure { Log.w(TAG, "card to ${contactId.take(8)}… not sent — their next exchange retries", it) }
                }
            }
        }
    }

    private companion object {
        const val TAG = "IntakeRotation"
    }
}
