package com.construct.messenger.media

import com.construct.messenger.media.MediaAutoDownload.ALWAYS
import com.construct.messenger.media.MediaAutoDownload.NEVER
import com.construct.messenger.media.MediaAutoDownload.UNMETERED
import com.construct.messenger.media.MediaAutoDownloadPolicy.UNMETERED_SIZE_CEILING
import com.construct.messenger.media.MediaAutoDownloadPolicy.shouldFetchOnArrival
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** As iOS `MediaAutoDownloadPolicyTests`. */
class MediaAutoDownloadPolicyTest {
    @Test
    fun neverAndAlways() {
        assertFalse(shouldFetchOnArrival(NEVER, 1, metered = false, constrained = false))
        assertTrue(shouldFetchOnArrival(ALWAYS, UNMETERED_SIZE_CEILING * 10, metered = true, constrained = true))
    }

    @Test
    fun wifiOnlyHoldsBackOnMobileDataDataSaverAndLargeFiles() {
        assertTrue(shouldFetchOnArrival(UNMETERED, 4_000_000, metered = false, constrained = false))
        assertFalse(shouldFetchOnArrival(UNMETERED, 4_000_000, metered = true, constrained = false))
        assertFalse(shouldFetchOnArrival(UNMETERED, 4_000_000, metered = false, constrained = true))
        assertTrue(shouldFetchOnArrival(UNMETERED, UNMETERED_SIZE_CEILING, metered = false, constrained = false))
        assertFalse(shouldFetchOnArrival(UNMETERED, UNMETERED_SIZE_CEILING + 1, metered = false, constrained = false))
    }

    @Test
    fun rawValuesAreIosAndTheDefaultIsWifiOnly() {
        assertEquals(listOf(0, 1, 2), MediaAutoDownload.entries.map { it.raw })
        assertEquals(UNMETERED, MediaAutoDownload.of(99))
    }
}
