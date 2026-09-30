package com.construct.messenger.service

import com.construct.messenger.data.local.db.UserDao
import com.construct.messenger.data.model.MediaItem
import com.construct.messenger.data.repository.MediaRepository
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
 * The avatar a contact's shared profile names, fetched and kept on their row. **Canon:** iOS
 * `ProfileShareViewModel.handleReceivedProfile` — downloaded after the profile is applied, not
 * before it is acknowledged; a profile without one leaves the avatar held as it was.
 *
 * The bytes are the peer's: the key must be a whole AES-256 key, the blob must open under it, and
 * what it opens to must decode as a picture of bounded size before it is stored.
 */
fun interface ContactAvatars {
    /** Starts fetching the avatar [profile] names, if any; returns at once. */
    fun fetch(accountId: String, profile: ProfileShare)
}

@Singleton
class MediaContactAvatars @Inject constructor(
    private val media: MediaRepository,
    private val userDao: UserDao,
) : ContactAvatars {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun fetch(accountId: String, profile: ProfileShare) {
        val mediaId = profile.avatarMediaId ?: return
        val key = profile.avatarMediaKey?.takeIf { it.size == KEY_BYTES } ?: return
        if (profile.avatarMediaType?.startsWith("image/") == false) return
        scope.launch {
            try {
                val bytes = media.bytes(
                    MediaItem(mediaId = mediaId, key = key, hash = ByteArray(0), sizeBytes = 0, mimeType = "image/jpeg"),
                )
                if (!AvatarPreparer.isAcceptable(bytes)) {
                    Log.w(TAG, "avatar from ${accountId.take(8)}… is not a picture we keep")
                    return@launch
                }
                // Only a row that is still there: a contact deleted meanwhile stays deleted.
                if (userDao.getById(accountId) == null) return@launch
                userDao.setAvatar(accountId, bytes)
                Log.i(TAG, "avatar from ${accountId.take(8)}… stored (${bytes.size}B)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "avatar from ${accountId.take(8)}… not fetched: ${e.javaClass.simpleName}")
            }
        }
    }

    private companion object {
        const val TAG = "ContactAvatars"
        const val KEY_BYTES = 32
    }
}
