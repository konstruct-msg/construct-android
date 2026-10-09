package com.construct.messenger.service

import com.construct.messenger.data.local.ContactRecord
import com.construct.messenger.data.local.resolvedName
import com.construct.messenger.util.DisplayNameGenerator
import com.construct.messenger.util.LegacyProfileShare
import com.construct.messenger.util.ProfileShare
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A generated name is no name (TODO 102). **Canon:** iOS `ProfileShareTests.testAGeneratedName*`,
 * `testAnEmptyNameShowsTheUsername`.
 */
class ProfileNameTest {
    private val id = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    private val generated = DisplayNameGenerator.generate(id)
    private val alice = ContactRecord(id = id, username = "alice", isContact = true)

    private fun typed(row: ContactRecord, name: String, at: Long) =
        ContactProfiles.typed(row, ProfileShare(name, at, ProfileShare.Avatar.Unchanged), nowMs = 0)!!.row

    /**
     * The defect of 2026-10-02: Android sent its generated name when its user had set none, and it
     * replaced the username the invite gave. Mutation: apply the shared name as it came — reddens.
     */
    @Test
    fun `a generated name does not replace the username`() {
        val row = typed(alice, generated.uppercase(), 100)
        // Not kept at all — not merely hidden by `resolvedName`, which would mask this.
        assertEquals("", row.displayName)
        assertEquals("alice", row.resolvedName(id))
        assertEquals("the profile still applies — only the name is none", 100L, row.profileEditedAtMs)
    }

    /** A profile is the whole state at its version: a newer one with no name drops the old name. */
    @Test
    fun `an empty name shows the username`() {
        val named = typed(alice, "Alice One", 100)
        assertEquals("Alice One", named.resolvedName(id))
        assertEquals("alice", typed(named, "", 101).resolvedName(id))
    }

    @Test
    fun `an untyped generated name does not replace the username`() {
        val held = alice.copy(displayName = "Alice One")
        val row = ContactProfiles.legacy(held, LegacyProfileShare(generated, timestampSec = 1), nowMs = 0)!!.row
        assertEquals("Alice One", row.resolvedName(id))
    }

    /**
     * Rows overwritten before the fix, and rows created with the generated name: the username
     * shows, and the generated name only when there is none. Mutation: drop the check in
     * `resolvedName` — reddens.
     */
    @Test
    fun `a generated name already held shows the username`() {
        assertEquals("alice", alice.copy(displayName = generated).resolvedName(id))
        assertEquals(generated, alice.copy(displayName = generated, username = "").resolvedName(id))
    }
}
