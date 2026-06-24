package com.construct.messenger.di

import com.construct.messenger.data.mock.MockChatsRepository
import com.construct.messenger.data.repository.AuthRepository
import com.construct.messenger.data.repository.AuthRepositoryImpl
import com.construct.messenger.data.repository.ChatsRepository
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
        repository: MockChatsRepository
    ): ChatsRepository
}
