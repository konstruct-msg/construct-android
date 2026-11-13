package com.construct.messenger.share

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import com.construct.messenger.data.model.ComposerCapacity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = android.app.Application::class)
class IncomingShareIntentTest {
    @Test
    fun streamExtrasAreAuthoritativeAndClipDataIsOnlyAFallback() {
        val a = Uri.parse("content://external/a")
        val b = Uri.parse("content://external/b")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putExtra(Intent.EXTRA_TEXT, "caption")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(b, a, b))
        intent.clipData = ClipData.newRawUri("ignored", Uri.parse("content://external/ignored"))
        val payload = (IncomingShareIntent.read(intent) as IncomingShareIntent.Result.Content).payload
        assertEquals("caption", payload.text)
        assertEquals(listOf(b, a), payload.uris)
        intent.removeExtra(Intent.EXTRA_STREAM)
        assertEquals(listOf(Uri.parse("content://external/ignored")), (IncomingShareIntent.read(intent) as IncomingShareIntent.Result.Content).payload.uris)
        val single = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, a)
        assertEquals(listOf(a), (IncomingShareIntent.read(single) as IncomingShareIntent.Result.Content).payload.uris)
    }

    @Test
    fun malformedAndOversizedInputsAreRefusedRatherThanTruncated() {
        assertEquals(IncomingShareIntent.Result.NotShare, IncomingShareIntent.read(Intent(Intent.ACTION_VIEW)))
        assertEquals(IncomingShareIntent.Result.Invalid, IncomingShareIntent.read(Intent(Intent.ACTION_SEND)))
        assertEquals(IncomingShareIntent.Result.Invalid, IncomingShareIntent.read(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, "not a URI")))
        for (uri in listOf("file:///data/private", "https://example.org/file", "content://10@external/file")) {
            assertEquals(IncomingShareIntent.Result.Invalid, IncomingShareIntent.read(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))))
        }
        val many = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM,
            ArrayList((0..ComposerCapacity.ATTACHMENTS).map { Uri.parse("content://external/$it") }))
        assertEquals(IncomingShareIntent.Result.Invalid, IncomingShareIntent.read(many))
        val text = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://example.org/")
        assertEquals("https://example.org/", (IncomingShareIntent.read(text) as IncomingShareIntent.Result.Content).payload.text)
    }

    @Test
    fun aSharedPayloadCannotAlsoRedeemASidecarInviteOrConfigurationLink() {
        val link = Uri.parse("konstruct://veil-config?d=untrusted")
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            val intent = Intent(action).setDataAndType(link, "text/plain").putExtra(Intent.EXTRA_TEXT, "only this text")
            assertNull(com.construct.messenger.MainActivity.viewLinkData(intent))
            assertEquals("only this text", (IncomingShareIntent.read(intent) as IncomingShareIntent.Result.Content).payload.text)
        }
        assertEquals(link.toString(), com.construct.messenger.MainActivity.viewLinkData(Intent(Intent.ACTION_VIEW, link)))
        assertNull(com.construct.messenger.MainActivity.viewLinkData(null))
    }

    @Test
    fun internalUrisAreOpaqueIdsNotPathsOrKeys() {
        val id = "10000000-0000-4000-8000-000000000001"
        val item = "20000000-0000-4000-8000-000000000002"
        val uri = ShareSourceUris.source("test.app", id, item)
        assertEquals(id to item, ShareSourceUris.ids(uri, "test.app"))
        assertNull(ShareSourceUris.ids(uri, "other.app"))
        assertNull(ShareSourceUris.ids(Uri.parse("content://test.app.incoming-share/../secret"), "test.app"))
        assertNull(ShareSourceUris.ids(Uri.parse("content://test.app.incoming-share/$id/$item/extra"), "test.app"))
    }
}
