package com.construct.messenger.data.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A wrap that XORs: what [LocalStoreKey] does around it is the subject, not the Keystore. */
private class FakeWrap : LocalStoreKey.KeyWrap {
    var held = true
    var forgotten = 0

    override fun wrap(key: ByteArray): ByteArray {
        held = true
        return key.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        check(held) { "no wrapping key" }
        return wrapped.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    override fun forget() {
        held = false
        forgotten++
    }
}

class LocalStoreKeyTest {
    @get:Rule val dir = TemporaryFolder()

    private val wrap = FakeWrap()
    private val file get() = dir.root.resolve("construct.db.key")
    private val key by lazy { LocalStoreKey(file, wrap) }

    @Test
    fun theFirstCallMintsAKeyAndLaterOnesReadIt() {
        val first = key.current()!!
        assertEquals(32, first.size)
        assertTrue(file.exists())
        assertFalse("kept wrapped", file.readBytes().contentEquals(first))
        assertArrayEquals(first, key.current())
    }

    /** The 2026-08-09 lesson: a key that is there and will not open is never replaced. */
    @Test
    fun aKeyTheKeystoreWillNotOpenIsNotReplaced() {
        key.current()!!
        val wrapped = file.readBytes()
        wrap.held = false
        assertNull(key.current())
        assertArrayEquals(wrapped, file.readBytes())
    }

    @Test
    fun aKeyOfTheWrongLengthIsNotReplaced() {
        file.writeBytes(ByteArray(16))
        assertNull(key.current())
        assertEquals(16, file.length())
    }

    @Test
    fun destroyForgetsTheWrappingKeyAndTheFile() {
        val first = key.current()!!
        key.destroy()
        assertFalse(file.exists())
        assertFalse(wrap.held)
        assertFalse("a new key after a wipe", first.contentEquals(key.current()))
    }
}

/** On the core's real store, in a file — the host build of the library the app ships. */
class LocalStoreSessionTest {
    @get:Rule val dir = TemporaryFolder()

    private val db get() = dir.root.resolve("construct.db")

    private fun opened(key: ByteArray?): LocalStoreSession =
        (LocalStoreSession.open(db, key) as LocalStoreSession.Opening.Opened).session

    @Test
    fun whatIsWrittenIsThereOnTheNextOpen() {
        opened(ByteArray(32) { 7 }).store.apply { put("a", byteArrayOf(1)); close() }
        assertArrayEquals(byteArrayOf(1), opened(ByteArray(32) { 7 }).store.get("a"))
    }

    @Test
    fun theKeyIsZeroedOnceTheStoreIsOpen() {
        val key = ByteArray(32) { 7 }
        opened(key)
        assertTrue(key.all { it == 0.toByte() })
    }

    /** Another key reads nothing and creates nothing: the file stays for the right key. */
    @Test
    fun anotherKeyIsWrongKeyNotAFreshStore() {
        opened(ByteArray(32) { 7 }).store.apply { put("a", byteArrayOf(1)); close() }
        assertEquals(
            LocalStoreSession.Opening.NotOpened(LocalStoreSession.Failure.WrongKey),
            LocalStoreSession.open(db, ByteArray(32) { 8 }),
        )
        assertArrayEquals(byteArrayOf(1), opened(ByteArray(32) { 7 }).store.get("a"))
    }

    @Test
    fun noKeyOpensNothing() {
        assertEquals(
            LocalStoreSession.Opening.NotOpened(LocalStoreSession.Failure.KeyUnavailable),
            LocalStoreSession.open(db, null),
        )
        assertFalse(db.exists())
    }

    @Test
    fun wipeDeletesTheFile() {
        opened(ByteArray(32) { 7 }).wipe()
        assertFalse(db.exists())
    }
}
