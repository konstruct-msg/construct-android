package com.construct.messenger.data.model

/** A local note from Settings → Drafts. [createdAt] is epoch milliseconds. */
data class Draft(val id: String, val text: String, val createdAt: Long)
