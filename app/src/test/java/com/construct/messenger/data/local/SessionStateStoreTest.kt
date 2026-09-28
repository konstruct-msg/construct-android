package com.construct.messenger.data.local

import com.construct.messenger.data.local.db.SessionStateDao
import com.construct.messenger.data.local.db.SessionStateEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.construct_core.CfeSecureStoreSlot

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

    private fun newStore(
        stateDao: FakeSessionStateDao = FakeSessionStateDao(),
    ) = SessionStateStore(stateDao)

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
    fun `typed slots map to isolated room keys and empty bytes delete`() = runTest {
        val store = newStore()

        store.saveSecureStore(CfeSecureStoreSlot.Session("peer-1"), byteArrayOf(1))
        store.saveSecureStore(CfeSecureStoreSlot.OrchestratorState, byteArrayOf(2))

        val all = store.loadAllSessions()
        assertEquals(setOf("session:peer-1", "core:orchestrator-state"), all.keys)

        store.saveSecureStore(CfeSecureStoreSlot.Session("peer-1"), ByteArray(0))
        assertNull(store.loadSession("session:peer-1"))
    }
}
