package com.construct.messenger.domain.usecase

import com.construct.messenger.data.local.LocalMessagePayload
import com.construct.messenger.data.local.MessageStore
import com.construct.messenger.data.local.Note
import com.construct.messenger.data.local.NoteStore
import com.construct.messenger.data.model.ForwardItem
import com.construct.messenger.data.model.MediaItem
import com.construct.messenger.data.model.Message
import com.construct.messenger.data.model.MessageMedia
import com.construct.messenger.data.repository.MediaRepository
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.util.MediaWire
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/**
 * "Save to notes" on a message (vault todo 279): a copy of what it says and holds, with whose it
 * was and when. Its files are fetched now — the server forgets them in days, and a note is for
 * later. **Canon:** iOS `NoteSaver`.
 */
class SaveToNotesUseCase @Inject constructor(
    private val messages: MessageStore,
    private val notes: NoteStore,
    private val media: MediaRepository,
) {
    enum class Outcome {
        SAVED,

        /** The note is there; a file of it could not be fetched, and may be gone for good. */
        SAVED_WITHOUT_FILES,
        FAILED,
    }

    /** The note is written first: what is on this device already is kept whatever the network does. */
    suspend operator fun invoke(messageId: String, sourceName: String?, nowMs: Long = System.currentTimeMillis()): Outcome {
        val note = try {
            val row = messages.get(messageId) ?: return Outcome.FAILED
            if (MediaWire.decode(row.mediaType, row.mediaPayload)?.let(MediaWire::isStaged) == true) return Outcome.FAILED
            Note(
                body = LocalMessagePayload.encode(row),
                createdAtMs = nowMs,
                sourceName = sourceName.takeUnless { row.isSentByMe },
                sourceAtMs = row.timestampMs,
            ).also { notes.insert(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "not saved", e)
            return Outcome.FAILED
        }
        var outcome = Outcome.SAVED
        for (item in files(note.media)) {
            try {
                media.prefetch(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "a file was not fetched", e)
                outcome = Outcome.SAVED_WITHOUT_FILES
            }
        }
        return outcome
    }

    private fun files(media: MessageMedia?): List<MediaItem> = when (media) {
        is MessageMedia.Album -> media.items
        is MessageMedia.Voice -> listOf(media.audio)
        is MessageMedia.Sticker, null -> emptyList()
    }

    companion object {
        private const val TAG = "Notes"

        /**
         * Whether [message] has anything to keep. One still uploading has not yet: the upload
         * renames its file, and the note would name the old one.
         */
        fun keeps(message: Message): Boolean =
            ForwardItem.of(message) != null && message.media?.let(MediaWire::isStaged) != true
    }
}
