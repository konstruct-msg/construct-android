package com.construct.messenger.share

import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.LocalStores
import com.construct.messenger.data.repository.AuthRepository
import com.construct.messenger.data.repository.IncomingShareRepository
import com.construct.messenger.data.repository.MediaRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** In-process instrumentation access, debug APK only. No exported component or secret output. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface IncomingShareStandAccess {
    fun stores(): LocalStores
    fun identity(): KeystoreManager
    fun shares(): IncomingShareRepository
    fun auth(): AuthRepository
    fun media(): MediaRepository
}
