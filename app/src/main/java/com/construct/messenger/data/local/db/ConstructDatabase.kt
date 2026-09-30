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
        ServerMessageIdEntity::class,
        PendingResendEntity::class,
        PendingChunkEntity::class,
    ],
    version = 15,
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
    abstract fun serverMessageIdDao(): ServerMessageIdDao
    abstract fun pendingResendDao(): PendingResendDao
    abstract fun pendingChunkDao(): PendingChunkDao

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

        /** An unacknowledged security event per contact ([SecurityNotice]). Existing rows: none. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE users ADD COLUMN securityNotice INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Server-assigned ids of our sealed copies ([com.construct.messenger.service.ServerMessageIds]),
         * which were in memory. Starts empty: ids from before the update were already lost.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `server_message_ids` (`serverId` TEXT NOT NULL, " +
                        "`localId` TEXT NOT NULL, `recordedAtMs` INTEGER NOT NULL, PRIMARY KEY(`serverId`))",
                )
            }
        }

        /** Resends the core asked for and the network refused ([com.construct.messenger.service.PendingResends]). */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_resends` (`messageId` TEXT NOT NULL, " +
                        "`deviceId` TEXT NOT NULL, `accountId` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, " +
                        "`attempts` INTEGER NOT NULL, PRIMARY KEY(`messageId`, `deviceId`))",
                )
            }
        }

        /** Which QR showing minted a code. Existing rows: none — each stays its own row. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE issued_invites ADD COLUMN sitting TEXT")
            }
        }

        /** The user's own name for a contact (iOS `localAlias`). Existing rows: none given. */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE users ADD COLUMN localAlias TEXT")
            }
        }

        /** Whether we share our profile with a contact (iOS `amISharingWith`). Nobody yet. */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE users ADD COLUMN amSharingWith INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Chunks of a message still arriving (`service/ChunkReassembler`). */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS pending_chunks (" +
                        "senderId TEXT NOT NULL, messageId TEXT NOT NULL, chunkIndex INTEGER NOT NULL, " +
                        "totalChunks INTEGER NOT NULL, plaintextLength INTEGER NOT NULL, " +
                        "contentType INTEGER NOT NULL, payload BLOB NOT NULL, receivedAtMs INTEGER NOT NULL, " +
                        "PRIMARY KEY(senderId, messageId, chunkIndex))",
                )
            }
        }
    }
}
