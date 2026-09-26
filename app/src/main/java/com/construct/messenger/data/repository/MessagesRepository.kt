package com.construct.messenger.data.repository

import com.construct.messenger.data.model.Message
import com.construct.messenger.data.model.ReplyRef
import com.construct.messenger.domain.usecase.SendOutcome
import kotlinx.coroutines.flow.Flow

/**
 * Transcript for a 1:1 chat. UI collects [observeContact] and calls [send].
 */
interface MessagesRepository {
    /** Messages in the `direct:<me>:<contact>` conversation, oldest first. */
    fun observeContact(contactId: String): Flow<List<Message>>

    /**
     * Send [text] to [contactId]. [reply] quotes one message in this chat; the quote
     * travels inside the ciphertext and is stored on the outgoing row.
     */
    suspend fun send(contactId: String, text: String, reply: ReplyRef? = null): SendOutcome

    /**
     * Replace the text of a message this account sent. The edit travels as
     * `MessageContent.edit` inside the ciphertext. The row changes only after a
     * recipient device accepts a copy.
     */
    suspend fun edit(contactId: String, messageId: String, newText: String): SendOutcome

    /**
     * Remove [messageId] from this phone's transcript. iOS delete does not tell
     * the peer, and a `DeleteMessage` Android sent would have no consumer there.
     */
    suspend fun delete(contactId: String, messageId: String)

    /**
     * The chat is on screen: what arrives is read as it lands, so unread goes to zero and its
     * notification is withdrawn. Call on every appearance, not once.
     */
    suspend fun chatShown(contactId: String)

    /** Off screen (navigated away, or the app went to the background). */
    fun chatHidden(contactId: String)
}
