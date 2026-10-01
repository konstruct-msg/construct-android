package com.construct.messenger.di

import com.construct.messenger.data.repository.AccountRepository
import com.construct.messenger.data.repository.AccountRepositoryImpl
import com.construct.messenger.data.repository.AuthRepository
import com.construct.messenger.data.repository.AuthRepositoryImpl
import com.construct.messenger.data.repository.ChatsRepository
import com.construct.messenger.data.repository.ChatsRepositoryImpl
import com.construct.messenger.data.repository.ContactsRepository
import com.construct.messenger.data.repository.DevicesRepository
import com.construct.messenger.data.repository.DevicesRepositoryImpl
import com.construct.messenger.data.repository.ContactsRepositoryImpl
import com.construct.messenger.data.repository.ConnectionRepository
import com.construct.messenger.data.repository.ConnectionRepositoryImpl
import com.construct.messenger.data.repository.MediaRepository
import com.construct.messenger.data.repository.MediaRepositoryImpl
import com.construct.messenger.data.repository.MessagesRepository
import com.construct.messenger.data.repository.MessagesRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindAuthRepository(
        repository: AuthRepositoryImpl
    ): AuthRepository

    @Binds
    @Singleton
    abstract fun bindChatsRepository(
        repository: ChatsRepositoryImpl
    ): ChatsRepository

    @Binds
    @Singleton
    abstract fun bindMessagesRepository(
        repository: MessagesRepositoryImpl
    ): MessagesRepository

    @Binds
    @Singleton
    abstract fun bindStickersRepository(
        impl: com.construct.messenger.stickers.StickerStore,
    ): com.construct.messenger.data.repository.StickersRepository

    @Binds
    @Singleton
    abstract fun bindMediaRepository(
        repository: MediaRepositoryImpl
    ): MediaRepository

    @Binds
    @Singleton
    abstract fun bindContactsRepository(
        repository: ContactsRepositoryImpl
    ): ContactsRepository

    @Binds
    @Singleton
    abstract fun bindAccountRepository(
        repository: AccountRepositoryImpl
    ): AccountRepository

    @Binds
    @Singleton
    abstract fun bindConnectionRepository(
        repository: ConnectionRepositoryImpl
    ): ConnectionRepository

    @Binds
    @Singleton
    abstract fun bindDevicesRepository(
        repository: DevicesRepositoryImpl
    ): DevicesRepository
}
