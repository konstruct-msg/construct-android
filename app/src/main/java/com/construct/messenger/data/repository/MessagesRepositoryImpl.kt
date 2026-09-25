package com.construct.messenger.data.repository

import com.construct.messenger.data.local.ChatPresence
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.db.ChatDao
import com.construct.messenger.data.local.db.MessageDao
import com.construct.messenger.data.local.db.MessageEntity
import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.data.model.Message
import com.construct.messenger.data.model.ReplyRef
import com.construct.messenger.domain.usecase.SendMessageUseCase
import com.construct.messenger.domain.usecase.SendOutcome
import com.construct.messenger.service.IncomingAlerts
import com.construct.messenger.util.ConversationId
import javax.inject.Inject
import javax.inject.Singleton
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
) : MessagesRepository {

    override fun observeContact(contactId: String): Flow<List<Message>> {
        val myId = keystoreManager.getUserId() ?: return emptyFlow()
        val chatId = ConversationId.direct(myId, contactId)
        return messageDao.observeChat(chatId).map { rows -> rows.map { it.toModel() } }
    }

    override suspend fun send(contactId: String, text: String, reply: ReplyRef?): SendOutcome =
        sendMessage(contactId, text, reply)

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
)
