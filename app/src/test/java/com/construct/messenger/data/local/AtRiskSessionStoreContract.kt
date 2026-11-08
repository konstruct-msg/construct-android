package com.construct.messenger.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Holds [AtRiskSessionStore]s to one behaviour; the fake the other tests use is held to it too. */
abstract class AtRiskSessionStoreContract {
    protected abstract val store: AtRiskSessionStore

    /** Mutation: key by account, or drop the prefix filter — the map comes back wrong. */
    @Test
    fun marksAreKeptByDeviceWithTheirAccount() = runTest {
        store.mark("dev-a", "ann")
        store.mark("dev-b", "ann")
        store.mark("dev-c", "bob")
        assertEquals(mapOf("dev-a" to "ann", "dev-b" to "ann", "dev-c" to "bob"), store.observe().first())
    }

    /** Mutation: make `clear` a no-op, or clear by account. */
    @Test
    fun clearingRemovesOnlyThatDevice() = runTest {
        store.mark("dev-a", "ann")
        store.mark("dev-b", "ann")
        store.clear("dev-a")
        store.clear("never-marked")
        assertEquals(mapOf("dev-b" to "ann"), store.observe().first())
    }
}

class InMemoryAtRiskSessionStoreTest : AtRiskSessionStoreContract() {
    override val store: AtRiskSessionStore = InMemoryAtRiskSessionStore()
}

class DataStoreAtRiskSessionStoreTest : AtRiskSessionStoreContract() {
    private val dir: File = Files.createTempDirectory("at-risk").toFile()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    override val store: AtRiskSessionStore = DataStoreAtRiskSessionStore(
        PreferenceDataStoreFactory.create(scope = scope) { File(dir, "at_risk.preferences_pb") },
    )

    @After
    fun close() {
        scope.cancel()
        dir.deleteRecursively()
    }
}
