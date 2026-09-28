package com.construct.messenger.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * App-local persistence.
 *
 * **Canon:** `docs/ANDROID_ONBOARDING.md` §8.3 (entities). Messages at rest hold E2EE ciphertext or
 * decrypted plaintext depending on the storage-privacy spec — this DB stores
 * what the repository layer hands it; encryption-at-rest is a later phase.
 */
@Database(
    entities = [
        ChatEntity::class,
        MessageEntity::class,
        UserEntity::class,
        AckedMessageEntity::class,
        SessionStateEntity::class,
        IssuedInviteEntity::class,
        PeerDeviceEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class ConstructDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun userDao(): UserDao
    abstract fun ackDao(): AckDao
    abstract fun sessionStateDao(): SessionStateDao
    abstract fun issuedInviteDao(): IssuedInviteDao
    abstract fun peerDeviceDao(): PeerDeviceDao

    companion object {
        const val NAME = "construct.db"

        /**
         * Reply quotes. `replyToId` was already on the v4 table; the preview and the
         * media kind are what a bubble shows when the quoted row is a photo or is not
         * in this database. Nullable columns, so existing rows stay ordinary messages.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN replyPreview TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN replyMediaType TEXT")
            }
        }

        /**
         * "edited" on a text row. Existing messages were not edited. A missing migration
         * must not wipe the phone: versions 4 and 5 already hold chats.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN isEdited INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * A contact's account address, from their v5 invite. Nullable: contacts added before
         * invites carried it keep being addressed by their server id.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE users ADD COLUMN accountAddress BLOB")
            }
        }

        /**
         * `session_meta` held when each session was established, for the filter that dropped a
         * stale END_SESSION. There is no END_SESSION since 2026-09-28, so the column was written
         * with no reader.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS session_meta")
            }
        }
    }
}
