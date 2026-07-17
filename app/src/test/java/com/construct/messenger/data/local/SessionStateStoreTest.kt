package com.construct.messenger.data.local

import com.construct.messenger.data.local.db.SessionMetaDao
import com.construct.messenger.data.local.db.SessionMetaEntity
import com.construct.messenger.data.local.db.SessionStateDao
import com.construct.messenger.data.local.db.SessionStateEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStateStoreTest {

    private class FakeSessionStateDao : SessionStateDao {
        val rows = mutableMapOf<String, SessionStateEntity>()

        override suspend fun upsert(entry: SessionStateEntity) {
            rows[entry.storeKey] = entry
        }

        override suspend fun get(key: String): SessionStateEntity? = rows[key]

        override suspend fun getAll(): List<SessionStateEntity> = rows.values.toList()

        override suspend fun delete(key: String) {
            rows.remove(key)
        }
    }

    private class FakeSessionMetaDao : SessionMetaDao {
        val rows = mutableMapOf<String, SessionMetaEntity>()

        override suspend fun setEstablishedAt(entry: SessionMetaEntity) {
            rows[entry.contactId] = entry
        }

        override suspend fun getEstablishedAt(contactId: String): Long? =
            rows[contactId]?.establishedAtMs

        override suspend fun getAll(): List<SessionMetaEntity> = rows.values.toList()

        override suspend fun delete(contactId: String) {
            rows.remove(contactId)
        }
    }

    private fun newStore(
        stateDao: FakeSessionStateDao = FakeSessionStateDao(),
        metaDao: FakeSessionMetaDao = FakeSessionMetaDao(),
    ) = SessionStateStore(stateDao, metaDao)

    @Test
    fun `session blob round-trips byte-identical`() = runTest {
        val store = newStore()
        val blob = byteArrayOf(0x43, 0x46, 0x01, 0x02, 0x7F) // CFE magic + payload-ish

        store.saveSession("session:peer-1", blob)

        assertArrayEquals(blob, store.loadSession("session:peer-1"))
    }

    @Test
    fun `loadAllSessions returns every persisted blob keyed by store key`() = runTest {
        val store = newStore()
        store.saveSession("session:peer-1", byteArrayOf(1))
        store.saveSession("session:peer-2", byteArrayOf(2))

        val all = store.loadAllSessions()

        assertEquals(setOf("session:peer-1", "session:peer-2"), all.keys)
        assertArrayEquals(byteArrayOf(1), all.getValue("session:peer-1"))
        assertArrayEquals(byteArrayOf(2), all.getValue("session:peer-2"))
    }

    @Test
    fun `removeSession deletes only the target blob`() = runTest {
        val store = newStore()
        store.saveSession("session:peer-1", byteArrayOf(1))
        store.saveSession("session:peer-2", byteArrayOf(2))

        store.removeSession("session:peer-1")

        assertNull(store.loadSession("session:peer-1"))
        assertArrayEquals(byteArrayOf(2), store.loadSession("session:peer-2"))
    }

    @Test
    fun `establishedAt set get and overwrite`() = runTest {
        val store = newStore()

        assertNull(store.getEstablishedAt("peer-1"))
        store.setEstablishedAt("peer-1", 1_000L)
        assertEquals(1_000L, store.getEstablishedAt("peer-1"))

        // A re-established session replaces the timestamp.
        store.setEstablishedAt("peer-1", 2_000L)
        assertEquals(2_000L, store.getEstablishedAt("peer-1"))
    }

    @Test
    fun `getAllEstablishedAt and removeMeta`() = runTest {
        val store = newStore()
        store.setEstablishedAt("peer-1", 1_000L)
        store.setEstablishedAt("peer-2", 2_000L)

        assertEquals(
            mapOf("peer-1" to 1_000L, "peer-2" to 2_000L),
            store.getAllEstablishedAt(),
        )

        store.removeMeta("peer-1")
        assertNull(store.getEstablishedAt("peer-1"))
        assertEquals(2_000L, store.getEstablishedAt("peer-2"))
    }
}
