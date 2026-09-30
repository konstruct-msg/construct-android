package com.construct.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BlurHashTest {

    @Test
    fun `the reference hash decodes to a picture of the size asked`() {
        val px = BlurHash.decode("LEHV6nWB2yk8pyo0adR*.7kCMdnj", 32, 32)
        assertNotNull(px)
        assertEquals(32 * 32, px!!.size)
        px.forEach { assertEquals(0xFF, it ushr 24) }
    }

    /** One component only: every pixel is the DC colour. */
    @Test
    fun `a single component is a flat colour`() {
        // Size flag 0 (1×1), quantised maximum 0, then the DC colour in four base-83 digits.
        val dc = 0x808080
        val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#\$%*+,-.:;=?@[]^_{|}~"
        fun b83(v: Int, n: Int) = (n - 1 downTo 0).map { chars[(v / Math.pow(83.0, it.toDouble()).toInt()) % 83] }.joinToString("")
        val px = BlurHash.decode("00" + b83(dc, 4), 4, 4)!!
        px.forEach { assertEquals(0xFF808080.toInt(), it) }
    }

    @Test
    fun `a peer's garbage is refused`() {
        assertNull(BlurHash.decode("", 32, 32))
        assertNull(BlurHash.decode("LEHV6nWB2yk8", 32, 32))
        assertNull(BlurHash.decode("LEHV6nWB2yk8pyo0adR*.7kCMdné", 32, 32))
    }
}
