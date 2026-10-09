package com.construct.messenger.invite

import com.construct.messenger.data.local.ChatPresence
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.data.local.FakeContactStore
import com.construct.messenger.data.local.ContactRecord
import com.construct.messenger.data.model.SecurityNotice
import com.construct.messenger.security.SecurityNotices
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock

/** The address conflict reaches the user: stored on the row the chat banner reads. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountAddressBookTest {

    private val peer = "11111111-2222-3333-4444-555555555555"
    private val pinned = ByteArray(32) { 1 }
    private val other = ByteArray(32) { 2 }
    private val users = FakeContactStore().also { it.rows[peer] = ContactRecord(id = peer, isContact = true, accountAddress = pinned) }
    private val presence = ChatPresence()
    private val book = AccountAddressBook(users, mock<KeystoreManager>(), SecurityNotices(users, presence))

    private fun notice() = SecurityNotice.of(users.rows.getValue(peer).securityNotice)

    /** Mutation: drop the raise in `pin` — Android logged conflicts only until 2026-09-28. */
    @Test
    fun `a card naming another address keeps the pin and raises the notice`() = runTest {
        book.pin(peer, other, AccountAddressSource.CARD)

        assertArrayEquals(pinned, users.rows.getValue(peer).accountAddress)
        assertEquals(SecurityNotice.ADDRESS_CHANGED, notice())
    }

    /** The invite replaces the pin, and the raise must land after that write, not under it. */
    @Test
    fun `an invite naming another address replaces the pin and still raises the notice`() = runTest {
        book.pin(peer, other, AccountAddressSource.INVITE)

        assertArrayEquals(other, users.rows.getValue(peer).accountAddress)
        assertEquals(SecurityNotice.ADDRESS_CHANGED, notice())
    }

    @Test
    fun `the same address raises nothing`() = runTest {
        book.pin(peer, pinned, AccountAddressSource.CARD)

        assertEquals(SecurityNotice.NONE, notice())
    }

    @Test
    fun `acknowledging clears the notice`() = runTest {
        val notices = SecurityNotices(users, presence)
        AccountAddressBook(users, mock(), notices).pin(peer, other, AccountAddressSource.CARD)

        notices.acknowledge(peer)

        assertEquals(SecurityNotice.NONE, notice())
    }

    /** The banner says it in the open chat; a second notice on top is noise. */
    @Test
    fun `no app-wide notice while their chat is on screen, one otherwise`() = runTest {
        val notices = SecurityNotices(users, presence)
        val heard = mutableListOf<SecurityNotices.Announcement>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { notices.announced.toList(heard) }

        presence.shown(peer)
        notices.raise(peer, SecurityNotice.ADDRESS_CHANGED)
        presence.hidden(peer)
        notices.raise(peer, SecurityNotice.ADDRESS_CHANGED)

        assertEquals(listOf(SecurityNotices.Announcement(peer, SecurityNotice.ADDRESS_CHANGED)), heard)
    }

}
