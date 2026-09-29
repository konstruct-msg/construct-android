package com.construct.messenger.data.repository

import android.content.Context
import com.construct.messenger.service.MessageNotifier
import com.construct.messenger.service.MessagingForegroundService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings → Notifications: whether a new message posts a notification at all.
 *
 * The one switch the app owns. Sound, vibration and lock-screen behaviour belong to the
 * notification channel on Android 8+ (the minimum here), where the system settings are the real
 * controls and an in-app copy could only drift from them. iOS keeps sound / vibration toggles that
 * nothing reads; they are not ported. Key is iOS's `notificationsEnabled`.
 */
@Singleton
class NotificationSettingsRepository @Inject constructor(
    @param:ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = state.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        state.value = enabled
    }

    companion object {
        /** New messages: sound, vibration, lock screen — the system's controls. */
        const val MESSAGES_CHANNEL = MessageNotifier.CHANNEL_ID
        /** The ongoing "connected" notification of the foreground service. */
        const val CONNECTION_CHANNEL = MessagingForegroundService.CHANNEL_ID
        private const val PREFS = "notification_prefs"
        private const val KEY_ENABLED = "notificationsEnabled"
    }
}
