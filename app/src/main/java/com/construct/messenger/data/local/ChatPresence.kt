package com.construct.messenger.data.local

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which chat the user is looking at right now — its messages are read as they land.
 *
 * **Canon:** iOS `InAppNotificationService.activeChatId` + `isChatVisible`. iOS learned that
 * "open" is not "visible": a chat left open while the app sat in the background swallowed every
 * banner for 20 minutes (2026-07-31). Here the chat screen reports itself shown on ON_START and
 * hidden on ON_STOP, so backgrounding the app hides it too.
 */
@Singleton
class ChatPresence @Inject constructor() {
    private val visible = AtomicReference<String?>(null)

    fun shown(contactId: String) = visible.set(contactId)

    /** Only the chat that is shown may hide itself — a late ON_STOP of the previous one must not. */
    fun hidden(contactId: String) {
        visible.compareAndSet(contactId, null)
    }

    fun isVisible(contactId: String): Boolean = visible.get() == contactId
}

/** A chat to open once the tabs are up — set by tapping a message notification. */
@Singleton
class PendingChatStore @Inject constructor() {
    private val pendingChat = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = pendingChat.asStateFlow()

    fun offer(contactId: String) {
        if (contactId.isNotBlank()) pendingChat.value = contactId
    }

    fun take(): String? = pendingChat.value.also { pendingChat.value = null }
}
