package com.construct.messenger.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the now-playing banner shows (TODO 151). **Canon:** iOS `ChatOnScreen` and its tests. */
class ChatOnScreenTest {
    private val song = PlaybackSource("kim", "m1", "demo.flac")

    /** Mutation: show it in the track's own chat too, or hide it everywhere. */
    @Test
    fun `the banner is up everywhere but the track's own chat`() {
        assertFalse(ChatOnScreen.showsBanner(null, null))
        assertTrue(ChatOnScreen.showsBanner(song, null))
        assertTrue(ChatOnScreen.showsBanner(song, "alex"))
        assertFalse(ChatOnScreen.showsBanner(song, "kim"))
    }

    @Test
    fun `only the chat that appeared clears it`() {
        val screen = ChatOnScreen()
        screen.appeared("kim")
        screen.appeared("alex")
        screen.disappeared("kim")
        assertEquals("alex", screen.contactId.value)
        screen.disappeared("alex")
        assertNull(screen.contactId.value)
    }

    @Test
    fun `the message to scroll to is given once, and only to its chat`() {
        val screen = ChatOnScreen()
        screen.requestFocus(song)
        assertNull(screen.takeFocus("alex"))
        assertEquals("m1", screen.takeFocus("kim"))
        assertNull(screen.takeFocus("kim"))
    }
}
