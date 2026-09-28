package com.construct.messenger.viewmodel

import androidx.lifecycle.ViewModel
import com.construct.messenger.data.local.db.UserDao
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
    private val userDao: UserDao,
) : ViewModel() {
    val announcements: Flow<SecurityNoticeAnnouncement> = securityNotices.announced.map { event ->
        val row = userDao.getById(event.userId)
        val name = row?.username?.takeIf { it.isNotBlank() }?.let { "@$it" }
            ?: row?.displayName?.takeIf { it.isNotBlank() }
            ?: DisplayNameGenerator.generate(event.userId)
        SecurityNoticeAnnouncement(event.userId, name, event.notice)
    }
}
