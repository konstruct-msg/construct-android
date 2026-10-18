package com.construct.messenger.data.repository

import com.construct.messenger.data.local.FakeSessionBlobStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import shared.proto.services.v1.MessagingServiceOuterClass.GetPendingMessagesResponse
import shared.proto.services.v1.MessagingServiceOuterClass.HeartbeatAck
import shared.proto.services.v1.MessagingServiceOuterClass.MailboxGap as WireGap
import shared.proto.services.v1.MessagingServiceOuterClass.MessageStreamResponse

/** TODO 79: the server's report of trimmed messages, kept until the person closes it. **Canon:** iOS `MailboxGapNotice`. */
class MailboxGapNoticeTest {
    private fun gap(after: String, resumes: String = ""): WireGap =
        WireGap.newBuilder().setAfterCursor(after).setResumesAt(resumes).build()

    // --- both carriers ---

    /** Mutation: `of(fetch)` returns null — a fetch's gap is never shown. */
    @Test
    fun `a fetch answer carries the gap, or none`() {
        val with = GetPendingMessagesResponse.newBuilder().setGap(gap("100-0", "200-0")).build()
        assertEquals(gap("100-0", "200-0"), MailboxGap.of(with))
        assertNull(MailboxGap.of(GetPendingMessagesResponse.getDefaultInstance()))
    }

    /** Mutation: `of(stream)` ignores `hasMailboxGap` — every frame would report an empty gap. */
    @Test
    fun `a stream frame carries the gap, or none`() {
        val with = MessageStreamResponse.newBuilder().setMailboxGap(gap("100-0")).build()
        assertEquals(gap("100-0"), MailboxGap.of(with))
        val heartbeat = MessageStreamResponse.newBuilder().setHeartbeatAck(HeartbeatAck.getDefaultInstance()).build()
        assertNull(MailboxGap.of(heartbeat))
    }

    @Test
    fun `a cursor names its millisecond, and nothing else does`() {
        assertEquals(1_791_000_000_123L, MailboxGap.msOfCursor("1791000000123-7"))
        assertEquals(5L, MailboxGap.msOfCursor("5"))
        assertNull(MailboxGap.msOfCursor(""))
        assertNull(MailboxGap.msOfCursor("abc-1"))
        assertEquals(MailboxGap.Interval(100, null), MailboxGap.interval("100-0", ""))
        assertNull("no cursor of ours: nothing to say", MailboxGap.interval("", "200-0"))
    }

    // --- merging (iOS `merge`) ---

    /** Mutation: the newer report replaces the notice — the first interval is forgotten. */
    @Test
    fun `two reports widen one notice`() {
        assertEquals(MailboxGap.Interval(100, 400), MailboxGap.merge(MailboxGap.Interval(200, 300), MailboxGap.Interval(100, 400)))
        assertEquals(MailboxGap.Interval(100, 400), MailboxGap.merge(MailboxGap.Interval(100, 400), MailboxGap.Interval(200, 300)))
        assertEquals(MailboxGap.Interval(5, 6), MailboxGap.merge(null, MailboxGap.Interval(5, 6)))
    }

    /** Mutation: `maxOf` over a null end — an open-ended loss would be shown as closed. */
    @Test
    fun `an open end stays open`() {
        assertEquals(MailboxGap.Interval(100, null), MailboxGap.merge(MailboxGap.Interval(100, 300), MailboxGap.Interval(200, null)))
        assertEquals(MailboxGap.Interval(100, null), MailboxGap.merge(MailboxGap.Interval(100, null), MailboxGap.Interval(200, 300)))
    }

    // --- across a restart ---

    private val blobs = FakeSessionBlobStore()

    /** Mutation: skip the write in `record` — the notice is gone after a restart. */
    @Test
    fun `the notice outlives a restart until it is closed`() = runTest {
        val before = MailboxGapNotice(blobs)
        before.record(gap("100-0", "300-0"))
        before.record(gap("200-0", "400-0"))
        assertEquals(MailboxGap.Interval(100, 400), before.interval.value)

        val after = MailboxGapNotice(blobs)
        assertNull("nothing until loaded", after.interval.value)
        after.load()
        assertEquals(MailboxGap.Interval(100, 400), after.interval.value)

        after.dismiss()
        assertNull(after.interval.value)
        val again = MailboxGapNotice(blobs)
        again.load()
        assertNull(again.interval.value)
    }

    /** Mutation: `record` merges with the shown value instead of the stored one — a report before `load` loses the stored notice. */
    @Test
    fun `a report before the notice is loaded widens the stored one`() = runTest {
        MailboxGapNotice(blobs).record(gap("100-0", "200-0"))
        val next = MailboxGapNotice(blobs)
        next.record(gap("300-0", "400-0"))
        assertEquals(MailboxGap.Interval(100, 400), next.interval.value)
    }

    @Test
    fun `a closed notice comes back with the next report`() = runTest {
        val notice = MailboxGapNotice(blobs)
        notice.record(gap("100-0", "200-0"))
        notice.dismiss()
        notice.record(gap("300-0", "400-0"))
        assertEquals(MailboxGap.Interval(300, 400), notice.interval.value)
    }
}
