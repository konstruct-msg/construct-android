package com.construct.messenger.di

import android.content.Context
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
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /** The core's store (TODO 136); every store below waits for it. */
    @Provides
    @Singleton
    fun provideLocalStores(
        @ApplicationContext context: Context,
        keystore: KeystoreManager,
    ): LocalStores = LocalStores(
        file = LocalStoreSession.defaultFile(context),
        key = LocalStoreSession.defaultKey(context),
        // Room's file before 2026-10-19; `deleteDatabase` takes its journal with it.
        deleteRoomLeftovers = { context.deleteDatabase(ROOM_FILE) },
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

    private const val ROOM_FILE = "construct.db"
}
