package com.construct.messenger.service

import com.construct.messenger.data.local.ContactStore
import com.construct.messenger.data.model.MediaItem
import com.construct.messenger.data.repository.MediaRepository
import com.construct.messenger.data.repository.MediaUnavailable
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.media.AvatarPreparer
import com.construct.messenger.util.ProfileShare
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The avatar a contact's profile named, fetched onto their row. **Canon:** iOS
 * `ProfileSharingManager.fetchPendingAvatar` and `AvatarRetryService`.
 *
 * The profile leaves the reference on the row (`ContactRecord.pendingAvatarRef`); it stays there until
 * the picture arrives, the store says the file is gone, or it is older than the store keeps
 * anything (7 days). Any other failure — offline, the transport still choosing a path — leaves it
 * for the next stream connect. Before 2026-10-02 nothing was kept, so a failed download was final.
 *
 * The bytes are the peer's: the key must be a whole AES-256 key, the blob must open under it, and
 * what it opens to must decode as a picture of bounded size before it is stored.
 */
interface ContactAvatars {
    /** Fetch the avatar pending on [accountId]'s row, if any; returns at once. */
    fun fetchPending(accountId: String)

    /** Every pending avatar again — on each stream connect. */
    fun retryPending()
}

@Singleton
class MediaContactAvatars internal constructor(
    private val media: MediaRepository,
    private val contacts: ContactStore,
    private val nowMs: () -> Long,
    private val isPicture: (ByteArray) -> Boolean,
) : ContactAvatars {
    @Inject
    constructor(media: MediaRepository, contacts: ContactStore) :
        this(media, contacts, System::currentTimeMillis, AvatarPreparer::isAcceptable)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun fetchPending(accountId: String) {
        scope.launch { fetchNow(accountId) }
    }

    override fun retryPending() {
        scope.launch {
            val pending = runCatching { contacts.pendingAvatarIds() }.getOrDefault(emptyList())
            if (pending.isEmpty()) return@launch
            Log.i(TAG, "${pending.size} avatar(s) pending")
            pending.forEach { fetchNow(it) }
        }
    }

    enum class Outcome { NOTHING_PENDING, STORED, DROPPED, LEFT_PENDING }

    internal suspend fun fetchNow(accountId: String): Outcome {
        val row = contacts.get(accountId) ?: return Outcome.NOTHING_PENDING
        val stored = row.pendingAvatarRef ?: return Outcome.NOTHING_PENDING
        val who = accountId.take(8)
        val since = row.pendingAvatarSinceMs
        if (since != null && nowMs() - since > PENDING_LIFETIME_MS) {
            Log.i(TAG, "avatar of $who… expired in the media store — dropped")
            contacts.clearPendingAvatar(accountId, stored)
            return Outcome.DROPPED
        }
        val ref = ProfileShare.AvatarRef.fromStored(stored)
        if (ref == null || !ref.mimeType.startsWith("image/")) {
            contacts.clearPendingAvatar(accountId, stored)
            return Outcome.DROPPED
        }
        return try {
            val bytes = media.bytes(
                MediaItem(mediaId = ref.mediaId, key = ref.mediaKey, hash = ByteArray(0), sizeBytes = 0, mimeType = ref.mimeType),
            )
            if (!isPicture(bytes)) {
                Log.w(TAG, "avatar of $who… is not a picture we keep — dropped")
                contacts.clearPendingAvatar(accountId, stored)
                return Outcome.DROPPED
            }
            // Guarded by the reference: a newer profile may have named another meanwhile, and a
            // contact deleted meanwhile has no row to update.
            if (!contacts.completePendingAvatar(accountId, stored, bytes)) return Outcome.NOTHING_PENDING
            Log.i(TAG, "avatar of $who… stored (${bytes.size}B)")
            Outcome.STORED
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaUnavailable) {
            Log.i(TAG, "avatar of $who… is gone from the media store (${e.message}) — dropped")
            contacts.clearPendingAvatar(accountId, stored)
            Outcome.DROPPED
        } catch (e: Exception) {
            Log.i(TAG, "avatar of $who… not fetched (${e.javaClass.simpleName}) — retried on reconnect")
            Outcome.LEFT_PENDING
        }
    }

    internal companion object {
        const val TAG = "ContactAvatars"
        /** `MEDIA_FILE_TTL_SECONDS`: the store deletes every blob after 7 days. */
        const val PENDING_LIFETIME_MS = 7L * 24 * 3600 * 1000
    }
}
