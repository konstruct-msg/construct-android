package com.construct.messenger.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** The cache size reads in the limit's own units: at the 1 GB step, "1 GB", not "1.1 GB". */
class FormatBytesTest {
    @Test
    fun binaryUnitsAsTheLimitSteps() {
        assertEquals("1 GB", formatBytes(1L shl 30))
        assertEquals("5 GB", formatBytes(5L shl 30))
        assertEquals("1.5 GB", formatBytes(3L shl 29))
        assertEquals("256 MB", formatBytes(256L shl 20))
        assertEquals("912 MB", formatBytes(912L shl 20))
        assertEquals("3 KB", formatBytes(3_000))
        assertEquals("0 MB", formatBytes(0))
    }
}
