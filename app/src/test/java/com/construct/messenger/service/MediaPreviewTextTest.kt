package com.construct.messenger.service

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.construct.messenger.util.MediaWire
import com.google.protobuf.ByteString
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import shared.proto.messaging.v1.Content.MediaAlbumMessage
import shared.proto.messaging.v1.Content.MediaMessage
import shared.proto.messaging.v1.Content.MessageContent

/** The chat list's line for an album with no caption — iOS `LocalMessagePayload.previewHint`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MediaPreviewTextTest {
    private val preview = AndroidMediaPreviewText(ApplicationProvider.getApplicationContext())

    private fun of(vararg items: Pair<String, String>): String {
        val album = MediaAlbumMessage.newBuilder()
        items.forEachIndexed { n, (mime, name) ->
            album.addItems(
                MediaMessage.newBuilder().setMediaId("m$n").setMimeType(mime).setFilename(name)
                    .setEncryptionKey(ByteString.copyFrom(ByteArray(32) { 1 })),
            )
        }
        return preview.of(MediaWire.stored(MessageContent.newBuilder().setMediaAlbum(album).build())!!)
    }

    /** Until 2026-10-13 a sent mp3 read "Photo" here. */
    @Test
    fun aTrackIsAudio() = assertEquals("Audio", of("audio/mpeg" to "song.mp3"))

    @Test
    fun tracksAreAudio() = assertEquals("Audio", of("audio/mpeg" to "a.mp3", "audio/flac" to "b.flac"))

    @Test
    fun aTrackBesideADocumentIsFiles() = assertEquals("2 files", of("audio/mpeg" to "a.mp3", "application/pdf" to "b.pdf"))

    @Test
    fun aDocumentIsNamed() = assertEquals("b.pdf", of("application/pdf" to "b.pdf"))
}
