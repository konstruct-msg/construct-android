package com.construct.messenger.data.repository

import com.construct.messenger.calls.CallHistoryPort
import com.construct.messenger.calls.CallRecordStatus
import com.construct.messenger.calls.CallSession
import com.construct.messenger.calls.CallTranscript
import com.construct.messenger.data.local.CallRecord
import com.construct.messenger.data.local.CallStore
import com.construct.messenger.data.local.ChatStore
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.MessageStore
import com.construct.messenger.data.local.refreshChatPreview
import com.construct.messenger.data.local.ContactStore
import com.construct.messenger.data.local.resolvedName
import com.construct.messenger.data.model.CallHistoryEntry
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.service.MediaPreviewText
import com.construct.messenger.service.of
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The Calls tab's list. Only this device's calls: the history is not synced, as on iOS. */
interface CallHistoryRepository {
    val recent: Flow<List<CallHistoryEntry>>
    suspend fun delete(id: String)
    suspend fun clear()
}

/**
 * **Canon:** iOS `CallHistoryService` — written when a call ends, from [CallHistoryPort], which
 * [com.construct.messenger.calls.CallManager] calls without waiting.
 */
@Singleton
class CallHistoryRepositoryImpl @Inject constructor(
    private val calls: CallStore,
    private val users: ContactStore,
    private val messages: MessageStore,
    private val chats: ChatStore,
    private val keystore: KeystoreManager,
    private val mediaPreview: MediaPreviewText,
    @ApplicationContext private val context: Context,
) : CallHistoryRepository, CallHistoryPort {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val recent: Flow<List<CallHistoryEntry>> =
        combine(calls.observeRecent(), users.observeAll()) { rows, people ->
            val byId = people.associateBy { it.id }
            rows.map { row ->
                val user = byId[row.peerUserId]
                CallHistoryEntry(
                    id = row.id,
                    peerUserId = row.peerUserId,
                    peerName = if (user != null) user.resolvedName(row.peerUserId) else row.peerName,
                    peerAvatar = user?.avatarData,
                    incoming = row.incoming,
                    status = CallHistoryEntry.Status.valueOf(row.status.name),
                    startedAtMs = row.startedAtMs,
                    durationSeconds = row.durationSeconds,
                )
            }
        }

    override fun record(
        session: CallSession,
        status: CallRecordStatus,
        startedAtMs: Long,
        endedAtMs: Long,
        durationSeconds: Int,
    ) {
        scope.launch {
            runCatching {
                val call = CallRecord(
                    id = session.id,
                    peerUserId = session.peerUserId,
                    peerName = session.peerName,
                    incoming = session.isIncoming,
                    status = status,
                    startedAtMs = startedAtMs,
                    endedAtMs = endedAtMs,
                    durationSeconds = durationSeconds,
                )
                calls.put(call)
                // The call in its conversation (`CallTranscript`); the log row above is what it reads.
                val myId = keystore.getUserId() ?: return@runCatching
                CallTranscript.write(
                    call, announce = true, myId = myId,
                    label = context.getString(CallTranscript.labelRes(call.incoming, call.status)),
                    messages = messages, chats = chats, contacts = users,
                )
            }.onFailure { Log.w(TAG, "call not recorded", it) }
        }
    }

    /** The call, and its row in the conversation; the chat's line is read again. */
    override suspend fun delete(id: String) {
        calls.delete(id)
        deleteRows(listOf(id))
    }

    /** The log the Calls list holds (its newest [CallStore.RECENT]), and their rows. */
    override suspend fun clear() {
        val held = calls.observeRecent().first().map { it.id }
        calls.deleteAll()
        deleteRows(held)
    }

    private suspend fun deleteRows(callIds: List<String>) = runCatching {
        val touched = mutableSetOf<String>()
        for (callId in callIds) {
            val row = messages.get(CallTranscript.messageId(callId)) ?: continue
            messages.delete(row.id)
            touched += row.chatId
        }
        for (chatId in touched) messages.refreshChatPreview(chats, chatId, mediaPreview::of)
    }.onFailure { Log.w(TAG, "call rows not deleted", it) }

    private companion object {
        const val TAG = "CallHistory"
    }
}
