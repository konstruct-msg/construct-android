package com.construct.messenger.viewmodel

import com.construct.messenger.data.repository.InviteRevocation
import com.construct.messenger.data.repository.IssuedInvite
import org.junit.Assert.assertEquals
import org.junit.Test

/** iOS `InviteJournal`: a link is one act, a QR showing is one act however many codes it turned through. */
class IssuedInvitesViewModelTest {

    private val now = 10_000L

    private fun qr(jti: String, at: Long, sitting: String? = "s1") = IssuedInvite(jti, "qr", at, 300, sitting)

    /** Mutation: group by jti alone — this reddens (three rows). */
    @Test
    fun `the codes of one showing are one row with the live ones counted`() {
        val acts = IssuedInvitesViewModel.actsOf(
            listOf(qr("a", now - 400), qr("b", now - 60), qr("c", now - 30)),
            now,
        )

        val act = acts.single()
        assertEquals(listOf("b", "c"), act.liveJtis)
        assertEquals(now - 400, act.startedAtEpochSec)
        assertEquals(now - 30 + 300, act.expiresAtEpochSec)
    }

    @Test
    fun `links and codes from before showings stay a row each, newest first`() {
        val acts = IssuedInvitesViewModel.actsOf(
            listOf(
                IssuedInvite("l1", "link", now - 100, 43_200),
                qr("q1", now - 50, sitting = null),
                qr("q2", now - 40, sitting = null),
            ),
            now,
        )

        assertEquals(listOf("q2", "q1", "l1"), acts.map { it.id })
    }

    @Test
    fun `a showing with nothing live is not listed`() {
        assertEquals(emptyList<IssuedAct>(), IssuedInvitesViewModel.actsOf(listOf(qr("a", now - 400)), now))
    }

    /** A code with no answer may still work, so the showing must not read as revoked. */
    @Test
    fun `one unanswered code makes the whole revoke unconfirmed`() {
        assertEquals(
            InviteRevocation.UNCONFIRMED,
            IssuedInvitesViewModel.combined(listOf(InviteRevocation.REVOKED, InviteRevocation.UNCONFIRMED)),
        )
        assertEquals(
            InviteRevocation.REVOKED,
            IssuedInvitesViewModel.combined(listOf(InviteRevocation.ALREADY_USED, InviteRevocation.REVOKED)),
        )
        assertEquals(InviteRevocation.ALREADY_USED, IssuedInvitesViewModel.combined(listOf(InviteRevocation.ALREADY_USED)))
    }
}
