package com.construct.messenger.data.local

import com.construct.messenger.data.model.DeliveryStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** [MessageStore] in a map, for tests; [MessageStoreContract] holds it to Room's behaviour. */
internal class FakeMessageStore : MessageStore {
    val rows = linkedMapOf<String, MessageRecord>()

    private fun stored(id: String) = rows.keys.firstOrNull { it.equals(id, ignoreCase = true) }

    private fun change(id: String, f: (MessageRecord) -> MessageRecord) {
        stored(id)?.let { key -> rows[key] = f(rows.getValue(key)) }
    }

    override fun observeChat(chatId: String): Flow<List<MessageRecord>> = flow {
        emit(rows.values.filter { it.chatId == chatId && it.contentType == 0 }.sortedWith(compareBy({ it.orderKey }, { it.id })))
    }

    override suspend fun get(id: String): MessageRecord? = stored(id)?.let(rows::get)

    override suspend fun insert(message: MessageRecord): Boolean {
        if (message.id in rows) return false
        rows[message.id] = message.copy(orderKey = message.orderKeyOrLocal())
        return true
    }

    override suspend fun setDeliveryStatus(id: String, status: DeliveryStatus): Boolean {
        val key = stored(id) ?: return false
        val row = rows.getValue(key)
        if (row.deliveryStatus == status || row.deliveryStatus.evidenceRank() > status.evidenceRank()) return false
        rows[key] = row.copy(deliveryStatus = status)
        return true
    }

    override suspend fun edit(id: String, text: String, mediaPayload: ByteArray?) = change(id) {
        it.copy(text = text, isEdited = true, mediaPayload = mediaPayload ?: it.mediaPayload)
    }

    override suspend fun setOrderKey(id: String, orderKey: String): Boolean {
        val key = stored(id) ?: return false
        if (rows.getValue(key).orderKey == orderKey) return false
        rows[key] = rows.getValue(key).copy(orderKey = orderKey)
        return true
    }

    override suspend fun setMedia(id: String, mediaType: String, mediaPayload: ByteArray) =
        change(id) { it.copy(mediaType = mediaType, mediaPayload = mediaPayload) }

    override suspend fun latestVisible(chatId: String): MessageRecord? =
        rows.values.filter { it.chatId == chatId && it.contentType == 0 }.maxWithOrNull(compareBy({ it.orderKey }, { it.id }))

    override suspend fun delete(id: String) {
        stored(id)?.let(rows::remove)
    }

    override suspend fun deleteChat(chatId: String) {
        rows.values.removeAll { it.chatId == chatId }
    }
}
