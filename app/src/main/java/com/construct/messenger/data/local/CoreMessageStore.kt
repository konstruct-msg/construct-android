package com.construct.messenger.data.local

import com.construct.messenger.data.model.DeliveryStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import uniffi.construct_core.LocalInsert
import uniffi.construct_core.LocalMessage
import uniffi.construct_core.LocalStore
import uniffi.construct_core.LocalStoreTable

/**
 * [MessageStore] on the core's encrypted `LocalStore` (TODO 136, step 3) — held to the same
 * [MessageStoreContract] as [RoomMessageStore]. Not wired into the app yet: Room stays the store
 * until the import moves its rows here.
 *
 * The row is iOS's, field for field — the owner's decision that both clients write one format:
 * - `body` is CTM1 ([LocalMessagePayload]): the whole wire `MessageContent`;
 * - `reply_to_content` is iOS's stored quote ([ReplyQuoteStorage]);
 * - `order_key` is `ServerMessageOrder`; `from`/`to` are account ids — ours and the chat's peer;
 * - statuses are the core's numbers; `suite_id` 0, as iOS leaves it.
 *
 * Ids: the core compares them exactly, so they cross this seam lowercase — as the iOS plan has it,
 * rather than carrying case-insensitivity into the core. Android's are lowercase already (incoming
 * ids are normalized, ours are UUIDs); a lookup tries the lowercase id, then the id as given.
 */
class CoreMessageStore(
    private val feed: LocalStoreFeed,
    private val myUserId: () -> String?,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : MessageStore {
    private val store: LocalStore get() = feed.store

    private suspend fun <T> io(block: (LocalStore) -> T): T = withContext(Dispatchers.IO) { block(store) }

    private fun find(s: LocalStore, id: String): LocalMessage? = s.message(id.lowercase()) ?: s.message(id)

    override fun observeChat(chatId: String): Flow<List<MessageRecord>> =
        feed.watch(LocalStoreTable.MESSAGES) { s -> visible(s, chatId).map { it.record() } }

    override suspend fun get(id: String): MessageRecord? = io { find(it, id)?.record() }

    override suspend fun insert(message: MessageRecord): Boolean = io { s ->
        val local = message.local(s) ?: return@io false
        s.insertMessage(local, message.searchText()) == LocalInsert.INSERTED
    }

    override suspend fun setDeliveryStatus(id: String, status: DeliveryStatus): Boolean = io { s ->
        val stored = find(s, id) ?: return@io false
        s.setDeliveryStatus(stored.id, status.code())
    }

    override suspend fun edit(id: String, text: String, mediaPayload: ByteArray?) {
        io { s ->
            val stored = find(s, id) ?: return@io
            val row = stored.record().let { it.copy(text = text, mediaPayload = mediaPayload ?: it.mediaPayload) }
            s.editMessage(stored.id, LocalMessagePayload.encode(row), row.searchText(), nowMs())
        }
    }

    override suspend fun setOrderKey(id: String, orderKey: String): Boolean = io { s ->
        val stored = find(s, id) ?: return@io false
        s.setOrderKey(stored.id, orderKey)
    }

    // A body that says what it said all along — the uploaded media in place of the staged copy —
    // not an edit (`set_message_body`, core 0.38.0).
    override suspend fun setMedia(id: String, mediaType: String, mediaPayload: ByteArray) {
        io { s ->
            val stored = find(s, id) ?: return@io
            val row = stored.record().copy(mediaType = mediaType, mediaPayload = mediaPayload)
            s.setMessageBody(stored.id, LocalMessagePayload.encode(row), row.searchText())
        }
    }

    override suspend fun latestVisible(chatId: String): MessageRecord? = io { s -> visible(s, chatId).lastOrNull()?.record() }

    override suspend fun delete(id: String) {
        io { s -> find(s, id)?.let { s.deleteMessage(it.id) } }
    }

    override suspend fun deleteChat(chatId: String) {
        io { s -> s.chatMessages(chatId).forEach { s.deleteMessage(it.id) } }
    }

    private fun visible(s: LocalStore, chatId: String) = s.chatMessages(chatId).filter { it.contentType.toInt() == 0 }

    private fun MessageRecord.local(s: LocalStore): LocalMessage? {
        val me = myUserId() ?: return null
        val peer = s.chat(chatId)?.peerId ?: return null
        val quote = ReplyQuoteStorage.encode(replyPreview, replyMediaType)
        return LocalMessage(
            id = id.lowercase(),
            chatId = chatId,
            fromUserId = if (isSentByMe) me else peer,
            toUserId = if (isSentByMe) peer else me,
            isSentByMe = isSentByMe,
            timestamp = timestampMs,
            orderKey = orderKeyOrLocal(),
            body = LocalMessagePayload.encode(this),
            contentType = contentType.toShort(),
            deliveryStatus = deliveryStatus.code(),
            retryCount = 0,
            suiteId = 0,
            isEdited = isEdited,
            editedAt = null,
            replyToMessageId = replyToId,
            replyToContent = quote,
            transcriptText = null,
            transcriptLanguage = null,
            transcriptGeneratedAt = null,
        )
    }

}

/** The chat's messages in the store's order, oldest first: newest page, then the pages before it. */
internal fun LocalStore.chatMessages(chatId: String): List<LocalMessage> {
    val page = 500u
    val pages = ArrayDeque<List<LocalMessage>>()
    var next = messagesBefore(chatId, null, null, page)
    while (next.isNotEmpty()) {
        pages.addFirst(next)
        if (next.size.toUInt() < page) break
        next = messagesBefore(chatId, next.first().orderKey, next.first().id, page)
    }
    return pages.flatten()
}

/** What the full-text index reads: the words a person wrote — a text, or a media caption. */
private fun MessageRecord.searchText(): String? = text.takeIf { it.isNotBlank() && contentType == 0 }

/** The core's numbers (`0 sending, 1 sent, 2 delivered, 3 queued, 4 failed`). */
private fun DeliveryStatus.code(): Short = when (this) {
    DeliveryStatus.SENDING -> 0
    DeliveryStatus.SENT -> 1
    DeliveryStatus.DELIVERED -> 2
    DeliveryStatus.FAILED -> 4
}

private fun deliveryStatusOf(code: Short): DeliveryStatus = when (code.toInt()) {
    1 -> DeliveryStatus.SENT
    2 -> DeliveryStatus.DELIVERED
    4 -> DeliveryStatus.FAILED
    // 3, queued: iOS's "to send again" — Android has one waiting state.
    else -> DeliveryStatus.SENDING
}

private fun LocalMessage.record(): MessageRecord {
    val fields = LocalMessagePayload.decode(body)
    val quote = ReplyQuoteStorage.decode(replyToContent)
    // The quote inside the body is the wire's own; the stored columns answer for a body without one.
    val reply = fields.reply
    return MessageRecord(
        id = id,
        chatId = chatId,
        text = fields.text,
        isSentByMe = isSentByMe,
        timestampMs = timestamp,
        deliveryStatus = deliveryStatusOf(deliveryStatus),
        replyToId = reply?.messageId ?: replyToMessageId,
        replyPreview = reply?.preview ?: quote?.first,
        replyMediaType = reply?.mediaType ?: quote?.second,
        isEdited = isEdited,
        mediaType = fields.mediaType,
        contentType = contentType.toInt(),
        mediaPayload = fields.mediaPayload,
        orderKey = orderKey,
    )
}
