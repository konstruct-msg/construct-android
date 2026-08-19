package com.construct.messenger.data.local

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds a deep-link invite until Synaps is ready to redeem it
 * (cold start may still be on Splash / Onboarding).
 */
@Singleton
class PendingInviteStore @Inject constructor() {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun offer(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isNotEmpty()) _pending.value = trimmed
    }

    fun take(): String? {
        val value = _pending.value
        _pending.value = null
        return value
    }
}
