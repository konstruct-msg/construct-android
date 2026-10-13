package com.construct.messenger.data.local

import com.construct.messenger.data.model.ChatActivity
import com.construct.messenger.data.model.DeliveryStatus
import kotlinx.coroutines.flow.Flow

/**
 * The stores the app is given (TODO 136): each one waits for [LocalStores] to decide which store
 * answers, then hands every call to it. A call made while the import runs — or while the store
 * would not open — waits; it neither fails nor lands in a store that is not the account's.
 *
 * A watch follows the backing when it changes (a retry after a failure installs one).
 */
interface LocalStoreGate {
    suspend fun backing(): LocalStores.Backing

    fun <T> watch(pick: (LocalStores.Backing) -> Flow<T>): Flow<T>
}

class GatedChatStore(private val gate: LocalStoreGate) : ChatStore {
    private suspend fun s() = gate.backing().chats
    override fun observeAll(): Flow<List<ChatRecord>> = gate.watch { it.chats.observeAll() }
    override fun observeActivity(): Flow<List<ChatActivity>> = gate.watch { it.chats.observeActivity() }
    override suspend fun get(id: String) = s().get(id)
    override suspend fun insert(chat: ChatRecord) = s().insert(chat)
    override suspend fun advancePreview(id: String, text: String, timeMs: Long) = s().advancePreview(id, text, timeMs)
    override suspend fun setPreview(id: String, text: String?, timeMs: Long?) = s().setPreview(id, text, timeMs)
    override suspend fun incrementUnread(id: String) = s().incrementUnread(id)
    override suspend fun setUnread(id: String, count: Int) = s().setUnread(id, count)
    override suspend fun setPinned(id: String, pinned: Boolean) = s().setPinned(id, pinned)
    override suspend fun delete(id: String) = s().delete(id)
}

class GatedMessageStore(private val gate: LocalStoreGate) : MessageStore {
    private suspend fun s() = gate.backing().messages
    override fun observeChat(chatId: String): Flow<List<MessageRecord>> = gate.watch { it.messages.observeChat(chatId) }
    override suspend fun get(id: String) = s().get(id)
    override suspend fun insert(message: MessageRecord) = s().insert(message)
    override suspend fun setDeliveryStatus(id: String, status: DeliveryStatus) = s().setDeliveryStatus(id, status)
    override suspend fun edit(id: String, text: String, mediaPayload: ByteArray?) = s().edit(id, text, mediaPayload)
    override suspend fun setOrderKey(id: String, orderKey: String) = s().setOrderKey(id, orderKey)
    override suspend fun setMedia(id: String, mediaType: String, mediaPayload: ByteArray) = s().setMedia(id, mediaType, mediaPayload)
    override suspend fun latestVisible(chatId: String) = s().latestVisible(chatId)
    override suspend fun delete(id: String) = s().delete(id)
    override suspend fun deleteChat(chatId: String) = s().deleteChat(chatId)
    override suspend fun setTranscript(id: String, text: String?, language: String?, atMs: Long) = s().setTranscript(id, text, language, atMs)
}

class GatedReactionStore(private val gate: LocalStoreGate) : ReactionStore {
    private suspend fun s() = gate.backing().reactions
    override fun observeChat(chatId: String): Flow<List<ReactionRecord>> = gate.watch { it.reactions.observeChat(chatId) }
    override suspend fun get(targetMessageId: String, reactorUserId: String) = s().get(targetMessageId, reactorUserId)
    override suspend fun put(reaction: ReactionRecord) = s().put(reaction)
    override suspend fun delete(targetMessageId: String, reactorUserId: String) = s().delete(targetMessageId, reactorUserId)
    override suspend fun deleteOrphansBefore(cutoffMs: Long) = s().deleteOrphansBefore(cutoffMs)
}

class GatedContactStore(private val gate: LocalStoreGate) : ContactStore {
    private suspend fun s() = gate.backing().contacts
    override fun observeContacts(): Flow<List<ContactRecord>> = gate.watch { it.contacts.observeContacts() }
    override fun observeBlocked(): Flow<List<ContactRecord>> = gate.watch { it.contacts.observeBlocked() }
    override fun observeAll(): Flow<List<ContactRecord>> = gate.watch { it.contacts.observeAll() }
    override fun observe(id: String): Flow<ContactRecord?> = gate.watch { it.contacts.observe(id) }
    override suspend fun get(id: String) = s().get(id)
    override suspend fun ensure(id: String) = s().ensure(id)
    override suspend fun upsert(record: ContactRecord) = s().upsert(record)
    override suspend fun delete(id: String) = s().delete(id)
    override suspend fun setBlocked(id: String, blocked: Boolean) = s().setBlocked(id, blocked)
    override suspend fun setAlias(id: String, alias: String?) = s().setAlias(id, alias)
    override suspend fun setSharingWith(id: String, sharing: Boolean) = s().setSharingWith(id, sharing)
    override suspend fun sharingWith(exceptAccount: String?) = s().sharingWith(exceptAccount)
    override suspend fun rememberIdentity(id: String, identityPublic: ByteArray) = s().rememberIdentity(id, identityPublic)
    override suspend fun setAccountAddress(id: String, address: ByteArray?) = s().setAccountAddress(id, address)
    override suspend fun setKtStatus(id: String, status: Int) = s().setKtStatus(id, status)
    override suspend fun setSecurityNotice(id: String, notice: Int) = s().setSecurityNotice(id, notice)
    override suspend fun pendingAvatarIds() = s().pendingAvatarIds()
    override suspend fun clearPendingAvatar(id: String, stored: ByteArray) = s().clearPendingAvatar(id, stored)
    override suspend fun completePendingAvatar(id: String, stored: ByteArray, avatar: ByteArray) = s().completePendingAvatar(id, stored, avatar)
}

