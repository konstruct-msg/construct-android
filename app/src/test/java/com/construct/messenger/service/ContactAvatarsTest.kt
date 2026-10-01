package com.construct.messenger.service

import com.construct.messenger.data.local.db.UserDao
import com.construct.messenger.data.model.MediaItem
import com.construct.messenger.data.repository.MediaRepository
import com.construct.messenger.data.api.MediaService
import com.construct.messenger.util.ProfileShare
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

class ContactAvatarsTest {
    private class RecordingMedia(private val answer: ByteArray) : MediaRepository {
        val asked = CompletableDeferred<MediaItem>()
        override suspend fun bytes(item: MediaItem): ByteArray = answer.also { asked.complete(item) }
        override suspend fun stage(localId: String, blob: ByteArray) = error("not here")
        override suspend fun upload(localId: String, sha256: ByteArray): MediaService.Uploaded = error("not here")
        override suspend fun openable(item: MediaItem, name: String): android.net.Uri = error("not here")
        override suspend fun saveToGallery(item: MediaItem) = error("not here")
    }

    private fun profile(key: ByteArray? = ByteArray(32) { 7 }, type: String? = "image/jpeg") = ProfileShare(
        displayName = "Kostya",
        avatarMediaId = "0b6f2c1e-4c1a-4d1e-9f00-0a1b2c3d4e5f",
        avatarMediaUrl = "https://media.example/x",
        avatarMediaKey = key,
        avatarMediaType = type,
        timestampSec = 1,
    )

    @Test
    fun `a whole key fetches the named blob`() = runBlocking {
        val media = RecordingMedia(ByteArray(10))
        MediaContactAvatars(media, mock()).fetch("peer", profile())
        val item = withTimeout(2_000) { media.asked.await() }
        assertEquals("0b6f2c1e-4c1a-4d1e-9f00-0a1b2c3d4e5f", item.mediaId)
        assertEquals(32, item.key.size)
    }

    @Test
    fun `a short key, a non-image type or no id fetches nothing`() = runBlocking {
        for (p in listOf(profile(key = ByteArray(16)), profile(type = "video/mp4"), profile().copy(avatarMediaId = null))) {
            val media = RecordingMedia(ByteArray(10))
            MediaContactAvatars(media, mock()).fetch("peer", p)
            assertNull(withTimeoutOrNull(300) { media.asked.await() })
        }
    }

    @Test
    fun `what does not decode as a picture is not stored`() = runBlocking {
        val media = RecordingMedia(ByteArray(10))
        val users = mock<UserDao>()
        MediaContactAvatars(media, users).fetch("peer", profile())
        withTimeout(2_000) { media.asked.await() }
        Thread.sleep(200)
        verify(users, never()).setAvatar(any(), any())
    }
}
