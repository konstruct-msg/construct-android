package com.construct.messenger.ui

import com.construct.messenger.ui.screens.chat.TranscriptFollow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading history is not interrupted by arrivals (testers, 2026-10-03; canon iOS `ChatViewport.flags`). */
class TranscriptFollowTest {

    @Test
    fun `the bottom is the last row ending within the slack`() {
        assertTrue(TranscriptFollow.nearBottom(9, 1000, 10, 1000, 60))
        assertTrue(TranscriptFollow.nearBottom(9, 1050, 10, 1000, 60))
        assertFalse(TranscriptFollow.nearBottom(9, 1100, 10, 1000, 60))
        assertFalse(TranscriptFollow.nearBottom(7, 1000, 10, 1000, 60))
        assertTrue(TranscriptFollow.nearBottom(null, 0, 0, 1000, 60))
    }

    /** Mutation: let "not at the bottom" alone stop following — every arrival would. Reddens. */
    @Test
    fun `an arrival below the viewport does not stop following`() {
        assertTrue(TranscriptFollow.next(following = true, nearBottom = false, userScrolling = false))
    }

    /** Mutation: ignore the person's scroll — the transcript keeps yanking them back. Reddens. */
    @Test
    fun `scrolling up reads history, and reaching the bottom follows again`() {
        assertFalse(TranscriptFollow.next(following = true, nearBottom = false, userScrolling = true))
        assertFalse(TranscriptFollow.next(following = false, nearBottom = false, userScrolling = false))
        assertTrue(TranscriptFollow.next(following = false, nearBottom = true, userScrolling = true))
    }
}
