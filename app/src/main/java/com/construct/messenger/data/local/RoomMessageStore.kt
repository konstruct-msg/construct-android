package com.construct.messenger.data.local

import com.construct.messenger.data.local.db.MessageDao
import com.construct.messenger.data.local.db.MessageEntity
import com.construct.messenger.data.model.DeliveryStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [MessageStore] on Room's `messages` table — until the core's `LocalStore` takes it (TODO 136).
 * Built by `DatabaseModule`, which provides no `MessageDao`: nothing else can reach the table.
 */
class RoomMessageStore(private val dao: MessageDao) : MessageStore {
    override fun observeChat(chatId: String): Flow<List<MessageRecord>> = dao.observeChat(chatId).map { rows -> rows.map { it.record() } }

    override suspend fun get(id: String): MessageRecord? = dao.getByIdIgnoreCase(id)?.record()

    override suspend fun insert(message: MessageRecord): Boolean = dao.insertIfAbsent(message.entity()) != -1L

    override suspend fun setDeliveryStatus(id: String, status: DeliveryStatus): Boolean {
        val stored = dao.getByIdIgnoreCase(id) ?: return false
        return dao.raiseDeliveryStatus(stored.id, status.name, status.evidenceRank()) > 0
    }

    override suspend fun edit(id: String, text: String, mediaPayload: ByteArray?) {
        val stored = dao.getByIdIgnoreCase(id) ?: return
        if (mediaPayload != null) dao.markEditedMedia(stored.id, text, mediaPayload) else dao.markEdited(stored.id, text)
    }

    override suspend fun setMedia(id: String, mediaType: String, mediaPayload: ByteArray) {
        val stored = dao.getByIdIgnoreCase(id) ?: return
        dao.setMedia(stored.id, mediaType, mediaPayload)
    }

    override suspend fun latestVisible(chatId: String): MessageRecord? = dao.latestVisible(chatId)?.record()

    override suspend fun delete(id: String) {
        val stored = dao.getByIdIgnoreCase(id) ?: return
        dao.deleteById(stored.id)
    }

    override suspend fun deleteChat(chatId: String) = dao.deleteChat(chatId)
}

private fun MessageEntity.record() = MessageRecord(
    id = id,
    chatId = chatId,
    text = text,
    isSentByMe = isSentByMe,
    timestampMs = timestamp,
    // A name this build does not know came from a newer one; SENT is what iOS shows for it.
    deliveryStatus = runCatching { DeliveryStatus.valueOf(deliveryStatus) }.getOrDefault(DeliveryStatus.SENT),
    replyToId = replyToId,
    replyPreview = replyPreview,
    replyMediaType = replyMediaType,
    isEdited = isEdited,
    mediaType = mediaType,
    contentType = contentType,
    mediaPayload = mediaPayload,
)

private fun MessageRecord.entity() = MessageEntity(
    id = id,
    chatId = chatId,
    text = text,
    isSentByMe = isSentByMe,
    timestamp = timestampMs,
    deliveryStatus = deliveryStatus.name,
    replyToId = replyToId,
    replyPreview = replyPreview,
    replyMediaType = replyMediaType,
    isEdited = isEdited,
    mediaType = mediaType,
    contentType = contentType,
    mediaPayload = mediaPayload,
)
