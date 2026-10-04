package com.construct.messenger.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** **Canon:** iOS `LinkDetectingText` — what a message's text makes tappable. */
class MessageLinksTest {

    private fun urls(text: String) = MessageLinks.find(text).map { it.url }

    @Test
    fun `an invite link in a sentence is found whole`() {
        val text = "Добавь меня: https://konstruct.cc/add?invite=eyJ2Ijo1fQ"
        val found = MessageLinks.find(text).single()
        assertEquals("https://konstruct.cc/add?invite=eyJ2Ijo1fQ", found.url)
        assertEquals(text.indexOf("https"), found.range.first)
        assertEquals(text.length - 1, found.range.last)
    }

    @Test
    fun `konstruct and plain web links are links, other schemes are not`() {
        assertEquals(
            listOf("konstruct://add?invite=xyz", "http://example.com/a"),
            urls("konstruct://add?invite=xyz or http://example.com/a, not ftp://x or mailto:a@b"),
        )
    }

    /** Mutation: keep the iOS pattern's tail — the dot and the bracket become part of the URL. */
    @Test
    fun `closing punctuation stays outside the link`() {
        assertEquals(listOf("https://konstruct.cc/add?invite=abc"), urls("see https://konstruct.cc/add?invite=abc."))
        assertEquals(listOf("https://example.com/x"), urls("(https://example.com/x)"))
        assertEquals(listOf("https://en.wikipedia.org/wiki/A_(b)"), urls("https://en.wikipedia.org/wiki/A_(b)!"))
    }

    @Test
    fun `a bare scheme or plain text is no link`() {
        assertEquals(emptyList<String>(), urls("https:// and konstruct.cc without a scheme"))
        assertEquals(emptyList<String>(), urls("just words"))
    }

    @Test
    fun `invite links are opened by the app, others by the system`() {
        assertTrue(MessageLinks.isOwn("https://konstruct.cc/add?invite=abc"))
        assertFalse(MessageLinks.isOwn("https://example.com/add?invite=abc"))
    }
}
