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
)

