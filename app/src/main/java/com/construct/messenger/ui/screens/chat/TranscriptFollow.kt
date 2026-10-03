package com.construct.messenger.ui.screens.chat

/**
 * Whether the transcript follows its newest message, or the person is reading history and must
 * not be yanked. **Canon:** iOS `ChatViewport` — two modes, `following` and `readingHistory`, and
 * the jump control (`chevron.down`, `scroll_to_newest`) shown while reading history.
 *
 * Testers, 2026-10-03: every arrival scrolled to the bottom, so reading back through a chat was
 * interrupted by each new message.
 */
internal object TranscriptFollow {

    /**
     * The last row's bottom is within [NEAR_BOTTOM_PX] of the viewport's end. An empty list is at
     * the bottom: there is nothing to have scrolled away from.
     */
    fun nearBottom(lastVisibleIndex: Int?, lastVisibleEnd: Int, totalItems: Int, viewportEnd: Int, slackPx: Int): Boolean {
        if (totalItems == 0) return true
        if (lastVisibleIndex == null || lastVisibleIndex < totalItems - 1) return false
        return lastVisibleEnd - viewportEnd <= slackPx
    }

    /**
     * The mode after one geometry sample. Reaching the bottom turns following on whatever moved
     * the list; only the person's own scroll turns it off. An arrival that pushes the tail below
     * the viewport is not a scroll — reading it as one would stop following on every message.
     */
    fun next(following: Boolean, nearBottom: Boolean, userScrolling: Boolean): Boolean = when {
        nearBottom -> true
        userScrolling -> false
        else -> following
    }
}
