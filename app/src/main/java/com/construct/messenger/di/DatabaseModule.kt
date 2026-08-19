package com.construct.messenger.di

import android.content.Context
import androidx.room.Room
import com.construct.messenger.data.local.AckStore
import com.construct.messenger.data.local.PersistentAckStore
import com.construct.messenger.data.local.db.AckDao
import com.construct.messenger.data.local.db.ChatDao
import com.construct.messenger.data.local.db.ConstructDatabase
import com.construct.messenger.data.local.db.MessageDao
import com.construct.messenger.data.local.db.SessionMetaDao
import com.construct.messenger.data.local.db.SessionStateDao
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
            // Greenfield: identityPublic added in v2. No production installs yet.
            .fallbackToDestructiveMigration()
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
    fun provideSessionMetaDao(db: ConstructDatabase): SessionMetaDao = db.sessionMetaDao()

    @Provides
    @Singleton
    fun provideAckStore(ackDao: AckDao): AckStore = PersistentAckStore(ackDao)
}
