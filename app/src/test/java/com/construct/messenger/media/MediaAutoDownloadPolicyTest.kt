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

    /** Off by default (todo 134). Mutation: DEFAULT back to UNMETERED — this reddens. */
    @Test
    fun rawValuesAreIosAndTheDefaultIsOff() {
        assertEquals(listOf(0, 1, 2), MediaAutoDownload.entries.map { it.raw })
        assertEquals(NEVER, MediaAutoDownload.DEFAULT)
        assertEquals(NEVER, MediaAutoDownload.of(99))
        assertEquals(UNMETERED, MediaAutoDownload.of(1))
    }

    /** 2–30 s, as iOS. Mutations: a lower bound of 0, an upper bound of 300 s — each reddens. */
    @Test
    fun theWaitBeforeAnArrivalFetchIsTwoToThirtySeconds() {
        assertEquals(2_000L, MediaAutoDownloadPolicy.prefetchDelayMs(0.0))
        val longest = MediaAutoDownloadPolicy.prefetchDelayMs(0.999_999)
        assertTrue("$longest", longest in 29_900L..30_000L)
        repeat(1_000) {
            val wait = MediaAutoDownloadPolicy.prefetchDelayMs()
            assertTrue("$wait", wait in 2_000L..30_000L)
        }
    }

    /** The arrival fetch waits before it downloads. Mutation: drop `delay(waitMs)` — this reddens. */
    @Test
    fun theArrivalFetchWaitsBeforeItDownloads() {
        val text = java.io.File("src/main/java/com/construct/messenger/media/MediaPrefetcher.kt").readText()
        val body = text.substringAfter("override fun onArrived(")
        val wait = body.indexOf("delay(waitMs)")
        val download = body.indexOf("media.prefetch(item)")
        assertTrue("onArrived does not wait", wait >= 0)
        assertTrue("onArrived downloads before it waits", download > wait)
        assertTrue("the wait is not drawn from the policy", "MediaAutoDownloadPolicy.prefetchDelayMs()" in body)
    }
}
