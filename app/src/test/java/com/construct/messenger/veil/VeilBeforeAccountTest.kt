package com.construct.messenger.veil

import com.construct.messenger.transport.TransportRouter
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** VEIL before an account starts the route only when there is a front to use. */
class VeilBeforeAccountTest {
    private val router: TransportRouter = mock()
    private val fronts: VeilFrontStore = mock()
    private val subject = VeilBeforeAccount(fronts) { router }

    /** Default-off in uncensored networks: no code imported, nothing runs. Mutation: drop the check — reddens. */
    @Test
    fun noFrontStartsNothing() {
        whenever(fronts.preferred()).thenReturn(null)
        subject.startIfConfigured()
        verify(router, never()).start()
    }

    /** A code imported on the first screen: registration can now escalate to VEIL. */
    @Test
    fun aFrontStartsTheRoute() {
        whenever(fronts.preferred()).thenReturn(VeilRelay("front.example:443", "front.example", "a".repeat(64)))
        subject.startIfConfigured()
        verify(router).start()
    }
}
