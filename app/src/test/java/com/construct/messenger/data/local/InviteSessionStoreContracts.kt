package com.construct.messenger.data.local

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.construct_core.LocalStore

/** What every [IssuedInviteStore] does — on the core's store and the fakes (TODO 136). */
abstract class IssuedInviteStoreContract {
    protected abstract val store: IssuedInviteStore

    /** A token's issue time — seconds, as Android keeps it; the core keeps ms. */
    private val t = 1_760_000_000L

    private fun invite(jti: String, at: Long = t, sitting: String? = "s1") = IssuedInviteRecord(jti, "contact", at, 600, sitting)

    @Test
    fun anInviteComesBackWithEveryField() = runTest {
        store.record(invite("j1"))
        assertEquals(listOf(invite("j1")), store.observeAll().first())
    }

    @Test
    fun theNewestComeFirstThenByJti() = runTest {
        store.record(invite("b", at = t))
        store.record(invite("old", at = t - 1))
        store.record(invite("a", at = t))
        assertEquals(listOf("a", "b", "old"), store.observeAll().first().map { it.jti })
    }

    @Test
    fun recordingAJtiAgainReplacesItsRow() = runTest {
        store.record(invite("j1", sitting = "s1"))
        store.record(invite("j1", at = t + 5, sitting = null))
        assertEquals(listOf(invite("j1", at = t + 5, sitting = null)), store.observeAll().first())
    }

    @Test
    fun deleteDropsOneInvite() = runTest {
        store.record(invite("j1"))
        store.record(invite("j2"))
        store.delete("j1")
        store.delete("unknown")
        assertEquals(listOf("j2"), store.observeAll().first().map { it.jti })
    }
}

class FakeIssuedInviteStoreTest : IssuedInviteStoreContract() {
    override val store: IssuedInviteStore = FakeIssuedInviteStore()
}

class CoreIssuedInviteStoreTest : IssuedInviteStoreContract() {
    private val core = LocalStore.inMemory(ByteArray(32) { 1 })
    override val store: IssuedInviteStore = CoreIssuedInviteStore(LocalStoreFeed(core))

    /** One time scale in the shared table: iOS writes ms there, so must we. */
    @Test
    fun theCoreRowHoldsMilliseconds() = runTest {
        store.record(IssuedInviteRecord("j1", "contact", 1_760_000_000L, 600))
        assertEquals(1_760_000_000_000L, core.issuedInvites().single().issuedAt)
    }

    @After
    fun close() = core.close()
}

/** What every [SessionBlobStore] does — on the core's `kv` and the fake (TODO 136). */
abstract class SessionBlobStoreContract {
    protected abstract val store: SessionBlobStore

    @Test
    fun aBlobComesBackByteIdentical() = runTest {
        val blob = byteArrayOf(0x43, 0x46, 0x01, 0x00, 0x7F, -1)
        store.put("session:a", blob)
        assertArrayEquals(blob, store.get("session:a"))
        assertNull(store.get("session:b"))
    }

    @Test
    fun putReplacesTheBlob() = runTest {
        store.put("session:a", byteArrayOf(1))
        store.put("session:a", byteArrayOf(2))
        assertArrayEquals(byteArrayOf(2), store.get("session:a"))
    }

    @Test
    fun removeDropsOnlyItsKey() = runTest {
        store.put("session:a", byteArrayOf(1))
        store.put("session:b", byteArrayOf(2))
        store.remove("session:a")
        store.remove("session:none")
        assertNull(store.get("session:a"))
        assertArrayEquals(byteArrayOf(2), store.get("session:b"))
    }

    /** The prefix is text, not a pattern: `_` and `%` match only themselves. */
    @Test
    fun aPrefixMatchesExactly() = runTest {
        store.put("session:a", byteArrayOf(1))
        store.put("session:b", byteArrayOf(2))
        store.put("sessionXa", byteArrayOf(3))
        store.put("core:orchestrator-state", byteArrayOf(4))
        assertEquals(setOf("session:a", "session:b"), store.withPrefix("session:").keys)
        assertTrue(store.withPrefix("session_").isEmpty())
        assertTrue(store.withPrefix("%").isEmpty())
        assertEquals(4, store.withPrefix("").size)
        assertArrayEquals(byteArrayOf(4), store.withPrefix("core:").getValue("core:orchestrator-state"))
    }
}

class FakeSessionBlobStoreTest : SessionBlobStoreContract() {
    override val store: SessionBlobStore = FakeSessionBlobStore()
}

class CoreSessionBlobStoreTest : SessionBlobStoreContract() {
    private val core = LocalStore.inMemory(ByteArray(32) { 1 })
    override val store: SessionBlobStore = CoreSessionBlobStore(core)

    @After
    fun close() = core.close()
}
