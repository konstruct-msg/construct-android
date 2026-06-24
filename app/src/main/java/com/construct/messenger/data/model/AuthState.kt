package com.construct.messenger.data.model

data class AuthState(
    val isInitialized: Boolean = false,
    val deviceId: String? = null,
    val username: String? = null,
)
