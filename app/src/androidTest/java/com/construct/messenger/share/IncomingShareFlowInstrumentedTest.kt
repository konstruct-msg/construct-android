package com.construct.messenger.share

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.construct.messenger.R
import com.construct.messenger.data.local.ensure
import com.construct.messenger.data.model.DeliveryStatus
import com.construct.messenger.data.model.IncomingShareStage
import com.construct.messenger.data.model.MessageMedia
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.util.ConversationId
import com.construct.messenger.util.MediaWire
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in production stand: only synthetic data to this device's OWN account.
 * Leaves canary messages in the self-chat as visible proof; never selects a peer chat.
 * Uses the real device clock/accessibility, not a Compose test dispatcher for MessagingRuntime. */
@RunWith(AndroidJUnit4::class)
class IncomingShareFlowInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun command(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
    }
    private suspend fun until(timeout: Long = 30_000, predicate: () -> Boolean) = withTimeout(timeout) {
        while (!predicate()) delay(25)
    }
    private fun node(root: AccessibilityNodeInfo?, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null || match(root)) return root
        for (i in 0 until root.childCount) node(root.getChild(i), match)?.let { return it }
        return null
    }

    @Test
    fun aMixedShareNeedsConfirmationAndUsesTheOrdinaryEncryptedSend(): Unit = runBlocking {
        assumeTrue("opt in with -e shareStand true on the test phone", InstrumentationRegistry.getArguments().getString("shareStand") == "true")
        val context = instrumentation.targetContext
        val access = EntryPointAccessors.fromApplication(context.applicationContext, IncomingShareStandAccess::class.java)
        command("am start -n com.construct.messenger/.MainActivity")
        Log.i("IncomingShareStand", "step: launched")
        withTimeout(60_000) { access.auth().authState.first { it.isInitialized } }
        val stores = access.stores().backing()
        val ownId = requireNotNull(access.identity().getUserId())
        val chatId = ConversationId.direct(ownId, ownId)
        stores.contacts.ensure(ownId)
        stores.chats.ensure(chatId, ownId)
        val shares = access.shares()
        shares.prepare()
        val pending = shares.state.value.draft
        check(pending == null || pending.stage == IncomingShareStage.FINISHED || pending.text.startsWith("SHARE-CANARY")) { "Finish the existing share before running the stand" }
        shares.cancel()
        val before = stores.messages.observeChat(chatId).first().map { it.id }.toSet()
        command("am start -n com.construct.messenger.test/com.construct.messenger.share.ShareSenderActivity --es kind mixed")
        until { shares.state.value.draft?.let { it.stage == IncomingShareStage.READY && it.items.size == 3 } == true }
        val draft = requireNotNull(shares.state.value.draft)
        assertNull(draft.contactId)
        shares.select(draft.id, ownId)
        val automation = instrumentation.uiAutomation
        until { node(automation.rootInActiveWindow) { it.isEditable && it.text?.toString() == CANARY } != null }
        assertTrue("selection sent a message", stores.messages.observeChat(chatId).first().none { it.id !in before && it.text == CANARY })
        Log.i("IncomingShareStand", "step: preview, no message before confirmation")
        // Revoke the ORIGINAL private provider grants. Sending must use sealed local snapshots.
        command("am start -n com.construct.messenger.test/com.construct.messenger.share.ShareSenderActivity --es kind mixed --ez revoke true")
        until {
            listOf("photo", "video", "pdf").all { kind -> context.checkUriPermission(Uri.parse("content://com.construct.messenger.test.share-source/$kind"), android.os.Process.myPid(), android.os.Process.myUid(), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        }
        command("am start -n com.construct.messenger/.MainActivity")
        until { node(automation.rootInActiveWindow) { it.isEditable && it.text?.toString() == CANARY } != null }
        check(shares.state.value.draft?.contactId == ownId) { "Never send this stand to a peer" }
        val send = context.getString(R.string.chat_send)
        until { node(automation.rootInActiveWindow) { it.contentDescription?.toString() == send } != null }
        assertTrue(requireNotNull(node(automation.rootInActiveWindow) { it.contentDescription?.toString() == send }).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        Log.i("IncomingShareStand", "step: explicit self-send")
        withTimeout(120_000) { shares.state.first { it.draft?.id == draft.id && it.draft?.stage == IncomingShareStage.FINISHED && !it.submitting } }
        val rows = stores.messages.observeChat(chatId).first().filter { it.id !in before && it.isSentByMe }
        val albums = rows.mapNotNull { row ->
            val album = MediaWire.decode(row.mediaType, row.mediaPayload) as? MessageMedia.Album
            if (album != null && (row.text == CANARY || album.items.any { it.filename == "share-canary.pdf" })) row to album else null
        }
        assertEquals("media and files are two separate messages", 2, albums.size)
        assertTrue("ordinary send failed", albums.all { it.first.deliveryStatus == DeliveryStatus.SENT || it.first.deliveryStatus == DeliveryStatus.DELIVERED })
        val visual = albums.single { !it.second.isFiles }
        assertEquals(CANARY, visual.first.text)
        assertEquals(2, visual.second.items.size)
        val document = albums.single { it.second.isFiles }
        assertEquals("", document.first.text)
        assertEquals(1, document.second.items.size)
        for (item in albums.flatMap { it.second.items }) {
            access.media().dropCopy(item.mediaId)
            val bytes = access.media().bytes(item) // Real DownloadMedia + native AEAD, not the staged copy.
            assertTrue(bytes.isNotEmpty())
            if (item.mimeType == "application/pdf") assertArrayEquals("%PDF-1.4\nSHARE-CANARY-DOCUMENT\n%%EOF\n".toByteArray(), bytes)
            if (item.mimeType.startsWith("image/")) {
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                assertNotNull("downloaded photo is not decodable", bitmap)
                bitmap?.recycle()
            }
            if (item.mimeType.startsWith("video/")) {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    // Decode the downloaded bytes, without a plaintext file or the revoked source.
                    retriever.setDataSource(object : android.media.MediaDataSource() {
                        override fun getSize() = bytes.size.toLong()
                        override fun close() = Unit
                        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                            if (position >= bytes.size) return -1
                            val n = minOf(size, bytes.size - position.toInt())
                            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
                            return n
                        }
                    })
                    val frame = retriever.getFrameAtTime(0)
                    assertNotNull("downloaded video has no decodable frame", frame)
                    frame?.recycle()
                } finally { retriever.release() }
            }
        }
        assertTrue(File(context.noBackupFilesDir, "incoming-share").listFiles().orEmpty().isEmpty())
        Log.i("IncomingShareStand", "confirmed self-send: 2 messages, 3 blobs downloaded and opened; snapshot cleanup checked")
    }

    private companion object { const val CANARY = "SHARE-CANARY https://example.org/" }
}
