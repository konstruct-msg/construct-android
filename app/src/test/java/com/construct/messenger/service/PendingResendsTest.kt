package com.construct.messenger.service

import com.construct.messenger.data.local.db.PendingResendDao
import com.construct.messenger.data.local.db.PendingResendEntity
import com.construct.messenger.domain.usecase.ResendOutcome
import com.construct.messenger.domain.usecase.SendMessageUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Plan B10. The core asks for a resend once per message, so a resend the network refused must be
 * tried again by us — on the stand one waited 60 s on a dead stream, failed, and was lost.
 */
class PendingResendsTest {

    private val dao = FakePendingResendDao()
    private val send: SendMessageUseCase = mock()
    private val resends = PendingResends(dao) { send }

    private suspend fun queued(id: String = "srv-1", attempts: Int = 0, createdAtMs: Long = 1_000) {
        dao.insert(PendingResendEntity(id, "dev-1", "acct-1", createdAtMs, attempts))
    }

    /** Mutation: delete the row on FAILED — this reddens. */
    @Test
    fun `a resend the network refused is tried again on the next drain`() = runTest {
        queued()
        whenever(send.resend(any(), any(), any())).thenReturn(ResendOutcome.FAILED, ResendOutcome.SENT)

        resends.drain(nowMs = 2_000)
        assertEquals(1, dao.rows.single().attempts)

        resends.drain(nowMs = 3_000)
        assertTrue(dao.rows.isEmpty())
        verify(send, times(2)).resend("acct-1", "dev-1", "srv-1")
    }

    @Test
    fun `a message that is not ours to resend is dropped, not retried`() = runTest {
        queued()
        whenever(send.resend(any(), any(), any())).thenReturn(ResendOutcome.NOT_OURS)

        resends.drain(nowMs = 2_000)

        assertTrue(dao.rows.isEmpty())
    }

    /** The bound: a device that never takes it does not keep us resending forever. */
    @Test
    fun `the last allowed try that fails drops the row`() = runTest {
        queued(attempts = PendingResends.MAX_ATTEMPTS - 1)
        whenever(send.resend(any(), any(), any())).thenReturn(ResendOutcome.FAILED)

        resends.drain(nowMs = 2_000)

        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `a request older than the server's queue is dropped unsent`() = runTest {
        queued(createdAtMs = 0)

        resends.drain(nowMs = ServerMessageIds.RETENTION_MS + 1)

        assertTrue(dao.rows.isEmpty())
        verify(send, times(0)).resend(any(), any(), any())
    }

    /** A second error naming the same copy is not a second resend queued. */
    @Test
    fun `the same copy is queued once`() = runTest {
        queued(attempts = 3)
        queued(attempts = 0)

        assertEquals(3, dao.rows.single().attempts)
    }
}

private class FakePendingResendDao : PendingResendDao {
    val rows = mutableListOf<PendingResendEntity>()
    override suspend fun insert(entry: PendingResendEntity) {
        if (rows.none { it.messageId == entry.messageId && it.deviceId == entry.deviceId }) rows += entry
    }
    override suspend fun all() = rows.sortedBy { it.createdAtMs }
    override suspend fun delete(messageId: String, deviceId: String) {
        rows.removeAll { it.messageId == messageId && it.deviceId == deviceId }
    }
    override suspend fun countAttempt(messageId: String, deviceId: String) {
        val i = rows.indexOfFirst { it.messageId == messageId && it.deviceId == deviceId }
        if (i >= 0) rows[i] = rows[i].copy(attempts = rows[i].attempts + 1)
    }
    override suspend fun pruneOlderThan(thresholdMs: Long): Int {
        val n = rows.count { it.createdAtMs < thresholdMs }
        rows.removeAll { it.createdAtMs < thresholdMs }
        return n
    }
}
