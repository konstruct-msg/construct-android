package com.construct.messenger.data.local

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AckStoreTest {

    @Test
    fun `markProcessed makes message visible to isProcessed`() = runTest {
        val store = PersistentAckStore(FakeProcessedStore())

        assertFalse(store.isProcessed("m1"))
        store.markProcessed("m1", "sender-1")
        assertTrue(store.isProcessed("m1"))
    }

    @Test
    fun `fresh store does not see persisted ids until hydrate`() = runTest {
        val dao = FakeProcessedStore()
        PersistentAckStore(dao).markProcessed("m1", "sender-1")

        // Simulates a process restart: same DB, empty memory mirror.
        val restarted = PersistentAckStore(dao)
        assertFalse(restarted.isProcessed("m1"))

        restarted.hydrate()
        assertTrue(restarted.isProcessed("m1"))
    }

    @Test
    fun `markProcessed persists to the store`() = runTest {
        val dao = FakeProcessedStore()
        val store = PersistentAckStore(dao)

        store.markProcessed("m1", "sender-1")

        assertEquals("sender-1", dao.rows.getValue("m1").first)
    }

    @Test
    fun `prune removes old rows and keeps valid ids in the mirror`() = runTest {
        val dao = FakeProcessedStore()
        val store = PersistentAckStore(dao)

        store.markProcessed("new", "sender-1")
        // An ancient row written straight to the DB (bypassing the mirror).
        dao.record("old", "sender-2", processedAtMs = 1_000L)

        // Use the exact timestamp boundary: the fresh row is not older than
        // its own timestamp, while the ancient row is.
        val keepAt = dao.rows.getValue("new").second
        val deleted = store.prune(olderThanMs = keepAt)

        assertEquals(1, deleted)
        assertFalse("old" in dao.rows)
        assertTrue("valid ids must survive prune re-hydration", store.isProcessed("new"))
        assertFalse(store.isProcessed("old"))
    }
}
