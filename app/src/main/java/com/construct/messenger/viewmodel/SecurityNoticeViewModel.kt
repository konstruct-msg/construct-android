package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import com.construct.messenger.data.local.ContactStore
import com.construct.messenger.data.model.SecurityNotice
import com.construct.messenger.security.SecurityNotices
import com.construct.messenger.util.DisplayNameGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One app-wide notice: who, what, and the name to say it with. */
data class SecurityNoticeAnnouncement(val userId: String, val name: String, val notice: SecurityNotice)

@HiltViewModel
class SecurityNoticeViewModel @Inject constructor(
    securityNotices: SecurityNotices,
    private val contacts: ContactStore,
) : ViewModel() {
    val announcements: Flow<SecurityNoticeAnnouncement> = securityNotices.announced.map { event ->
        val row = contacts.get(event.userId)
        val name = row?.localName
            ?: row?.username?.takeIf { it.isNotBlank() }?.let { "@$it" }
            ?: row?.displayName?.takeIf { it.isNotBlank() }
            ?: DisplayNameGenerator.generate(event.userId)
        SecurityNoticeAnnouncement(event.userId, name, event.notice)
    }
}
