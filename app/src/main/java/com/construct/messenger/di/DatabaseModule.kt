package com.construct.messenger.di

import android.content.Context
import androidx.room.Room
import com.construct.messenger.data.local.AckStore
import com.construct.messenger.data.local.CallStore
import com.construct.messenger.data.local.ChatStore
import com.construct.messenger.data.local.ContactStore
import com.construct.messenger.data.local.GatedCallStore
import com.construct.messenger.data.local.GatedChatStore
import com.construct.messenger.data.local.GatedContactStore
import com.construct.messenger.data.local.GatedIssuedInviteStore
import com.construct.messenger.data.local.GatedMessageStore
import com.construct.messenger.data.local.GatedPeerDeviceStore
import com.construct.messenger.data.local.GatedPendingChunkStore
import com.construct.messenger.data.local.GatedPendingResendStore
import com.construct.messenger.data.local.GatedProcessedStore
import com.construct.messenger.data.local.GatedReactionStore
import com.construct.messenger.data.local.GatedServerMessageIdStore
import com.construct.messenger.data.local.GatedSessionBlobStore
import com.construct.messenger.data.local.IssuedInviteStore
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.LocalStoreSession
import com.construct.messenger.data.local.LocalStores
import com.construct.messenger.data.local.MessageStore
import com.construct.messenger.data.local.PeerDeviceStore
import com.construct.messenger.data.local.PendingChunkStore
import com.construct.messenger.data.local.PendingResendStore
import com.construct.messenger.data.local.PersistentAckStore
import com.construct.messenger.data.local.ProcessedStore
import com.construct.messenger.data.local.ReactionStore
import com.construct.messenger.data.local.ServerMessageIdStore
import com.construct.messenger.data.local.SessionBlobStore
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

    /**
     * Which store answers — the core's, or Room until its rows have moved (TODO 136). Every store
     * below waits for it; Room is opened only if it is still the store or has rows to move.
     */
    @Provides
    @Singleton
    fun provideLocalStores(
        @ApplicationContext context: Context,
        db: dagger.Lazy<ConstructDatabase>,
        keystore: KeystoreManager,
    ): LocalStores = LocalStores(
        file = LocalStoreSession.defaultFile(context),
        key = LocalStoreSession.defaultKey(context),
        room = { db.get() },
        roomExists = { context.getDatabasePath(ConstructDatabase.NAME).exists() },
        deleteRoom = {
            db.get().close()
            context.deleteDatabase(ConstructDatabase.NAME)
        },
        myUserId = keystore::getUserId,
    )

    @Provides
    @Singleton
    fun provideChatStore(stores: LocalStores): ChatStore = GatedChatStore(stores)

    @Provides
    @Singleton
    fun provideMessageStore(stores: LocalStores): MessageStore = GatedMessageStore(stores)

    @Provides
    @Singleton
    fun provideReactionStore(stores: LocalStores): ReactionStore = GatedReactionStore(stores)

    @Provides
    @Singleton
    fun provideContactStore(stores: LocalStores): ContactStore = GatedContactStore(stores)

    @Provides
    @Singleton
    fun provideProcessedStore(stores: LocalStores): ProcessedStore = GatedProcessedStore(stores)

    @Provides
    @Singleton
    fun provideSessionBlobStore(stores: LocalStores): SessionBlobStore = GatedSessionBlobStore(stores)

    @Provides
    @Singleton
    fun provideIssuedInviteStore(stores: LocalStores): IssuedInviteStore = GatedIssuedInviteStore(stores)

    @Provides
    @Singleton
    fun providePeerDeviceStore(stores: LocalStores): PeerDeviceStore = GatedPeerDeviceStore(stores)

    @Provides
    @Singleton
    fun provideServerMessageIdStore(stores: LocalStores): ServerMessageIdStore = GatedServerMessageIdStore(stores)

    @Provides
    @Singleton
    fun providePendingResendStore(stores: LocalStores): PendingResendStore = GatedPendingResendStore(stores)

    @Provides
    @Singleton
    fun providePendingChunkStore(stores: LocalStores): PendingChunkStore = GatedPendingChunkStore(stores)

    @Provides
    @Singleton
    fun provideCallStore(stores: LocalStores): CallStore = GatedCallStore(stores)

    @Provides
    @Singleton
    fun provideAckStore(processed: ProcessedStore): AckStore = PersistentAckStore(processed)
}
