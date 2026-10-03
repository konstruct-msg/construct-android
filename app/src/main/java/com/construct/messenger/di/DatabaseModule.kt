package com.construct.messenger.di

import android.content.Context
import androidx.room.Room
import com.construct.messenger.data.local.AckStore
import com.construct.messenger.data.local.PersistentAckStore
import com.construct.messenger.data.local.db.AckDao
import com.construct.messenger.data.local.db.ChatDao
import com.construct.messenger.data.local.db.CallRecordDao
import com.construct.messenger.data.local.db.ConstructDatabase
import com.construct.messenger.data.local.db.MessageDao
import com.construct.messenger.data.local.db.SessionStateDao
import com.construct.messenger.data.local.db.IssuedInviteDao
import com.construct.messenger.data.local.db.PeerDeviceDao
import com.construct.messenger.data.local.db.PendingChunkDao
import com.construct.messenger.data.local.db.PendingResendDao
import com.construct.messenger.data.local.db.ReactionDao
import com.construct.messenger.data.local.db.ServerMessageIdDao
import com.construct.messenger.data.local.db.UserDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ConstructDatabase =
        Room.databaseBuilder(context, ConstructDatabase::class.java, ConstructDatabase.NAME)
            // A phone already holds chats (first device run, 2026-09-24). A version we
            // know upgrades in place. Versions 1–3 predate that phone; anything newer
            // with no migration fails the open instead of deleting the history.
            .addMigrations(
                ConstructDatabase.MIGRATION_4_5,
                ConstructDatabase.MIGRATION_5_6,
                ConstructDatabase.MIGRATION_6_7,
                ConstructDatabase.MIGRATION_7_8,
                ConstructDatabase.MIGRATION_8_9,
                ConstructDatabase.MIGRATION_9_10,
                ConstructDatabase.MIGRATION_10_11,
                ConstructDatabase.MIGRATION_11_12,
                ConstructDatabase.MIGRATION_12_13,
                ConstructDatabase.MIGRATION_13_14,
                ConstructDatabase.MIGRATION_14_15,
                ConstructDatabase.MIGRATION_15_16,
                ConstructDatabase.MIGRATION_16_17,
                ConstructDatabase.MIGRATION_17_18,
                ConstructDatabase.MIGRATION_18_19,
                ConstructDatabase.MIGRATION_19_20,
            )
            .fallbackToDestructiveMigrationFrom(1, 2, 3)
            .build()

    @Provides
    fun provideChatDao(db: ConstructDatabase): ChatDao = db.chatDao()

    @Provides
    fun provideMessageDao(db: ConstructDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideUserDao(db: ConstructDatabase): UserDao = db.userDao()

    @Provides
    fun provideAckDao(db: ConstructDatabase): AckDao = db.ackDao()

    @Provides
    fun provideSessionStateDao(db: ConstructDatabase): SessionStateDao = db.sessionStateDao()

    @Provides
    fun provideIssuedInviteDao(db: ConstructDatabase): IssuedInviteDao = db.issuedInviteDao()

    @Provides
    fun providePeerDeviceDao(db: ConstructDatabase): PeerDeviceDao = db.peerDeviceDao()

    @Provides
    fun provideServerMessageIdDao(db: ConstructDatabase): ServerMessageIdDao = db.serverMessageIdDao()

    @Provides
    fun providePendingResendDao(db: ConstructDatabase): PendingResendDao = db.pendingResendDao()

    @Provides
    fun providePendingChunkDao(db: ConstructDatabase): PendingChunkDao = db.pendingChunkDao()

    @Provides
    fun provideReactionDao(db: ConstructDatabase): ReactionDao = db.reactionDao()

    @Provides
    fun provideCallRecordDao(db: ConstructDatabase): CallRecordDao = db.callRecordDao()

    @Provides
    @Singleton
    fun provideAckStore(ackDao: AckDao): AckStore = PersistentAckStore(ackDao)
}
