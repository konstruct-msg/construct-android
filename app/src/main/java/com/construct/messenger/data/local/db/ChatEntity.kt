package com.construct.messenger.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import com.construct.messenger.data.model.ChatActivity
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

    /** -1 when the chat was already there. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(chat: ChatEntity): Long

    /** Forward only: an older message does not replace a newer preview; an equal time does. */
    @Query(
        "UPDATE chats SET lastMessageText = :text, lastMessageTime = :timeMs " +
            "WHERE id = :chatId AND (lastMessageTime IS NULL OR lastMessageTime <= :timeMs)",
    )
    suspend fun advancePreview(chatId: String, text: String, timeMs: Long)

    @Query("UPDATE chats SET lastMessageText = :text, lastMessageTime = :timeMs WHERE id = :chatId")
    suspend fun setPreview(chatId: String, text: String?, timeMs: Long?)

    @Query("UPDATE chats SET unreadCount = :count WHERE id = :chatId")
    suspend fun updateUnreadCount(chatId: String, count: Int)

    @Query("UPDATE chats SET unreadCount = unreadCount + 1 WHERE id = :chatId")
    suspend fun incrementUnreadCount(chatId: String)

    @Query("UPDATE chats SET isPinned = :pinned WHERE id = :chatId")
    suspend fun setPinned(chatId: String, pinned: Boolean)

    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun delete(chatId: String)

    /** Every chat with its user-visible message count — how active each contact is (Synapses). */
    @Query(
        "SELECT c.otherUserId AS contactId, COUNT(m.id) AS messages, " +
            "c.lastMessageTime AS lastMessageTime, c.unreadCount AS unreadCount " +
            "FROM chats c LEFT JOIN messages m ON m.chatId = c.id AND m.contentType = 0 GROUP BY c.id",
    )
    fun observeActivity(): Flow<List<ChatActivity>>
}
