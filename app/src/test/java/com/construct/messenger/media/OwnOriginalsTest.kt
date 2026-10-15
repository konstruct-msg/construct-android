package com.construct.messenger.media

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A sent file read where it is, and only while it still is that file (TODO 154). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OwnOriginalsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val bytes = ByteArray(1000) { (it % 251).toByte() }

    private fun original(content: ByteArray = bytes): File =
        File.createTempFile("track", ".flac", context.cacheDir).apply { writeBytes(content) }

    @Test
    fun `the original is read while it is the file that was sent`() {
        val file = original()
        val originals = OwnOriginals(context)
        originals.remember("m1", Uri.fromFile(file), bytes.size.toLong(), OwnOriginals.sha256(bytes))
        assertArrayEquals(bytes, OwnOriginals(context).read("m1"))
    }

    /** Mutation: skip the hash check — a file edited after it was sent would play as the message. */
    @Test
    fun `a changed, moved or unknown original is not used`() {
        val file = original()
        val originals = OwnOriginals(context)
        originals.remember("m1", Uri.fromFile(file), bytes.size.toLong(), OwnOriginals.sha256(bytes))
        file.writeBytes(bytes.copyOf().also { it[10] = 99 })
        assertNull("changed", originals.read("m1"))
        file.delete()
        assertNull("gone", originals.read("m1"))
        assertNull("never kept", originals.read("m2"))
    }

    @Test
    fun `an entry survives its encoding, a uri with colons included`() {
        val e = OwnOriginals.Entry("content://a/b:c/d", 42, OwnOriginals.sha256(bytes))
        val back = OwnOriginals.decode(OwnOriginals.encode(e))!!
        assertEquals(e.uri, back.uri)
        assertEquals(42L, back.size)
        assertArrayEquals(e.sha256, back.sha256)
        assertNull(OwnOriginals.decode("garbage"))
    }
}
