package com.construct.messenger.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Conversation row.
 *
 * **Canon:** `docs/ANDROID_ONBOARDING.md` §8.3 —
 * `Chat(id, otherUserId, lastMessageText?, lastMessageTime?, unreadCount = 0, isPinned = false)`.
 *
 * [id] is the conversation id (`direct:<idA>:<idB>`, ids sorted — same rule as
 * stream subscriptions in `MessageStreamService`).
 */
@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: String,
    val otherUserId: String,
    val lastMessageText: String? = null,
    val lastMessageTime: Long? = null,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
)

@Dao
interface ChatDao {

    /** Chats list: pinned first, then most recent. */
    @Query("SELECT * FROM chats ORDER BY isPinned DESC, lastMessageTime DESC")
    fun observeAll(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :chatId")
    suspend fun getById(chatId: String): ChatEntity?

    @Query("SELECT id FROM chats")
    suspend fun getAllIds(): List<String>

    @Upsert
    suspend fun upsert(chat: ChatEntity)

    @Query(
        "UPDATE chats SET lastMessageText = :text, lastMessageTime = :timeMs WHERE id = :chatId",
    )
    suspend fun updateLastMessage(chatId: String, text: String?, timeMs: Long)

    @Query("UPDATE chats SET unreadCount = :count WHERE id = :chatId")
    suspend fun updateUnreadCount(chatId: String, count: Int)

    @Query("UPDATE chats SET unreadCount = unreadCount + 1 WHERE id = :chatId")
    suspend fun incrementUnreadCount(chatId: String)

    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun delete(chatId: String)
}
