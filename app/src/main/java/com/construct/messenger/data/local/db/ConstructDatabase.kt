package com.construct.messenger.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

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
    ],
    version = 3,
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

    companion object {
        const val NAME = "construct.db"
    }
}
