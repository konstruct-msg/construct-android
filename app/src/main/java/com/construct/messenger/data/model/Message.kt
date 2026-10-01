package com.construct.messenger.data.model

data class Message(
    val id: String,
    val chatId: String,
    val body: String,
    val isOutgoing: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val deliveryStatus: DeliveryStatus = DeliveryStatus.SENT,
    /** Id of the message this one quotes. Null when it is not a reply. */
    val replyToId: String? = null,
    /** Short user-authored preview of the quoted message. Empty when the quote is media. */
    val replyPreview: String? = null,
    /** Proto `MediaType` name when the quote is a photo, voice note, file, or sticker. */
    val replyMediaType: String? = null,
    /** Set when a later `MessageContent.edit` replaced [body]. The timestamp stays the original. */
    val isEdited: Boolean = false,
    /** Photos, videos, files or a voice note; [body] is then the caption, possibly empty. */
    val media: MessageMedia? = null,
    /** One per reactor, oldest first. A reaction is never a row of its own. */
    val reactions: List<MessageReaction> = emptyList(),
) {
    /** The emoji this account has on it, if any — what a repeat tap takes off. */
    val myReaction: String? get() = reactions.firstOrNull { it.isMine }?.emoji

    /**
     * iOS menu rule for Edit: ours, and text or a photo/video caption. Not a file or a voice note —
     * their text is not what the bubble is about, and a voice note has none.
     */
    val isEditable: Boolean
        get() = isOutgoing && when (val m = media) {
            null -> body.isNotBlank()
            is MessageMedia.Album -> !m.isFiles && !com.construct.messenger.util.MediaWire.isStaged(m)
            is MessageMedia.Voice -> false
        }
}

data class MessageReaction(val emoji: String, val isMine: Boolean)

