package com.construct.messenger.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Message row.
 *
 * **Canon:** `docs/ANDROID_ONBOARDING.md` §8.3 —
 * `Message(id, chatId, text, isSentByMe, timestamp, deliveryStatus, replyToId?,
 * replyPreview?, replyMediaType?, isEdited, mediaType?, mediaUrl?, contentType: Int = 0)`.
 *
 * [replyToId] / [replyPreview] / [replyMediaType] are the encrypted `QuotedMessage`
 * (iOS `replyToMessageId` + `ReplyPreviewPayload`), never an envelope field.
 *
 * **Control-message guard (layer 2 of 3, §8.3):** control signals (session
 * ping/ready/reset/end) are normally consumed before persisting (layer 1 —
 * content_type dispatch in the receive path). If one ever lands here anyway,
 * [contentType] must be stamped non-zero so [MessageDao.observeChat] excludes
 * it (`contentType = 0` = regular user-visible message only). Layer 3 is a
 * Kotlin-side display guard on decrypted text.
 *
 * [deliveryStatus] stores `DeliveryStatus.name` (SENDING/SENT/DELIVERED/READ/FAILED)
 * as a plain String — no TypeConverter needed.
 */
@Entity(
    tableName = "messages",
    indices = [Index("chatId", "timestamp")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val text: String,
    val isSentByMe: Boolean,
    val timestamp: Long,
    val deliveryStatus: String,
    val replyToId: String? = null,
    /** Wire `text_preview`, already capped at 200 characters. Null when there is no quote. */
    val replyPreview: String? = null,
    /** Proto `MediaType` name (`MEDIA_TYPE_IMAGE`, …). Null for a plain-text quote. */
    val replyMediaType: String? = null,
    /** True after `MessageContent.edit` replaced [text]. A redelivery must not put the old text back. */
    val isEdited: Boolean = false,
    val mediaType: String? = null,
    val mediaUrl: String? = null,
    /** 0 = regular message; control types (21/24/25/26) are never user-visible. */
    val contentType: Int = 0,
)

@Dao
interface MessageDao {

    /** Chat history — user-visible rows only (control guard layer 3, §8.3). */
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND contentType = 0 ORDER BY timestamp ASC")
    fun observeChat(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getById(messageId: String): MessageEntity?

    /** Edits and quotes compare ids the way iOS does (`==[c]`). */
    @Query("SELECT * FROM messages WHERE id = :messageId COLLATE NOCASE LIMIT 1")
    suspend fun getByIdIgnoreCase(messageId: String): MessageEntity?

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query("UPDATE messages SET deliveryStatus = :status WHERE id = :messageId")
    suspend fun updateDeliveryStatus(messageId: String, status: String)

    @Query("UPDATE messages SET text = :text, isEdited = 1 WHERE id = :id")
    suspend fun markEdited(id: String, text: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query(
        "SELECT * FROM messages WHERE chatId = :chatId AND contentType = 0 " +
            "ORDER BY timestamp DESC LIMIT 1",
    )
    suspend fun latestVisible(chatId: String): MessageEntity?

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteChat(chatId: String)
}

/** Point the chat row at whatever message is now last. An empty transcript clears the preview. */
internal suspend fun refreshChatPreview(chatDao: ChatDao, messageDao: MessageDao, chatId: String) {
    val latest = messageDao.latestVisible(chatId)
    chatDao.updateLastMessage(chatId, latest?.text, latest?.timestamp ?: 0L)
}
