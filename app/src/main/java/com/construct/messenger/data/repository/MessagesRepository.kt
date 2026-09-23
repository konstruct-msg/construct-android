package com.construct.messenger.data.repository

import com.construct.messenger.data.model.Message
import com.construct.messenger.domain.usecase.SendOutcome
import kotlinx.coroutines.flow.Flow

/**
 * Transcript for a 1:1 chat. UI collects [observeContact] and calls [send].
 */
interface MessagesRepository {
    /** Messages in the `direct:<me>:<contact>` conversation, oldest first. */
    fun observeContact(contactId: String): Flow<List<Message>>

    suspend fun send(contactId: String, text: String): SendOutcome
}
