package com.construct.messenger.share

import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Debug
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.model.IncomingShare
import com.construct.messenger.data.model.IncomingShareStage
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.media.PickedFiles
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Only canary sources and a separate scratch directory. Never opens or edits the account store. */
@RunWith(AndroidJUnit4::class)
class IncomingShareFilesInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val scratch = File(target.cacheDir, "incoming-share-instrumented-${UUID.randomUUID()}")
    private val context = object : ContextWrapper(target) {
        override fun getNoBackupFilesDir(): File = scratch.apply { mkdirs() }
        override fun getCacheDir(): File = File(scratch, "cache").apply { mkdirs() }
    }

    private fun source(size: Int, declared: Int = size): Uri = Uri.Builder().scheme("content")
        .authority("com.construct.messenger.test.share-source").appendPath("bytes")
        .appendQueryParameter("bytes", size.toString()).appendQueryParameter("declared", declared.toString())
        .appendQueryParameter("name", "../../private.bin").build()

    private fun grant(size: Int, declared: Int = size, revoke: Boolean = false) {
        val operation = UUID.randomUUID().toString()
        val ack = Uri.parse("content://com.construct.messenger.test.share-source/ack/$operation")
        val command = "am start -n com.construct.messenger.test/com.construct.messenger.share.ShareSenderActivity --ez grantOnly true --es kind bytes --es bytes $size --es declared $declared --es name ../../private.bin --ez revoke $revoke --es ack $operation"
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        val expected = if (revoke) PackageManager.PERMISSION_DENIED else PackageManager.PERMISSION_GRANTED
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (target.checkUriPermission(ack, android.os.Process.myPid(), android.os.Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "fixture grant/revoke did not complete" }
            Thread.sleep(25)
        }
        assertEquals(expected, target.checkUriPermission(source(size, declared), android.os.Process.myPid(), android.os.Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    @Test
    fun anExternalGrantBecomesASealedSeekableSnapshotAndCancellationDeletesIt() = runBlocking {
        val info = target.packageManager.resolveContentProvider("com.construct.messenger.test.share-source", 0)!!
        assertFalse(info.exported)
        assertNotEquals(target.applicationInfo.uid, info.applicationInfo.uid)
        val internal = target.packageManager.resolveContentProvider(ShareSourceUris.authority(target.packageName), 0)!!
        assertFalse(internal.exported)
        assertFalse(internal.grantUriPermissions)
        val files = IncomingShareFiles(context, CryptoManager())
        try {
            val abandoned = File(context.cacheDir, "video/v_abandoned.mp4").apply { parentFile!!.mkdirs(); writeText("PLAINTEXT-CANARY") }
            com.construct.messenger.media.VideoPreparer(context, com.construct.messenger.media.ImagePreparer(context))
            assertFalse("abandoned video export survived the next preparer", abandoned.exists())
            for (size in listOf(1024 * 1024, 10 * 1024 * 1024)) {
                grant(size, revoke = true)
                assertEquals(PackageManager.PERMISSION_DENIED, target.checkUriPermission(source(size), android.os.Process.myPid(), android.os.Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION))
                grant(size)
                assertEquals(PackageManager.PERMISSION_GRANTED, target.checkUriPermission(source(size), android.os.Process.myPid(), android.os.Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION))
                val id = UUID.randomUUID().toString()
                val before = Debug.getPss()
                val start = android.os.SystemClock.elapsedRealtime()
                val imported = files.import(id, source(size), files.importBudget)
                val importMs = android.os.SystemClock.elapsedRealtime() - start
                assertEquals("private.bin", imported.name)
                assertEquals(size.toLong(), imported.size)
                val draft = IncomingShare(id, "canary", listOf(imported), stage = IncomingShareStage.READY)
                files.install(draft)
                assertTrue(files.present(draft))
                grant(size, revoke = true)
                assertEquals(PackageManager.PERMISSION_DENIED, target.checkUriPermission(source(size), android.os.Process.myPid(), android.os.Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION))
                // Real FUSE proxy FD: consumers such as MediaExtractor can seek without a plaintext file.
                ParcelFileDescriptor.AutoCloseInputStream(files.open(files.uri(id, imported.id))).use { input ->
                    input.channel.position(987)
                    val sample = ByteArray(128)
                    assertEquals(128, input.read(sample))
                    assertArrayEquals(ByteArray(128) { ((it + 987) % 251).toByte() }, sample)
                    input.channel.position(0)
                    val all = input.readBytes()
                    assertArrayEquals(ByteArray(size) { (it % 251).toByte() }, all)
                    all.fill(0)
                }
                val blobs = File(scratch, "incoming-share").walkTopDown().filter { it.isFile }.toList()
                assertTrue(blobs.isNotEmpty())
                assertTrue(blobs.all { it.extension == "blob" })
                Log.i("IncomingShareProbe", "bytes=$size importMs=$importMs pssBeforeKiB=$before pssAfterKiB=${Debug.getPss()} budget=${files.importBudget}")
                files.install(null)
                files.prune(null)
                assertTrue(File(scratch, "incoming-share").listFiles().orEmpty().isEmpty())
            }
        } finally { scratch.deleteRecursively() }
    }

    @Test
    fun actualByteCountAndOwnProviderChecksCannotBeBypassedByMetadata() = runBlocking {
        val files = IncomingShareFiles(context, CryptoManager())
        try {
            grant(4096, declared = 0)
            var tooLarge = false
            try { files.import(UUID.randomUUID().toString(), source(4096, 0), 32) }
            catch (_: PickedFiles.TooLarge) { tooLarge = true }
            assertTrue("false declared size bypassed the byte cap", tooLarge)
            var refused = false
            try { files.import(UUID.randomUUID().toString(), Uri.parse("content://${target.packageName}.media/private"), files.importBudget) }
            catch (_: SecurityException) { refused = true }
            assertTrue("our provider was read on behalf of an external sender", refused)
            assertTrue(File(scratch, "incoming-share").walkTopDown().none { it.isFile })
        } finally {
            grant(4096, declared = 0, revoke = true)
            scratch.deleteRecursively()
        }
    }
}
