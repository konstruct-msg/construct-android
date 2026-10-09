package com.construct.messenger.media

import com.construct.messenger.media.MediaEvictionPolicy.Candidate
import com.construct.messenger.media.MediaEvictionPolicy.SERVER_RETENTION_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** As iOS `MediaEvictionPolicyTests`: the quota deletes only what the store may still have. */
class MediaEvictionPolicyTest {
    private val day = 24 * 60 * 60 * 1000L

    @Test
    fun aFileOlderThanRetentionIsTheOnlyCopy() {
        assertTrue(MediaEvictionPolicy.mayEvict(SERVER_RETENTION_MS - 1))
        assertFalse(MediaEvictionPolicy.mayEvict(SERVER_RETENTION_MS))
        assertFalse(MediaEvictionPolicy.mayEvict(30 * day))
    }

    @Test
    fun underQuotaNothingGoes() {
        val c = listOf(Candidate("a", 10, day))
        assertEquals(emptyList<String>(), MediaEvictionPolicy.filesToEvict(c, totalBytes = 10, quotaBytes = 100))
        assertEquals(emptyList<String>(), MediaEvictionPolicy.filesToEvict(c, totalBytes = 1_000, quotaBytes = 0))
    }

    @Test
    fun oldestOfTheEvictableFirstAndStopsAtTheQuota() {
        val c = listOf(
            Candidate("new", 40, 1 * day),
            Candidate("mid", 40, 3 * day),
            Candidate("old", 40, 5 * day),
        )
        assertEquals(listOf("old", "mid"), MediaEvictionPolicy.filesToEvict(c, totalBytes = 120, quotaBytes = 50))
    }

    @Test
    fun neverTheOnlyCopyEvenOverQuota() {
        val c = listOf(Candidate("gone-from-store", 500, 9 * day), Candidate("fresh", 10, day))
        assertEquals(listOf("fresh"), MediaEvictionPolicy.filesToEvict(c, totalBytes = 510, quotaBytes = 100))
    }
}
