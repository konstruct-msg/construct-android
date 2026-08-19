package com.construct.messenger.data.model

data class Contact(
    val userId: String,
    val displayName: String,
    val username: String = "",
)