class GatedProcessedStore(private val gate: LocalStoreGate) : ProcessedStore {
    private suspend fun s() = gate.backing().processed
    override suspend fun record(messageId: String, senderId: String, processedAtMs: Long) = s().record(messageId, senderId, processedAtMs)
    override suspend fun ids() = s().ids()
    override suspend fun forgetBefore(cutoffMs: Long) = s().forgetBefore(cutoffMs)
}

class GatedSessionBlobStore(private val gate: LocalStoreGate) : SessionBlobStore {
    private suspend fun s() = gate.backing().sessionBlobs
    override suspend fun put(key: String, bytes: ByteArray) = s().put(key, bytes)
    override suspend fun get(key: String) = s().get(key)
    override suspend fun remove(key: String) = s().remove(key)
    override suspend fun withPrefix(prefix: String) = s().withPrefix(prefix)
}

class GatedIssuedInviteStore(private val gate: LocalStoreGate) : IssuedInviteStore {
    private suspend fun s() = gate.backing().issuedInvites
    override fun observeAll(): Flow<List<IssuedInviteRecord>> = gate.watch { it.issuedInvites.observeAll() }
    override suspend fun record(invite: IssuedInviteRecord) = s().record(invite)
    override suspend fun delete(jti: String) = s().delete(jti)
}

class GatedPeerDeviceStore(private val gate: LocalStoreGate) : PeerDeviceStore {
    private suspend fun s() = gate.backing().peerDevices
    override suspend fun forAccount(accountId: String) = s().forAccount(accountId)
    override suspend fun forDevice(deviceId: String) = s().forDevice(deviceId)
    override suspend fun record(device: PeerDeviceRecord) = s().record(device)
    override suspend fun retain(accountId: String, active: List<String>) = s().retain(accountId, active)
}

class GatedServerMessageIdStore(private val gate: LocalStoreGate) : ServerMessageIdStore {
    private suspend fun s() = gate.backing().serverMessageIds
    override suspend fun record(serverId: String, localId: String, recordedAtMs: Long) = s().record(serverId, localId, recordedAtMs)
    override suspend fun localId(serverId: String) = s().localId(serverId)
    override suspend fun forgetBefore(cutoffMs: Long) = s().forgetBefore(cutoffMs)
}

class GatedPendingResendStore(private val gate: LocalStoreGate) : PendingResendStore {
    private suspend fun s() = gate.backing().pendingResends
    override suspend fun queue(resend: PendingResend) = s().queue(resend)
    override suspend fun all() = s().all()
    override suspend fun delete(messageId: String, deviceId: String) = s().delete(messageId, deviceId)
    override suspend fun countAttempt(messageId: String, deviceId: String) = s().countAttempt(messageId, deviceId)
    override suspend fun forgetBefore(cutoffMs: Long) = s().forgetBefore(cutoffMs)
}

class GatedPendingChunkStore(private val gate: LocalStoreGate) : PendingChunkStore {
    private suspend fun s() = gate.backing().pendingChunks
    override suspend fun add(chunk: PendingChunk) = s().add(chunk)
    override suspend fun chunks(senderId: String, messageId: String) = s().chunks(senderId, messageId)
    override suspend fun delete(senderId: String, messageId: String) = s().delete(senderId, messageId)
    override suspend fun forgetBefore(cutoffMs: Long) = s().forgetBefore(cutoffMs)
}

class GatedCallStore(private val gate: LocalStoreGate) : CallStore {
    private suspend fun s() = gate.backing().calls
    override fun observeRecent(): Flow<List<CallRecord>> = gate.watch { it.calls.observeRecent() }
    override suspend fun put(call: CallRecord) = s().put(call)
    override suspend fun delete(id: String) = s().delete(id)
    override suspend fun deleteAll() = s().deleteAll()
}
