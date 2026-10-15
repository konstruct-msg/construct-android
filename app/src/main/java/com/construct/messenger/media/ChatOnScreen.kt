package com.construct.messenger.media

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which chat is on screen, for the now-playing banner: it stays out of the chat its track came
 * from, where the message's own row is the control. And the message to bring into view once a
 * chat opened from the banner. **Canon:** iOS `ChatOnScreen` (TODO 151).
 */
@Singleton
class ChatOnScreen @Inject constructor() {
    private val _contactId = MutableStateFlow<String?>(null)

    /** The chat whose transcript is on screen; null on every other screen. */
    val contactId: StateFlow<String?> = _contactId.asStateFlow()

    private var focus: PlaybackSource? = null

    fun appeared(contactId: String) {
        _contactId.value = contactId
    }

    /** Only the chat that appeared clears it: the next chat may show before the last one has gone. */
    fun disappeared(contactId: String) {
        _contactId.compareAndSet(contactId, null)
    }

    fun requestFocus(source: PlaybackSource) {
        focus = source
    }

    /** The message to scroll to in [contactId], once. */
    fun takeFocus(contactId: String): String? {
        val f = focus?.takeIf { it.contactId == contactId } ?: return null
        focus = null
        return f.messageId
    }

    companion object {
        /** Up while a track it can name is loaded, except in that track's own chat. */
        fun showsBanner(source: PlaybackSource?, onScreen: String?): Boolean =
            source != null && source.contactId != onScreen
    }
}
