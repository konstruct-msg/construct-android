package com.construct.messenger.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The vectors are iOS `ProfileShareData.toBinaryData` output, produced by its own code
 * (construct-messenger `ProtocolTypes.swift`), so a drift on either side reddens here.
 */
class ProfileShareTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val nameOnly = hex("010a00d09ad0bed181d182d18f0000000050fbbc6a00000000")
    private val withAvatar = hex(
        "0106004b6f737479610102006d31010b0068747470733a2f2f782f79010300010203010a00696d6167652f6a70656750fbbc6a00000000",
    )

    @Test
    fun `encodes a name-only profile exactly as iOS`() {
        assertArrayEquals(nameOnly, ProfileShare("Костя", timestampSec = 1_790_770_000).encode())
    }

    @Test
    fun `decodes an iOS profile with an avatar`() {
        val p = ProfileShare.decode(withAvatar)!!
        assertEquals("Kostya", p.displayName)
        assertEquals("m1", p.avatarMediaId)
        assertEquals("https://x/y", p.avatarMediaUrl)
        assertArrayEquals(byteArrayOf(1, 2, 3), p.avatarMediaKey)
        assertEquals("image/jpeg", p.avatarMediaType)
        assertEquals(1_790_770_000L, p.timestampSec)
    }

    /** Peer-controlled bytes: a cut anywhere is "not a profile", never a crash. */
    @Test
    fun `a truncated or foreign payload is not a profile`() {
        for (n in 0 until withAvatar.size) assertNull("cut at $n", ProfileShare.decode(withAvatar.copyOf(n)))
        assertNull(ProfileShare.decode("hello".toByteArray()))
    }
}
