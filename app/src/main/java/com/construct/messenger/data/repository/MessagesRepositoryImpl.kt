package com.construct.messenger.data.repository

import android.net.Uri
import com.construct.messenger.data.local.ChatPresence
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.db.ChatDao
import com.construct.messenger.data.local.db.MessageDao
import com.construct.messenger.data.local.db.MessageEntity
import com.construct.messenger.util.MediaWire
import com.construct.messenger.data.local.db.refreshChatPreview
import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.data.model.Message
import com.construct.messenger.data.model.ReplyRef
import com.construct.messenger.domain.usecase.SendContactCardUseCase
import com.construct.messenger.domain.usecase.SendMediaUseCase
import com.construct.messenger.domain.usecase.SendMessageUseCase
import com.construct.messenger.domain.usecase.SendOutcome
import com.construct.messenger.service.IncomingAlerts
import com.construct.messenger.util.ConversationId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

@Singleton
class MessagesRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
    private val keystoreManager: KeystoreManager,
    private val sendMessage: SendMessageUseCase,
    private val chatDao: ChatDao,
    private val presence: ChatPresence,
    private val alerts: IncomingAlerts,
    private val sendContactCard: SendContactCardUseCase,
    private val sendMedia: SendMediaUseCase,
    private val media: MediaRepository,
    private val pickedFiles: com.construct.messenger.media.PickedFiles,
) : MessagesRepository {

    override fun observeContact(contactId: String): Flow<List<Message>> {
        val myId = keystoreManager.getUserId() ?: return emptyFlow()
        val chatId = ConversationId.direct(myId, contactId)
        return messageDao.observeChat(chatId).map { rows -> rows.map { it.toModel() } }
    }

    override suspend fun send(contactId: String, text: String, reply: ReplyRef?): SendOutcome {
        val outcome = sendMessage(contactId, text, reply)
        // The first time we write to a device, it gets our card too (the other trigger is
        // hearing from it). After the message, so a control envelope never delays the bubble.
        if (outcome is SendOutcome.Sent) {
            runCatching { sendContactCard.sendIfOwed(contactId) }
        }
        return outcome
    }

    /** On a scope of its own: leaving the chat must not cancel an upload half done. */
    override suspend fun sendPhotos(contactId: String, uris: List<Uri>, caption: String, reply: ReplyRef?): SendOutcome =
        scope.async {
            val outcome = sendMedia.photos(contactId, uris, caption, reply)
            if (outcome is SendOutcome.Sent) {
                runCatching { sendContactCard.sendIfOwed(contactId) }
            }
            outcome
        }.await()

    override suspend fun sendVoice(contactId: String, recording: java.io.File, durationMs: Long, waveform: List<Float>): SendOutcome =
        scope.async { sendMedia.voice(contactId, recording, durationMs, waveform) }.await()

    override suspend fun sendFiles(contactId: String, uris: List<Uri>, caption: String): SendOutcome =
        scope.async { sendMedia.files(contactId, uris, caption) }.await()

    override suspend fun openable(item: com.construct.messenger.data.model.MediaItem, name: String): Uri = media.openable(item, name)

    override fun describe(uri: Uri): Pair<String, Long> = pickedFiles.describe(uri)

    override suspend fun mediaBytes(item: com.construct.messenger.data.model.MediaItem): ByteArray = media.bytes(item)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun edit(contactId: String, messageId: String, newText: String): SendOutcome =
        sendMessage.edit(contactId, messageId, newText)

    override suspend fun delete(contactId: String, messageId: String) {
        val myId = keystoreManager.getUserId() ?: return
        val chatId = ConversationId.direct(myId, contactId)
        val row = messageDao.getByIdIgnoreCase(messageId) ?: return
        if (row.chatId != chatId) return
        messageDao.deleteById(row.id)
        refreshChatPreview(chatDao, messageDao, chatId)
    }

    override suspend fun chatShown(contactId: String) {
        // Presence first: a message landing between these two lines is then not counted.
        presence.shown(contactId)
        keystoreManager.getUserId()?.let { myId ->
            chatDao.updateUnreadCount(ConversationId.direct(myId, contactId), 0)
        }
        alerts.clear(contactId)
    }

    override fun chatHidden(contactId: String) = presence.hidden(contactId)
}

private fun MessageEntity.toModel(): Message = Message(
    id = id,
    chatId = chatId,
    body = text,
    isOutgoing = isSentByMe,
    timestamp = timestamp,
    deliveryStatus = runCatching { DeliveryStatus.valueOf(deliveryStatus) }
        .getOrDefault(DeliveryStatus.SENT),
    replyToId = replyToId,
    replyPreview = replyPreview,
    replyMediaType = replyMediaType,
    isEdited = isEdited,
    media = MediaWire.decode(mediaType, mediaPayload),
)
