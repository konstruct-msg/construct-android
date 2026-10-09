package com.construct.messenger.service

import com.construct.messenger.data.local.ContactRecord
import com.construct.messenger.data.local.FakeContactStore
import com.construct.messenger.data.model.MediaItem
import com.construct.messenger.data.repository.MediaRepository
import com.construct.messenger.data.repository.MediaUnavailable
import com.construct.messenger.data.api.MediaService
import com.construct.messenger.util.ProfileShare
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pending avatar on a contact's row: kept until it arrives, is gone, or is a week old. */
class ContactAvatarsTest {
    private class Media(private val answer: () -> ByteArray) : MediaRepository {
        val asked = mutableListOf<MediaItem>()
        override suspend fun bytes(item: MediaItem): ByteArray { asked += item; return answer() }
        override suspend fun stage(localId: String, blob: ByteArray) = error("not here")
        override suspend fun upload(localId: String, sha256: ByteArray): MediaService.Uploaded = error("not here")
        override suspend fun openable(item: MediaItem, name: String): android.net.Uri = error("not here")
        override suspend fun saveToGallery(item: MediaItem) = error("not here")
    }

    private val ref = ProfileShare.AvatarRef("m-1", "https://media.example/m-1", ByteArray(32) { 7 }, "image/jpeg")
    private val now = 10_000_000_000L
    private val users = FakeContactStore()

    private fun pending(stored: ByteArray = ref.stored(), sinceMs: Long = now - 1_000) {
        users.rows["peer"] = ContactRecord(id = "peer", isContact = true, avatarData = byteArrayOf(1), pendingAvatarRef = stored, pendingAvatarSinceMs = sinceMs)
    }

    private fun avatars(media: Media, picture: Boolean = true) = MediaContactAvatars(media, users, { now }, { picture })

    @Test
    fun `a download that lands replaces the avatar and clears the reference`() = runBlocking {
        pending()
        val media = Media { byteArrayOf(9, 9) }
        assertEquals(MediaContactAvatars.Outcome.STORED, avatars(media).fetchNow("peer"))
        assertEquals("m-1", media.asked.single().mediaId)
        assertArrayEquals(byteArrayOf(9, 9), users.rows["peer"]!!.avatarData)
        assertNull(users.rows["peer"]!!.pendingAvatarRef)
    }

    /** Mutation: clear the reference on any failure — this reddens. */
    @Test
    fun `a network failure leaves it pending for the next connect`() = runBlocking {
        pending()
        val outcome = avatars(Media { throw java.io.IOException("offline") }).fetchNow("peer")
        assertEquals(MediaContactAvatars.Outcome.LEFT_PENDING, outcome)
        assertArrayEquals(ref.stored(), users.rows["peer"]!!.pendingAvatarRef)
        assertArrayEquals("the avatar held stays", byteArrayOf(1), users.rows["peer"]!!.avatarData)
    }

    @Test
    fun `gone from the store drops the reference and keeps the avatar held`() = runBlocking {
        pending()
        assertEquals(MediaContactAvatars.Outcome.DROPPED, avatars(Media { throw MediaUnavailable("not found") }).fetchNow("peer"))
        assertNull(users.rows["peer"]!!.pendingAvatarRef)
        assertArrayEquals(byteArrayOf(1), users.rows["peer"]!!.avatarData)
    }

    @Test
    fun `older than the store keeps anything is dropped without asking`() = runBlocking {
        pending(sinceMs = now - MediaContactAvatars.PENDING_LIFETIME_MS - 1)
        val media = Media { byteArrayOf(9) }
        assertEquals(MediaContactAvatars.Outcome.DROPPED, avatars(media).fetchNow("peer"))
        assertEquals(0, media.asked.size)
    }

    @Test
    fun `what is not a picture is not stored`() = runBlocking {
        pending()
        assertEquals(MediaContactAvatars.Outcome.DROPPED, avatars(Media { byteArrayOf(9) }, picture = false).fetchNow("peer"))
        assertArrayEquals(byteArrayOf(1), users.rows["peer"]!!.avatarData)
    }

    @Test
    fun `a newer reference named meanwhile is not overwritten by the older download`() = runBlocking {
        pending()
        val newer = ProfileShare.AvatarRef("m-2", "u", ByteArray(32) { 8 }, "image/jpeg").stored()
        val media = Media { users.rows["peer"] = users.rows["peer"]!!.copy(pendingAvatarRef = newer); byteArrayOf(9) }
        assertEquals(MediaContactAvatars.Outcome.NOTHING_PENDING, avatars(media).fetchNow("peer"))
        assertArrayEquals(newer, users.rows["peer"]!!.pendingAvatarRef)
        assertArrayEquals(byteArrayOf(1), users.rows["peer"]!!.avatarData)
    }
}
