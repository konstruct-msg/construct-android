package com.construct.messenger.data.local.db

import com.construct.messenger.util.DisplayNameGenerator
import org.junit.Assert.assertEquals
import org.junit.Test

/** iOS `User.resolvedDisplayName`: the user's own name, their shared name, their username, the generated one. */
class ResolvedNameTest {

    private val id = "00000000-0000-0000-0000-000000000002"

    /** Mutation: put the shared name first — this reddens. */
    @Test
    fun `the name the user gave them wins`() {
        val row = UserEntity(id, username = "kostya", displayName = "Konstantin", localAlias = " Kostya ")
        assertEquals("Kostya", row.resolvedName(id))
    }

    @Test
    fun `without one, the shared name, then the username, then the generated name`() {
        assertEquals("Konstantin", UserEntity(id, username = "kostya", displayName = "Konstantin", localAlias = " ").resolvedName(id))
        assertEquals("kostya", UserEntity(id, username = "kostya").resolvedName(id))
        assertEquals(DisplayNameGenerator.generate(id), (null as UserEntity?).resolvedName(id))
    }
}
