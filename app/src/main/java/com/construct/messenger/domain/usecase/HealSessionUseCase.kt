package com.construct.messenger.domain.usecase

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CFE `SessionHealNeeded` executor.
 *
 * **Canon:** iOS `MessageRouter.handleRustHealDecision`.
 * - `Initiator` (we win tie-break): send END_SESSION so the peer becomes RESPONDER.
 * - otherwise: archive locally and wait for the peer's fresh X3DH.
 */
@Singleton
class HealSessionUseCase @Inject constructor(
    private val sessionControl: SessionControlUseCase,
) {
    suspend fun heal(contactId: String, role: String) {
        if (role.equals("Initiator", ignoreCase = true)) {
            Log.i(TAG, "tie-break win ${contactId.take(8)}… — END_SESSION")
            sessionControl.sendEndSession(contactId)
        } else {
            Log.i(TAG, "tie-break lose ${contactId.take(8)}… — archive, wait for peer init")
            sessionControl.inboundEndSession(contactId)
        }
    }

    private companion object {
        const val TAG = "HealSession"
    }
}
