package com.construct.messenger.data.local.db

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.construct.messenger.data.local.RoomMessageStore
import kotlinx.coroutines.runBlocking
import com.construct.messenger.util.ServerMessageOrder
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 20 → 21 gives every message already stored the key that puts it at its own time. The SQL must
 * write exactly the string `ServerMessageOrder.local` does, or old and new rows interleave wrongly.
 * Mutation: drop `max(timestamp, 1)` or `lower(id)` — this reddens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MigrationOrderKeyTest {
    @Test
    fun existingMessagesGetTheirLocalKey() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext<Application>())
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(20) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE messages (id TEXT NOT NULL PRIMARY KEY, chatId TEXT NOT NULL, text TEXT NOT NULL, " +
                                "isSentByMe INTEGER NOT NULL, timestamp INTEGER NOT NULL, deliveryStatus TEXT NOT NULL, " +
                                "replyToId TEXT, replyPreview TEXT, replyMediaType TEXT, isEdited INTEGER NOT NULL, " +
                                "mediaType TEXT, mediaUrl TEXT, contentType INTEGER NOT NULL, mediaPayload BLOB)",
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val db = helper.writableDatabase
        db.execSQL("INSERT INTO messages VALUES ('AbC-1', 'c', 'hi', 1, 1760000000123, 'SENT', NULL, NULL, NULL, 0, NULL, NULL, 0, NULL)")
        db.execSQL("INSERT INTO messages VALUES ('zero', 'c', 'old', 0, 0, 'DELIVERED', NULL, NULL, NULL, 0, NULL, NULL, 0, NULL)")

        ConstructDatabase.MIGRATION_20_21.migrate(db)

        fun key(id: String) = db.query("SELECT orderKey FROM messages WHERE id = ?", arrayOf(id)).use { it.moveToFirst(); it.getString(0) }
        assertEquals(ServerMessageOrder.local(1_760_000_000_123, "AbC-1"), key("AbC-1"))
        assertEquals(ServerMessageOrder.local(0, "zero"), key("zero"))
        helper.close()
    }

    /**
     * What an update does on a phone: a version-20 file opened by this build. Room checks the
     * migrated table against the entity — a default or an index that differs fails the open, at
     * start-up, on every tester's phone.
     */
    @Test
    fun aVersion20DatabaseOpensAfterTheMigration() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "migration-20-21.db"
        context.deleteDatabase(name)
        // This build's schema, taken back to 20: the column and its index removed.
        Room.databaseBuilder(context, ConstructDatabase::class.java, name).allowMainThreadQueries().build().apply {
            openHelper.writableDatabase.apply {
                execSQL("INSERT INTO messages (id, chatId, text, isSentByMe, timestamp, deliveryStatus, isEdited, contentType, orderKey) VALUES ('m1', 'c', 'hi', 1, 1000, 'SENT', 0, 0, 'x')")
                execSQL("DROP INDEX index_messages_chatId_orderKey")
                execSQL("ALTER TABLE messages DROP COLUMN orderKey")
                version = 20
            }
            close()
        }

        val db = Room.databaseBuilder(context, ConstructDatabase::class.java, name)
            .addMigrations(ConstructDatabase.MIGRATION_20_21)
            .allowMainThreadQueries()
            .build()
        val row = runBlocking { RoomMessageStore(db.messageDao()).get("m1") }
        assertEquals(ServerMessageOrder.local(1000, "m1"), row?.orderKey)
        db.close()
        context.deleteDatabase(name)
    }
}
