package com.construct.messenger.data.repository

import android.net.Uri
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
     * Send the photos at [uris] (picked by the user, readable now) as one album, [caption] its
     * text. The message shows at once and is sent when every photo is uploaded.
     */
    suspend fun sendPhotos(contactId: String, uris: List<Uri>, caption: String, reply: ReplyRef? = null): SendOutcome

    /** Send the files at [uris] as one message, [caption] its text. */
    suspend fun sendFiles(contactId: String, uris: List<Uri>, caption: String): SendOutcome

    /** A received file, ready to hand to another app: a content URI with a read grant to give. */
    suspend fun openable(item: com.construct.messenger.data.model.MediaItem, name: String): Uri

    /** Name and size of a picked file, for the strip — nothing is read. */
    fun describe(uri: Uri): Pair<String, Long>

    /** Send a recorded voice note; [recording] is taken over (and deleted once sealed). */
    suspend fun sendVoice(contactId: String, recording: java.io.File, durationMs: Long, waveform: List<Float>): SendOutcome

    /** A recorded video note; its files are deleted once handed over. */
    suspend fun sendVideoNote(contactId: String, take: com.construct.messenger.media.VideoNoteTake): SendOutcome

    /** [item] decrypted into the phone's gallery (Android 10+). Throws when it could not be. */
    suspend fun saveToGallery(item: com.construct.messenger.data.model.MediaItem)

    /** The decrypted bytes of an item of a message, fetched if they are not here. */
    suspend fun mediaBytes(item: com.construct.messenger.data.model.MediaItem): ByteArray

    /**
     * Replace the text of a message this account sent. The edit travels as
     * `MessageContent.edit` inside the ciphertext. The row changes only after a
     * recipient device accepts a copy.
     */
    suspend fun edit(contactId: String, messageId: String, newText: String): SendOutcome

    /** Send [ref], a sticker from a pack on this phone. Recorded among the recents. */
    suspend fun sendSticker(contactId: String, ref: com.construct.messenger.stickers.StickerReference): SendOutcome

    /** Send again one of ours that no device took (iOS Retry). */
    suspend fun retry(contactId: String, messageId: String): SendOutcome

    /**
     * React to [messageId] with [emoji], or take the reaction off when it is the one already set
     * (iOS `localToggle`). Shown at once; put back as it was when no device took it. False when
     * nothing was sent.
     */
    suspend fun react(contactId: String, messageId: String, emoji: String): Boolean

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
