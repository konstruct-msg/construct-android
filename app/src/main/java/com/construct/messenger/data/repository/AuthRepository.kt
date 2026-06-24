package com.construct.messenger.data.repository

import com.construct.messenger.data.model.AuthState
import kotlinx.coroutines.flow.StateFlow

interface AuthRepository {
    val authState: StateFlow<AuthState>

    suspend fun initializeIdentity()
}
