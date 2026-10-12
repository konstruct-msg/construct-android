package com.construct.messenger.data.local

import com.construct.messenger.data.local.db.RoomExportDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.construct_core.LocalStore

/**
 * The one-time move of Room's rows into the core's store (TODO 136, step 4). **Canon:** iOS's
 * Core Data import (`LOCAL_STORE_MIGRATION_PLAN.md` §4): in the background, checked by counts, the
 * old store left alone until the check passes, and safe to run again after an interruption.
 *
 * Rows are read straight from Room ([RoomExportDao]) and written through the `Core*Store`s, so
 * the iOS row format is decided in one place — the code the store contracts already hold.
 *
 * Order: contacts (a chat needs its peer), chats (a message needs its chat), messages, then the
 * rest. Every write is an insert that leaves what is there or an overwrite with the same value,
 * so a second run after a crash writes nothing new. [MARKER] is written last, and only when every
 * count matches; until then the app keeps reading Room.
 */
class RoomImport(
    private val room: RoomExportDao,
    private val feed: LocalStoreFeed,
    private val myUserId: String,
) {
    /** What went in, per table; [skipped]: rows the core cannot hold (a message with no chat). */
    data class Report(val imported: Map<String, Int>, val skipped: Map<String, Int>)

    /** The counts did not match; nothing is marked done and Room stays the store. */
    class Mismatch(val table: String, val expected: Int, val found: Int) :
        Exception("room import: $table expected $expected, found $found")

    private val store: LocalStore get() = feed.store

    suspend fun isDone(): Boolean = withContext(Dispatchers.IO) { store.get(MARKER) != null }

    suspend fun run(): Report {
        val imported = linkedMapOf<String, Int>()
        val skipped = linkedMapOf<String, Int>()
        val contacts = CoreContactStore(feed)
        val chats = CoreChatStore(feed)

        val users = room.users()
        users.forEach { contacts.upsert(it.record()) }
        val roomChats = room.chats()
        roomChats.forEach { chat ->
            contacts.ensure(chat.otherUserId)
            chats.insert(chat.record())
        }
        imported["contacts"] = users.size
        imported["chats"] = roomChats.size

        val messageCount = messages(roomChats.map { it.id }.toSet(), skipped)
        imported["messages"] = messageCount

        val reactions = CoreReactionStore(feed)
        room.reactions().also { imported["reactions"] = it.size }.forEach { reactions.put(it.record()) }
        val calls = CoreCallStore(feed)
        room.calls().also { imported["calls"] = it.size }.forEach { calls.put(it.record()) }
        val devices = CorePeerDeviceStore(store)
        room.peerDevices().also { imported["peer_devices"] = it.size }.forEach { devices.record(it.record()) }
        val serverIds = CoreServerMessageIdStore(store)
        room.serverMessageIds().also { imported["server_message_ids"] = it.size }
            .forEach { serverIds.record(it.serverId, it.localId, it.recordedAtMs) }
        val processed = CoreProcessedStore(store)
        room.processed().also { imported["processed"] = it.size }
            .forEach { processed.record(it.messageId, it.senderId, it.processedAtMs) }
        val chunks = CorePendingChunkStore(store)
        room.pendingChunks().also { imported["pending_chunks"] = it.size }.forEach {
            chunks.add(PendingChunk(it.senderId, it.messageId, it.chunkIndex, it.totalChunks, it.plaintextLength, it.contentType, it.payload, it.receivedAtMs))
        }
        val resends = CorePendingResendStore(store)
        room.pendingResends().also { imported["pending_resends"] = it.size }.forEach {
            resends.queue(PendingResend(it.messageId, it.deviceId, it.accountId, it.createdAtMs, it.attempts))
        }
        val invites = CoreIssuedInviteStore(feed)
        room.issuedInvites().also { imported["issued_invites"] = it.size }.forEach {
            invites.record(IssuedInviteRecord(it.jti, it.kind, it.issuedAtEpochSec, it.ttlSeconds, it.sitting))
        }
        val blobs = CoreSessionBlobStore(store)
        room.sessionBlobs().also { imported["session_blobs"] = it.size }.forEach { blobs.put(it.storeKey, it.cfeBytes) }

        verify(users.map { it.id } + roomChats.map { it.otherUserId }, roomChats.size, messageCount)
        withContext(Dispatchers.IO) { store.put(MARKER, byteArrayOf(1)) }
        return Report(imported, skipped)
    }

    /**
     * Page by page, one transaction each. A message whose chat Room does not hold is not shown
     * there either, and is left out. How many distinct messages there are — the core keeps ids
     * lowercase, so two that differ only in case are one.
     */
    private suspend fun messages(chatIds: Set<String>, skipped: MutableMap<String, Int>): Int {
        val messages = CoreMessageStore(feed, myUserId = { myUserId })
        var offset = 0
        val ids = HashSet<String>()
        var orphans = 0
        while (true) {
            val page = room.messages(PAGE, offset)
            if (page.isEmpty()) break
            offset += page.size
            val (held, orphaned) = page.partition { it.chatId in chatIds }
            orphans += orphaned.size
            held.forEach { ids += it.id.lowercase() }
            messages.insertAll(held.map { it.record() })
        }
        if (orphans > 0) skipped["messages"] = orphans
        return ids.size
    }

    /** Every count the core can give, against what Room held. Ids are compared as the core keeps them. */
    private suspend fun verify(contactIds: List<String>, chatCount: Int, messageCount: Int) = withContext(Dispatchers.IO) {
        fun check(table: String, expected: Int, found: Int) {
            if (expected != found) throw Mismatch(table, expected, found)
        }
        check("contacts", contactIds.toSet().size, store.everyContact(null).size)
        check("chats", chatCount, store.chats().size)
        check("messages", messageCount, store.messageCount().toInt())
        check("reactions", room.reactions().size, store.allReactions().size)
        check("calls", room.calls().size, store.calls(UInt.MAX_VALUE).size)
        check("peer_devices", room.peerDevices().size, store.allPeerDevices().size)
        check("processed", room.processed().size, store.processedMessageIds().size)
        check("pending_resends", room.pendingResends().size, store.pendingResends().size)
        check("issued_invites", room.issuedInvites().size, store.issuedInvites().size)
        check(
            "pending_chunks",
            room.pendingChunks().size,
            store.pendingChunkMessages().sumOf { store.pendingChunkCount(it.senderId, it.messageId).toInt() },
        )
        check("session_blobs", room.sessionBlobs().size, store.entriesWithPrefix("").count { it.key != MARKER })
        room.serverMessageIds().forEach { row ->
            if (!store.localMessageId(row.serverId).equals(row.localId, ignoreCase = true)) throw Mismatch("server_message_ids:${row.serverId}", 1, 0)
        }
    }

    companion object {
        /** In the core's key-value table, beside the session blobs; `MessagingRuntime` reads only `session:` keys and the orchestrator's. */
        const val MARKER = "store:room-import:v1"
        private const val PAGE = 200
    }
}
