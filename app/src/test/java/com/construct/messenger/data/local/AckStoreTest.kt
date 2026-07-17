package com.construct.messenger.data.local

import com.construct.messenger.data.local.db.AckDao
import com.construct.messenger.data.local.db.AckedMessageEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AckStoreTest {

    /** In-memory stand-in for the Room-generated DAO. */
    private class FakeAckDao : AckDao {
        val rows = LinkedHashMap<String, AckedMessageEntity>()

        override suspend fun insert(entry: AckedMessageEntity) {
            rows.putIfAbsent(entry.messageId, entry)
        }

        override suspend fun exists(messageId: String): Boolean = rows.containsKey(messageId)

        override suspend fun getAllIds(): List<String> = rows.keys.toList()

        override suspend fun pruneOlderThan(thresholdMs: Long): Int {
            val before = rows.size
            rows.values.removeAll { it.processedAtMs < thresholdMs }
            return before - rows.size
        }
    }

    @Test
    fun `markProcessed makes message visible to isProcessed`() = runTest {
        val store = PersistentAckStore(FakeAckDao())

        assertFalse(store.isProcessed("m1"))
        store.markProcessed("m1", "sender-1")
        assertTrue(store.isProcessed("m1"))
    }

    @Test
    fun `fresh store does not see persisted ids until hydrate`() = runTest {
        val dao = FakeAckDao()
        PersistentAckStore(dao).markProcessed("m1", "sender-1")

        // Simulates a process restart: same DB, empty memory mirror.
        val restarted = PersistentAckStore(dao)
        assertFalse(restarted.isProcessed("m1"))

        restarted.hydrate()
        assertTrue(restarted.isProcessed("m1"))
    }

    @Test
    fun `markProcessed persists to the dao`() = runTest {
        val dao = FakeAckDao()
        val store = PersistentAckStore(dao)

        store.markProcessed("m1", "sender-1")

        assertTrue(dao.exists("m1"))
        assertEquals("sender-1", dao.rows.getValue("m1").senderId)
    }

    @Test
    fun `prune removes old rows and keeps valid ids in the mirror`() = runTest {
        val dao = FakeAckDao()
        val store = PersistentAckStore(dao)

        store.markProcessed("new", "sender-1")
        // An ancient row written straight to the DB (bypassing the mirror).
        dao.insert(AckedMessageEntity("old", "sender-2", processedAtMs = 1_000L))

        val deleted = store.prune(olderThanMs = System.currentTimeMillis())

        assertEquals(1, deleted)
        assertFalse(dao.exists("old"))
        assertTrue("valid ids must survive prune re-hydration", store.isProcessed("new"))
        assertFalse(store.isProcessed("old"))
    }
}
