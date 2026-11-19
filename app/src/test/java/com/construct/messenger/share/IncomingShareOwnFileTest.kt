package com.construct.messenger.share

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.diagnostics.Diagnostics
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The log archive goes into the share draft sealed, and nothing but the export can go in this way. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = android.app.Application::class)
class IncomingShareOwnFileTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val files = IncomingShareFiles(context, CryptoManager())
    private val request = "10000000-0000-4000-8000-000000000001"

    @Test
    fun theExportIsSealedIntoTheDraftAndItsPlainCopyRemoved() = runBlocking {
        val archive = File(Diagnostics.exportDir(context).apply { mkdirs() }, "construct-logs-7.txt")
        archive.writeText("line one\nline two\n")
        val item = files.importOwn(request, archive, 1 shl 20)
        assertEquals("construct-logs-7.txt", item.name)
        assertEquals("text/plain", item.mime)
        assertEquals(18L, item.size)
        assertFalse(archive.exists())
    }

    @Test
    fun aPrivateFileOutsideTheExportIsNotRead() = runBlocking {
        val secret = File(context.filesDir, "session.blob").apply { writeText("ratchet") }
        try {
            files.importOwn(request, secret, 1 shl 20)
            fail("a private file was imported")
        } catch (_: SecurityException) {
        }
        assertTrue(secret.exists())
    }

    @Test
    fun anExportOverTheBudgetIsRefused() = runBlocking {
        val archive = File(Diagnostics.exportDir(context).apply { mkdirs() }, "construct-logs-8.txt")
        archive.writeText("0123456789")
        try {
            files.importOwn(request, archive, 4)
            fail("an export over the budget was imported")
        } catch (_: com.construct.messenger.media.PickedFiles.TooLarge) {
        }
    }
}
