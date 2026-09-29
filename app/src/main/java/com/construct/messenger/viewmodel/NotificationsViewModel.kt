package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import com.construct.messenger.data.repository.NotificationSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Settings → Notifications. The permission and the channels are the system's and are read by the
 * screen itself; this holds the one switch the app owns.
 */
@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val repository: NotificationSettingsRepository,
) : ViewModel() {
    val enabled: StateFlow<Boolean> = repository.enabled

    fun setEnabled(enabled: Boolean) = repository.setEnabled(enabled)
}
