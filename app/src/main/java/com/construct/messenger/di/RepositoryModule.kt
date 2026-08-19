package com.construct.messenger.di

import com.construct.messenger.data.repository.AuthRepository
import com.construct.messenger.data.repository.AuthRepositoryImpl
import com.construct.messenger.data.repository.ChatsRepository
import com.construct.messenger.data.repository.ChatsRepositoryImpl
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
}
