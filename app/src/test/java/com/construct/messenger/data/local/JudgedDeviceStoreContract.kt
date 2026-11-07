package com.construct.messenger.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

abstract class JudgedDeviceStoreContract {
    protected abstract val store: JudgedDeviceStore

    @Test
    fun marksAddUp() = runTest {
        store.add(listOf("a"))
        store.add(listOf("b"))
        assertEquals(setOf("a", "b"), store.all())
    }

    /** A device that was never judged leaves nothing behind, or a stranger would stop being a first meeting. */
    @Test
    fun retiringMovesOnlyWhatWasJudged() = runTest {
        store.add(listOf("a", "b"))
        store.retire("ann", listOf("a", "never-judged"))
        assertEquals(setOf("b"), store.all())
        assertEquals(setOf("a"), store.formerlyTrusted("ann"))
        assertEquals(emptySet<String>(), store.formerlyTrusted("bob"))
    }

    /** A second unjudged device passing through must not erase that the account had a verdict. */
    @Test
    fun anUnjudgedDeviceLeavingKeepsTheJudgedOneThatLeft() = runTest {
        store.add(listOf("a"))
        store.retire("ann", listOf("a"))
        store.retire("ann", listOf("never-judged"))
        assertEquals(setOf("a"), store.formerlyTrusted("ann"))
    }

    @Test
    fun seedingMarksAndIsRemembered() = runTest {
        assertFalse(store.isSeeded())
        store.seed(listOf("a"))
        assertTrue(store.isSeeded())
        assertEquals(setOf("a"), store.all())
    }
}

class FakeJudgedDeviceStoreTest : JudgedDeviceStoreContract() {
    override val store: JudgedDeviceStore = FakeJudgedDeviceStore()
}

class DataStoreJudgedDeviceStoreTest : JudgedDeviceStoreContract() {
    private val dir: File = Files.createTempDirectory("judged").toFile()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    override val store: JudgedDeviceStore = DataStoreJudgedDeviceStore(
        PreferenceDataStoreFactory.create(scope = scope) { File(dir, "judged.preferences_pb") },
    )

    @After
    fun close() {
        scope.cancel()
        dir.deleteRecursively()
    }
}
