package com.construct.messenger.data.local.db

import androidx.room.Dao
import androidx.room.Query

/**
 * Every row of every table, for the one-time move into the core's store ([RoomImport]
 * [com.construct.messenger.data.local.RoomImport], TODO 136) and nothing else: the app reads Room
 * only through its stores.
 */
@Dao
interface RoomExportDao {
    @Query("SELECT * FROM users")
    suspend fun users(): List<UserEntity>

    @Query("SELECT * FROM chats")
    suspend fun chats(): List<ChatEntity>

    /** A page in a stable order: a row holds a media descriptor, so the whole table at once may not fit a cursor. */
    @Query("SELECT * FROM messages ORDER BY chatId, orderKey, id LIMIT :limit OFFSET :offset")
    suspend fun messages(limit: Int, offset: Int): List<MessageEntity>

    @Query("SELECT * FROM reactions")
    suspend fun reactions(): List<ReactionEntity>

    @Query("SELECT * FROM call_records")
    suspend fun calls(): List<CallRecordEntity>

    @Query("SELECT * FROM peer_devices")
    suspend fun peerDevices(): List<PeerDeviceEntity>

    @Query("SELECT * FROM server_message_ids")
    suspend fun serverMessageIds(): List<ServerMessageIdEntity>

    @Query("SELECT * FROM acked_messages")
    suspend fun processed(): List<AckedMessageEntity>

    @Query("SELECT * FROM pending_chunks")
    suspend fun pendingChunks(): List<PendingChunkEntity>

    @Query("SELECT * FROM pending_resends")
    suspend fun pendingResends(): List<PendingResendEntity>

    @Query("SELECT * FROM issued_invites")
    suspend fun issuedInvites(): List<IssuedInviteEntity>

    @Query("SELECT * FROM session_state")
    suspend fun sessionBlobs(): List<SessionStateEntity>
}
