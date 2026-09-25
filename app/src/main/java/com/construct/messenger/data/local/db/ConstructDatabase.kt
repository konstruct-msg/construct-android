package com.construct.messenger.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * App-local persistence.
 *
 * **Canon:** `docs/ANDROID_ONBOARDING.md` §8.3 (entities) + §12 (session_meta
 * for the stale-END_SESSION filter). Messages at rest hold E2EE ciphertext or
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
        SessionMetaEntity::class,
        IssuedInviteEntity::class,
        PeerDeviceEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class ConstructDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun userDao(): UserDao
    abstract fun ackDao(): AckDao
    abstract fun sessionStateDao(): SessionStateDao
    abstract fun sessionMetaDao(): SessionMetaDao
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
    }
}
