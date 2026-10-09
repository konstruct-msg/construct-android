package com.construct.messenger.security

import com.construct.messenger.data.local.ChatPresence
import com.construct.messenger.data.model.KtStatus
import com.construct.messenger.data.model.SecurityNotice
import com.construct.messenger.data.local.ContactStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A contact's security event, made visible: stored on their row, where the chat banner reads it
 * until the user acknowledges it, and announced once app-wide unless their chat is the one open
 * (the banner already says it there). **Canon:** iOS `KeyChangeUX`.
 */
@Singleton
class SecurityNotices @Inject constructor(
    private val contacts: ContactStore,
    private val presence: ChatPresence,
) {
    data class Announcement(val userId: String, val notice: SecurityNotice)

    private val announcements = MutableSharedFlow<Announcement>(extraBufferCapacity = 8)
    val announced: SharedFlow<Announcement> = announcements.asSharedFlow()

    suspend fun raise(userId: String, notice: SecurityNotice) {
        contacts.setSecurityNotice(userId, notice.code)
        if (userId.isNotEmpty() && !presence.isVisible(userId)) {
            announcements.tryEmit(Announcement(userId, notice))
        }
    }

    /**
     * The user saw the warning. A failed KT proof goes back to "not verified", not to verified:
     * iOS `KeyChangeUX.acknowledgeKeyChange` writes `.verified` there, which shows the verified
     * mark for a proof that never passed. The next fetch with a proof writes the real verdict.
     */
    suspend fun acknowledge(userId: String) {
        contacts.setSecurityNotice(userId, SecurityNotice.NONE.code)
        if (contacts.get(userId)?.ktStatus == KtStatus.FAILED.code) {
            contacts.setKtStatus(userId, KtStatus.UNVERIFIED.code)
        }
    }
}
