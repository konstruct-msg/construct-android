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
 * Sharing sends a typed profile (content type 29, [ProfileShare]) to every device of theirs; it is
 * marked shared only once a device took it. It carries the version our name or avatar last
 * changed ([AccountRepository.profileVersion]), so a contact applies it only if newer, and the
 * avatar in one of three states: uploaded → set; we have none → removed, so a contact holding an old
 * one clears it; the upload failed → unchanged, so contacts keep what they have and the rebroadcast
 * is owed ([rebroadcastIfOwed]). The avatar goes sealed under a fresh key; the store holds a blob it
 * cannot open. Stopping sends nothing — what they already received stays theirs.
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
        val profile = profile() ?: return false
        val sent = sendMessage.shareProfile(contactId, profile.encoded())
        if (sent) userDao.setAmSharingWith(contactId, true)
        return sent
    }

    suspend fun stop(contactId: String) {
        userDao.setAmSharingWith(contactId, false)
    }

    /**
     * The profile again to everyone it is shared with — after the name or avatar changed. **Canon:**
     * iOS `rebroadcastProfileToSharedContacts`: prepared once, the avatar uploaded once for all.
     */
    fun rebroadcast() {
        scope.launch { rebroadcastNow() }
    }

    /** The rebroadcast an earlier one owed (its avatar did not upload). On every stream connect. */
    fun rebroadcastIfOwed() {
        if (!account.profileRebroadcastOwed) return
        account.profileRebroadcastOwed = false
        Log.i(TAG, "profile rebroadcast owed from a failed avatar upload — sending again")
        rebroadcast()
    }

    internal suspend fun rebroadcastNow() {
        val contacts = userDao.sharingWithIds()
        if (contacts.isEmpty()) return
        val payload = profile()?.encoded() ?: return
        for (contactId in contacts) {
            val sent = sendMessage.shareProfile(contactId, payload)
            Log.i(TAG, "profile rebroadcast to ${contactId.take(8)}… ${if (sent) "taken" else "not taken"}")
        }
    }

    internal suspend fun profile(): ProfileShare? {
        val myId = keystoreManager.getUserId() ?: return null
        val own = account.account.value
        val name = own?.displayName?.takeIf { it.isNotBlank() } ?: DisplayNameGenerator.generate(myId)
        val avatar = when (val jpeg = own?.avatar) {
            null -> ProfileShare.Avatar.Removed
            else -> uploadAvatar(jpeg)?.let(ProfileShare.Avatar::Set) ?: run {
                account.profileRebroadcastOwed = true
                ProfileShare.Avatar.Unchanged
            }
        }
        return ProfileShare(displayName = name, editedAtMs = account.profileVersion(), avatar = avatar)
    }

    private suspend fun uploadAvatar(jpeg: ByteArray): ProfileShare.AvatarRef? = try {
        val sealed = MediaCrypto.seal(jpeg)
        val localId = MediaWire.LOCAL_PREFIX + UUID.randomUUID()
        media.stage(localId, sealed.blob)
        val uploaded = media.upload(localId, sealed.sha256)
        ProfileShare.AvatarRef(uploaded.mediaId, uploaded.downloadUrl, sealed.key, AVATAR_TYPE)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "avatar upload failed — profile goes with the avatar unchanged, rebroadcast owed", e)
        null
    }

    private companion object {
        const val TAG = "ShareProfile"
        const val AVATAR_TYPE = "image/jpeg"
    }
}
