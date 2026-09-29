package com.construct.messenger.data.repository

import android.content.Context
import com.construct.messenger.stealth.StealthPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lockdown: on since [activatedAt] (epoch ms), letting through only [approved] senders. */
data class Lockdown(
    val activatedAt: Long? = null,
    val approved: Set<String> = emptySet(),
) {
    val isActive: Boolean get() = activatedAt != null
}

/**
 * Settings → Security state that lives on this device.
 *
 * **Lockdown** (iOS `LockdownManager`): a receiver-side shield. When turned on, the contacts of
 * that moment are the approved set; a message from anyone else still arrives, is decrypted and
 * saved, but posts no notification. Nothing is sent and the server cannot tell it is on.
 *
 * **Sender anonymity** is read from [StealthPolicy] so the Security screen states it without
 * reaching into `stealth/` itself.
 */
@Singleton
class SecuritySettingsRepository @Inject constructor(
    @param:ApplicationContext context: Context,
    private val stealthPolicy: StealthPolicy,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lockdownState = MutableStateFlow(loadLockdown())
    val lockdown: StateFlow<Lockdown> = lockdownState.asStateFlow()

    /** Always true in release; a debug override can turn it off, and the screen says so. */
    val senderAnonymity: Boolean get() = stealthPolicy.isEnabled

    fun enableLockdown(approvedIds: Set<String>) {
        val next = Lockdown(activatedAt = System.currentTimeMillis(), approved = approvedIds)
        prefs.edit()
            .putLong(KEY_ACTIVATED_AT, next.activatedAt!!)
            .putStringSet(KEY_APPROVED, approvedIds)
            .apply()
        lockdownState.value = next
    }

    fun disableLockdown() {
        prefs.edit().remove(KEY_ACTIVATED_AT).remove(KEY_APPROVED).apply()
        lockdownState.value = Lockdown()
    }

    /** iOS `LockdownManager.shouldSuppress`: a sender outside the approved set gets no alert. */
    fun suppressesAlertFrom(senderId: String): Boolean {
        val current = lockdownState.value
        return current.isActive && senderId !in current.approved
    }

    private fun loadLockdown(): Lockdown {
        if (!prefs.contains(KEY_ACTIVATED_AT)) return Lockdown()
        return Lockdown(
            activatedAt = prefs.getLong(KEY_ACTIVATED_AT, 0),
            approved = prefs.getStringSet(KEY_APPROVED, emptySet()).orEmpty().toSet(),
        )
    }

    private companion object {
        const val PREFS = "security_prefs"
        const val KEY_ACTIVATED_AT = "lockdown.activatedAt"
        const val KEY_APPROVED = "lockdown.approvedSenders"
    }
}
