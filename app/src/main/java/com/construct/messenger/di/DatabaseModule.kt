package com.construct.messenger.di

import android.content.Context
import androidx.room.Room
import com.construct.messenger.data.local.AckStore
import com.construct.messenger.data.local.IssuedInviteStore
import com.construct.messenger.data.local.RoomIssuedInviteStore
import com.construct.messenger.data.local.RoomSessionBlobStore
import com.construct.messenger.data.local.SessionBlobStore
import com.construct.messenger.data.local.PendingChunkStore
import com.construct.messenger.data.local.PendingResendStore
import com.construct.messenger.data.local.ProcessedStore
import com.construct.messenger.data.local.RoomPendingChunkStore
import com.construct.messenger.data.local.RoomPendingResendStore
import com.construct.messenger.data.local.RoomProcessedStore
import com.construct.messenger.data.local.ChatStore
import com.construct.messenger.data.local.MessageStore
import com.construct.messenger.data.local.RoomMessageStore
import com.construct.messenger.data.local.CallStore
import com.construct.messenger.data.local.PeerDeviceStore
import com.construct.messenger.data.local.RoomPeerDeviceStore
import com.construct.messenger.data.local.RoomServerMessageIdStore
import com.construct.messenger.data.local.ServerMessageIdStore
import com.construct.messenger.data.local.ReactionStore
import com.construct.messenger.data.local.RoomCallStore
import com.construct.messenger.data.local.RoomChatStore
import com.construct.messenger.data.local.RoomReactionStore
import com.construct.messenger.data.local.ContactStore
import com.construct.messenger.data.local.RoomContactStore
import com.construct.messenger.data.local.PersistentAckStore
import com.construct.messenger.data.local.db.ConstructDatabase
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
                ConstructDatabase.MIGRATION_20_21,
            )
            .fallbackToDestructiveMigrationFrom(1, 2, 3)
            .build()

    /** The `chats` table only through here (TODO 136): no `ChatDao` is provided. */
    @Provides
    @Singleton
    fun provideChatStore(db: ConstructDatabase): ChatStore = RoomChatStore(db.chatDao())

    /** The `messages` table only through here (TODO 136): no `MessageDao` is provided. */
    @Provides
    @Singleton
    fun provideMessageStore(db: ConstructDatabase): MessageStore = RoomMessageStore(db.messageDao())

    /** The `reactions` table only through here (TODO 136): no `ReactionDao` is provided. */
    @Provides
    @Singleton
    fun provideReactionStore(db: ConstructDatabase): ReactionStore = RoomReactionStore(db.reactionDao())

    /** The `users` table only through here (TODO 136): no `UserDao` is provided. */
    @Provides
    @Singleton
    fun provideContactStore(db: ConstructDatabase): ContactStore = RoomContactStore(db.userDao())

    /** The `acked_messages` table only through here (TODO 136): no `AckDao` is provided. */
    @Provides
    @Singleton
    fun provideProcessedStore(db: ConstructDatabase): ProcessedStore = RoomProcessedStore(db.ackDao())

    /** The `session_state` table only through here (TODO 136): no `SessionStateDao` is provided. */
    @Provides
    @Singleton
    fun provideSessionBlobStore(db: ConstructDatabase): SessionBlobStore = RoomSessionBlobStore(db.sessionStateDao())

    /** The `issued_invites` table only through here (TODO 136): no `IssuedInviteDao` is provided. */
    @Provides
    @Singleton
    fun provideIssuedInviteStore(db: ConstructDatabase): IssuedInviteStore = RoomIssuedInviteStore(db.issuedInviteDao())

    /** The `peer_devices` table only through here (TODO 136): no `PeerDeviceDao` is provided. */
    @Provides
    @Singleton
    fun providePeerDeviceStore(db: ConstructDatabase): PeerDeviceStore = RoomPeerDeviceStore(db.peerDeviceDao())

    /** The `server_message_ids` table only through here (TODO 136): no `ServerMessageIdDao` is provided. */
    @Provides
    @Singleton
    fun provideServerMessageIdStore(db: ConstructDatabase): ServerMessageIdStore = RoomServerMessageIdStore(db.serverMessageIdDao())

    /** The `pending_resends` table only through here (TODO 136): no `PendingResendDao` is provided. */
    @Provides
    @Singleton
    fun providePendingResendStore(db: ConstructDatabase): PendingResendStore = RoomPendingResendStore(db.pendingResendDao())

    /** The `pending_chunks` table only through here (TODO 136): no `PendingChunkDao` is provided. */
    @Provides
    @Singleton
    fun providePendingChunkStore(db: ConstructDatabase): PendingChunkStore = RoomPendingChunkStore(db.pendingChunkDao())

    /** The `call_records` table only through here (TODO 136): no `CallRecordDao` is provided. */
    @Provides
    @Singleton
    fun provideCallStore(db: ConstructDatabase): CallStore = RoomCallStore(db.callRecordDao())

    @Provides
    @Singleton
    fun provideAckStore(processed: ProcessedStore): AckStore = PersistentAckStore(processed)
}
