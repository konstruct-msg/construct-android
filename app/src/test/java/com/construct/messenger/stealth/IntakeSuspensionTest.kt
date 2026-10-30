package com.construct.messenger.stealth

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.construct.messenger.data.local.KeystoreManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.construct_core.IntakeKeyState

/**
 * A suspension (a tag the server refused) names the key we held; a newer generation from the
 * contact's card lifts it, for that account only. Agreed with iOS (construct-messenger), 2026-10-30.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IntakeSuspensionTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = context.getSharedPreferences("intake_prefs", Context.MODE_PRIVATE)
    private val peer = "6f1c2a9e-0b7d-4c55-9a1e-3d2f8b7c6a10"
    private val other = "00000000-0000-0000-0000-000000000009"
    private val keystore = mock<KeystoreManager>()
    private val intake = IntakeCredentials(context, keystore, mock(), mock(), { mock() })

    private fun holding(generation: UInt) {
        whenever(keystore.peerIntakeKey(any())).thenReturn(
            IntakeCredentials.KeyRecord.encode(IntakeKeyState(generation, ByteArray(32) { 1 })),
        )
    }

    private fun suspended(accountId: String) = prefs.contains("rejected:${accountId.lowercase()}")

    /** Mutation: no lift — a contact who rotated costs tokens until the epoch rolls. */
    @Test
    fun `a newer key lifts the suspension of that account only`() {
        holding(1u)
        intake.noteRejected(peer)
        intake.noteRejected(other)

        intake.recordPeerKey(peer.uppercase(), IntakeKeyState(2u, ByteArray(32) { 2 }))

        assertFalse(suspended(peer))
        assertTrue(suspended(other))
    }

    /** Mutation: lift before the offer is decided — a replayed old card lifts it every time. */
    @Test
    fun `a card of the same or an older generation leaves it suspended`() {
        holding(3u)
        intake.noteRejected(peer)

        intake.recordPeerKey(peer, IntakeKeyState(3u, ByteArray(32) { 2 }))
        intake.recordPeerKey(peer, IntakeKeyState(2u, ByteArray(32) { 2 }))

        assertTrue(suspended(peer))
    }
}
