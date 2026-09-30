package com.construct.messenger.media

import java.util.zip.Deflater
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileContentTest {

    private fun rawDeflate(bytes: ByteArray): ByteArray {
        val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        d.setInput(bytes)
        d.finish()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    /** What iOS's `.zlib` makes of a text file comes back as the text. */
    @Test
    fun `a raw-deflated document from iOS is unpacked`() {
        val text = "hello konstruct\n".repeat(200).toByteArray()
        assertArrayEquals(text, FileContent.unpacked(rawDeflate(text), "text/plain"))
    }

    /** Ordinary files are not mistaken for DEFLATE, and compressed types are not tried. */
    @Test
    fun `a file that is not deflated stays as it is`() {
        val text = "Hello, this is a plain text file.\n".repeat(50).toByteArray()
        assertArrayEquals(text, FileContent.unpacked(text, "text/plain"))
        assertNull(FileContent.inflateExactly("{\"a\":1}".toByteArray()))
        val packed = rawDeflate(text)
        assertArrayEquals(packed, FileContent.unpacked(packed, "application/pdf"))
    }

    /** A trailing byte after the stream is not a compressed file. */
    @Test
    fun `a stream with bytes after its end is not taken`() {
        assertNull(FileContent.inflateExactly(rawDeflate("abc".repeat(100).toByteArray()) + byteArrayOf(1)))
    }

    @Test
    fun `a peer's file name is only a name`() {
        assertEquals("passwd", FileContent.safeName("../../etc/passwd"))
        assertEquals("x.txt", FileContent.safeName("..\\x.txt"))
        assertEquals("env", FileContent.safeName(".env"))
        assertEquals("file", FileContent.safeName("  "))
    }
}
