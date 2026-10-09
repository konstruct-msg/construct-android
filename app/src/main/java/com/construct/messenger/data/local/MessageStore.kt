package com.construct.messenger.data.local

import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.util.MediaWire
import kotlinx.coroutines.flow.Flow

/**
 * One message of a chat, as this device keeps it.
 *
 * Our own type — not Room's `MessageEntity`, not the core's `LocalMessage` (TODO 136, the seam).
 */
data class MessageRecord(
    val id: String,
    val chatId: String,
    val text: String,
    val isSentByMe: Boolean,
    val timestampMs: Long,
    val deliveryStatus: DeliveryStatus,
    val replyToId: String? = null,
    /** Wire `text_preview`, already capped at 200 characters. Null when there is no quote. */
    val replyPreview: String? = null,
    /** Proto `MediaType` name (`MEDIA_TYPE_IMAGE`, …). Null for a plain-text quote. */
    val replyMediaType: String? = null,
    /** True after `MessageContent.edit` replaced [text]. A redelivery must not put the old text back. */
    val isEdited: Boolean = false,
    /** `MediaWire.KIND_*` when the message carries media; [mediaPayload] is then its wire message. */
    val mediaType: String? = null,
    /** 0 = regular message; control types are never user-visible. */
    val contentType: Int = 0,
    /** The `MediaAlbumMessage` / `VoiceMessage` bytes as received — keys included, as iOS keeps them. */
    val mediaPayload: ByteArray? = null,
) {
    // ByteArray field: structural equality must be explicit.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageRecord) return false
        return id == other.id && chatId == other.chatId && text == other.text && isSentByMe == other.isSentByMe &&
            timestampMs == other.timestampMs && deliveryStatus == other.deliveryStatus && replyToId == other.replyToId &&
            replyPreview == other.replyPreview && replyMediaType == other.replyMediaType && isEdited == other.isEdited &&
            mediaType == other.mediaType && contentType == other.contentType && mediaPayload.contentEquals(other.mediaPayload)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + chatId.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + deliveryStatus.hashCode()
        result = 31 * result + isEdited.hashCode()
        result = 31 * result + mediaPayload.contentHashCode()
        return result
    }
}

/**
 * How strong a delivery status is as evidence that the message arrived — the core's
 * `delivery::evidence_rank`. A status is written only over one no stronger: a slower writer with a
 * weaker fact is refused (a `SENT` written after the receipt already said `DELIVERED`). `READ` is
 * Android's alone; nothing writes it yet, and the core has no such status.
 */
internal fun DeliveryStatus.evidenceRank(): Int = when (this) {
    DeliveryStatus.READ -> 3
    DeliveryStatus.DELIVERED -> 2
    DeliveryStatus.SENT -> 1
    DeliveryStatus.SENDING, DeliveryStatus.FAILED -> 0
}

/**
 * The messages this device holds — the only way in to their rows (TODO 136, step 1). Ids are
 * compared without case, as iOS compares them (`==[c]`); a write lands on the stored id. Writes
 * change their named fields of one message, as `LocalStore`'s do in the core; a write to a message
 * that does not exist changes nothing.
 *
 * Its chat's row must exist first ([ChatStore]) — the core holds the message to it.
 */
interface MessageStore {
    /** A chat's user-visible messages, oldest first. */
    fun observeChat(chatId: String): Flow<List<MessageRecord>>

    suspend fun get(id: String): MessageRecord?

    /** Adds [message] unless one with its id is there. False: it was, and nothing changed. */
    suspend fun insert(message: MessageRecord): Boolean

    /** Written unless the stored status is stronger evidence ([evidenceRank]). False: refused or unchanged. */
    suspend fun setDeliveryStatus(id: String, status: DeliveryStatus): Boolean

    /** New text, marked edited; for media also the wire message that carries the caption. */
    suspend fun edit(id: String, text: String, mediaPayload: ByteArray?)

    /** The uploaded media, by the ids the store gave it, in place of the staged ones. */
    suspend fun setMedia(id: String, mediaType: String, mediaPayload: ByteArray)

    /** The chat's newest user-visible message — what its preview shows. */
    suspend fun latestVisible(chatId: String): MessageRecord?

    suspend fun delete(id: String)

    /** Every message of the chat. */
    suspend fun deleteChat(chatId: String)
}

/** Apply an edit to [row]: the text, and for a photo also the caption inside its stored album. */
suspend fun MessageStore.applyEdit(row: MessageRecord, text: String) =
    edit(row.id, text, MediaWire.withCaption(row.mediaType, row.mediaPayload, text))

/** Point the chat at whatever message is now last. An empty transcript clears the preview. */
suspend fun MessageStore.refreshChatPreview(chats: ChatStore, chatId: String) {
    val latest = latestVisible(chatId)
    chats.setPreview(chatId, latest?.text, latest?.timestampMs)
}
