package com.construct.messenger.data.model

data class AuthState(
    val isInitialized: Boolean = false,
    val deviceId: String? = null,
    val username: String? = null,
    /**
     * The server said, over direct TLS, that this device was removed from its account
     * (`DeviceRefusalReading.erasesDevice`). The device is to be erased; nothing else runs.
     */
    val removed: Boolean = false,
)
