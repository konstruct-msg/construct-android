package com.construct.messenger.ui.screens.chat

import com.construct.messenger.ui.theme.CTLayout
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a finger lets go on the header's action palette decides what happens — or that nothing
 * does. Search to the left, call on the diagonal, video straight down. Each test names the
 * mutation that reddens it. **Canon:** iOS `ChatActionPaletteTests`.
 */
class ChatActionPaletteTest {
    private val all = ChatAction.entries
    private val r = ChatActionPaletteGeometry.RADIUS.value

    private fun pick(dx: Float, dy: Float, among: List<ChatAction> = all) = ChatActionPaletteGeometry.action(dx, dy, among)

    @Test
    fun `each direction picks its action`() {
        assertEquals(ChatAction.SEARCH, pick(-r, 0f))
        assertEquals(ChatAction.CALL, pick(-r * 0.7f, r * 0.7f))
        assertEquals(ChatAction.VIDEO_CALL, pick(0f, r))
    }

    /** Mutation: drop the dead zone — a hold that does not move picks whatever is nearest. */
    @Test
    fun `a finger that has not moved picks nothing`() {
        assertNull(pick(0f, 0f))
        assertNull(pick(-10f, 10f))
    }

    /** Mutation: drop the outer bound — a finger dragged away to give up still starts a call. */
    @Test
    fun `a finger far past the arc picks nothing`() {
        assertNull(pick(0f, r + ChatActionPaletteGeometry.CANCEL_BEYOND.value + 1))
    }

    /** Up and to the right is off the screen's edge; nothing is there. Mutation: drop the sector bound. */
    @Test
    fun `an empty direction picks nothing`() {
        assertNull(pick(r, 0f))
        assertNull(pick(0f, -r))
    }

    /** An action keeps its direction whatever else is offered. Mutation: lay the present actions out evenly. */
    @Test
    fun `without video, down is not the call`() {
        val noVideo = listOf(ChatAction.SEARCH, ChatAction.CALL)
        assertNull(pick(0f, r, noVideo))
        assertEquals(ChatAction.CALL, pick(-r * 0.7f, r * 0.7f, noVideo))
    }

    /** The actions sit apart enough that a fingertip on one does not touch the next. */
    @Test
    fun `neighbours are far apart`() {
        val (sx, sy) = ChatActionPaletteGeometry.offset(ChatAction.SEARCH)
        val (cx, cy) = ChatActionPaletteGeometry.offset(ChatAction.CALL)
        val apart = hypot((sx - cx).value, (sy - cy).value)
        assertTrue(apart > (ChatActionPaletteGeometry.ITEM_SIZE + CTLayout.hitTarget / 2).value)
    }

    @Test
    fun `what is offered`() {
        assertEquals(listOf(ChatAction.SEARCH), ChatAction.available(canCall = false))
        assertEquals(listOf(ChatAction.SEARCH, ChatAction.CALL, ChatAction.VIDEO_CALL), ChatAction.available(canCall = true))
    }
}
