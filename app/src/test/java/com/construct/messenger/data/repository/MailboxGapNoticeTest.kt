package com.construct.messenger.data.repository

import com.construct.messenger.data.local.FakeSessionBlobStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import shared.proto.services.v1.MessagingServiceOuterClass.GetPendingMessagesResponse
import shared.proto.services.v1.MessagingServiceOuterClass.HeartbeatAck
import shared.proto.services.v1.MessagingServiceOuterClass.MessageStreamResponse
import shared.proto.services.v1.MessagingServiceOuterClass.MailboxGap as WireGap

/**
 * TODO 79 / 290: the server's report of trimmed messages, kept until the person closes it. The rule
 * is the core's (construct-rules `mailbox_gap`, tested and mutation-checked there); these check
 * what this app does with it, on the real core the JVM tests load.
 */
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

    // --- the core's rule, kept here ---

    private val blobs = FakeSessionBlobStore()

    /** Two reports widen one notice, as the core says. Mutation: show the first report only. */
    @Test
    fun `reports widen one notice`() = runTest {
        val notice = MailboxGapNotice(blobs)
        notice.record(gap("200-0", "300-0"))
        notice.record(gap("100-0", "400-0"))
        assertEquals(MailboxGap.Interval(100, 400), notice.interval.value)
    }

    /** Mutation: skip the write in `changed` — the notice is gone after a restart. */
    @Test
    fun `the notice outlives a restart until it is closed`() = runTest {
        MailboxGapNotice(blobs).record(gap("100-0", "300-0"))

        val after = MailboxGapNotice(blobs)
        assertNull("nothing until loaded", after.interval.value)
        after.load()
        assertEquals(MailboxGap.Interval(100, 300), after.interval.value)

        after.dismiss()
        assertNull(after.interval.value)
        val again = MailboxGapNotice(blobs)
        again.load()
        assertNull(again.interval.value)
    }

    /**
     * The owner's phone, 2026-11-09 (iOS, the same rule here): nothing held after the cursor, so the
     * server reported the same loss on every connection and a closed notice came back each time.
     * Mutation: `dismiss` forgets instead of closing (the old `store.remove`) — this reddens.
     */
    @Test
    fun `a closed report does not come back, after a restart either`() = runTest {
        val notice = MailboxGapNotice(blobs)
        notice.record(gap("100-0"))
        notice.dismiss()
        notice.record(gap("100-0"))
        assertNull("the same report on the next connection", notice.interval.value)

        val restarted = MailboxGapNotice(blobs)
        restarted.load()
        restarted.record(gap("100-0"))
        assertNull("and after a restart", restarted.interval.value)

        restarted.record(gap("500-0"))
        assertEquals("a new loss is shown", MailboxGap.Interval(500, null), restarted.interval.value)
    }

    /** v1 kept only dates and is dropped; what it held does not show. */
    @Test
    fun `the dates an earlier build kept are dropped`() = runTest {
        blobs.put("mailbox-gap.v1", """{"from":100,"until":200}""".toByteArray())
        val notice = MailboxGapNotice(blobs)
        notice.load()
        assertNull(notice.interval.value)
        assertNull(blobs.get("mailbox-gap.v1"))
        notice.record(gap("100-0", "200-0"))
        assertNotNull("the next report shows", notice.interval.value)
    }
}
