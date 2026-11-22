package com.construct.messenger.data.local

import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.diagnostics.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import uniffi.construct_core.LocalInsert
import uniffi.construct_core.LocalMediaKind
import uniffi.construct_core.LocalMessage
import uniffi.construct_core.LocalMessageInsert
import uniffi.construct_core.LocalStore
import uniffi.construct_core.LocalStoreTable

/**
 * [MessageStore] on the core's encrypted `LocalStore` (TODO 136, step 3), the only store since
 * Room went (2026-10-19), held to [MessageStoreContract].
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
        if (s.insertMessage(local, message.searchText()) != LocalInsert.INSERTED) return@io false
        indexMedia(s, local.id, local.body, replacing = false)
        true
    }

    /**
     * [messages] in one transaction (`insert_messages`) — the import's write. A message whose chat
     * is not here is left out, as [insert] leaves it; one already here is not counted. How many
     * were added.
     */
    internal suspend fun insertAll(messages: List<MessageRecord>): Int = io { s ->
        val rows = messages.mapNotNull { m -> m.local(s)?.let { LocalMessageInsert(it, m.searchText()) } }
        if (rows.isEmpty()) return@io 0
        val added = s.insertMessages(rows).toSet()
        rows.filter { it.message.id in added }.forEach { indexMedia(s, it.message.id, it.message.body, replacing = false) }
        added.size
    }

    override suspend fun setDeliveryStatus(id: String, status: DeliveryStatus): Boolean = io { s ->
        val stored = find(s, id) ?: return@io false
        s.setDeliveryStatus(stored.id, status.code())
    }

    override suspend fun edit(id: String, text: String, mediaPayload: ByteArray?) {
        io { s ->
            val stored = find(s, id) ?: return@io
            val row = stored.record().let { it.copy(text = text, mediaPayload = mediaPayload ?: it.mediaPayload) }
            val body = LocalMessagePayload.encode(row)
            if (s.editMessage(stored.id, body, row.searchText(), nowMs())) indexMedia(s, stored.id, body, replacing = true)
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
            val body = LocalMessagePayload.encode(row)
            if (s.setMessageBody(stored.id, body, row.searchText())) indexMedia(s, stored.id, body, replacing = true)
        }
    }

    override suspend fun media(chatId: String, kinds: List<LocalMediaKind>, before: ChatMediaItem?, limit: Int): List<ChatMediaItem> =
        io { s ->
            s.chatMedia(chatId, kinds, before?.orderKey, before?.messageId, before?.item?.toUInt(), limit.coerceAtLeast(0).toUInt())
                .map {
                    ChatMediaItem(
                        messageId = it.messageId, item = it.item.toInt(), mediaId = it.mediaId, kind = it.kind,
                        byteSize = it.byteSize, timestampMs = it.timestamp, isSentByMe = it.isSentByMe, orderKey = it.orderKey,
                    )
                }
        }

    // From the newest page back, not the whole chat: every stored message asks this.
    override suspend fun latestVisible(chatId: String): MessageRecord? = io { s ->
        var page = s.messagesBefore(chatId, null, null, LATEST_PAGE)
        while (page.isNotEmpty()) {
            page.lastOrNull { it.contentType.toInt() == 0 }?.let { return@io it.record() }
            if (page.size.toUInt() < LATEST_PAGE) break
            page = s.messagesBefore(chatId, page.first().orderKey, page.first().id, LATEST_PAGE)
        }
        null
    }

    override suspend fun ownInStatus(status: DeliveryStatus, limit: Int): List<MessageRecord> = io { s ->
        s.ownMessagesInStatus(status.code(), limit.toUInt()).map { it.record() }
    }

    override suspend fun delete(id: String) {
        io { s -> find(s, id)?.let { s.deleteMessage(it.id) } }
    }

    override suspend fun setTranscript(id: String, text: String?, language: String?, atMs: Long): Boolean = io { s ->
        val stored = find(s, id) ?: return@io false
        s.setTranscript(stored.id, text, language?.takeIf { text != null }, atMs.takeIf { text != null })
    }

    override suspend fun deleteChat(chatId: String) {
        io { s -> s.chatMessages(chatId).forEach { s.deleteMessage(it.id) } }
    }

    /**
     * The media index for a message just written ([MessageMediaIndex]). A body that cannot hold
     * media is not indexed on insert; one that replaces a body clears what the old one indexed. A
     * failure is logged, not thrown: the message is written, and [indexUnindexedMedia] after the
     * next open lists it again.
     */
    private fun indexMedia(s: LocalStore, id: String, body: ByteArray, replacing: Boolean) {
        val entries = MessageMediaIndex.entries(body) ?: if (replacing) emptyList() else return
        runCatching { s.setMessageMedia(id, entries) }
            .onFailure { Log.e(TAG, "media index for ${id.take(8)}… not written, the next open retries", it) }
    }

    /**
     * Index what the store lists as not indexed yet — rows an older build wrote, or one a failed
     * [indexMedia] left — a page at a time; how many were indexed. The first run on a long history
     * parses every album and message content once, so it runs off the open ([LocalStores]).
     */
    internal suspend fun indexUnindexedMedia(pageSize: UInt = 200u): Int = io { s ->
        var indexed = 0
        while (true) {
            val ids = s.unindexedMediaMessages(pageSize)
            if (ids.isEmpty()) break
            for (id in ids) {
                val body = s.message(id)?.body ?: ByteArray(0)
                // Indexed with no entries when nothing is found, so the store stops listing it.
                s.setMessageMedia(id, MessageMediaIndex.entries(body) ?: emptyList())
                indexed++
            }
        }
        indexed
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

private const val LATEST_PAGE = 50u
private const val TAG = "CoreMessageStore"

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
    DeliveryStatus.QUEUED -> 3
    DeliveryStatus.FAILED -> 4
}

private fun deliveryStatusOf(code: Short): DeliveryStatus = when (code.toInt()) {
    1 -> DeliveryStatus.SENT
    2 -> DeliveryStatus.DELIVERED
    3 -> DeliveryStatus.QUEUED
    4 -> DeliveryStatus.FAILED
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
        transcript = transcriptText,
        transcriptLanguage = transcriptLanguage,
    )
}
