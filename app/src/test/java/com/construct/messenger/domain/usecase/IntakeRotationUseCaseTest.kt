package com.construct.messenger.domain.usecase

import com.construct.messenger.data.local.ContactRecord
import com.construct.messenger.data.local.FakeContactStore
import com.construct.messenger.data.local.KeystoreManager
import com.construct.messenger.stealth.IntakeCredentials
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import uniffi.construct_core.IntakeKeyState

class IntakeRotationUseCaseTest {
    private val me = "00000000-0000-0000-0000-000000000001"
    private val intake = mock<IntakeCredentials>()
    private val cards = mock<SendContactCardUseCase>()
    private val keystore = mock<KeystoreManager> { on { getUserId() } doReturn me }
    private val contacts = FakeContactStore().apply {
        for (id in listOf("a", "b", "dropped")) rows[id] = ContactRecord(id = id, isContact = true)
        rows["blocked"] = ContactRecord(id = "blocked", isContact = true, isBlocked = true)
    }
    private val pauses = mutableListOf<Long>()
    private val rotation = IntakeRotationUseCase(
        intake, contacts, keystore, cards, CoroutineScope(Dispatchers.Unconfined), Random(3), { pauses += it },
    )

    /** Each remaining contact, after a pause of its own; the dropped and the blocked none. */
    @Test
    fun `once the server holds the new key, each remaining contact is handed it`() = runTest {
        whenever(intake.rotate(any(), any())).thenReturn(IntakeKeyState(5u, ByteArray(32)))

        rotation.onContactDropped("dropped", "blocked")

        verify(cards).sendIfOwed("a")
        verify(cards).sendIfOwed("b")
        verify(cards, never()).sendIfOwed("dropped")
        verify(cards, never()).sendIfOwed("blocked")
        assertEquals(2, pauses.size)
        assertTrue(pauses.all { it in 0 until IntakeCredentials.Rotation.SPREAD_MS })
    }

    /**
     * A card for a key the server does not hold would have the contact's envelopes refused and
     * the key suspended for the epoch. Mutation: sweep whatever `rotate` returned — this reddens.
     */
    @Test
    fun `no cards while the server does not hold the new key`() = runTest {
        whenever(intake.rotate(any(), any())).thenReturn(null)

        rotation.onContactDropped("dropped", "removed")

        verify(cards, never()).sendIfOwed(any())
    }
}
