package com.construct.messenger.data.repository

import com.construct.messenger.data.local.ContactRecord
import com.construct.messenger.data.local.FakeContactStore
import com.construct.messenger.data.local.InMemoryAtRiskSessionStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * A contact is at risk while a device of theirs is marked, and stops being so when the mark goes
 * (todo/282). Mutations, one at a time: read the device ids instead of the accounts, or never
 * map the flag — the first reddens.
 */
class ContactSessionAtRiskTest {
    private val store = FakeContactStore()
    private val atRisk = InMemoryAtRiskSessionStore()
    private val repo = ContactsRepositoryImpl(
        store, mock(), mock(), mock(), mock(), mock(), mock(), mock(), mock(), mock(), mock(), atRisk,
    )

    @Test
    fun `a marked device makes its account at risk, and no other`() = runTest {
        store.upsert(ContactRecord(id = "alice", isContact = true))
        store.upsert(ContactRecord(id = "bob", isContact = true))
        atRisk.mark(DEVICE, "alice")

        val flagged = repo.contacts.first().associate { it.userId to it.sessionAtRisk }

        assertEquals(mapOf("alice" to true, "bob" to false), flagged)
    }

    @Test
    fun `the flag goes when the mark does`() = runTest {
        store.upsert(ContactRecord(id = "alice", isContact = true))
        atRisk.mark(DEVICE, "alice")
        atRisk.clear(DEVICE)

        assertEquals(false, repo.contacts.first().single().sessionAtRisk)
    }

    private companion object {
        const val DEVICE = "33333333333333333333333333333333"
    }
}
