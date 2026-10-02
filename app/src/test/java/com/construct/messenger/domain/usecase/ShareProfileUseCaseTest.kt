package com.construct.messenger.domain.usecase

import com.construct.messenger.data.api.MediaService
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.model.MediaItem
import com.construct.messenger.data.repository.MediaRepository
import com.construct.messenger.util.ProfileShare
import com.construct.messenger.viewmodel.FakeAccountRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** The avatar's three states on the way out, and the version that does not move on a resend. */
class ShareProfileUseCaseTest {
    private class Media(private val uploads: Boolean) : MediaRepository {
        override suspend fun bytes(item: MediaItem): ByteArray = error("not here")
        override suspend fun stage(localId: String, blob: ByteArray) = Unit
        override suspend fun upload(localId: String, sha256: ByteArray): MediaService.Uploaded =
            if (uploads) MediaService.Uploaded("m-1", "https://media.example/m-1") else throw java.io.IOException("offline")
        override suspend fun openable(item: MediaItem, name: String): android.net.Uri = error("not here")
        override suspend fun saveToGallery(item: MediaItem) = error("not here")
    }

    private val keystore = mock<KeystoreManager>().also { whenever(it.getUserId()).thenReturn("u1") }

    private fun useCase(account: FakeAccountRepository, uploads: Boolean = true) =
        ShareProfileUseCase(keystore, account, mock(), mock(), Media(uploads))

    /** Mutation: send "no avatar" as unchanged — a contact would keep the old one; this reddens. */
    @Test
    fun `no avatar goes as removed`() = runBlocking {
        val profile = useCase(FakeAccountRepository()).profile()!!
        assertEquals(ProfileShare.Avatar.Removed, profile.avatar)
        assertEquals("soft lion", profile.displayName)
    }

    @Test
    fun `an uploaded avatar goes as set, under a whole key`() = runBlocking {
        val account = FakeAccountRepository().apply { state.value = state.value!!.copy(avatar = ByteArray(64) { 5 }) }
        val avatar = useCase(account).profile()!!.avatar as ProfileShare.Avatar.Set
        assertEquals("m-1", avatar.ref.mediaId)
        assertEquals(32, avatar.ref.mediaKey.size)
        assertFalse(account.profileRebroadcastOwed)
    }

    /** Mutation: send a failed upload as removed — contacts would lose the avatar; this reddens. */
    @Test
    fun `a failed upload goes as unchanged and owes the rebroadcast`() = runBlocking {
        val account = FakeAccountRepository().apply { state.value = state.value!!.copy(avatar = ByteArray(64) { 5 }) }
        val profile = useCase(account, uploads = false).profile()!!
        assertEquals(ProfileShare.Avatar.Unchanged, profile.avatar)
        assertTrue(account.profileRebroadcastOwed)
    }

    @Test
    fun `the version is the account's, not the send time`() = runBlocking {
        val account = FakeAccountRepository()
        val first = useCase(account).profile()!!
        Thread.sleep(5)
        assertEquals(first.editedAtMs, useCase(account).profile()!!.editedAtMs)
        assertEquals(account.profileVersion(), first.editedAtMs)
    }
}
