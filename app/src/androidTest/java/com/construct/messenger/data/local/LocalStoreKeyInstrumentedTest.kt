package com.construct.messenger.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The store key against the real Keystore, and the store opened under it on the device's own core.
 * Its own alias and files — the app's are not touched. Emulator only:
 *
 * ```
 * adb -s emulator-5554 shell am instrument -w -e class \
 *   com.construct.messenger.data.local.LocalStoreKeyInstrumentedTest \
 *   com.construct.messenger.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class LocalStoreKeyInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.noBackupFilesDir, "local-store-key-test").apply { deleteRecursively(); mkdirs() }
    private val wrap = KeystoreKeyWrap(alias = "construct.localStore.key.test")
    private val key = LocalStoreKey(File(dir, "construct.db.key"), wrap)

    @After
    fun clean() {
        key.destroy()
        dir.deleteRecursively()
    }

    @Test
    fun theKeyIsKeptWrappedAndOpensTheStoreAgain() {
        val first = key.current()!!
        assertFalse(File(dir, "construct.db.key").readBytes().contentEquals(first))
        assertArrayEquals(first, key.current())

        val db = File(dir, "construct.db")
        (LocalStoreSession.open(db, key.current()) as LocalStoreSession.Opening.Opened).session.store.apply {
            put("a", byteArrayOf(1))
            close()
        }
        val again = LocalStoreSession.open(db, key.current()) as LocalStoreSession.Opening.Opened
        assertArrayEquals(byteArrayOf(1), again.session.store.get("a"))
    }

    /** The Keystore key gone, the wrapped one stays: no new key, and the store is not recreated. */
    @Test
    fun aLostWrappingKeyIsNotReplaced() {
        key.current()!!
        val wrapped = File(dir, "construct.db.key").readBytes()
        wrap.forget()
        assertNull(key.current())
        assertArrayEquals(wrapped, File(dir, "construct.db.key").readBytes())
        assertEquals(
            LocalStoreSession.Opening.NotOpened(LocalStoreSession.Failure.KeyUnavailable),
            LocalStoreSession.open(File(dir, "construct.db"), key.current()),
        )
        assertTrue(File(dir, "construct.db.key").exists())
    }
}
