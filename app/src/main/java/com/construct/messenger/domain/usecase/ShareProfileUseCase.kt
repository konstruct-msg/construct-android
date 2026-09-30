package com.construct.messenger.domain.usecase

import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.db.UserDao
import com.construct.messenger.data.repository.AccountRepository
import com.construct.messenger.data.repository.MediaRepository
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.media.MediaCrypto
import com.construct.messenger.util.DisplayNameGenerator
import com.construct.messenger.util.MediaWire
import com.construct.messenger.util.ProfileShare
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * "Share my profile" / "Stop sharing profile" on a contact's card. **Canon:** iOS
 * `UserProfileView.handleShareToggle` and `ProfileShareViewModel`.
 *
 * Sharing sends the name this account goes by and its avatar, end to end, to every device of
 * theirs; it is marked shared only once a device took it. The avatar goes as iOS sends it: sealed
 * under a fresh key, uploaded to the media store, and named in the profile with that key — the
 * store holds a blob it cannot open. An avatar that fails to upload is left out, not the profile.
 * Stopping sends nothing — it is the mark alone, as on iOS: what they already received stays theirs.
 */
@Singleton
class ShareProfileUseCase @Inject constructor(
    private val keystoreManager: KeystoreManager,
    private val account: AccountRepository,
    private val sendMessage: SendMessageUseCase,
    private val userDao: UserDao,
    private val media: MediaRepository,
) {
    // Outlives the screen that asked: leaving Account must not stop the rebroadcast.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun share(contactId: String): Boolean {
        val profile = profile(withAvatar = uploadAvatar()) ?: return false
        val sent = sendMessage.shareProfile(contactId, profile)
        if (sent) userDao.setAmSharingWith(contactId, true)
        return sent
    }

    suspend fun stop(contactId: String) {
        userDao.setAmSharingWith(contactId, false)
    }

    /**
     * The profile again to everyone it is shared with — after the avatar changed. **Canon:** iOS
     * `rebroadcastProfileToSharedContacts`, except that the avatar is uploaded once for all of
     * them rather than once each.
     */
    fun rebroadcast() {
        scope.launch {
            val contacts = userDao.sharingWithIds()
            if (contacts.isEmpty()) return@launch
            val profile = profile(withAvatar = uploadAvatar()) ?: return@launch
            for (contactId in contacts) {
                val sent = sendMessage.shareProfile(contactId, profile)
                Log.i(TAG, "profile rebroadcast to ${contactId.take(8)}… ${if (sent) "taken" else "not taken"}")
            }
        }
    }

    private fun profile(withAvatar: UploadedAvatar?): ProfileShare? {
        val myId = keystoreManager.getUserId() ?: return null
        val name = account.account.value?.displayName?.takeIf { it.isNotBlank() }
            ?: DisplayNameGenerator.generate(myId)
        return ProfileShare(
            displayName = name,
            avatarMediaId = withAvatar?.mediaId,
            avatarMediaUrl = withAvatar?.url,
            avatarMediaKey = withAvatar?.key,
            avatarMediaType = withAvatar?.let { AVATAR_TYPE },
            timestampSec = System.currentTimeMillis() / 1000,
        )
    }

    private class UploadedAvatar(val mediaId: String, val url: String, val key: ByteArray)

    private suspend fun uploadAvatar(): UploadedAvatar? {
        val jpeg = account.account.value?.avatar ?: return null
        return try {
            val sealed = MediaCrypto.seal(jpeg)
            val localId = MediaWire.LOCAL_PREFIX + UUID.randomUUID()
            media.stage(localId, sealed.blob)
            val uploaded = media.upload(localId, sealed.sha256)
            UploadedAvatar(uploaded.mediaId, uploaded.downloadUrl, sealed.key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "avatar upload failed — profile goes without it", e)
            null
        }
    }

    private companion object {
        const val TAG = "ShareProfile"
        const val AVATAR_TYPE = "image/jpeg"
    }
}
