package com.construct.messenger.viewmodel

import com.construct.messenger.data.model.CallHistoryEntry
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class CallHistorySectionsTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private fun at(day: Int, hour: Int) = ZonedDateTime.of(2026, 10, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun entry(id: String, ms: Long) =
        CallHistoryEntry(id, "u", "n", null, true, CallHistoryEntry.Status.COMPLETED, ms, 0)

    @Test
    fun `today, yesterday by the calendar, the last seven days, then older — newest first`() {
        val now = at(10, 9)
        val sections = CallHistorySections.group(
            listOf(
                entry("old", at(1, 12)),
                entry("today-early", at(10, 0)),
                entry("yesterday-late", at(9, 23)),
                entry("week", at(5, 12)),
                entry("today-later", at(10, 8)),
            ),
            now,
            zone,
        )
        assertEquals(
            listOf(
                CallHistorySection.Kind.TODAY to listOf("today-later", "today-early"),
                CallHistorySection.Kind.YESTERDAY to listOf("yesterday-late"),
                CallHistorySection.Kind.EARLIER to listOf("week"),
                CallHistorySection.Kind.OLDER to listOf("old"),
            ),
            sections.map { s -> s.kind to s.entries.map { it.id } },
        )
    }
}
